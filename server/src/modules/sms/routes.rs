use std::collections::HashSet;

use axum::{
    Extension, Json, Router,
    extract::{DefaultBodyLimit, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, patch, post},
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use uuid::Uuid;

use super::{encoding, recipients, repository};
use crate::{modules::auth::AccessIdentity, state::AppState};

const MAX_SMS_PARTS: usize = 5;
const MAX_SEND_TIMES: usize = 5;
const MIN_LEAD_MINUTES: i64 = 10;
const MAX_REQUEST_BYTES: usize = 16 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ScheduleRequest {
    message: String,
    recipient_ids: Vec<String>,
    send_times: Vec<DateTime<Utc>>,
}

#[derive(Serialize)]
struct ScheduleResponse {
    job_ids: Vec<String>,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

enum ScheduleRejection {
    Invalid(&'static str),
    InactiveSession,
    Unavailable,
    Unconfirmed,
}

impl From<sqlx::Error> for ScheduleRejection {
    fn from(_: sqlx::Error) -> Self {
        Self::Unavailable
    }
}

impl IntoResponse for ScheduleRejection {
    fn into_response(self) -> Response {
        let (status, error) = match self {
            Self::Invalid(error) => (StatusCode::UNPROCESSABLE_ENTITY, error),
            Self::InactiveSession => (StatusCode::UNAUTHORIZED, "sms_session_inactive"),
            Self::Unavailable => (
                StatusCode::SERVICE_UNAVAILABLE,
                "sms_scheduling_unavailable",
            ),
            Self::Unconfirmed => (StatusCode::SERVICE_UNAVAILABLE, "sms_schedule_unconfirmed"),
        };
        (status, Json(ErrorResponse { error })).into_response()
    }
}

pub(crate) fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/api/sms/jobs",
            post(schedule).layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES)),
        )
        .route(
            "/api/sms/recipients",
            get(recipients::list)
                .post(recipients::save)
                .layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES)),
        )
        .route(
            "/api/sms/recipients/{recipient_id}",
            patch(recipients::update)
                .delete(recipients::delete)
                .layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES)),
        )
}

async fn schedule(
    State(pool): State<PgPool>,
    Extension(identity): Extension<AccessIdentity>,
    Json(request): Json<ScheduleRequest>,
) -> Result<(StatusCode, Json<ScheduleResponse>), ScheduleRejection> {
    if request.message.trim().is_empty() || request.message.contains('\0') {
        return Err(ScheduleRejection::Invalid("invalid_message"));
    }
    if encoding::part_count(&request.message) > MAX_SMS_PARTS {
        return Err(ScheduleRejection::Invalid("message_too_long"));
    }
    let recipient_ids = parse_recipient_ids(&request.recipient_ids)?;
    validate_send_times(&request.send_times)?;

    let mut tx = pool.begin().await?;
    if !repository::lock_active_session(&mut tx, identity).await? {
        return Err(ScheduleRejection::InactiveSession);
    }

    // Check after any lock wait so the minute-based cutoff is still current.
    let earliest_minute = Utc::now().timestamp().div_euclid(60) + MIN_LEAD_MINUTES;
    if request
        .send_times
        .iter()
        .any(|time| time.timestamp().div_euclid(60) < earliest_minute)
    {
        return Err(ScheduleRejection::Invalid("send_time_too_soon"));
    }

    let job_ids = repository::insert_jobs(
        &mut tx,
        identity.account_id(),
        &recipient_ids,
        &request.message,
        &request.send_times,
    )
    .await?;
    if job_ids.is_empty() {
        return Err(ScheduleRejection::Invalid("invalid_recipient"));
    }
    repository::notify_jobs_changed(&mut tx).await?;
    // A commit error may mean PostgreSQL saved the jobs but its reply was lost.
    tx.commit()
        .await
        .map_err(|_| ScheduleRejection::Unconfirmed)?;

    Ok((StatusCode::CREATED, Json(ScheduleResponse { job_ids })))
}

fn validate_send_times(send_times: &[DateTime<Utc>]) -> Result<(), ScheduleRejection> {
    if !(1..=MAX_SEND_TIMES).contains(&send_times.len()) {
        return Err(ScheduleRejection::Invalid("invalid_send_time_count"));
    }

    let mut seen = HashSet::with_capacity(send_times.len());
    if send_times
        .iter()
        .any(|time| !seen.insert(time.timestamp().div_euclid(60)))
    {
        return Err(ScheduleRejection::Invalid("duplicate_send_time"));
    }

    Ok(())
}

fn parse_recipient_ids(input: &[String]) -> Result<Vec<Uuid>, ScheduleRejection> {
    if input.is_empty() {
        return Err(ScheduleRejection::Invalid("recipients_required"));
    }

    let mut ids = Vec::with_capacity(input.len());
    let mut seen = HashSet::with_capacity(input.len());
    for value in input {
        let id =
            Uuid::parse_str(value).map_err(|_| ScheduleRejection::Invalid("invalid_recipient"))?;
        if !seen.insert(id) {
            return Err(ScheduleRejection::Invalid("duplicate_recipient"));
        }
        ids.push(id);
    }

    Ok(ids)
}
