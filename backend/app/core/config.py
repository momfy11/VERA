"""Application configuration."""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Typed settings loaded from environment variables."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    database_url: str = "postgresql+psycopg2://vera:vera@localhost:5432/vera"
    environment: str = "development"
    log_level: str = "INFO"
    allowed_origins: str = "http://localhost:5173"


settings = Settings()
