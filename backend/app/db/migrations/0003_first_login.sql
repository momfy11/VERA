-- Add first_login flag to track whether a user has logged in before.
-- Existing users get TRUE so they also see the welcome on their next login.
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_login BOOLEAN NOT NULL DEFAULT TRUE;
