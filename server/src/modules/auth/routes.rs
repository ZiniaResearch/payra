use super::{browser_key, cookies, google::VerifyError, logout, refresh, repository};
use crate::state::AppState;
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::{
        HeaderMap, HeaderValue, StatusCode,
        header::{CACHE_CONTROL, ORIGIN},
    },
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Deserialize)]
struct GoogleLoginRequest {
    code: String,
    browser_public_key: Value,
}

#[derive(Serialize)]
pub(super) struct SessionResponse {
    pub(super) session_id: String,
    pub(super) refresh_token_hash: String,
}

pub(crate) fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/auth/google", post(google_login))
        .route(
            "/api/auth/refresh",
            post(refresh::refresh).layer(DefaultBodyLimit::max(4096)),
        )
        .route(
            "/api/auth/logout",
            post(logout::logout).layer(DefaultBodyLimit::max(4096)),
        )
}

async fn google_login(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<GoogleLoginRequest>,
) -> Result<Response, StatusCode> {
    if headers.get(ORIGIN) != Some(&state.ui_origin)
        || headers
            .get("x-requested-with")
            .and_then(|value| value.to_str().ok())
            != Some("XmlHttpRequest")
    {
        return Err(StatusCode::FORBIDDEN);
    }

    if request.code.trim().is_empty() {
        return Err(StatusCode::BAD_REQUEST);
    }

    let public_key = match browser_key::validate_public_key(&request.browser_public_key) {
        Ok(public_key) => public_key,
        Err(_) => return Err(StatusCode::BAD_REQUEST),
    };

    let identity = match state.google.verify_code(&request.code).await {
        Ok(identity) => identity,
        Err(VerifyError::InvalidToken | VerifyError::InvalidCode) => {
            return Err(StatusCode::UNAUTHORIZED);
        }
        Err(VerifyError::KeysUnavailable | VerifyError::ExchangeUnavailable) => {
            return Err(StatusCode::SERVICE_UNAVAILABLE);
        }
    };
    let ui_origin = state
        .ui_origin
        .to_str()
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .trim_end_matches('/');

    let default_avatar_url = format!("{ui_origin}/profile/default-profile.webp");

    let cover_urls = [1, 2, 3].map(|number| format!("{ui_origin}/profile/cover-{number}.webp"));

    let name = identity
        .name
        .as_deref()
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .unwrap_or("Account");

    let avatar_url = identity
        .picture
        .as_deref()
        .map(str::trim)
        .filter(|picture| {
            reqwest::Url::parse(picture).is_ok_and(|url| {
                url.scheme() == "https"
                    && url.host_str().is_some()
                    && url.username().is_empty()
                    && url.password().is_none()
            })
        })
        .unwrap_or(default_avatar_url.as_str());

    let account_id = match repository::find_or_create_google_account(
        &state.pool,
        &identity.sub,
        &identity.email,
        name,
        avatar_url,
        &cover_urls,
    )
    .await
    {
        Ok(account_id) => account_id,
        Err(_) => return Err(StatusCode::INTERNAL_SERVER_ERROR),
    };

    let session = match repository::create_session(&state.pool, account_id, &public_key).await {
        Ok(session) => session,
        Err(_) => return Err(StatusCode::INTERNAL_SERVER_ERROR),
    };

    let tokens = match state.token_signer.issue(
        account_id,
        session.id,
        session.refresh_counter,
        session.idle_expires_at,
        session.absolute_expires_at,
    ) {
        Ok(tokens) => tokens,
        Err(_) => return Err(StatusCode::INTERNAL_SERVER_ERROR),
    };

    let mut response_headers =
        cookies::session_cookie_headers(&tokens).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    response_headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));

    let body = SessionResponse {
        session_id: session.id.to_string(),
        refresh_token_hash: tokens.refresh_token_hash,
    };

    Ok((response_headers, Json(body)).into_response())
}
