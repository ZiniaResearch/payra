//! Account profile reads; access-JWT authentication is applied centrally.

mod repository;
mod routes;

pub(crate) use self::routes::routes;
