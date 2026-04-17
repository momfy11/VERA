"""Application configuration."""
from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Resolve .env relative to this file so it works regardless of working directory
_ENV_FILE = Path(__file__).parent.parent.parent / ".env"


class Settings(BaseSettings):
    """Typed settings loaded from environment variables."""

    model_config = SettingsConfigDict(env_file=str(_ENV_FILE), env_file_encoding="utf-8", extra="ignore")

    database_url: str = "postgresql+psycopg2://vera:vera@localhost:5432/vera"
    environment: str = "development"
    log_level: str = "INFO"
    allowed_origins: str = "http://localhost:5173"

    # LLM provider: "mistral" or "groq"
    llm_provider: str = "mistral"
    # Mistral: mistral-small-latest | mistral-medium-latest | open-mistral-7b
    # Groq:    llama-3.3-70b-versatile | mixtral-8x7b-32768 | llama3-8b-8192
    llm_model: str = "mistral-small-latest"
    mistral_api_key: str = ""
    groq_api_key: str = ""

    # Conversation history kept in memory per session (number of message pairs)
    llm_history_turns: int = 20


settings = Settings()
