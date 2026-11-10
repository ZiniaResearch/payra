use sqlx::{PgPool, postgres::PgPoolOptions};

pub(crate) fn create_pool(database_url: &str) -> Result<PgPool, sqlx::Error> {
    PgPoolOptions::new()
        .max_connections(5)
        .connect_lazy(database_url)
}
