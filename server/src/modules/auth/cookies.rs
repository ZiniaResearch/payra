use super::tokens::IssuedTokens;
use axum::http::{
    HeaderMap, HeaderValue,
    header::{COOKIE, InvalidHeaderValue, SET_COOKIE},
};
use chrono::Utc;

pub(super) const ACCESS_COOKIE_NAME: &str = "payra_access";
pub(super) const REFRESH_COOKIE_NAME: &str = "payra_refresh";
pub(super) const ACCESS_COOKIE_PATH: &str = "/api";
pub(super) const REFRESH_COOKIE_PATH: &str = "/api/auth";

#[derive(Debug)]
pub(super) struct InvalidCookie;

pub(super) fn read_cookie<'a>(
    headers: &'a HeaderMap,
    name: &str,
) -> Result<Option<&'a str>, InvalidCookie> {
    let mut token = None;

    for header in headers.get_all(COOKIE) {
        let cookies = header.to_str().map_err(|_| InvalidCookie)?;

        for cookie in cookies.split(';') {
            let Some((cookie_name, value)) = cookie.trim().split_once('=') else {
                continue;
            };
            if cookie_name != name {
                continue;
            }
            if value.is_empty() || token.replace(value).is_some() {
                return Err(InvalidCookie);
            }
        }
    }

    Ok(token)
}

pub(crate) fn clear_session_cookie_headers() -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.append(
        SET_COOKIE,
        HeaderValue::from_static(
            "payra_access=; Path=/api; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
        ),
    );
    headers.append(
        SET_COOKIE,
        HeaderValue::from_static(
            "payra_refresh=; Path=/api/auth; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
        ),
    );
    headers
}

pub(super) fn session_cookie_headers(
    tokens: &IssuedTokens,
) -> Result<HeaderMap, InvalidHeaderValue> {
    let now = Utc::now().timestamp();
    let mut headers = HeaderMap::new();

    headers.append(
        SET_COOKIE,
        token_cookie(
            ACCESS_COOKIE_NAME,
            &tokens.access_token,
            ACCESS_COOKIE_PATH,
            tokens.access_expires_at,
            now,
        )?,
    );

    headers.append(
        SET_COOKIE,
        token_cookie(
            REFRESH_COOKIE_NAME,
            &tokens.refresh_token,
            REFRESH_COOKIE_PATH,
            tokens.refresh_expires_at,
            now,
        )?,
    );

    Ok(headers)
}

fn token_cookie(
    name: &str,
    token: &str,
    path: &str,
    expires_at: i64,
    now: i64,
) -> Result<HeaderValue, InvalidHeaderValue> {
    let max_age = expires_at.saturating_sub(now).max(0);
    HeaderValue::from_str(&format!(
        "{name}={token}; Path={path}; Max-Age={max_age}; Secure; HttpOnly; SameSite=Lax"
    ))
}
