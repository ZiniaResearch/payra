use axum::{extract::FromRef, http::HeaderValue};
use sqlx::PgPool;

use crate::modules::auth::{GoogleVerifier, TokenSigner};

#[derive(Clone)]
pub(crate) struct AppState {
    pub(crate) pool: PgPool,
    pub(crate) google: GoogleVerifier,
    pub(crate) ui_origin: HeaderValue,
    pub(crate) token_signer: TokenSigner,
    pub(crate) refresh_url: String,
    pub(crate) logout_url: String,
}

impl FromRef<AppState> for PgPool {
    fn from_ref(state: &AppState) -> Self {
        state.pool.clone()
    }
}
