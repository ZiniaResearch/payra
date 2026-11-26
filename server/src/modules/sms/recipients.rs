use axum::{
    Extension, Json,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;

use super::{
    phone::normalize_phone,
    repository::{self, SavedRecipient},
};
use crate::modules::auth::AccessIdentity;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct SaveRecipientRequest {
    phone: String,
    name: Option<String>,
}

pub(super) enum RecipientRejection {
    InvalidPhone,
    InvalidName,
    InactiveSession,
    Unavailable,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

impl From<sqlx::Error> for RecipientRejection {
    fn from(_: sqlx::Error) -> Self {
        Self::Unavailable
    }
}

impl IntoResponse for RecipientRejection {
    fn into_response(self) -> Response {
        let (status, error) = match self {
            Self::InvalidPhone => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_recipient"),
            Self::InvalidName => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_recipient_name"),
            Self::InactiveSession => (StatusCode::UNAUTHORIZED, "sms_session_inactive"),
            Self::Unavailable => (
                StatusCode::SERVICE_UNAVAILABLE,
                "sms_recipients_unavailable",
            ),
        };
        (status, Json(ErrorResponse { error })).into_response()
    }
}

pub(super) async fn save(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
    Json(request): Json<SaveRecipientRequest>,
) -> Result<(StatusCode, Json<SavedRecipient>), RecipientRejection> {
    let phone = normalize_phone(&request.phone).ok_or(RecipientRejection::InvalidPhone)?;
    let name = request
        .name
        .as_deref()
        .map(str::trim)
        .filter(|name| !name.is_empty());
    if name.is_some_and(|name| name.contains('\0')) {
        return Err(RecipientRejection::InvalidName);
    }

    let mut tx = pool.begin().await?;
    if !repository::lock_active_session(&mut tx, identity).await? {
        return Err(RecipientRejection::InactiveSession);
    }

    let account_id = identity.account_id();
    let (status, recipient) =
        match repository::insert_recipient(&mut tx, account_id, &phone, name).await? {
            Some(recipient) => (StatusCode::CREATED, recipient),
            None => (
                StatusCode::OK,
                repository::find_recipient(&mut tx, account_id, &phone).await?,
            ),
        };
    tx.commit().await?;

    Ok((status, Json(recipient)))
}
