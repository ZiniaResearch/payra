use chrono::{DateTime, TimeDelta, Utc};
use serde::Deserialize;
use sqlx::{PgPool, Row};
use uuid::Uuid;

use super::repository::{lock_gateway, notify_jobs_changed};

const MAX_ATTEMPTS: i32 = 2;
const RETRY_DELAY: TimeDelta = TimeDelta::minutes(15);

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
pub(super) enum SmsOutcome {
    Sent,
    Failed,
    Partial,
    Unknown,
}

impl SmsOutcome {
    fn status(&self) -> &'static str {
        match self {
            Self::Sent => "sent",
            Self::Failed => "failed",
            Self::Partial => "partial",
            Self::Unknown => "unknown",
        }
    }
}

pub(super) enum ResultRecord {
    Acknowledged,
    NotFound,
    Conflict,
}

pub(super) async fn record_result(
    pool: &PgPool,
    job_id: Uuid,
    attempt: i32,
    outcome: SmsOutcome,
) -> Result<ResultRecord, sqlx::Error> {
    let mut tx = pool.begin().await?;
    lock_gateway(&mut tx).await?;

    let assignment = sqlx::query(
        "SELECT attempt.attempt_number, attempt.result_status, job.status = 'sending' AS is_sending,
                job.next_attempt_at, job.send_by, clock_timestamp() AS checked_at
         FROM sms_jobs AS job
         JOIN sms_attempts AS attempt ON attempt.job_id = job.id
         WHERE job.id = $1
         FOR UPDATE OF job",
    )
    .bind(job_id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some(assignment) = assignment else {
        return Ok(ResultRecord::NotFound);
    };
    if attempt != assignment.try_get::<i32, _>("attempt_number")? {
        return Ok(ResultRecord::Conflict);
    }

    let status = outcome.status();
    match assignment.try_get::<Option<&str>, _>("result_status")? {
        Some(saved) if saved == status => {
            tx.commit().await?;
            return Ok(ResultRecord::Acknowledged);
        }
        Some(_) => return Ok(ResultRecord::Conflict),
        None if !assignment.try_get::<bool, _>("is_sending")? => {
            return Ok(ResultRecord::Conflict);
        }
        None => {}
    }

    let checked_at: DateTime<Utc> = assignment.try_get("checked_at")?;
    let previous_due_at: DateTime<Utc> = assignment.try_get("next_attempt_at")?;
    let send_by: DateTime<Utc> = assignment.try_get("send_by")?;
    let retry_at = (checked_at + RETRY_DELAY).max(previous_due_at);
    let next_attempt_at =
        (!matches!(outcome, SmsOutcome::Sent) && attempt < MAX_ATTEMPTS && retry_at < send_by)
            .then_some(retry_at);

    sqlx::query(
        "WITH recorded_result AS (
             UPDATE sms_attempts SET result_status = $2 WHERE job_id = $1
             RETURNING job_id
         )
         UPDATE sms_jobs AS job
         SET status = CASE WHEN $3::timestamptz IS NULL THEN $2 ELSE 'queued' END,
             next_attempt_at = COALESCE($3, job.next_attempt_at)
         FROM recorded_result
         WHERE job.id = recorded_result.job_id",
    )
    .bind(job_id)
    .bind(status)
    .bind(next_attempt_at)
    .execute(&mut *tx)
    .await?;
    if next_attempt_at.is_some() {
        notify_jobs_changed(&mut tx).await?;
    }

    tx.commit().await?;
    Ok(ResultRecord::Acknowledged)
}
