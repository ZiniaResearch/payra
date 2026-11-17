-- Add migration script here
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    google_sub TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
