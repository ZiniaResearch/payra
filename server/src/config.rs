use reqwest::Url;
use std::{env, error::Error, io};

pub(crate) struct Config {
    pub(crate) database_url: String,
    pub(crate) bind_addr: String,
    pub(crate) ui_origin: String,
    pub(crate) api_origin: String,
    pub(crate) media_base_url: Url,
    pub(crate) google_client_id: String,
    pub(crate) google_client_secret: String,
    pub(crate) access_jwt_secret: String,
    pub(crate) refresh_jwt_secret: String,
}

pub(crate) fn load() -> Result<Config, Box<dyn Error>> {
    Ok(Config {
        database_url: env::var("DATABASE_URL")?,
        bind_addr: env::var("BIND_ADDR")?,
        ui_origin: env::var("UI_ORIGIN")?,
        api_origin: env::var("API_ORIGIN")?,
        media_base_url: parse_http_url(&env::var("MEDIA_BASE_URL")?, "MEDIA_BASE_URL")?,
        google_client_id: env::var("GOOGLE_CLIENT_ID")?,
        google_client_secret: env::var("GOOGLE_CLIENT_SECRET")?,
        access_jwt_secret: env::var("ACCESS_JWT_SECRET")?,
        refresh_jwt_secret: env::var("REFRESH_JWT_SECRET")?,
    })
}

fn parse_http_url(value: &str, name: &str) -> Result<Url, io::Error> {
    let url = Url::parse(value).map_err(|error| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid {name}: {error}"),
        )
    })?;

    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{name} must be an HTTP(S) URL without credentials, a query, or a fragment"),
        ));
    }

    Ok(url)
}
