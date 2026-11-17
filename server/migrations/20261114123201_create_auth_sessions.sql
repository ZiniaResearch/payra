-- Add migration script here
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    browser_public_key JSONB NOT NULL,
    refresh_counter BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idle_expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '30 days'),
    absolute_expires_at TIMESTAMPTZ NOT NULL DEFAULT (
        now() + INTERVAL '90 days'
    ),

    CONSTRAINT auth_sessions_positive_counter
    CHECK (refresh_counter >= 1),

    CONSTRAINT auth_sessions_valid_deadlines
    CHECK (
        created_at < idle_expires_at
        AND idle_expires_at <= absolute_expires_at
    ),
    CONSTRAINT auth_sessions_public_key_object
    CHECK (jsonb_typeof(browser_public_key) = 'object'),
    CONSTRAINT auth_sessions_no_private_key
    CHECK (NOT (browser_public_key ? 'd'))
);

CREATE INDEX auth_sessions_account_id_idx
ON auth_sessions (account_id);
