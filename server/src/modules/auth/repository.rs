use chrono::{DateTime, Utc};
use serde_json::Value;
use sqlx::{
    PgConnection, PgPool, Row,
    types::{Json, Uuid},
};

pub(super) struct CreatedSession {
    pub(super) id: Uuid,
    pub(super) refresh_counter: i64,
    pub(super) idle_expires_at: DateTime<Utc>,
    pub(super) absolute_expires_at: DateTime<Utc>,
}

pub(super) struct LockedSession {
    pub(super) id: Uuid,
    pub(super) account_id: Uuid,
    pub(super) browser_public_key: Value,
    pub(super) refresh_counter: i64,
    pub(super) idle_expires_at: DateTime<Utc>,
    pub(super) absolute_expires_at: DateTime<Utc>,
}

pub(super) async fn find_or_create_google_account(
    pool: &PgPool,
    google_sub: &str,
    email: &str,
) -> Result<Uuid, sqlx::Error> {
    sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO accounts (google_sub, email)
         VALUES ($1, $2)
         ON CONFLICT (google_sub) DO UPDATE
         SET email = EXCLUDED.email
         RETURNING id",
    )
    .bind(google_sub)
    .bind(email)
    .fetch_one(pool)
    .await
}

pub(super) async fn create_session(
    pool: &PgPool,
    account_id: Uuid,
    browser_public_key: &Value,
) -> Result<CreatedSession, sqlx::Error> {
    let row = sqlx::query(
        "INSERT INTO auth_sessions (account_id, browser_public_key)
         VALUES ($1, $2)
         RETURNING id, refresh_counter, idle_expires_at, absolute_expires_at",
    )
    .bind(account_id)
    .bind(Json(browser_public_key))
    .fetch_one(pool)
    .await?;

    Ok(CreatedSession {
        id: row.try_get("id")?,
        refresh_counter: row.try_get("refresh_counter")?,
        idle_expires_at: row.try_get("idle_expires_at")?,
        absolute_expires_at: row.try_get("absolute_expires_at")?,
    })
}

pub(super) async fn lock_session(
    connection: &mut PgConnection,
    session_id: Uuid,
) -> Result<Option<LockedSession>, sqlx::Error> {
    let row = sqlx::query(
        "SELECT id, account_id, browser_public_key, refresh_counter,
                idle_expires_at, absolute_expires_at
         FROM auth_sessions WHERE id = $1 FOR UPDATE",
    )
    .bind(session_id)
    .fetch_optional(connection)
    .await?;

    let Some(row) = row else {
        return Ok(None);
    };

    Ok(Some(LockedSession {
        id: row.try_get("id")?,
        account_id: row.try_get("account_id")?,
        browser_public_key: row.try_get("browser_public_key")?,
        refresh_counter: row.try_get("refresh_counter")?,
        idle_expires_at: row.try_get("idle_expires_at")?,
        absolute_expires_at: row.try_get("absolute_expires_at")?,
    }))
}

pub(super) async fn rotate_session(
    connection: &mut PgConnection,
    session_id: Uuid,
    next_counter: i64,
) -> Result<CreatedSession, sqlx::Error> {
    let row = sqlx::query(
        "UPDATE auth_sessions
         SET refresh_counter = $2,
             idle_expires_at = LEAST(
                 clock_timestamp() + INTERVAL '30 days', absolute_expires_at
             )
         WHERE id = $1
         RETURNING id, refresh_counter, idle_expires_at, absolute_expires_at",
    )
    .bind(session_id)
    .bind(next_counter)
    .fetch_one(connection)
    .await?;

    Ok(CreatedSession {
        id: row.try_get("id")?,
        refresh_counter: row.try_get("refresh_counter")?,
        idle_expires_at: row.try_get("idle_expires_at")?,
        absolute_expires_at: row.try_get("absolute_expires_at")?,
    })
}

pub(super) async fn delete_session(
    connection: &mut PgConnection,
    session_id: Uuid,
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM auth_sessions WHERE id = $1")
        .bind(session_id)
        .execute(connection)
        .await?;
    Ok(())
}
