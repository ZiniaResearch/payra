use sqlx::{PgPool, types::Uuid};

pub(super) async fn find_account_email(
    pool: &PgPool,
    account_id: Uuid,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT email FROM accounts WHERE id = $1")
        .bind(account_id)
        .fetch_optional(pool)
        .await
}
