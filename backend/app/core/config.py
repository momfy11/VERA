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

    # LLM provider: "groq" | "gemini" | "ollama" | "mistral"
    llm_provider: str = "gemini"
    # Recommended models per provider:
    #   groq:    openai/gpt-oss-20b | openai/gpt-oss-120b | meta-llama/llama-4-scout-17b-16e-instruct
    #   gemini:  gemini-2.5-flash | gemini-2.0-flash | gemini-2.5-pro
    #   ollama:  qwen2.5:7b | llama3.1:8b | mistral-nemo:latest
    #   mistral: mistral-small-latest | mistral-medium-latest
    llm_model: str = "gemini-2.5-flash"
    mistral_api_key: str = ""
    groq_api_key: str = ""
    gemini_api_key: str = ""

    # Conversation history kept in memory per session (number of message pairs).
    # Lower this if you hit Groq's 8K tokens-per-minute rate limit on free tier.
    llm_history_turns: int = 10

    # Embedding provider for semantic memory search (Phase 5.1)
    # "fastembed" — local ONNX (default, recommended)
    # "ollama"    — local Ollama HTTP service
    # "openai"    — cloud, paid
    embedding_provider: str = "fastembed"
    # Model name (provider-specific). Defaults are picked per provider if blank.
    embedding_model: str = "BAAI/bge-small-en-v1.5"
    # Must match the dim of the chosen model AND the vector(N) column in DB
    embedding_dim: int = 384
    # Ollama base URL (only used when embedding_provider=ollama)
    ollama_url: str = "http://localhost:11434"
    # OpenAI key (only used when embedding_provider=openai)
    openai_api_key: str = ""

    # NewsAPI key (newsapi.org free tier: 100 req/day)
    news_api_key: str = ""


settings = Settings()
