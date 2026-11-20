ALTER TABLE accounts ADD COLUMN avatar_object_key TEXT
    CHECK (avatar_object_key ~ '^avatars/[0-9a-f-]{36}[.]webp$');

-- No account foreign key: cleanup must survive account deletion.
CREATE TABLE avatar_cleanup_jobs (
    object_key TEXT PRIMARY KEY CHECK (object_key ~ '^avatars/[0-9a-f-]{36}[.]webp$'),
    retry_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8)
);

CREATE INDEX avatar_cleanup_jobs_retry_idx ON avatar_cleanup_jobs (retry_at);
