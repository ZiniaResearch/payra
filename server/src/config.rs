use reqwest::Url;
use std::{env, error::Error, io};

pub(crate) struct Config {
    pub(crate) database_url: String,
    pub(crate) bind_addr: String,
    pub(crate) ui_origin: String,
    pub(crate) api_origin: String,
    pub(crate) media_base_url: Url,
    pub(crate) avatar_storage: Option<AvatarStorageConfig>,
    pub(crate) google_client_id: String,
    pub(crate) google_client_secret: String,
    pub(crate) access_jwt_secret: String,
    pub(crate) refresh_jwt_secret: String,
}

pub(crate) struct AvatarStorageConfig {
    pub(crate) endpoint: Url,
    pub(crate) public_base: Url,
    pub(crate) bucket: String,
    pub(crate) region: String,
    pub(crate) access_key: String,
    pub(crate) secret_key: String,
}

pub(crate) fn load() -> Result<Config, Box<dyn Error>> {
    let settings = Config {
        database_url: env::var("DATABASE_URL")?,
        bind_addr: env::var("BIND_ADDR")?,
        ui_origin: env::var("UI_ORIGIN")?,
        api_origin: env::var("API_ORIGIN")?,
        media_base_url: parse_http_url(&env::var("MEDIA_BASE_URL")?, "MEDIA_BASE_URL")?,
        avatar_storage: load_avatar_storage()?,
        google_client_id: env::var("GOOGLE_CLIENT_ID")?,
        google_client_secret: env::var("GOOGLE_CLIENT_SECRET")?,
        access_jwt_secret: env::var("ACCESS_JWT_SECRET")?,
        refresh_jwt_secret: env::var("REFRESH_JWT_SECRET")?,
    };
    if let Some(storage) = &settings.avatar_storage
        && storage.public_base.as_str().trim_end_matches('/')
            == settings.media_base_url.as_str().trim_end_matches('/')
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "AVATAR_PUBLIC_BASE_URL must be separate from MEDIA_BASE_URL",
        )
        .into());
    }
    Ok(settings)
}

fn load_avatar_storage() -> Result<Option<AvatarStorageConfig>, io::Error> {
    let endpoint = match env::var("AVATAR_S3_ENDPOINT") {
        Ok(value) => parse_http_url(&value, "AVATAR_S3_ENDPOINT")?,
        Err(env::VarError::NotPresent) => return Ok(None),
        Err(_) => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "invalid AVATAR_S3_ENDPOINT",
            ));
        }
    };

    let public_base = parse_http_url(
        &required_avatar_setting("AVATAR_PUBLIC_BASE_URL")?,
        "AVATAR_PUBLIC_BASE_URL",
    )?;
    let bucket = required_avatar_setting("AVATAR_S3_BUCKET")?;
    if bucket == "payra-presets" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "AVATAR_S3_BUCKET must be separate from payra-presets",
        ));
    }

    Ok(Some(AvatarStorageConfig {
        endpoint,
        public_base,
        bucket,
        region: env::var("AVATAR_S3_REGION").unwrap_or_else(|_| "garage".to_owned()),
        access_key: required_avatar_setting("AVATAR_S3_ACCESS_KEY_ID")?,
        secret_key: required_avatar_setting("AVATAR_S3_SECRET_ACCESS_KEY")?,
    }))
}

fn required_avatar_setting(name: &str) -> Result<String, io::Error> {
    match env::var(name) {
        Ok(value) if !value.trim().is_empty() => Ok(value),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{name} is required for avatar storage"),
        )),
    }
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
