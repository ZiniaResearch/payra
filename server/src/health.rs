use axum::{Json, Router, extract::State, http::StatusCode, routing::get};
use chrono::Utc;
use serde_json::{Value, json};
use sqlx::PgPool;
use std::time::Duration;
use tokio::time::timeout;

pub(crate) fn routes() -> Router<PgPool> {
    Router::new().route("/health", get(health))
}

async fn health(State(conn_pool): State<PgPool>) -> (StatusCode, Json<Value>) {
    let query = sqlx::query("SELECT 1").fetch_one(&conn_pool);
    let result = timeout(Duration::from_secs(2), query).await;
    let (code, status, database) = match result {
        Ok(Ok(_)) => (StatusCode::OK, "ok", "reachable"),
        Ok(Err(_)) | Err(_) => (
            StatusCode::SERVICE_UNAVAILABLE,
            "unavailable",
            "unavailable",
        ),
    };
    (
        code,
        Json(json!({
            "status": status,
            "database": database,
            "checked_at": Utc::now().to_rfc3339(),
        })),
    )
}
