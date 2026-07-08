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

# Download openwakeword ONNX base models at build time (pip package v0.6+ doesn't bundle them)
RUN python -c "import openwakeword.utils; openwakeword.utils.download_models()"

# Copy backend code (skip credentials/, logs/, .env — mount via volume or env)
COPY backend/app /app/backend/app
COPY backend/init_db.py /app/backend/init_db.py
COPY backend/scripts /app/backend/scripts
COPY backend/migrations /app/backend/migrations
COPY backend/alembic.ini /app/backend/alembic.ini
COPY backend/entrypoint.sh /app/backend/entrypoint.sh

# Normalise line endings (entrypoint.sh may be checked out CRLF on Windows)
# and make it executable.
RUN sed -i 's/\r$//' /app/backend/entrypoint.sh && chmod +x /app/backend/entrypoint.sh

# fastembed model cache (~33 MB) goes here on first run
ENV FASTEMBED_CACHE_DIR=/app/.fastembed_cache

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8000/api/health || exit 1

# Entrypoint runs DB bootstrap + alembic + pgvector enable, then execs uvicorn.
CMD ["/app/backend/entrypoint.sh"]
