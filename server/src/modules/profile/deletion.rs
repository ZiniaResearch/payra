use axum::{
    Extension, Json,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};
use sqlx::types::Uuid;

use super::repository;
use crate::{
    modules::auth::{
        AccessIdentity, ProofPurpose, clear_session_cookie_headers, verify_browser_proof,
    },
    state::AppState,
};

#[derive(Serialize)]
pub(super) struct ChallengeResponse {
    account_id: String,
    nonce: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct DeleteRequest {
    nonce: String,
    proof: String,
}

#[derive(Serialize)]
struct DeleteResponse {
    session_id: String,
}

pub(super) enum DeletionRejection {
    InactiveSession,
    InvalidChallenge,
    InvalidProof,
    Unavailable,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

impl IntoResponse for DeletionRejection {
    fn into_response(self) -> Response {
        let (status, error) = match self {
            Self::InactiveSession => (StatusCode::CONFLICT, "deletion_session_inactive"),
            Self::InvalidChallenge => (StatusCode::CONFLICT, "deletion_challenge_invalid"),
            Self::InvalidProof => (StatusCode::FORBIDDEN, "deletion_proof_invalid"),
            Self::Unavailable => (StatusCode::SERVICE_UNAVAILABLE, "deletion_unavailable"),
        };
        (status, Json(ErrorResponse { error })).into_response()
    }
}

pub(super) async fn prepare(
    State(state): State<AppState>,
    Extension(identity): Extension<AccessIdentity>,
) -> Result<Json<ChallengeResponse>, DeletionRejection> {
    let nonce =
        repository::prepare_deletion(&state.pool, identity.account_id(), identity.session_id())
            .await
            .map_err(unavailable)?
            .ok_or(DeletionRejection::InactiveSession)?;

    Ok(Json(ChallengeResponse {
        account_id: identity.account_id().to_string(),
        nonce: nonce.to_string(),
    }))
}

pub(super) async fn delete_account(
    State(state): State<AppState>,
    Extension(identity): Extension<AccessIdentity>,
    Json(request): Json<DeleteRequest>,
) -> Result<Response, DeletionRejection> {
    let nonce = Uuid::parse_str(&request.nonce).map_err(|_| DeletionRejection::InvalidChallenge)?;

    let account_id = identity.account_id();
    let session_id = identity.session_id();
    let mut tx = state.pool.begin().await.map_err(unavailable)?;
    // Match the account-first lock order used for avatar publication.
    let account = repository::lock_account(&mut tx, account_id)
        .await
        .map_err(unavailable)?
        .ok_or(DeletionRejection::InactiveSession)?;
    let public_key = repository::lock_active_session(&mut tx, account_id, session_id)
        .await
        .map_err(unavailable)?
        .ok_or(DeletionRejection::InactiveSession)?;

    if !repository::lock_deletion_challenge(&mut tx, session_id, nonce)
        .await
        .map_err(unavailable)?
    {
        return Err(DeletionRejection::InvalidChallenge);
    }

    if !verify_browser_proof(
        &request.proof,
        &public_key,
        &nonce.to_string(),
        session_id,
        &state.api_origin,
        ProofPurpose::DeleteProfile,
    ) {
        return Err(DeletionRejection::InvalidProof);
    }
    repository::delete_account(&mut tx, account_id)
        .await
        .map_err(unavailable)?;
    tx.commit().await.map_err(unavailable)?;
    if let (Some(storage), Some(key)) = (&state.avatar_storage, account.avatar_object_key) {
        storage.delete_in_background(key);
    }

    Ok((
        clear_session_cookie_headers(),
        Json(DeleteResponse {
            session_id: session_id.to_string(),
        }),
    )
        .into_response())
}

fn unavailable(_: sqlx::Error) -> DeletionRejection {
    DeletionRejection::Unavailable
}
