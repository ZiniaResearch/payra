mod db;
mod health;
mod web;

use std::{env, error::Error};
use tokio::net::TcpListener;

fn main() -> Result<(), Box<dyn Error>> {
    match dotenvy::dotenv() {
        Ok(path) => println!("Loaded .env from {}", path.display()),
        Err(error) if error.not_found() => {
            println!("No .env file found. Reading settings from the process environment.");
        }
        Err(_) => return Err("could not load .env".into()),
    }

    tokio::runtime::Runtime::new()?.block_on(serve())
}

async fn serve() -> Result<(), Box<dyn Error>> {
    let database_url = env::var("DATABASE_URL")?;
    let bind_addr = env::var("BIND_ADDR")?;

    let pool = db::create_pool(&database_url)?;
    let app = web::router(pool);

    let listener = TcpListener::bind(bind_addr.as_str()).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
