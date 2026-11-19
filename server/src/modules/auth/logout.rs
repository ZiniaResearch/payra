use super::{cookies, proof, repository};
use crate::state::AppState;
use axum::{
    Json,
    extract::State,
    http::{HeaderMap, HeaderValue, StatusCode, header::CACHE_CONTROL},
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
    fn into_response(self) -> Response {
        let (status, error) = match self {
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

pub(super) async fn logout(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<LogoutRequest>,
) -> Result<Response, LogoutRejection> {
    let refresh_token = cookies::read_cookie(&headers, cookies::REFRESH_COOKIE_NAME)
        .map_err(|_| LogoutRejection::InvalidToken)?;
    let Some(refresh_token) = refresh_token else {
        return Ok(signed_out_response());
    };
    let identity = match state.token_signer.verify_refresh(refresh_token) {
        Ok(identity) => identity,
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

fn signed_out_response() -> Response {
    let mut headers = cookies::clear_session_cookie_headers();
    headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    (StatusCode::NO_CONTENT, headers).into_response()
}
