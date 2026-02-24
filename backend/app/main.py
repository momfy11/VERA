"""FastAPI entrypoint for VERA backend."""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from backend.app.core.config import settings
from backend.app.observability.logger import configure_logging
from backend.app.api.routes import auth, health, suggestions
from backend.app.api.ws import websocket_endpoint

configure_logging()

app = FastAPI(title="VERA API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in settings.allowed_origins.split(",")],
    allow_credentials=True,
    allow_methods=["*"] ,
    allow_headers=["*"],
)

app.include_router(health.router, prefix="/api")
app.include_router(auth.router, prefix="/api")
app.include_router(suggestions.router, prefix="/api")


@app.websocket("/ws")
async def ws_gateway(websocket):
    await websocket_endpoint(websocket)
