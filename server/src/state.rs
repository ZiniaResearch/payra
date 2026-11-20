use axum::{extract::FromRef, http::HeaderValue};
use reqwest::Url;
use sqlx::PgPool;
use std::sync::Arc;

use crate::modules::{
    auth::{GoogleVerifier, TokenSigner},
    profile::AvatarStorage,
};

#[derive(Clone)]
pub(crate) struct AppState {
    pub(crate) pool: PgPool,
    pub(crate) google: GoogleVerifier,
    pub(crate) ui_origin: HeaderValue,
    pub(crate) token_signer: TokenSigner,
    pub(crate) api_origin: Url,
    pub(crate) media_base_url: Url,
    pub(crate) avatar_storage: Option<Arc<AvatarStorage>>,
}

impl FromRef<AppState> for PgPool {
    fn from_ref(state: &AppState) -> Self {
        state.pool.clone()
    }
}
