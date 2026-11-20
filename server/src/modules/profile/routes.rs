use super::{
    avatar, deletion,
    presets::COVER_PRESETS,
    repository::{self, AccountProfile},
};
use crate::{modules::auth::AccessIdentity, state::AppState};
use axum::{
    Extension, Json, Router,
    extract::{DefaultBodyLimit, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use serde::{Deserialize, Serialize};
use sqlx::types::Uuid;

const MAX_NAME_LENGTH: usize = 80;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct UpdateProfileRequest {
    name: Option<String>,
    cover_id: Option<String>,
}

#[derive(Serialize)]
pub(super) struct ProfileResponse {
    account_id: String,
    email: String,
    name: String,
    avatar_url: String,
    cover_url: String,
    cover_id: Option<&'static str>,
    avatar_uploads_enabled: bool,
}

#[derive(Serialize)]
struct CoverResponse {
    id: &'static str,
    label: &'static str,
    url: String,
}

pub(super) enum ProfileRejection {
    InvalidName,
    InvalidCover,
    EmptyUpdate,
    InvalidAvatar,
    AvatarTooLarge,
    AvatarReadTimeout,
    AvatarUploadsDisabled,
    AvatarBusy,
    AccountMissing,
    Unavailable,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

impl IntoResponse for ProfileRejection {
    fn into_response(self) -> Response {
        let (status, error) = match self {
            Self::InvalidName => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_profile_name"),
            Self::InvalidCover => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_cover_id"),
            Self::EmptyUpdate => (StatusCode::UNPROCESSABLE_ENTITY, "empty_profile_update"),
            Self::InvalidAvatar => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_avatar"),
            Self::AvatarTooLarge => (StatusCode::PAYLOAD_TOO_LARGE, "avatar_too_large"),
            Self::AvatarReadTimeout => (StatusCode::REQUEST_TIMEOUT, "avatar_read_timeout"),
            Self::AvatarUploadsDisabled => {
                (StatusCode::SERVICE_UNAVAILABLE, "avatar_uploads_disabled")
            }
            Self::AvatarBusy => (StatusCode::TOO_MANY_REQUESTS, "avatar_upload_busy"),
            Self::AccountMissing => (StatusCode::UNAUTHORIZED, "account_missing"),
            Self::Unavailable => (StatusCode::SERVICE_UNAVAILABLE, "profile_unavailable"),
        };

        (status, Json(ErrorResponse { error })).into_response()
    }
}

pub(crate) fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/api/profile",
            get(profile)
                .patch(update_profile)
                .delete(deletion::delete_account)
                .layer(DefaultBodyLimit::max(8192)),
        )
        .route("/api/profile/covers", get(covers))
        .route(
            "/api/profile/avatar",
            put(avatar::upload).layer(DefaultBodyLimit::max(avatar::MAX_UPLOAD_BYTES)),
        )
        .route("/api/profile/deletion", post(deletion::prepare))
}

async fn covers(State(state): State<AppState>) -> Json<[CoverResponse; COVER_PRESETS.len()]> {
    Json(COVER_PRESETS.map(|preset| CoverResponse {
        id: preset.id,
        label: preset.label,
        url: preset.url(&state.media_base_url),
    }))
}

async fn profile(
    State(state): State<AppState>,
    Extension(identity): Extension<AccessIdentity>,
) -> Result<Json<ProfileResponse>, ProfileRejection> {
    let account_id = identity.account_id();

    let account = repository::find_account_profile(&state.pool, account_id)
        .await
        .map_err(|_| ProfileRejection::Unavailable)?
        .ok_or(ProfileRejection::AccountMissing)?;

    Ok(profile_response(&state, account_id, account))
}

async fn update_profile(
    State(state): State<AppState>,
    Extension(identity): Extension<AccessIdentity>,
    Json(request): Json<UpdateProfileRequest>,
) -> Result<Json<ProfileResponse>, ProfileRejection> {
    let name = request.name.as_deref().map(parse_name).transpose()?;
    let cover = request
        .cover_id
        .as_deref()
        .map(|id| {
            COVER_PRESETS
                .iter()
                .find(|preset| preset.id == id)
                .map(|preset| preset.url(&state.media_base_url))
                .ok_or(ProfileRejection::InvalidCover)
        })
        .transpose()?;

    if name.is_none() && cover.is_none() {
        return Err(ProfileRejection::EmptyUpdate);
    }

    let account_id = identity.account_id();

    let account = repository::update_profile(&state.pool, account_id, name, cover.as_deref())
        .await
        .map_err(|_| ProfileRejection::Unavailable)?
        .ok_or(ProfileRejection::AccountMissing)?;

    Ok(profile_response(&state, account_id, account))
}

fn parse_name(input: &str) -> Result<&str, ProfileRejection> {
    let name = input.trim();

    if name.is_empty()
        || input.chars().any(char::is_control)
        || name.chars().count() > MAX_NAME_LENGTH
    {
        return Err(ProfileRejection::InvalidName);
    }

    Ok(name)
}

pub(super) fn profile_response(
    state: &AppState,
    account_id: Uuid,
    account: AccountProfile,
) -> Json<ProfileResponse> {
    let legacy_origin = state.ui_origin.to_str().ok();
    let cover_id = COVER_PRESETS
        .iter()
        .find(|preset| preset.matches_url(&account.cover_url, &state.media_base_url, legacy_origin))
        .map(|preset| preset.id);

    Json(ProfileResponse {
        account_id: account_id.to_string(),
        email: account.email,
        name: account.name,
        avatar_url: account.avatar_url,
        cover_url: account.cover_url,
        cover_id,
        avatar_uploads_enabled: state.avatar_storage.is_some(),
    })
}
