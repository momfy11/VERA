"""Utility tools — currency conversion, etc. All free, no API keys."""
from __future__ import annotations

import logging

import httpx

logger = logging.getLogger(__name__)

_HTTP = httpx.AsyncClient(timeout=10.0)


async def convert_currency(amount: float, from_currency: str, to_currency: str) -> str:
    """Convert between currencies using ECB rates via frankfurter.app (free, no key).

    Example: convert_currency(100, "USD", "SEK")
    Supports all major fiat currencies. NOT crypto.
    """
    try:
        f = from_currency.strip().upper()
        t = to_currency.strip().upper()
        if f == t:
            return f"{amount} {f} = {amount} {t} (same currency)"
        resp = await _HTTP.get(
            "https://api.frankfurter.dev/v1/latest",
            params={"amount": amount, "base": f, "symbols": t},
        )
        data = resp.json()
        if "rates" not in data or t not in data["rates"]:
            return f"Could not convert {f} → {t} (one of these may be unsupported)"
        result = data["rates"][t]
        rate = result / amount if amount else 0
        return f"{amount:g} {f} = {result:.2f} {t} (rate: 1 {f} = {rate:.4f} {t}, ECB)"
    except Exception as exc:
        logger.warning("convert_currency error: %r", exc)
        return f"Currency conversion failed: {exc}"
