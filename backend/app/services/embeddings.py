"""Embedding providers for semantic memory search.

Three backends supported, in order of recommended priority:

  1. fastembed   — local ONNX, ~50 MB, no external service (default)
  2. ollama      — local HTTP service, GPU-accelerated if available
  3. openai      — cloud API, paid, highest quality

The orchestrator builds a single provider at startup based on EMBEDDING_PROVIDER
env var. If the chosen provider can't initialize (package missing, service
unreachable), build_embedding_provider() returns None and VERA falls back to
recency-based memory retrieval — never crashes.
"""
from __future__ import annotations

import asyncio
import logging
from abc import ABC, abstractmethod

import httpx

logger = logging.getLogger(__name__)


class EmbeddingProvider(ABC):
    """Async embedding interface."""

    dim: int  # Embedding dimension — must match DB column

    @abstractmethod
    async def embed(self, text: str) -> list[float]:
        """Embed a single string into a fixed-length float vector."""

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Default batch impl — providers can override for efficiency."""
        return [await self.embed(t) for t in texts]


class FastembedProvider(EmbeddingProvider):
    """fastembed — ONNX-runtime embeddings, no external service.

    Default model BAAI/bge-small-en-v1.5: 384 dims, ~33 MB, English-optimized.
    First use downloads the model (~30 s). Subsequent runs are instant.
    Embedding latency: ~10-30 ms per text on CPU.
    """

    dim = 384

    def __init__(self, model_name: str = "BAAI/bge-small-en-v1.5") -> None:
        from fastembed import TextEmbedding  # noqa: PLC0415
        logger.info("Initializing fastembed model %r (first run downloads ~33 MB)", model_name)
        self._model = TextEmbedding(model_name=model_name)
        self._model_name = model_name

    async def embed(self, text: str) -> list[float]:
        # fastembed is sync — run in thread pool to not block the event loop
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, self._embed_sync, text)

    def _embed_sync(self, text: str) -> list[float]:
        # .embed() returns a generator yielding numpy arrays
        result = next(self._model.embed([text]))
        return result.tolist()

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, self._embed_batch_sync, texts)

    def _embed_batch_sync(self, texts: list[str]) -> list[list[float]]:
        return [arr.tolist() for arr in self._model.embed(texts)]


class OllamaProvider(EmbeddingProvider):
    """Ollama HTTP embedding API. Requires Ollama running locally.

    Default model nomic-embed-text: 768 dims, multilingual, very high quality.
    Pull first: `ollama pull nomic-embed-text`
    """

    dim = 768

    def __init__(self, base_url: str = "http://localhost:11434", model: str = "nomic-embed-text") -> None:
        self._url = base_url.rstrip("/") + "/api/embeddings"
        self._model = model
        self._client = httpx.AsyncClient(timeout=30.0)

    async def embed(self, text: str) -> list[float]:
        resp = await self._client.post(self._url, json={"model": self._model, "prompt": text})
        resp.raise_for_status()
        return resp.json()["embedding"]


class OpenAIProvider(EmbeddingProvider):
    """OpenAI text-embedding-3-small. Requires OPENAI_API_KEY.

    1536 dims, $0.02 per 1M tokens. Best general quality.
    """

    dim = 1536

    def __init__(self, api_key: str, model: str = "text-embedding-3-small") -> None:
        if not api_key:
            raise ValueError("OPENAI_API_KEY missing")
        self._model = model
        self._client = httpx.AsyncClient(
            base_url="https://api.openai.com/v1",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=30.0,
        )

    async def embed(self, text: str) -> list[float]:
        resp = await self._client.post("/embeddings", json={"input": text, "model": self._model})
        resp.raise_for_status()
        return resp.json()["data"][0]["embedding"]


_provider_cache: EmbeddingProvider | None = None
_init_attempted = False


def build_embedding_provider() -> EmbeddingProvider | None:
    """Return the configured embedding provider, or None if unavailable.

    Reads EMBEDDING_PROVIDER from settings. On failure (missing package,
    service unreachable), returns None so memory retrieval falls back to
    recency-based ranking instead of crashing.
    """
    global _provider_cache, _init_attempted
    if _init_attempted:
        return _provider_cache
    _init_attempted = True

    from backend.app.core.config import settings  # noqa: PLC0415

    provider_name = getattr(settings, "embedding_provider", "fastembed").lower()
    try:
        if provider_name == "fastembed":
            _provider_cache = FastembedProvider(
                model_name=getattr(settings, "embedding_model", "BAAI/bge-small-en-v1.5")
            )
        elif provider_name == "ollama":
            _provider_cache = OllamaProvider(
                base_url=getattr(settings, "ollama_url", "http://localhost:11434"),
                model=getattr(settings, "embedding_model", "nomic-embed-text"),
            )
        elif provider_name == "openai":
            _provider_cache = OpenAIProvider(
                api_key=getattr(settings, "openai_api_key", ""),
                model=getattr(settings, "embedding_model", "text-embedding-3-small"),
            )
        else:
            logger.warning("Unknown EMBEDDING_PROVIDER %r — semantic memory disabled", provider_name)
            return None

        logger.info("Embedding provider initialized: %s (dim=%d)", provider_name, _provider_cache.dim)
        return _provider_cache

    except Exception as exc:
        logger.warning(
            "Could not initialize embedding provider %r: %r — semantic memory disabled, "
            "falling back to recency-based retrieval",
            provider_name,
            exc,
        )
        _provider_cache = None
        return None
