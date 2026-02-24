# Backend

## Setup

1) Create a Postgres database named `vera`.
2) Copy `.env.example` to `.env` and update credentials.
3) Install dependencies:

```
pip install -r backend/requirements.txt
```

## Migrations (manual for now)

Run the initial schema SQL file using your preferred Postgres client:

- File: `backend/app/db/migrations/0001_init.sql`

## Run the API

```
uvicorn backend.app.main:app --reload --host 0.0.0.0 --port 8000
```

## Health check

```
GET http://localhost:8000/api/health
```
