use std::{sync::Arc, time::Duration};

use object_store::{
    Attribute, Attributes, ClientOptions, ObjectStore, ObjectStoreExt, PutOptions, RetryConfig,
    aws::{AmazonS3, AmazonS3Builder},
    path::Path,
};
use reqwest::Url;
use tokio::sync::Semaphore;

use super::presets::asset_url;
use crate::config::AvatarStorageConfig;

pub(crate) struct AvatarStorage {
    store: AmazonS3,
    public_base: Url,
    pub(super) upload_slots: Arc<Semaphore>,
}

impl AvatarStorage {
    pub(crate) fn new(config: AvatarStorageConfig) -> object_store::Result<Self> {
        let store = AmazonS3Builder::new()
            .with_bucket_name(config.bucket)
            .with_access_key_id(config.access_key)
            .with_secret_access_key(config.secret_key)
            .with_region(config.region)
            .with_endpoint(config.endpoint.as_str().trim_end_matches('/'))
            .with_disable_bulk_delete(true)
            .with_client_options(
                ClientOptions::new()
                    .with_allow_http(config.endpoint.scheme() == "http")
                    .with_timeout(Duration::from_secs(10)),
            )
            .with_retry(RetryConfig {
                max_retries: 0,
                ..RetryConfig::default()
            })
            .build()?;

        Ok(Self {
            store,
            public_base: config.public_base,
            upload_slots: Arc::new(Semaphore::new(2)),
        })
    }

    pub(super) fn url(&self, key: &str) -> String {
        asset_url(&self.public_base, key)
    }

    pub(super) async fn put(&self, key: &str, bytes: Vec<u8>) -> object_store::Result<()> {
        let attributes = Attributes::from_iter([
            (Attribute::ContentType, "image/webp"),
            (Attribute::CacheControl, "no-store"),
        ]);

        self.store
            .put_opts(&Path::from(key), bytes.into(), PutOptions::from(attributes))
            .await?;
        Ok(())
    }

    pub(super) async fn delete(&self, key: &str) -> object_store::Result<()> {
        // S3 DELETE is idempotent. An HTTP 404 can mean the entire bucket is missing.
        self.store.delete(&Path::from(key)).await
    }

    pub(super) fn delete_in_background(self: &Arc<Self>, key: String) {
        let storage = Arc::clone(self);

        tokio::spawn(async move {
            if storage.delete(&key).await.is_ok() {
                return;
            }

            tokio::time::sleep(Duration::from_millis(250)).await;

            if storage.delete(&key).await.is_err() {
                eprintln!("could not delete avatar after retry");
            }
        });
    }
}
