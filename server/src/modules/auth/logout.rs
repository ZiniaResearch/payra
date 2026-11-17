//! Ends one browser login without rotating tokens or checking access credentials.
//!
//! A live session is deleted only after checking its refresh JWT and a fresh,
//! logout-specific browser proof. Cookie removal follows the database commit.

use super::{cookies, proof, repository};
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
use jsonwebtoken::errors::ErrorKind;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct LogoutRequest {
    proof: String,
}

pub(super) enum LogoutRejection {
    Forbidden,
    InvalidToken,
    InvalidProof,
    FutureCounter,
    Unavailable,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

impl IntoResponse for LogoutRejection {
    /// Failed verification must not clear legitimate cookies or delete a session.
    fn into_response(self) -> Response {
        let (status, error) = match self {
            Self::Forbidden => (StatusCode::FORBIDDEN, "logout_forbidden"),
            Self::InvalidToken => (StatusCode::UNAUTHORIZED, "logout_invalid"),
            Self::InvalidProof => (StatusCode::UNAUTHORIZED, "logout_proof_invalid"),
            Self::FutureCounter => (StatusCode::UNAUTHORIZED, "logout_counter_invalid"),
            Self::Unavailable => (StatusCode::SERVICE_UNAVAILABLE, "logout_unavailable"),
        };
        (
            status,
            [(CACHE_CONTROL, "no-store")],
            Json(ErrorResponse { error }),
        )
            .into_response()
    }
}

/// Signs out an already-ended login or deletes a session after proof verification.
///
/// Missing/expired refresh cookies only clear local cookies; no row is targeted.
/// A valid cookie naming an already-deleted row also succeeds, so a lost response
/// can be retried without rotating a token or resurrecting the login.
pub(super) async fn logout(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<LogoutRequest>,
) -> Result<Response, LogoutRejection> {
    if headers.get(ORIGIN) != Some(&state.ui_origin)
        || headers
            .get("x-requested-with")
            .and_then(|value| value.to_str().ok())
            != Some("XmlHttpRequest")
    {
        return Err(LogoutRejection::Forbidden);
    }

    let refresh_token = cookies::read_cookie(&headers, cookies::REFRESH_COOKIE_NAME)
        .map_err(|_| LogoutRejection::InvalidToken)?;
    let Some(refresh_token) = refresh_token else {
        return Ok(signed_out_response());
    };
    let identity = match state.token_signer.verify_refresh(refresh_token) {
        Ok(identity) => identity,
        // No refresh authority remains in this cookie. Clear the browser state,
        // but do not claim to have deleted a row using expired credentials.
        Err(error) if matches!(error.kind(), ErrorKind::ExpiredSignature) => {
            return Ok(signed_out_response());
        }
        Err(_) => return Err(LogoutRejection::InvalidToken),
    };

    let mut transaction = state
        .pool
        .begin()
        .await
        .map_err(|_| LogoutRejection::Unavailable)?;
    let session = repository::lock_session(&mut transaction, identity.session_id)
        .await
        .map_err(|_| LogoutRejection::Unavailable)?;

    if let Some(session) = session {
        if !proof::verify_browser_proof(
            &request.proof,
            &session.browser_public_key,
            refresh_token,
            session.id,
            &state.logout_url,
            proof::ProofPurpose::Logout,
        ) {
            return Err(LogoutRejection::InvalidProof);
        }
        if identity.counter > session.refresh_counter {
            return Err(LogoutRejection::FutureCounter);
        }

        // Even an older valid token plus its matching logout proof authorizes
        // ending this login. Unlike refresh, logout never increments the counter.
        repository::delete_session(&mut transaction, session.id)
            .await
            .map_err(|_| LogoutRejection::Unavailable)?;
    }

    transaction
        .commit()
        .await
        .map_err(|_| LogoutRejection::Unavailable)?;
    Ok(signed_out_response())
}

/// Clears both host-only cookies using the exact original paths and flags.
fn signed_out_response() -> Response {
    let mut headers = cookies::clear_session_cookie_headers();
    headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    (StatusCode::NO_CONTENT, headers).into_response()
}
