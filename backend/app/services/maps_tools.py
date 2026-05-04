"""Maps tools — open Google Maps URLs, fetch route/place info via free APIs.

Architecture: tools that "open" things return a special JSON marker that the
orchestrator intercepts to emit a WebSocket event. The frontend handles the
event by calling window.open() — works on PC (opens Chrome tab) and phone
(deep-links into Google Maps app via Android intent / iOS URL scheme).

Data fetchers (route info, place search) use free no-key APIs:
- Nominatim (OpenStreetMap) for geocoding + place search — 1 req/s rate limit
- OSRM public demo for routing — fine for dev, document upgrade for prod
"""
from __future__ import annotations

import asyncio
import json
import logging
from urllib.parse import quote

import httpx

logger = logging.getLogger(__name__)

# Reuse a single client for all map calls
_HTTP = httpx.AsyncClient(
    timeout=15.0,
    follow_redirects=True,
    headers={"User-Agent": "VERA-assistant/1.0 (personal-use)"},
)

# Nominatim asks consumers to throttle to 1 req/sec
_NOMINATIM_LOCK = asyncio.Lock()


async def _geocode(query: str) -> dict | None:
    """Return {lat, lon, display_name} for a query, or None if not found."""
    async with _NOMINATIM_LOCK:
        try:
            resp = await _HTTP.get(
                "https://nominatim.openstreetmap.org/search",
                params={"q": query, "format": "json", "limit": 1},
            )
            data = resp.json()
            if not data:
                return None
            r = data[0]
            return {
                "lat": float(r["lat"]),
                "lon": float(r["lon"]),
                "display_name": r.get("display_name", query),
            }
        except Exception as exc:
            logger.debug("geocode %r failed: %r", query, exc)
            return None


# ---------------------------------------------------------------------------
# URL openers — return a marker the orchestrator translates to a WS event
# ---------------------------------------------------------------------------

def _open_url_marker(url: str, label: str) -> str:
    return json.dumps({"__open_url__": True, "url": url, "label": label})


async def open_url(url: str) -> str:
    """Open any URL in the user's browser (or default app on mobile)."""
    if not url.startswith(("http://", "https://", "geo:", "tel:", "mailto:")):
        return f"Refusing to open suspicious URL: {url}"
    return _open_url_marker(url, url)


async def maps_directions(origin: str, destination: str, mode: str = "driving") -> str:
    """Open Google Maps with a directions route loaded.

    On phone: deep-links into the Google Maps app via the universal URL.
    On PC: opens a new tab in the default browser.
    Modes: driving | walking | bicycling | transit
    """
    valid = {"driving", "walking", "bicycling", "transit"}
    m = mode.lower()
    if m not in valid:
        m = "driving"
    url = (
        "https://www.google.com/maps/dir/?api=1"
        f"&origin={quote(origin)}"
        f"&destination={quote(destination)}"
        f"&travelmode={m}"
    )
    return _open_url_marker(url, f"Directions: {origin} → {destination} ({m})")


async def maps_search(query: str) -> str:
    """Open Google Maps with a search/place query."""
    url = f"https://www.google.com/maps/search/?api=1&query={quote(query)}"
    return _open_url_marker(url, f"Maps: {query}")


# ---------------------------------------------------------------------------
# Data fetchers — return route/place info as text (no URL opening)
# ---------------------------------------------------------------------------

async def get_route(origin: str, destination: str, mode: str = "driving") -> str:
    """Get distance + duration for a route via free OSRM + Nominatim.

    No browser needed. Use this when the user asks "how far" / "how long".
    Modes: driving | walking | cycling
    """
    o = await _geocode(origin)
    if not o:
        return f"Could not find location: {origin}"
    d = await _geocode(destination)
    if not d:
        return f"Could not find location: {destination}"

    profile_map = {
        "driving": "driving", "drive": "driving", "car": "driving",
        "walking": "foot", "walk": "foot",
        "cycling": "bike", "cycle": "bike", "bike": "bike",
    }
    profile = profile_map.get(mode.lower(), "driving")

    try:
        coords = f"{o['lon']},{o['lat']};{d['lon']},{d['lat']}"
        resp = await _HTTP.get(
            f"https://router.project-osrm.org/route/v1/{profile}/{coords}",
            params={"overview": "false"},
        )
        data = resp.json()
        if data.get("code") != "Ok" or not data.get("routes"):
            return f"No {profile} route from {origin} to {destination}"
        route = data["routes"][0]
        km = route["distance"] / 1000
        mins = route["duration"] / 60
        hours = int(mins // 60)
        rem_min = int(mins % 60)
        time_str = f"{hours}h {rem_min}m" if hours else f"{rem_min} min"
        return (
            f"Route: {o['display_name'].split(',')[0]} → {d['display_name'].split(',')[0]}\n"
            f"Distance: {km:.1f} km\n"
            f"Duration: ~{time_str} by {profile}"
        )
    except Exception as exc:
        logger.warning("get_route error: %r", exc)
        return f"Route lookup failed: {exc}"


async def nearby_places(query: str, near: str = "", limit: int = 5) -> str:
    """Search for places by name/category, optionally near a location.

    Nominatim's free-text search handles `<query> <location>` better than
    bounded viewbox filtering for category searches like "pizza".
    """
    try:
        full_q = f"{query} {near}".strip() if near else query
        params: dict = {"q": full_q, "format": "json", "limit": max(1, min(limit, 10))}

        async with _NOMINATIM_LOCK:
            resp = await _HTTP.get(
                "https://nominatim.openstreetmap.org/search",
                params=params,
            )
        data = resp.json()
        if not data:
            return f"No places found for '{full_q}'"

        lines = [f"Places matching '{full_q}':"]
        for p in data[:limit]:
            name = p.get("display_name", "?")
            # Trim region/country tail — keep first 3 comma-segments for readability
            short = ", ".join(name.split(",")[:3])
            lines.append(f"• {short}")
        return "\n".join(lines)
    except Exception as exc:
        logger.warning("nearby_places error: %r", exc)
        return f"Place search failed: {exc}"
