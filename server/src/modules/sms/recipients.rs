use axum::{
    Extension, Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Deserializer, Serialize};
use sqlx::PgPool;
use uuid::Uuid;

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

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct UpdateRecipientRequest {
    #[serde(default, deserialize_with = "deserialize_present_field")]
    phone: Option<String>,
    #[serde(default, deserialize_with = "deserialize_present_field")]
    name: Option<Option<String>>,
}

// A missing field uses the default. A present name can explicitly contain null.
fn deserialize_present_field<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de>,
{
    T::deserialize(deserializer).map(Some)
}

pub(super) enum RecipientRejection {
    InvalidId,
    InvalidPhone,
    InvalidName,
    EmptyUpdate,
    NotFound,
    PhoneAlreadySaved,
    InactiveSession,
    Unavailable,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

impl From<sqlx::Error> for RecipientRejection {
    fn from(error: sqlx::Error) -> Self {
        if error
            .as_database_error()
            .and_then(|error| error.constraint())
            == Some("sms_recipients_account_phone_key")
        {
            Self::PhoneAlreadySaved
        } else {
            Self::Unavailable
        }
    }
}

impl IntoResponse for RecipientRejection {
    fn into_response(self) -> Response {
        let (status, error) = match self {
            Self::InvalidId => (StatusCode::BAD_REQUEST, "invalid_recipient_id"),
            Self::InvalidPhone => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_recipient"),
            Self::InvalidName => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_recipient_name"),
            Self::EmptyUpdate => (StatusCode::UNPROCESSABLE_ENTITY, "empty_recipient_update"),
            Self::NotFound => (StatusCode::NOT_FOUND, "recipient_not_found"),
            Self::PhoneAlreadySaved => (StatusCode::CONFLICT, "recipient_phone_already_saved"),
            Self::InactiveSession => (StatusCode::UNAUTHORIZED, "sms_session_inactive"),
            Self::Unavailable => (
                StatusCode::SERVICE_UNAVAILABLE,
                "sms_recipients_unavailable",
            ),
        };
        (status, Json(ErrorResponse { error })).into_response()
    }
}

pub(super) async fn list(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
) -> Result<Json<Vec<SavedRecipient>>, RecipientRejection> {
    let recipients = repository::list_recipients(&pool, identity.account_id()).await?;
    Ok(Json(recipients))
}

pub(super) async fn save(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
    Json(request): Json<SaveRecipientRequest>,
) -> Result<(StatusCode, Json<SavedRecipient>), RecipientRejection> {
    let phone = normalize_phone(&request.phone).ok_or(RecipientRejection::InvalidPhone)?;
    let name = normalize_name(request.name.as_deref())?;

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

pub(super) async fn update(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
    Path(recipient_id): Path<String>,
    Json(request): Json<UpdateRecipientRequest>,
) -> Result<Json<SavedRecipient>, RecipientRejection> {
    let recipient_id = Uuid::parse_str(&recipient_id).map_err(|_| RecipientRejection::InvalidId)?;
    if request.phone.is_none() && request.name.is_none() {
        return Err(RecipientRejection::EmptyUpdate);
    }
    let phone = request
        .phone
        .as_deref()
        .map(|phone| normalize_phone(phone).ok_or(RecipientRejection::InvalidPhone))
        .transpose()?;
    let name = request
        .name
        .as_ref()
        .map(|name| normalize_name(name.as_deref()))
        .transpose()?;

    let mut tx = pool.begin().await?;
    if !repository::lock_active_session(&mut tx, identity).await? {
        return Err(RecipientRejection::InactiveSession);
    }

    let recipient = repository::update_recipient(
        &mut tx,
        identity.account_id(),
        recipient_id,
        phone.as_deref(),
        name,
    )
    .await?
    .ok_or(RecipientRejection::NotFound)?;
    tx.commit().await?;

    Ok(Json(recipient))
}

fn normalize_name(name: Option<&str>) -> Result<Option<&str>, RecipientRejection> {
    let name = name.map(str::trim).filter(|name| !name.is_empty());
    if name.is_some_and(|name| name.contains('\0')) {
        return Err(RecipientRejection::InvalidName);
    }
    Ok(name)
}
