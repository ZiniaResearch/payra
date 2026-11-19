mod config;
mod db;
mod modules;
mod request_origin;
mod router;
mod state;

use std::error::Error;
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
    // Compare signed proof URLs with configuration, not untrusted Host headers.
    let api_origin = reqwest::Url::parse(&settings.api_origin)?;
    let refresh_url = api_origin.join("/api/auth/refresh")?.to_string();
    let logout_url = api_origin.join("/api/auth/logout")?.to_string();

    let state = state::AppState {
        pool,
        google,
        ui_origin,
        token_signer,
        refresh_url,
        logout_url,
    };
    let app = router::build(state);

    let listener = TcpListener::bind(settings.bind_addr.as_str()).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
