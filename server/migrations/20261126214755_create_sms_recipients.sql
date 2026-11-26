-- Add migration script here
CREATE TABLE sms_recipients (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    phone TEXT NOT NULL,
    name TEXT,

    CONSTRAINT sms_recipients_valid_phone
        CHECK (phone ~ '^[+]8801[3-9][0-9]{8}$'),

    CONSTRAINT sms_recipients_account_phone_key
        UNIQUE (account_id, phone)
);
