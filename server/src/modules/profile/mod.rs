mod avatar;
mod avatar_storage;
mod presets;
mod repository;
mod routes;

pub(crate) use self::{
    avatar_storage::AvatarStorage,
    presets::{COVER_PRESETS, default_avatar_url},
    routes::routes,
};
