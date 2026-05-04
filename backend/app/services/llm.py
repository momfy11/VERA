"""LLM client abstraction with Mistral and Groq implementations.

Swap providers by changing LLM_PROVIDER + the matching API key in .env.
Both implementations expose the same async interface so the orchestrator
is fully provider-agnostic.

Tool calling is supported via generate_with_tools(), which returns both the
assistant's text reply and any tool_calls the model wants to make.
"""
from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict


@dataclass
class LLMResult:
    """Return value from generate_with_tools()."""
    content: str
    tool_calls: list[ToolCall] = field(default_factory=list)

    @property
    def has_tool_calls(self) -> bool:
        return bool(self.tool_calls)


class LLMClient(ABC):
    """Abstract interface for all LLM backends."""

    @abstractmethod
    async def generate(
        self,
        messages: list[dict],
        system: str | None = None,
    ) -> str:
        """Generate a plain text reply (no tools)."""

    async def generate_with_tools(
        self,
        messages: list[dict],
        system: str | None = None,
        tools: list[dict] | None = None,
    ) -> LLMResult:
        """Generate a reply, optionally with tool calls.

        Default implementation falls back to plain generate() (no tool support).
        Override in subclasses that support function calling.
        """
        text = await self.generate(messages, system)
        return LLMResult(content=text)


class MistralClient(LLMClient):
    """Mistral AI chat completion client.

    Free-tier models: mistral-small-latest, open-mistral-7b.
    """

    def __init__(self, api_key: str, model: str = "mistral-small-latest") -> None:
        if not api_key:
            raise ValueError("MISTRAL_API_KEY is not set. Add it to your .env file.")
        from mistralai import Mistral  # noqa: PLC0415
        self._client = Mistral(api_key=api_key)
        self._model = model

    async def generate(
        self,
        messages: list[dict],
        system: str | None = None,
    ) -> str:
        payload: list[dict] = []
        if system:
            payload.append({"role": "system", "content": system})
        payload.extend(messages)
        try:
            response = await self._client.chat.complete_async(
                model=self._model,
                messages=payload,  # type: ignore[arg-type]
            )
            content = response.choices[0].message.content
            return content if isinstance(content, str) else str(content)
        except Exception as exc:
            logger.error("Mistral API error: %r", exc)
            raise RuntimeError(f"LLM call failed: {exc}") from exc


class GroqClient(LLMClient):
    """Groq chat completion client (OpenAI-compatible, very fast inference).

    Free-tier models: llama-3.3-70b-versatile, mixtral-8x7b-32768.
    Supports full tool/function calling via the OpenAI-compatible API.
    """

    def __init__(self, api_key: str, model: str = "llama-3.3-70b-versatile") -> None:
        if not api_key:
            raise ValueError("GROQ_API_KEY is not set. Add it to your .env file.")
        from groq import AsyncGroq  # noqa: PLC0415
        self._client = AsyncGroq(api_key=api_key)
        self._model = model

    async def generate(
        self,
        messages: list[dict],
        system: str | None = None,
    ) -> str:
        result = await self.generate_with_tools(messages, system=system, tools=None)
        return result.content

    async def generate_with_tools(
        self,
        messages: list[dict],
        system: str | None = None,
        tools: list[dict] | None = None,
    ) -> LLMResult:
        payload: list[dict] = []
        if system:
            payload.append({"role": "system", "content": system})
        payload.extend(messages)

        kwargs: dict = {
            "model": self._model,
            "messages": payload,  # type: ignore[arg-type]
        }
        if tools:
            kwargs["tools"] = tools  # type: ignore[assignment]
            kwargs["tool_choice"] = "auto"

        try:
            response = await self._client.chat.completions.create(**kwargs)
            choice = response.choices[0]
            content = choice.message.content or ""

            tool_calls: list[ToolCall] = []
            if choice.message.tool_calls:
                import json  # noqa: PLC0415
                for tc in choice.message.tool_calls:
                    try:
                        args = json.loads(tc.function.arguments) if tc.function.arguments else {}
                    except Exception:
                        args = {}
                    tool_calls.append(ToolCall(id=tc.id, name=tc.function.name, arguments=args))

            return LLMResult(content=content, tool_calls=tool_calls)

        except Exception as exc:
            err_str = str(exc)
            # Groq sometimes mis-emits tool calls as raw text (Llama tool-call format leak).
            # When that happens, retry once without tools so the user always gets a reply.
            if tools and ("tool_use_failed" in err_str or "tool call validation failed" in err_str):
                logger.warning("Tool-call validation failed — retrying without tools")
                try:
                    fallback = await self._client.chat.completions.create(
                        model=self._model,
                        messages=payload,  # type: ignore[arg-type]
                    )
                    return LLMResult(content=fallback.choices[0].message.content or "", tool_calls=[])
                except Exception as exc2:
                    logger.error("Groq fallback also failed: %r", exc2)
                    raise RuntimeError(f"LLM call failed: {exc2}") from exc2
            logger.error("Groq API error: %r", exc)
            raise RuntimeError(f"LLM call failed: {exc}") from exc


