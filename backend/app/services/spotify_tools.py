"""Spotify tools for VERA — control playback on whichever device the user has open.

Uses the Web API via spotipy. First-run authorization opens a browser to grant
access; subsequent runs auto-refresh the cached token.

Required env vars in backend/.env:
    SPOTIFY_CLIENT_ID
    SPOTIFY_CLIENT_SECRET
    SPOTIFY_REDIRECT_URI=http://127.0.0.1:8888/callback
"""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path

logger = logging.getLogger(__name__)

_SCOPE = (
    "user-read-playback-state user-modify-playback-state "
    "user-read-currently-playing user-read-recently-played "
    "playlist-read-private streaming"
)

_CACHE_PATH = Path(__file__).resolve().parent.parent.parent / "credentials" / "spotify_token.json"

_spotipy_client = None


def _get_client():
    """Build a cached spotipy client. Raises RuntimeError if creds missing."""
    global _spotipy_client
    if _spotipy_client is not None:
        return _spotipy_client

    from backend.app.core.config import settings  # noqa: PLC0415
    import spotipy  # noqa: PLC0415
    from spotipy.oauth2 import SpotifyOAuth  # noqa: PLC0415

    cid = getattr(settings, "spotify_client_id", "")
    cs = getattr(settings, "spotify_client_secret", "")
    ru = getattr(settings, "spotify_redirect_uri", "http://127.0.0.1:8888/callback")
    if not cid or not cs:
        raise RuntimeError("SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET missing in .env")

    _CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    auth = SpotifyOAuth(
        client_id=cid,
        client_secret=cs,
        redirect_uri=ru,
        scope=_SCOPE,
        cache_path=str(_CACHE_PATH),
        open_browser=True,
    )
    _spotipy_client = spotipy.Spotify(auth_manager=auth)
    return _spotipy_client


def _run(fn, *args, **kwargs):
    """Run sync spotipy calls off the event loop."""
    loop = asyncio.get_running_loop()
    return loop.run_in_executor(None, lambda: fn(*args, **kwargs))


def _spotify_friendly_error(exc: Exception, action: str) -> str:
    """Translate raw spotipy errors into actionable messages."""
    msg = str(exc).lower()
    if "premium" in msg or "403" in msg:
        return (
            "Spotify control requires a Premium subscription on the account that owns the "
            "Spotify Developer app. Without Premium, Spotify blocks all playback control "
            "API calls — this is a Spotify policy, not a VERA bug."
        )
    if "no active device" in msg or "404" in msg:
        return "No active Spotify device. Open the Spotify app on your phone or desktop and play something briefly to wake the device, then try again."
    if "rate" in msg or "429" in msg:
        return "Spotify rate limit hit — wait a moment and try again."
    return f"Spotify error ({action}): {exc}"


async def spotify_play(query: str = "") -> str:
    """Resume playback, or search for a track/album/artist and play it.

    Examples:
      query=""                   → resume current playback
      query="hotel california"   → search and play first match
    """
    try:
        sp = _get_client()
        if query.strip():
            results = await _run(lambda: sp.search(q=query, limit=1, type="track"))
            tracks = results.get("tracks", {}).get("items", [])
            if not tracks:
                return f"No tracks found for '{query}'."
            uri = tracks[0]["uri"]
            name = f"{tracks[0]['name']} — {tracks[0]['artists'][0]['name']}"
            await _run(lambda: sp.start_playback(uris=[uri]))
            return f"Playing: {name}"
        else:
            await _run(sp.start_playback)
            return "Resumed playback."
    except Exception as exc:
        logger.warning("spotify_play error: %r", exc)
        return _spotify_friendly_error(exc, "play")


async def spotify_pause() -> str:
    try:
        sp = _get_client()
        await _run(sp.pause_playback)
        return "Paused."
    except Exception as exc:
        return _spotify_friendly_error(exc, "pause")


async def spotify_skip() -> str:
    try:
        sp = _get_client()
        await _run(sp.next_track)
        return "Skipped to next track."
    except Exception as exc:
        return _spotify_friendly_error(exc, "skip")


async def spotify_now_playing() -> str:
    try:
        sp = _get_client()
        current = await _run(sp.current_playback)
        if not current or not current.get("item"):
            return "Nothing is currently playing."
        item = current["item"]
        name = item["name"]
        artist = item["artists"][0]["name"]
        album = item.get("album", {}).get("name", "")
        is_playing = current.get("is_playing", False)
        state = "Playing" if is_playing else "Paused"
        return f"{state}: {name} — {artist}\nAlbum: {album}"
    except Exception as exc:
        return _spotify_friendly_error(exc, "now_playing")


async def spotify_queue(query: str) -> str:
    """Search for a track and add it to the playback queue."""
    try:
        sp = _get_client()
        results = await _run(lambda: sp.search(q=query, limit=1, type="track"))
        tracks = results.get("tracks", {}).get("items", [])
        if not tracks:
            return f"No tracks found for '{query}'."
        uri = tracks[0]["uri"]
        name = f"{tracks[0]['name']} — {tracks[0]['artists'][0]['name']}"
        await _run(lambda: sp.add_to_queue(uri))
        return f"Queued: {name}"
    except Exception as exc:
        logger.warning("spotify_queue error: %r", exc)
        return _spotify_friendly_error(exc, "queue")
