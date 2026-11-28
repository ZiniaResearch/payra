ALTER TABLE sms_jobs
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL,
    ADD CONSTRAINT sms_jobs_valid_retry_time
        CHECK (next_attempt_at >= send_at AND next_attempt_at < send_by);

DROP INDEX sms_jobs_queued_send_at_idx;

CREATE INDEX sms_jobs_queued_attempt_at_idx
    ON sms_jobs (next_attempt_at, id)
    WHERE status = 'queued';
