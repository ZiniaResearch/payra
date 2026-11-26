use std::collections::HashSet;

use axum::{
    Extension, Json, Router,
    extract::{DefaultBodyLimit, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::post,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;

use super::{encoding, phone::normalize_phone, repository};
use crate::{modules::auth::AccessIdentity, state::AppState};

const MAX_SMS_PARTS: usize = 5;
const MAX_SEND_TIMES: usize = 5;
const MIN_LEAD_MINUTES: i64 = 10;
const MAX_REQUEST_BYTES: usize = 16 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ScheduleRequest {
    message: String,
    recipients: Vec<String>,
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
}

impl ScheduleRejection {
    fn database(_: sqlx::Error) -> Self {
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
    let recipients = parse_recipients(&request.recipients)?;
    validate_send_times(&request.send_times)?;

    let mut tx = pool.begin().await.map_err(ScheduleRejection::database)?;
    if !repository::lock_active_session(&mut tx, identity)
        .await
        .map_err(ScheduleRejection::database)?
    {
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
        &recipients,
        &request.message,
        &request.send_times,
    )
    .await
    .map_err(ScheduleRejection::database)?;
    tx.commit().await.map_err(ScheduleRejection::database)?;

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

fn parse_recipients(input: &[String]) -> Result<Vec<String>, ScheduleRejection> {
    if input.is_empty() {
        return Err(ScheduleRejection::Invalid("recipients_required"));
    }

    let recipients = input
        .iter()
        .map(|phone| normalize_phone(phone).ok_or(ScheduleRejection::Invalid("invalid_recipient")))
        .collect::<Result<Vec<_>, _>>()?;

    let mut seen = HashSet::with_capacity(recipients.len());
    if recipients.iter().any(|phone| !seen.insert(phone.as_str())) {
        return Err(ScheduleRejection::Invalid("duplicate_recipient"));
    }

    Ok(recipients)
}
