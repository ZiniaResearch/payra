-- Add migration script here
CREATE TABLE sms_attempts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    job_id UUID NOT NULL UNIQUE REFERENCES sms_jobs (id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    result_status TEXT CHECK (result_status IN ('sent', 'failed', 'partial', 'unknown'))
);

CREATE UNIQUE INDEX sms_attempts_one_open_claim_idx
    ON sms_attempts ((1))
    WHERE result_status IS NULL;
