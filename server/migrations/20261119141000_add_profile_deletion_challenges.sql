CREATE TABLE profile_deletion_challenges (
    session_id UUID PRIMARY KEY REFERENCES auth_sessions (id) ON DELETE CASCADE,
    nonce UUID NOT NULL DEFAULT gen_random_uuid(),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
