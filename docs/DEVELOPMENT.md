"""Development setup and first-run instructions."""

# VERA Development Environment

## Prerequisites

- Python 3.9+
- Node.js 18+
- PostgreSQL 13+

## Quick Start

### 1. Backend Setup

```bash
cd backend
cp .env.example .env
# Edit .env with your Postgres credentials
pip install -r requirements.txt
```

Initialize the database (PostgreSQL required).

**Installation required:** You must have PostgreSQL 13+ installed on your system.

- **Windows**: Download from https://www.postgresql.org/download/windows/
- **Mac**: `brew install postgresql@15`
- **Linux**: `apt install postgresql postgresql-contrib`

Once PostgreSQL is running locally on port 5432, from the **repo root**:

```bash
python backend/init_db.py
```

This script will:
1. Create the database (if it doesn't exist)
2. Create all tables and extensions
3. Confirm success

**Note:** Make sure `.env` has the correct database URL (default is `postgresql+psycopg2://vera:vera@localhost:5432/vera`).

Start the API:

```bash
uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000
```

✅ API runs at `http://localhost:8000`  
✅ Health check: `GET http://localhost:8000/api/health`

### 2. Client Setup

```bash
cd client
npm install
npm run dev
```

✅ UI runs at `http://localhost:5173`

### 3. Test the flow

1. Open `http://localhost:5173` in a browser
2. Open backend console to see logs
3. Try to login / create a session
4. Check database for new user entry

---

## Database Notes

- The SQL file `backend/app/db/migrations/0001_init.sql` is the initial schema reference.
- `backend/init_db.py` creates the database (if missing) and creates tables from models.
- For proper migrations later, we can integrate Alembic or a migration runner.

---

## CI/CD and Deployment

- Both backend and client are in **monorepo root**
- Deployment can be done with Docker or systemd services
- Benchmarks are in `benchmarks/` and run separately

---

## Feature Flags and `.env`

All configuration is in `.env` files (backend only for now; client can read from API).

Never commit secrets. Use `.env.example` as template.

---

## Next Steps

After Sprint 1 is working:
- Implement agent orchestrator (Sprint 4)
- Add voice capture and WebSocket streaming (Sprint 3)
- Add policy gates (Sprint 4)
- Add embeddings + memory (Sprint 4)
