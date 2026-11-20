-- Add migration script here
ALTER TABLE accounts
ADD COLUMN name TEXT NOT NULL,
ADD COLUMN avatar_url TEXT NOT NULL,
ADD COLUMN cover_url TEXT NOT NULL;
