"""Initialize the database schema using SQLAlchemy."""
from __future__ import annotations

import sys
import os

# Add parent directory to path so we can import backend module
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    from sqlalchemy import create_engine, text
    from sqlalchemy.engine.url import make_url
    from sqlalchemy.exc import OperationalError
    from backend.app.core.config import settings
    from backend.app.db.models import Base
except ImportError as err:
    print(f"Error: {err}")
    print("Run: pip install -r backend/requirements.txt")
    sys.exit(1)


def ensure_database_exists() -> None:
    """Create the target database if it does not exist."""
    url = make_url(settings.database_url)
    target_db = url.database or "vera"
    admin_url = url.set(database="postgres")
    admin_engine = create_engine(admin_url, isolation_level="AUTOCOMMIT")

    with admin_engine.connect() as conn:
        exists = conn.execute(
            text("SELECT 1 FROM pg_database WHERE datname = :name"),
            {"name": target_db},
        ).scalar()
        if not exists:
            conn.execute(text(f'CREATE DATABASE "{target_db}"'))
            print(f"✓ Created database: {target_db}")

    admin_engine.dispose()


def init_db() -> None:
    """Create all tables and run initial migrations."""
    try:
        ensure_database_exists()
    except OperationalError as err:
        print("Error: could not connect to PostgreSQL.")
        print("- Ensure Postgres is running on localhost:5432")
        print("- Verify DATABASE_URL in backend/.env")
        print(f"Details: {err}")
        raise SystemExit(1)

    engine = create_engine(settings.database_url)

    try:
        with engine.begin() as conn:
            conn.execute(text('CREATE EXTENSION IF NOT EXISTS "pgcrypto"'))
        print("✓ Created pgcrypto extension")
    except Exception as err:
        print(f"Note: {err}")

    Base.metadata.create_all(engine)
    print("✓ Created all tables")
    print(f"✓ Database initialized at {settings.database_url}")


if __name__ == "__main__":
    init_db()
