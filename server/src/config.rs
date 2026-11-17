use std::env;

pub(crate) struct Config {
    pub(crate) database_url: String,
    pub(crate) bind_addr: String,
    pub(crate) ui_origin: String,
    pub(crate) api_origin: String,
    pub(crate) google_client_id: String,
    pub(crate) google_client_secret: String,
    pub(crate) access_jwt_secret: String,
    pub(crate) refresh_jwt_secret: String,
}

pub(crate) fn load() -> Result<Config, env::VarError> {
    Ok(Config {
        database_url: env::var("DATABASE_URL")?,
        bind_addr: env::var("BIND_ADDR")?,
        ui_origin: env::var("UI_ORIGIN")?,
        api_origin: env::var("API_ORIGIN")?,
        google_client_id: env::var("GOOGLE_CLIENT_ID")?,
        google_client_secret: env::var("GOOGLE_CLIENT_SECRET")?,
        access_jwt_secret: env::var("ACCESS_JWT_SECRET")?,
        refresh_jwt_secret: env::var("REFRESH_JWT_SECRET")?,
    })
}
