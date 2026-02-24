"""Initialize the database schema using SQLAlchemy."""
from __future__ import annotations

import sys
import os

# Add parent directory to path so we can import backend module
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    from sqlalchemy import create_engine, text
    from backend.app.core.config import settings
    from backend.app.db.models import Base
except ImportError as err:
    print(f"Error: {err}")
    print("Run: pip install -r backend/requirements.txt")
    sys.exit(1)


def init_db() -> None:
    """Create all tables and run initial migrations."""
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
