mod scheduler;

use std::{env, error::Error};

use sqlx::postgres::PgPoolOptions;

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn Error>> {
    match dotenvy::dotenv() {
        Ok(_) => {}
        Err(error) if error.not_found() => {}
        Err(_) => return Err("could not load .env".into()),
    }

    let database_url = env::var("DATABASE_URL")?;
    // One connection for LISTEN and one for queue checks and notifications.
    let pool = PgPoolOptions::new()
        .max_connections(2)
        .connect_lazy(&database_url)?;
    scheduler::run(&pool).await?;
    Ok(())
}
