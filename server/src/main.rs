mod config;
mod db;
mod modules;
mod request_origin;
mod router;
mod state;

use modules::profile::AvatarStorage;
use std::{error::Error, sync::Arc};
use tokio::net::TcpListener;

fn main() -> Result<(), Box<dyn Error>> {
    match dotenvy::dotenv() {
        Ok(_) => {}
        Err(error) if error.not_found() => {}
        Err(_) => return Err("could not load .env".into()),
    }

    tokio::runtime::Runtime::new()?.block_on(serve())
}

async fn serve() -> Result<(), Box<dyn Error>> {
    let settings = config::load()?;
    let pool = db::create_pool(&settings.database_url)?;
    let ui_origin = settings.ui_origin.parse::<axum::http::HeaderValue>()?;
    let google = modules::auth::GoogleVerifier::new(
        &settings.google_client_id,
        &settings.google_client_secret,
        &settings.ui_origin,
    )?;
    let token_signer =
        modules::auth::TokenSigner::new(&settings.access_jwt_secret, &settings.refresh_jwt_secret);
    let api_origin = reqwest::Url::parse(&settings.api_origin)?;
    let avatar_storage = settings
        .avatar_storage
        .map(AvatarStorage::new)
        .transpose()?
        .map(Arc::new);

    let listener = TcpListener::bind(settings.bind_addr.as_str()).await?;
    let gateway = modules::sms::gateway::routes(
        pool.clone(),
        settings.database_url,
        settings.gateway_key_sha256,
    );
    let state = state::AppState {
        pool,
        google,
        ui_origin,
        token_signer,
        api_origin,
        media_base_url: settings.media_base_url,
        avatar_storage,
    };
    let app = router::build(state).merge(gateway);

    axum::serve(listener, app).await?;
    Ok(())
}
