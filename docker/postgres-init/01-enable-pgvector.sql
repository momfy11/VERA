-- Auto-run by the official postgres image on first init via
-- /docker-entrypoint-initdb.d/. Re-running the container with an existing
-- data volume will NOT re-execute this file — that's expected.
--
-- The backend entrypoint also calls `CREATE EXTENSION IF NOT EXISTS vector;`
-- as a fallback, so this file is convenience, not the only path.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
