use super::repository::{self, AccountProfile};
use crate::{modules::auth::AccessIdentity, state::AppState};
use axum::{
    Extension, Json, Router,
    extract::{DefaultBodyLimit, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, types::Uuid};

const MAX_NAME_LENGTH: usize = 80;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct UpdateNameRequest {
    name: String,
}

#[derive(Serialize)]
struct ProfileResponse {
    account_id: String,
    email: String,
    name: String,
    avatar_url: String,
    cover_url: String,
}

enum ProfileRejection {
    InvalidName,
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
            Self::AccountMissing => (StatusCode::UNAUTHORIZED, "account_missing"),
            Self::Unavailable => (StatusCode::SERVICE_UNAVAILABLE, "profile_unavailable"),
        };

        (status, Json(ErrorResponse { error })).into_response()
    }
}
pub(crate) fn routes() -> Router<AppState> {
    Router::new().route(
        "/api/profile",
        get(profile)
            .patch(update_name)
            .layer(DefaultBodyLimit::max(4096)),
    )
}

async fn profile(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
) -> Result<Json<ProfileResponse>, ProfileRejection> {
    let account_id = identity.account_id();

    let account = repository::find_account_profile(&pool, account_id)
        .await
        .map_err(|_| ProfileRejection::Unavailable)?
        .ok_or(ProfileRejection::AccountMissing)?;

    Ok(profile_response(account_id, account))
}

async fn update_name(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
    Json(request): Json<UpdateNameRequest>,
) -> Result<Json<ProfileResponse>, ProfileRejection> {
    let name = parse_name(&request.name)?;
    let account_id = identity.account_id();

    let account = repository::update_account_name(&pool, account_id, name)
        .await
        .map_err(|_| ProfileRejection::Unavailable)?
        .ok_or(ProfileRejection::AccountMissing)?;

    Ok(profile_response(account_id, account))
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

fn profile_response(account_id: Uuid, account: AccountProfile) -> Json<ProfileResponse> {
    Json(ProfileResponse {
        account_id: account_id.to_string(),
        email: account.email,
        name: account.name,
        avatar_url: account.avatar_url,
        cover_url: account.cover_url,
    })
}
