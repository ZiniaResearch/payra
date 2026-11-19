use axum::{
    Json,
    extract::{Request, State},
    http::{
        HeaderValue, Method, StatusCode,
        header::{CACHE_CONTROL, ORIGIN},
    },
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde::Serialize;

#[derive(Serialize)]
struct ErrorResponse {
    error: &'static str,
}

pub(crate) async fn require_allowed_origin(
    State(ui_origin): State<HeaderValue>,
    request: Request,
    next: Next,
) -> Response {
    if request.method() == Method::OPTIONS {
        return next.run(request).await;
    }

    let headers = request.headers();
    let read_only = matches!(*request.method(), Method::GET | Method::HEAD);

    let origin_matches = headers
        .get_all(ORIGIN)
        .iter()
        .eq(std::iter::once(&ui_origin));

    let origin_allowed = origin_matches || (read_only && !headers.contains_key(ORIGIN));

    let expected_header = HeaderValue::from_static("XmlHttpRequest");
    let custom_header_matches = headers
        .get_all("x-requested-with")
        .iter()
        .eq(std::iter::once(&expected_header));

    if !origin_allowed || (!read_only && !custom_header_matches) {
        let error = match request.uri().path() {
            "/api/auth/refresh" => "refresh_forbidden",
            "/api/auth/logout" => "logout_forbidden",
            _ => "request_forbidden",
        };

        return (
            StatusCode::FORBIDDEN,
            [(CACHE_CONTROL, "no-store")],
            Json(ErrorResponse { error }),
        )
            .into_response();
    }

    next.run(request).await
}