class OpenAICompatibleClient(LLMClient):
    """Generic OpenAI-API-compatible client.

    Works with any provider that exposes OpenAI's /v1/chat/completions schema:
    - Google Gemini (https://generativelanguage.googleapis.com/v1beta/openai/)
    - Ollama (http://localhost:11434/v1/)
    - OpenAI itself
    - DeepSeek, Mistral, OpenRouter, Together, Cerebras, etc.

    Function calling Just Works™ because Gemini/Ollama/OpenAI all accept the
    same OpenAI tools schema we already build for Groq.
    """

    def __init__(self, api_key: str, base_url: str, model: str, provider_name: str = "llm") -> None:
        from openai import AsyncOpenAI  # noqa: PLC0415
        self._client = AsyncOpenAI(api_key=api_key or "ollama-no-key", base_url=base_url)
        self._model = model
        self._provider_name = provider_name

    async def generate(self, messages: list[dict], system: str | None = None) -> str:
        result = await self.generate_with_tools(messages, system=system, tools=None)
        return result.content

    async def generate_with_tools(
        self,
        messages: list[dict],
        system: str | None = None,
        tools: list[dict] | None = None,
    ) -> LLMResult:
        payload: list[dict] = []
        if system:
            payload.append({"role": "system", "content": system})
        payload.extend(messages)

        kwargs: dict = {"model": self._model, "messages": payload}
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"

        try:
            response = await self._client.chat.completions.create(**kwargs)
            choice = response.choices[0]
            content = choice.message.content or ""

            tool_calls: list[ToolCall] = []
            if choice.message.tool_calls:
                import json  # noqa: PLC0415
                for tc in choice.message.tool_calls:
                    try:
                        args = json.loads(tc.function.arguments) if tc.function.arguments else {}
                    except Exception:
                        args = {}
                    tool_calls.append(ToolCall(id=tc.id, name=tc.function.name, arguments=args))
            return LLMResult(content=content, tool_calls=tool_calls)

        except Exception as exc:
            err_str = str(exc)
            if tools and ("tool_use_failed" in err_str or "tool call validation failed" in err_str):
                logger.warning("%s tool-call validation failed — retrying without tools", self._provider_name)
                try:
                    fallback = await self._client.chat.completions.create(
                        model=self._model, messages=payload,
                    )
                    return LLMResult(content=fallback.choices[0].message.content or "", tool_calls=[])
                except Exception as exc2:
                    logger.error("%s fallback also failed: %r", self._provider_name, exc2)
                    raise RuntimeError(f"LLM call failed: {exc2}") from exc2
            logger.error("%s API error: %r", self._provider_name, exc)
            raise RuntimeError(f"LLM call failed: {exc}") from exc


_llm_client_cache: LLMClient | None = None


def build_llm_client() -> LLMClient:
    """Return the process-wide LLM client singleton.

    Provider selection via LLM_PROVIDER env var:
      groq    — Groq cloud (fast, 8K TPM free tier)
      gemini  — Google Gemini (250K TPM free tier — recommended)
      ollama  — local Ollama at OLLAMA_URL (offline, private, slow)
      mistral — Mistral La Plateforme
    """
    global _llm_client_cache
    if _llm_client_cache is not None:
        return _llm_client_cache

    from backend.app.core.config import settings  # noqa: PLC0415

    provider = settings.llm_provider.lower()
    model = settings.llm_model

    if provider == "mistral":
        _llm_client_cache = MistralClient(api_key=settings.mistral_api_key, model=model)
    elif provider == "groq":
        _llm_client_cache = GroqClient(api_key=settings.groq_api_key, model=model)
    elif provider == "gemini":
        if not settings.gemini_api_key:
            raise ValueError("GEMINI_API_KEY is not set in .env")
        _llm_client_cache = OpenAICompatibleClient(
            api_key=settings.gemini_api_key,
            base_url="https://generativelanguage.googleapis.com/v1beta/openai/",
            model=model or "gemini-2.5-flash",
            provider_name="Gemini",
        )
    elif provider == "ollama":
        _llm_client_cache = OpenAICompatibleClient(
            api_key="ollama",
            base_url=settings.ollama_url.rstrip("/") + "/v1",
            model=model or "qwen2.5:7b",
            provider_name="Ollama",
        )
    else:
        raise ValueError(
            f"Unknown LLM_PROVIDER '{provider}'. Choose: groq | gemini | ollama | mistral"
        )

    logger.info("LLM provider initialized: %s (model=%s)", provider, model)
    return _llm_client_cache
