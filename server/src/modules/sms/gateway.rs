use std::time::Duration;

use axum::{
    Json, Router,
    extract::{
        State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{
        HeaderMap, StatusCode,
        header::{AUTHORIZATION, CACHE_CONTROL},
    },
    response::{IntoResponse, Response},
    routing::{get, post},
};
use sha2::{Digest, Sha256};
use sqlx::{PgPool, postgres::PgListener};
use subtle::ConstantTimeEq;
use tokio::{sync::watch, time::sleep};

use super::repository;

const WORK_CHANNEL: &str = "sms_work_available";
const WORK_NOTICE: &str = r#"{"type":"work_available"}"#;

#[derive(Clone)]
struct GatewayState {
    pool: PgPool,
    key_sha256: Option<[u8; 32]>,
    notices: watch::Receiver<()>,
}

pub(crate) fn routes(pool: PgPool, database_url: String, key_sha256: Option<[u8; 32]>) -> Router {
    let (notify, notices) = watch::channel(());
    if key_sha256.is_some() {
        tokio::spawn(relay_work(database_url, pool.clone(), notify));
    }

    Router::new()
        .route("/api/sms/gateway/ws", get(upgrade))
        .route("/api/sms/gateway/jobs/claim", post(claim))
        .with_state(GatewayState {
            pool,
            key_sha256,
            notices,
        })
}

async fn upgrade(
    State(state): State<GatewayState>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Result<Response, StatusCode> {
    require_gateway_key(&headers, state.key_sha256.as_ref())?;
    Ok(ws
        .max_message_size(1024)
        .max_frame_size(1024)
        .on_upgrade(move |socket| serve_socket(socket, state)))
}

async fn claim(
    State(state): State<GatewayState>,
    headers: HeaderMap,
) -> Result<Response, StatusCode> {
    require_gateway_key(&headers, state.key_sha256.as_ref())?;
    let job = repository::claim_job(&state.pool).await.map_err(|error| {
        eprintln!("SMS gateway could not confirm a claim: {error}");
        StatusCode::SERVICE_UNAVAILABLE
    })?;

    Ok(match job {
        Some(job) => ([(CACHE_CONTROL, "no-store")], Json(job)).into_response(),
        None => StatusCode::NO_CONTENT.into_response(),
    })
}

fn require_gateway_key(headers: &HeaderMap, expected: Option<&[u8; 32]>) -> Result<(), StatusCode> {
    let expected = expected.ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
    let mut values = headers.get_all(AUTHORIZATION).iter();
    let Some((scheme, key)) = values
        .next()
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.split_once(' '))
    else {
        return Err(StatusCode::UNAUTHORIZED);
    };
    if values.next().is_some()
        || !scheme.eq_ignore_ascii_case("bearer")
        || key.len() != 64
        || !key.bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let supplied: [u8; 32] = Sha256::digest(key.as_bytes()).into();
    if bool::from(supplied.ct_eq(expected)) {
        Ok(())
    } else {
        Err(StatusCode::UNAUTHORIZED)
    }
}

async fn serve_socket(
    mut socket: WebSocket,
    GatewayState {
        pool, mut notices, ..
    }: GatewayState,
) {
    // Subscribe before checking work so changes during the query are still observed.
    notices.mark_unchanged();
    match has_claimable_work(&pool).await {
        Ok(true) => {
            if socket
                .send(Message::Text(WORK_NOTICE.into()))
                .await
                .is_err()
            {
                return;
            }
        }
        Ok(false) => {}
        Err(error) => {
            eprintln!("SMS gateway could not check claimable work: {error}");
            return;
        }
    }

    loop {
        tokio::select! {
            message = socket.recv() => match message {
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => return,
                Some(Ok(_)) => {}
            },
            change = notices.changed() => {
                if change.is_err() || socket.send(Message::Text(WORK_NOTICE.into())).await.is_err() {
                    return;
                }
            }
        }
    }
}

async fn relay_work(database_url: String, pool: PgPool, notify: watch::Sender<()>) {
    loop {
        if let Err(error) = listen_for_work(&database_url, &pool, &notify).await {
            eprintln!("SMS gateway listener failed: {error}. Retrying.");
        }
        sleep(Duration::from_secs(2)).await;
    }
}

async fn listen_for_work(
    database_url: &str,
    pool: &PgPool,
    notify: &watch::Sender<()>,
) -> Result<(), sqlx::Error> {
    let mut listener = PgListener::connect(database_url).await?;
    listener.listen(WORK_CHANNEL).await?;

    loop {
        // A None means SQLx has reconnected and restored LISTEN. Catch up from the table.
        if has_claimable_work(pool).await? {
            notify.send_replace(());
        }
        while listener.try_recv().await?.is_some() {
            notify.send_replace(());
        }
    }
}

async fn has_claimable_work(pool: &PgPool) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM sms_attempts WHERE result_status IS NULL)
             OR EXISTS (
                 SELECT 1 FROM sms_jobs
                 WHERE status = 'queued'
                   AND next_attempt_at <= statement_timestamp()
                   AND send_by > statement_timestamp()
             )",
    )
    .fetch_one(pool)
    .await
}
