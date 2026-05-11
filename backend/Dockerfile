# VERA backend — Python 3.11 + FastAPI + uvicorn
FROM python:3.11-slim

# System deps for psycopg2-binary and pgvector client
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python deps first (better layer caching)
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --no-cache-dir -r backend/requirements.txt

# Copy backend code (skip credentials/, logs/, .env — mount via volume or env)
COPY backend/app /app/backend/app
COPY backend/init_db.py /app/backend/init_db.py
COPY backend/scripts /app/backend/scripts
COPY backend/migrations /app/backend/migrations
COPY backend/alembic.ini /app/backend/alembic.ini

# fastembed model cache (~33 MB) goes here on first run
ENV FASTEMBED_CACHE_DIR=/app/.fastembed_cache

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -f http://localhost:8000/api/health || exit 1

# Don't use --reload in prod
CMD ["uvicorn", "backend.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
