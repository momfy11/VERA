-- Backfill: existing users who already have sessions are not first-time users.
UPDATE users SET first_login = FALSE
WHERE first_login = TRUE
  AND id IN (SELECT DISTINCT user_id FROM sessions);
