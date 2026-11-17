use super::{cookies, proof, repository, routes::SessionResponse};
use crate::state::AppState;
use axum::{
    Json,
    extract::State,
    http::{
        HeaderMap, HeaderValue, StatusCode,
        header::{CACHE_CONTROL, ORIGIN},
    },
    response::{IntoResponse, Response},
};
use chrono::Utc;
use jsonwebtoken::errors::ErrorKind;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct RefreshRequest {
    proof: String,
}

pub(super) enum RefreshRejection {
    Forbidden,
    MissingToken,
    InvalidToken,
    ExpiredToken,
    MissingSession,
    ExpiredSession,
    InvalidProof,
    ReusedToken,
    FutureCounter,
    Unavailable,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

impl IntoResponse for RefreshRejection {
    fn into_response(self) -> Response {
        let (status, error, clear_cookies) = match self {
            Self::Forbidden => (StatusCode::FORBIDDEN, "refresh_forbidden", false),
            Self::MissingToken => (StatusCode::UNAUTHORIZED, "refresh_missing", false),
            Self::InvalidToken => (StatusCode::UNAUTHORIZED, "refresh_invalid", false),
            Self::ExpiredToken => (StatusCode::UNAUTHORIZED, "refresh_expired", true),
            Self::MissingSession => (StatusCode::UNAUTHORIZED, "session_missing", true),
            Self::ExpiredSession => (StatusCode::UNAUTHORIZED, "session_expired", true),
            Self::InvalidProof => (StatusCode::UNAUTHORIZED, "proof_invalid", false),
            Self::ReusedToken => (StatusCode::UNAUTHORIZED, "refresh_reused", true),
            Self::FutureCounter => (StatusCode::UNAUTHORIZED, "refresh_counter_invalid", false),
            Self::Unavailable => (
                StatusCode::SERVICE_UNAVAILABLE,
                "refresh_unavailable",
                false,
            ),
        };

        // Invalid proofs alone do not clear a legitimate browser's cookies.
        let mut headers = if clear_cookies {
            cookies::clear_session_cookie_headers()
        } else {
            HeaderMap::new()
        };
        headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
        (status, headers, Json(ErrorResponse { error })).into_response()
    }
}

pub(super) async fn refresh(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<RefreshRequest>,
) -> Result<Response, RefreshRejection> {
    if headers.get(ORIGIN) != Some(&state.ui_origin)
        || headers
            .get("x-requested-with")
            .and_then(|value| value.to_str().ok())
            != Some("XmlHttpRequest")
    {
        return Err(RefreshRejection::Forbidden);
    }

    let refresh_token = cookies::read_cookie(&headers, cookies::REFRESH_COOKIE_NAME)
        .map_err(|_| RefreshRejection::InvalidToken)?
        .ok_or(RefreshRejection::MissingToken)?;
    let identity = state
        .token_signer
        .verify_refresh(refresh_token)
        .map_err(|error| {
            if matches!(error.kind(), ErrorKind::ExpiredSignature) {
                RefreshRejection::ExpiredToken
            } else {
                RefreshRejection::InvalidToken
            }
        })?;

    let mut transaction = state
        .pool
        .begin()
        .await
        .map_err(|_| RefreshRejection::Unavailable)?;
    let session = repository::lock_session(&mut transaction, identity.session_id)
        .await
        .map_err(|_| RefreshRejection::Unavailable)?
        .ok_or(RefreshRejection::MissingSession)?;

    // Check after acquiring the lock, not before waiting for another refresh.
    let now = Utc::now();
    if session.idle_expires_at <= now || session.absolute_expires_at <= now {
        return Err(RefreshRejection::ExpiredSession);
    }

    if !proof::verify_browser_proof(
        &request.proof,
        &session.browser_public_key,
        refresh_token,
        session.id,
        &state.refresh_url,
        proof::ProofPurpose::Refresh,
    ) {
        return Err(RefreshRejection::InvalidProof);
    }

    if identity.counter < session.refresh_counter {
        repository::delete_session(&mut transaction, session.id)
            .await
            .map_err(|_| RefreshRejection::Unavailable)?;
        transaction
            .commit()
            .await
            .map_err(|_| RefreshRejection::Unavailable)?;
        return Err(RefreshRejection::ReusedToken);
    }
    if identity.counter > session.refresh_counter {
        return Err(RefreshRejection::FutureCounter);
    }

    let next_counter = session
        .refresh_counter
        .checked_add(1)
        .ok_or(RefreshRejection::Unavailable)?;
    let rotated = repository::rotate_session(&mut transaction, session.id, next_counter)
        .await
        .map_err(|_| RefreshRejection::Unavailable)?;
    let tokens = state
        .token_signer
        .issue(
            session.account_id,
            rotated.id,
            rotated.refresh_counter,
            rotated.idle_expires_at,
            rotated.absolute_expires_at,
        )
        .map_err(|_| RefreshRejection::Unavailable)?;

    let mut response_headers =
        cookies::session_cookie_headers(&tokens).map_err(|_| RefreshRejection::Unavailable)?;
    response_headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    let body = SessionResponse {
        session_id: rotated.id.to_string(),
        refresh_token_hash: tokens.refresh_token_hash,
    };

    transaction
        .commit()
        .await
        .map_err(|_| RefreshRejection::Unavailable)?;
    Ok((response_headers, Json(body)).into_response())
}
