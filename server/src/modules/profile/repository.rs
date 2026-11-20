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
