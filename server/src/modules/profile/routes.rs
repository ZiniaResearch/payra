use super::repository;
use crate::{modules::auth::AccessIdentity, state::AppState};
use axum::{
    Extension, Json, Router,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;
use sqlx::PgPool;

#[derive(Serialize)]
struct ProfileResponse {
    account_id: String,
    email: String,
}

enum ProfileRejection {
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
            Self::AccountMissing => (StatusCode::UNAUTHORIZED, "account_missing"),
            Self::Unavailable => (StatusCode::SERVICE_UNAVAILABLE, "profile_unavailable"),
        };

        (status, Json(ErrorResponse { error })).into_response()
    }
}

pub(crate) fn routes() -> Router<AppState> {
    Router::new().route("/api/profile", get(profile))
}

async fn profile(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
) -> Result<Json<ProfileResponse>, ProfileRejection> {
    let account_id = identity.account_id();
    let email = repository::find_account_email(&pool, account_id)
        .await
        .map_err(|_| ProfileRejection::Unavailable)?
        .ok_or(ProfileRejection::AccountMissing)?;

    Ok(Json(ProfileResponse {
        account_id: account_id.to_string(),
        email,
    }))
}
