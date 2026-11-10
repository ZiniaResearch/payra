use crate::health;
use axum::Router;
use sqlx::PgPool;

pub(crate) fn router(conn_pool: PgPool) -> Router {
    Router::new().merge(health::routes()).with_state(conn_pool)
}
