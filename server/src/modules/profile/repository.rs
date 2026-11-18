use sqlx::{PgPool, Row, types::Uuid};

pub(super) struct AccountProfile {
    pub(super) email: String,
    pub(super) name: String,
    pub(super) avatar_url: String,
    pub(super) cover_url: String,
}

pub(super) async fn find_account_profile(
    pool: &PgPool,
    account_id: Uuid,
) -> Result<Option<AccountProfile>, sqlx::Error> {
    let row = sqlx::query(
        "SELECT email, name, avatar_url, cover_url
         FROM accounts
         WHERE id = $1",
    )
    .bind(account_id)
    .fetch_optional(pool)
    .await?;

    let Some(row) = row else {
        return Ok(None);
    };

    Ok(Some(AccountProfile {
        email: row.try_get("email")?,
        name: row.try_get("name")?,
        avatar_url: row.try_get("avatar_url")?,
        cover_url: row.try_get("cover_url")?,
    }))
}
