use super::{
    cookies::{ACCESS_COOKIE_NAME, read_cookie},
    tokens::TokenSigner,
};
use axum::{
    Json,
    extract::{Request, State},
    http::{HeaderValue, StatusCode, header::CACHE_CONTROL},
    middleware::Next,
    response::{IntoResponse, Response},
};
use jsonwebtoken::errors::ErrorKind;
use serde::Serialize;

pub(crate) enum AuthRejection {
    Missing,
    Expired,
    Invalid,
}

#[derive(Serialize)]
struct AuthErrorResponse {
    error: &'static str,
}

impl IntoResponse for AuthRejection {
    fn into_response(self) -> Response {
        let error = match self {
            Self::Missing => "access_missing",
            Self::Expired => "access_expired",
            Self::Invalid => "access_invalid",
        };

        (
            StatusCode::UNAUTHORIZED,
            [(CACHE_CONTROL, "no-store")],
            Json(AuthErrorResponse { error }),
        )
            .into_response()
    }
}

pub(crate) async fn require_access(
    State(token_signer): State<TokenSigner>,
    mut request: Request,
    next: Next,
) -> Result<Response, AuthRejection> {
    let token = read_cookie(request.headers(), ACCESS_COOKIE_NAME)
        .map_err(|_| AuthRejection::Invalid)?
        .ok_or(AuthRejection::Missing)?;
    let identity = token_signer.verify_access(token).map_err(|error| {
        if matches!(error.kind(), ErrorKind::ExpiredSignature) {
            AuthRejection::Expired
        } else {
            AuthRejection::Invalid
        }
    })?;

    request.extensions_mut().insert(identity);

    let mut response = next.run(request).await;
    response
        .headers_mut()
        .insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(response)
}
