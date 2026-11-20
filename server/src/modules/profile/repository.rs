use chrono::{DateTime, Utc};
use serde_json::Value;
use sqlx::{FromRow, PgConnection, PgPool, Row, postgres::PgRow, types::Uuid};

pub(super) struct AccountProfile {
    pub(super) email: String,
    pub(super) name: String,
    pub(super) avatar_url: String,
    pub(super) cover_url: String,
}

pub(super) struct LockedAccount {
    pub(super) avatar_object_key: Option<String>,
}

impl FromRow<'_, PgRow> for AccountProfile {
    fn from_row(row: &PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            email: row.try_get("email")?,
            name: row.try_get("name")?,
            avatar_url: row.try_get("avatar_url")?,
            cover_url: row.try_get("cover_url")?,
        })
    }
}

pub(super) async fn find_account_profile(
    pool: &PgPool,
    account_id: Uuid,
) -> Result<Option<AccountProfile>, sqlx::Error> {
    sqlx::query_as(
        "SELECT email, name, avatar_url, cover_url
         FROM accounts
         WHERE id = $1",
    )
    .bind(account_id)
    .fetch_optional(pool)
    .await
}

pub(super) async fn update_profile(
    pool: &PgPool,
    account_id: Uuid,
    name: Option<&str>,
    cover_url: Option<&str>,
) -> Result<Option<AccountProfile>, sqlx::Error> {
    sqlx::query_as(
        "UPDATE accounts
         SET name = COALESCE($2, name), cover_url = COALESCE($3, cover_url)
         WHERE id = $1
         RETURNING email, name, avatar_url, cover_url",
    )
    .bind(account_id)
    .bind(name)
    .bind(cover_url)
    .fetch_optional(pool)
    .await
}

pub(super) async fn lock_account(
    connection: &mut PgConnection,
    account_id: Uuid,
) -> Result<Option<LockedAccount>, sqlx::Error> {
    sqlx::query_as("SELECT avatar_object_key FROM accounts WHERE id = $1 FOR UPDATE")
        .bind(account_id)
        .fetch_optional(connection)
        .await
}

impl FromRow<'_, PgRow> for LockedAccount {
    fn from_row(row: &PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            avatar_object_key: row.try_get("avatar_object_key")?,
        })
    }
}

pub(super) async fn lock_active_session(
    connection: &mut PgConnection,
    account_id: Uuid,
    session_id: Uuid,
) -> Result<Option<Value>, sqlx::Error> {
    let session: Option<(DateTime<Utc>, DateTime<Utc>, Value)> = sqlx::query_as(
        "SELECT idle_expires_at, absolute_expires_at, browser_public_key FROM auth_sessions
         WHERE id = $1 AND account_id = $2
         FOR UPDATE",
    )
    .bind(session_id)
    .bind(account_id)
    .fetch_optional(connection)
    .await?;
    let now = Utc::now();
    Ok(session
        .filter(|(idle, absolute, _)| *idle > now && *absolute > now)
        .map(|(_, _, public_key)| public_key))
}

pub(super) async fn prepare_deletion(
    pool: &PgPool,
    account_id: Uuid,
    session_id: Uuid,
) -> Result<Option<Uuid>, sqlx::Error> {
    sqlx::query_scalar(
        "INSERT INTO profile_deletion_challenges (session_id)
         SELECT id FROM auth_sessions
         WHERE id = $1 AND account_id = $2
           AND idle_expires_at > clock_timestamp() AND absolute_expires_at > clock_timestamp()
         ON CONFLICT (session_id) DO UPDATE
         SET nonce = EXCLUDED.nonce, started_at = EXCLUDED.started_at
         RETURNING nonce",
    )
    .bind(session_id)
    .bind(account_id)
    .fetch_optional(pool)
    .await
}

pub(super) async fn lock_deletion_challenge(
    connection: &mut PgConnection,
    session_id: Uuid,
    nonce: Uuid,
) -> Result<bool, sqlx::Error> {
    let started_at: Option<DateTime<Utc>> = sqlx::query_scalar(
        "SELECT started_at
         FROM profile_deletion_challenges
         WHERE session_id = $1 AND nonce = $2
         FOR UPDATE",
    )
    .bind(session_id)
    .bind(nonce)
    .fetch_optional(connection)
    .await?;
    let oldest = Utc::now() - chrono::TimeDelta::seconds(60);
    Ok(started_at.is_some_and(|started_at| started_at > oldest))
}

pub(super) async fn delete_account(
    connection: &mut PgConnection,
    account_id: Uuid,
) -> Result<(), sqlx::Error> {
    // Sessions and deletion challenges cascade with the account.
    sqlx::query("DELETE FROM accounts WHERE id = $1")
        .bind(account_id)
        .execute(connection)
        .await?;
    Ok(())
}

pub(super) async fn update_avatar(
    connection: &mut PgConnection,
    account_id: Uuid,
    object_key: &str,
    avatar_url: &str,
) -> Result<AccountProfile, sqlx::Error> {
    sqlx::query_as(
        "UPDATE accounts
         SET avatar_object_key = $2, avatar_url = $3
         WHERE id = $1
         RETURNING email, name, avatar_url, cover_url",
    )
    .bind(account_id)
    .bind(object_key)
    .bind(avatar_url)
    .fetch_one(connection)
    .await
}
