use chrono::{DateTime, Utc};
use sqlx::PgConnection;
use uuid::Uuid;

use crate::modules::auth::AccessIdentity;

pub(super) async fn lock_active_session(
    connection: &mut PgConnection,
    identity: AccessIdentity,
) -> Result<bool, sqlx::Error> {
    // Match account deletion's lock order before taking the session lock.
    let account =
        sqlx::query_scalar::<_, Uuid>("SELECT id FROM accounts WHERE id = $1 FOR KEY SHARE")
            .bind(identity.account_id())
            .fetch_optional(&mut *connection)
            .await?;

    if account.is_none() {
        return Ok(false);
    }

    let session: Option<(DateTime<Utc>, DateTime<Utc>)> = sqlx::query_as(
        "SELECT idle_expires_at, absolute_expires_at
         FROM auth_sessions
         WHERE id = $1 AND account_id = $2
         FOR SHARE",
    )
    .bind(identity.session_id())
    .bind(identity.account_id())
    .fetch_optional(connection)
    .await?;

    let now = Utc::now();
    Ok(session.is_some_and(|(idle, absolute)| idle > now && absolute > now))
}

pub(super) async fn insert_jobs(
    connection: &mut PgConnection,
    account_id: Uuid,
    recipients: &[String],
    message: &str,
    send_times: &[DateTime<Utc>],
) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        "INSERT INTO sms_jobs (account_id, recipient_phone, message_text, send_at, send_by)
         SELECT $1, recipient.phone, $3, schedule.send_at,
                schedule.send_at + interval '6 hours'
         FROM unnest($2::text[]) AS recipient(phone)
         CROSS JOIN unnest($4::timestamptz[]) AS schedule(send_at)
         RETURNING id::text",
    )
    .bind(account_id)
    .bind(recipients)
    .bind(message)
    .bind(send_times)
    .fetch_all(connection)
    .await
}
