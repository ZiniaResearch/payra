use axum::{extract::FromRef, http::HeaderValue};
use reqwest::Url;
use sqlx::PgPool;

use crate::modules::auth::{GoogleVerifier, TokenSigner};

#[derive(Clone)]
pub(crate) struct AppState {
    pub(crate) pool: PgPool,
    pub(crate) google: GoogleVerifier,
    pub(crate) ui_origin: HeaderValue,
    pub(crate) token_signer: TokenSigner,
    pub(crate) api_origin: Url,
    pub(crate) media_base_url: Url,
}

impl FromRef<AppState> for PgPool {
    fn from_ref(state: &AppState) -> Self {
        state.pool.clone()
    }
}
