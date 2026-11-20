use std::{io::Cursor, sync::Arc, time::Duration};

use axum::{
    Extension, Json,
    body::Bytes,
    extract::{FromRequest, Request, State},
    http::StatusCode,
};
use image::{DynamicImage, ImageDecoder, ImageFormat, ImageReader, Limits};
use tokio::time::timeout;
use uuid::Uuid;

use super::{
    repository,
    routes::{ProfileRejection, ProfileResponse, profile_response},
};
use crate::{modules::auth::AccessIdentity, state::AppState};

pub(super) const MAX_UPLOAD_BYTES: usize = 5 * 1024 * 1024;
const MAX_DIMENSION: u32 = 4096;
const AVATAR_SIZE: u32 = 512;

fn unavailable<T>(_: T) -> ProfileRejection {
    ProfileRejection::Unavailable
}

pub(super) async fn upload(
    State(state): State<AppState>,
    Extension(identity): Extension<AccessIdentity>,
    request: Request,
) -> Result<Json<ProfileResponse>, ProfileRejection> {
    let storage = state
        .avatar_storage
        .as_ref()
        .ok_or(ProfileRejection::AvatarUploadsDisabled)?;
    let permit = Arc::clone(&storage.upload_slots)
        .try_acquire_owned()
        .map_err(|_| ProfileRejection::AvatarBusy)?;
    let bytes = timeout(
        Duration::from_secs(15),
        Bytes::from_request(request, &state),
    )
    .await
    .map_err(|_| ProfileRejection::AvatarReadTimeout)?
    .map_err(|error| {
        if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
            ProfileRejection::AvatarTooLarge
        } else {
            ProfileRejection::InvalidAvatar
        }
    })?;

    let (bytes, _permit) = tokio::task::spawn_blocking(move || (prepare_avatar(bytes), permit))
        .await
        .map_err(|_| ProfileRejection::Unavailable)?;
    let bytes = bytes?;
    let account_id = identity.account_id();
    let key = format!("avatars/{}.webp", Uuid::now_v7());
    let url = storage.url(&key);

    storage.put(&key, bytes).await.map_err(unavailable)?;

    let mut tx = state.pool.begin().await.map_err(unavailable)?;
    let account = repository::lock_account(&mut tx, account_id)
        .await
        .map_err(unavailable)?
        .ok_or(ProfileRejection::AccountMissing)?;

    let profile = repository::update_avatar(&mut tx, account_id, &key, &url)
        .await
        .map_err(unavailable)?;
    tx.commit().await.map_err(unavailable)?;

    if let Some(previous_key) = account.avatar_object_key {
        storage.delete_in_background(previous_key);
    }

    Ok(profile_response(&state, account_id, profile))
}

fn prepare_avatar(bytes: Bytes) -> Result<Vec<u8>, ProfileRejection> {
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| ProfileRejection::InvalidAvatar)?;
    if !matches!(
        reader.format(),
        Some(ImageFormat::Jpeg | ImageFormat::Png | ImageFormat::WebP)
    ) {
        return Err(ProfileRejection::InvalidAvatar);
    }

    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_DIMENSION);
    limits.max_image_height = Some(MAX_DIMENSION);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);

    let mut decoder = reader
        .into_decoder()
        .map_err(|_| ProfileRejection::InvalidAvatar)?;
    let orientation = decoder
        .orientation()
        .map_err(|_| ProfileRejection::InvalidAvatar)?;
    let mut image =
        DynamicImage::from_decoder(decoder).map_err(|_| ProfileRejection::InvalidAvatar)?;
    image.apply_orientation(orientation);
    let thumbnail = image.thumbnail(AVATAR_SIZE, AVATAR_SIZE).into_rgba8();
    let mut output = Cursor::new(Vec::new());
    DynamicImage::ImageRgba8(thumbnail)
        .write_to(&mut output, ImageFormat::WebP)
        .map_err(unavailable)?;
    Ok(output.into_inner())
}
