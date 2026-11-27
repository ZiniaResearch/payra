use crate::{
    modules::{auth, health, profile, sms},
    request_origin,
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
        .allow_methods([
            Method::GET,
            Method::HEAD,
            Method::POST,
            Method::PATCH,
            Method::PUT,
            Method::DELETE,
        ])
        .allow_headers([CONTENT_TYPE, HeaderName::from_static("x-requested-with")])
        .allow_credentials(true);

    let origin_protection = middleware::from_fn_with_state(
        state.ui_origin.clone(),
        request_origin::require_allowed_origin,
    );

    let authentication =
        middleware::from_fn_with_state(state.token_signer.clone(), auth::require_access);
    let protected = Router::new()
        .merge(profile::routes())
        .merge(sms::routes())
        .route_layer(authentication);

    Router::new()
        .merge(health::routes())
        .merge(auth::routes())
        .merge(protected)
        .layer(origin_protection)
        .layer(cors)
        .with_state(state)
}
