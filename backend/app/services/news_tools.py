"""News headlines via NewsAPI (https://newsapi.org)."""
from __future__ import annotations

import logging

import httpx

from backend.app.core.config import settings

logger = logging.getLogger(__name__)

_HTTP = httpx.AsyncClient(timeout=15.0)


async def get_news(topic: str = "", country: str = "us", limit: int = 8) -> str:
    """Get top news headlines, optionally filtered by topic.

    topic    — search term (e.g. "AI", "stockholm"). Empty = top headlines.
    country  — 2-letter country code for top headlines (us, gb, se, de, ...).
               Ignored when topic is given.
    """
    if not settings.news_api_key:
        return "NEWS_API_KEY not configured in .env."
    try:
        if topic.strip():
            url = "https://newsapi.org/v2/everything"
            params = {
                "q": topic,
                "sortBy": "publishedAt",
                "language": "en",
                "pageSize": max(1, min(limit, 20)),
                "apiKey": settings.news_api_key,
            }
        else:
            url = "https://newsapi.org/v2/top-headlines"
            params = {
                "country": country,
                "pageSize": max(1, min(limit, 20)),
                "apiKey": settings.news_api_key,
            }

        resp = await _HTTP.get(url, params=params)
        data = resp.json()
        if data.get("status") != "ok":
            return f"NewsAPI error: {data.get('message', 'unknown')}"

        articles = data.get("articles", [])
        if not articles:
            return f"No news found for {('topic ' + topic) if topic else 'top headlines'}."

        header = f"Top news on '{topic}'" if topic else f"Top headlines ({country.upper()})"
        lines = [header + ":"]
        for a in articles:
            title = a.get("title", "")
            source = a.get("source", {}).get("name", "")
            url = a.get("url", "")
            published = (a.get("publishedAt", "") or "")[:10]
            lines.append(f"\n• [{published} {source}] {title}\n  → {url}")
        return "\n".join(lines)

    except Exception as exc:
        logger.warning("get_news error: %r", exc)
        return f"News fetch failed: {exc}"
