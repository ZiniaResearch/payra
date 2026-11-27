CREATE TABLE sms_jobs (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    recipient_phone TEXT NOT NULL,
    message_text TEXT NOT NULL,
    send_at TIMESTAMPTZ NOT NULL,
    send_by TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT sms_jobs_valid_send_window
    CHECK (send_at < send_by),

    CONSTRAINT sms_jobs_valid_status
    CHECK (status IN (
        'queued', 'sending', 'sent', 'failed', 'partial', 'unknown',
        'cancelled', 'expired'
    ))
);

CREATE INDEX sms_jobs_queued_send_at_idx
ON sms_jobs (send_at, id)
WHERE status = 'queued';

CREATE INDEX sms_jobs_account_id_idx
ON sms_jobs (account_id);
