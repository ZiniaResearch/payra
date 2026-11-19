use sqlx::{FromRow, PgPool, Row, postgres::PgRow, types::Uuid};

pub(super) struct AccountProfile {
    pub(super) email: String,
    pub(super) name: String,
    pub(super) avatar_url: String,
    pub(super) cover_url: String,
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

pub(super) async fn update_account_name(
    pool: &PgPool,
    account_id: Uuid,
    name: &str,
) -> Result<Option<AccountProfile>, sqlx::Error> {
    sqlx::query_as(
        "UPDATE accounts
         SET name = $2
         WHERE id = $1
         RETURNING email, name, avatar_url, cover_url",
    )
    .bind(account_id)
    .bind(name)
    .fetch_optional(pool)
    .await
}
