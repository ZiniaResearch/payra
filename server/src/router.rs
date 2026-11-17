use crate::{
    modules::{auth, health, profile},
    state::AppState,
};
use axum::{
    Router,
    http::{HeaderName, Method, header::CONTENT_TYPE},
    middleware,
};
use tower_http::cors::CorsLayer;

pub(crate) fn build(state: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(state.ui_origin.clone())
        .allow_methods([Method::GET, Method::POST])
        .allow_headers([CONTENT_TYPE, HeaderName::from_static("x-requested-with")])
        .allow_credentials(true);

    let authentication =
        middleware::from_fn_with_state(state.token_signer.clone(), auth::require_access);
    let protected = Router::new()
        .merge(profile::routes())
        .route_layer(authentication);

    Router::new()
        .merge(health::routes())
        .merge(auth::routes())
        .merge(protected)
        .layer(cors)
        .with_state(state)
}
