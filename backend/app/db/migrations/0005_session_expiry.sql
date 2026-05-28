-- Add expires_at to sessions for token expiry support.
-- Existing open sessions (ended_at IS NULL) get 30 days from now.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
UPDATE sessions SET expires_at = NOW() + INTERVAL '30 days' WHERE ended_at IS NULL AND expires_at IS NULL;
