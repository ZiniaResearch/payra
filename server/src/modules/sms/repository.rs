use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::{FromRow, PgConnection, PgPool, Row, postgres::PgRow};
use uuid::Uuid;

use crate::modules::auth::AccessIdentity;

const SMS_CLAIM_LOCK: i64 = i64::from_be_bytes(*b"smsclaim");

#[derive(Serialize)]
pub(super) struct SavedRecipient {
    id: String,
    phone: String,
    name: Option<String>,
}

impl FromRow<'_, PgRow> for SavedRecipient {
    fn from_row(row: &PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            id: row.try_get("id")?,
            phone: row.try_get("phone")?,
            name: row.try_get("name")?,
        })
    }
}

#[derive(Serialize)]
pub(super) struct ClaimedJob {
    id: String,
    attempt: i32,
    #[serde(rename = "to")]
    recipient_phone: String,
    #[serde(rename = "text")]
    message_text: String,
}

impl FromRow<'_, PgRow> for ClaimedJob {
    fn from_row(row: &PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            id: row.try_get("id")?,
            attempt: row.try_get("attempt")?,
            recipient_phone: row.try_get("recipient_phone")?,
            message_text: row.try_get("message_text")?,
        })
    }
}

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
    recipient_ids: &[Uuid],
    message: &str,
    send_times: &[DateTime<Utc>],
) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        "WITH selected_recipients AS (
             SELECT phone FROM sms_recipients
             WHERE account_id = $1 AND id = ANY($2::uuid[])
         )
         INSERT INTO sms_jobs (
             account_id, recipient_phone, message_text, send_at, next_attempt_at, send_by
         )
         SELECT $1, recipient.phone, $3, schedule.send_at, schedule.send_at,
                schedule.send_at + interval '6 hours'
         FROM selected_recipients AS recipient
         CROSS JOIN unnest($4::timestamptz[]) AS schedule(send_at)
         WHERE (SELECT count(*) FROM selected_recipients) = cardinality($2::uuid[])
         RETURNING id::text",
    )
    .bind(account_id)
    .bind(recipient_ids)
    .bind(message)
    .bind(send_times)
    .fetch_all(connection)
    .await
}

pub(super) async fn claim_job(pool: &PgPool) -> Result<Option<ClaimedJob>, sqlx::Error> {
    let mut tx = pool.begin().await?;

    // Serialize claims for the single gateway. Read the queue after acquiring the lock.
    sqlx::query("SELECT pg_advisory_xact_lock($1)")
        .bind(SMS_CLAIM_LOCK)
        .execute(&mut *tx)
        .await?;

    let job = sqlx::query_as(
        "WITH open_attempt AS (
             SELECT job_id, attempt_number FROM sms_attempts
             WHERE result_status IS NULL
         ), claimed_job AS (
             UPDATE sms_jobs
             SET status = 'sending'
             WHERE id = (
                 SELECT id FROM sms_jobs
                 WHERE status = 'queued'
                   AND next_attempt_at <= statement_timestamp()
                   AND send_by > statement_timestamp()
                   AND NOT EXISTS (SELECT 1 FROM open_attempt)
                 ORDER BY next_attempt_at, id
                 LIMIT 1
                 FOR UPDATE
             )
             RETURNING id
         ), recorded_attempt AS (
             INSERT INTO sms_attempts AS attempt (job_id, attempt_number, claimed_at)
             SELECT id, 1, statement_timestamp() FROM claimed_job
             ON CONFLICT (job_id) DO UPDATE
             SET attempt_number = attempt.attempt_number + 1,
                 claimed_at = EXCLUDED.claimed_at,
                 result_status = NULL
             RETURNING job_id, attempt_number
         )
         SELECT job.id::text, attempt.attempt_number AS attempt,
                job.recipient_phone, job.message_text
         FROM (
             SELECT job_id, attempt_number FROM open_attempt
             UNION ALL
             SELECT job_id, attempt_number FROM recorded_attempt
         ) AS attempt
         JOIN sms_jobs AS job ON job.id = attempt.job_id",
    )
    .fetch_optional(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(job)
}

pub(super) async fn notify_jobs_changed(connection: &mut PgConnection) -> Result<(), sqlx::Error> {
    sqlx::query("NOTIFY sms_jobs_changed")
        .execute(connection)
        .await?;
    Ok(())
}

pub(super) async fn list_recipients(
    pool: &PgPool,
    account_id: Uuid,
) -> Result<Vec<SavedRecipient>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id::text, phone, name FROM sms_recipients
         WHERE account_id = $1
         ORDER BY phone",
    )
    .bind(account_id)
    .fetch_all(pool)
    .await
}

pub(super) async fn insert_recipient(
    connection: &mut PgConnection,
    account_id: Uuid,
    phone: &str,
    name: Option<&str>,
) -> Result<Option<SavedRecipient>, sqlx::Error> {
    sqlx::query_as(
        "INSERT INTO sms_recipients (account_id, phone, name)
         VALUES ($1, $2, $3)
         ON CONFLICT (account_id, phone) DO NOTHING
         RETURNING id::text, phone, name",
    )
    .bind(account_id)
    .bind(phone)
    .bind(name)
    .fetch_optional(connection)
    .await
}

pub(super) async fn find_recipient(
    connection: &mut PgConnection,
    account_id: Uuid,
    phone: &str,
) -> Result<SavedRecipient, sqlx::Error> {
    sqlx::query_as(
        "SELECT id::text, phone, name FROM sms_recipients
         WHERE account_id = $1 AND phone = $2",
    )
    .bind(account_id)
    .bind(phone)
    .fetch_one(connection)
    .await
}

pub(super) async fn update_recipient(
    connection: &mut PgConnection,
    account_id: Uuid,
    recipient_id: Uuid,
    phone: Option<&str>,
    name: Option<Option<&str>>,
) -> Result<Option<SavedRecipient>, sqlx::Error> {
    sqlx::query_as(
        "UPDATE sms_recipients
         SET phone = COALESCE($3, phone),
             name = CASE WHEN $4 THEN $5 ELSE name END
         WHERE account_id = $1 AND id = $2
         RETURNING id::text, phone, name",
    )
    .bind(account_id)
    .bind(recipient_id)
    .bind(phone)
    .bind(name.is_some())
    .bind(name.flatten())
    .fetch_optional(connection)
    .await
}

pub(super) async fn delete_recipient(
    connection: &mut PgConnection,
    account_id: Uuid,
    recipient_id: Uuid,
) -> Result<bool, sqlx::Error> {
    let result = sqlx::query("DELETE FROM sms_recipients WHERE account_id = $1 AND id = $2")
        .bind(account_id)
        .bind(recipient_id)
        .execute(connection)
        .await?;
    Ok(result.rows_affected() != 0)
}
