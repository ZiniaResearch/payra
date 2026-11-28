use std::time::Duration;

use chrono::{DateTime, Utc};
use sqlx::{PgPool, postgres::PgListener};
use tokio::{
    sync::watch,
    time::{Instant, sleep},
};

const JOBS_CHANGED_CHANNEL: &str = "sms_jobs_changed";
const RETRY_DELAY: Duration = Duration::from_secs(2);

struct SendTimer {
    next_attempt_at: DateTime<Utc>,
    deadline: Instant,
}

pub(super) async fn run(pool: &PgPool) -> Result<(), watch::error::RecvError> {
    // A watch signal coalesces changes because each check reads the current queue.
    let (wake, changes) = watch::channel(());
    tokio::select! {
        () = listen(pool, wake) => Ok(()),
        result = schedule(pool, changes) => result,
    }
}

async fn listen(pool: &PgPool, wake: watch::Sender<()>) {
    loop {
        if let Err(error) = listen_for_changes(pool, &wake).await {
            eprintln!("SMS scheduler listener error: {error}. Retrying.");
        }
        sleep(RETRY_DELAY).await;
    }
}

async fn listen_for_changes(pool: &PgPool, wake: &watch::Sender<()>) -> Result<(), sqlx::Error> {
    let mut listener = PgListener::connect_with(pool).await?;
    listener.listen(JOBS_CHANGED_CHANNEL).await?;
    println!("SMS scheduler listening for {JOBS_CHANGED_CHANNEL}.");
    wake.send_replace(());

    loop {
        // SQLx restores LISTEN before returning None on a successful reconnect.
        if listener.try_recv().await?.is_none() {
            println!("SMS scheduler listener reconnected. Rechecking the queue.");
        }
        wake.send_replace(());
    }
}

async fn schedule(
    pool: &PgPool,
    mut changes: watch::Receiver<()>,
) -> Result<(), watch::error::RecvError> {
    changes.changed().await?;
    let timer = sleep(Duration::ZERO);
    tokio::pin!(timer);
    let mut armed_for = None;

    loop {
        let next_timer = match check_queue(pool).await {
            Ok(next_timer) => next_timer,
            Err(error) => {
                eprintln!("SMS scheduler queue check failed: {error}. Retrying.");
                armed_for = None;
                sleep(RETRY_DELAY).await;
                continue;
            }
        };

        match next_timer {
            Some(next) if armed_for != Some(next.next_attempt_at) => {
                timer.as_mut().reset(next.deadline);
                armed_for = Some(next.next_attempt_at);
                println!("SMS scheduler next send time: {}", next.next_attempt_at);
            }
            Some(_) => {}
            None => armed_for = None,
        }

        tokio::select! {
            () = &mut timer, if armed_for.is_some() => armed_for = None,
            result = changes.changed() => result?,
        }
    }
}

async fn check_queue(pool: &PgPool) -> Result<Option<SendTimer>, sqlx::Error> {
    let (has_due_jobs, next_attempt_at, checked_at): (bool, Option<DateTime<Utc>>, DateTime<Utc>) =
        sqlx::query_as(
            "SELECT EXISTS (
                 SELECT 1 FROM sms_jobs
                 WHERE status = 'queued'
                   AND next_attempt_at <= statement_timestamp()
                   AND send_by > statement_timestamp()
             ), (
                 SELECT next_attempt_at FROM sms_jobs
                 WHERE status = 'queued' AND next_attempt_at > statement_timestamp()
                 ORDER BY next_attempt_at, id
                 LIMIT 1
             ), statement_timestamp()",
        )
        .fetch_one(pool)
        .await?;

    // Measure the delay using PostgreSQL's clock so the worker can run on a separate host.
    let timer = next_attempt_at.map(|next_attempt_at| SendTimer {
        next_attempt_at,
        deadline: Instant::now() + (next_attempt_at - checked_at).to_std().unwrap_or_default(),
    });

    if has_due_jobs {
        sqlx::query("NOTIFY sms_work_available")
            .execute(pool)
            .await?;
        println!("SMS scheduler published sms_work_available.");
    }

    Ok(timer)
}
