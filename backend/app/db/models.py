"""SQLAlchemy models for VERA."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, Text, func, text
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    """Base declarative class."""


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False)
    display_name: Mapped[str] = mapped_column(String(255), nullable=True)

    settings = relationship("UserSettings", back_populates="user", uselist=False)


class UserSettings(Base):
    __tablename__ = "user_settings"

    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), primary_key=True)
    quiet_hours_json: Mapped[dict] = mapped_column(JSONB, default=dict)
    feature_flags_json: Mapped[dict] = mapped_column(JSONB, default=dict)

    user = relationship("User", back_populates="settings")


class Session(Base):
    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    ended_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    client_meta_json: Mapped[dict] = mapped_column(JSONB, default=dict)
    session_token: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)


class SessionEvent(Base):
    __tablename__ = "session_events"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    session_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("sessions.id"), nullable=False)
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload_json: Mapped[dict] = mapped_column(JSONB, default=dict)


class AgentSuggestion(Base):
    __tablename__ = "agent_suggestions"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=False)
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    type: Mapped[str] = mapped_column(String(64), nullable=False)
    priority: Mapped[int] = mapped_column(Integer, default=0)
    payload_json: Mapped[dict] = mapped_column(JSONB, default=dict)
    status: Mapped[str] = mapped_column(String(32), default="new")


class AgentAction(Base):
    __tablename__ = "agent_actions"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=False)
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload_json: Mapped[dict] = mapped_column(JSONB, default=dict)
    requires_approval: Mapped[bool] = mapped_column(Boolean, default=True)
    approval_status: Mapped[str] = mapped_column(String(32), default="pending")
    executed_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    result_json: Mapped[dict] = mapped_column(JSONB, default=dict)
    error_json: Mapped[dict] = mapped_column(JSONB, default=dict)


class MemoryItem(Base):
    __tablename__ = "memory_items"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=False)
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    embedding_vector: Mapped[list] = mapped_column(ARRAY(Float), nullable=True)
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    source: Mapped[str] = mapped_column(String(64), nullable=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)


class MemoryFeedback(Base):
    __tablename__ = "memory_feedback"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    memory_item_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("memory_items.id"), nullable=False)
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=False)
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    action: Mapped[str] = mapped_column(String(32), nullable=False)
    note: Mapped[str] = mapped_column(Text, nullable=True)


class AuditLog(Base):
    __tablename__ = "audit_log"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=True)
    session_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("sessions.id"), nullable=True)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    severity: Mapped[str] = mapped_column(String(16), default="info")
    payload_json: Mapped[dict] = mapped_column(JSONB, default=dict)


class ChatMessage(Base):
    __tablename__ = "chat_messages"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()"))
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), nullable=False)
    session_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("sessions.id"), nullable=True)
    ts: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)


class MetricsRollup(Base):
    __tablename__ = "metrics_rollup"

    day: Mapped[datetime] = mapped_column(DateTime, primary_key=True)
    user_id: Mapped[str] = mapped_column(UUID(as_uuid=False), ForeignKey("users.id"), primary_key=True)
    barge_in_ms_avg: Mapped[int] = mapped_column(Integer, default=0)
    ttfb_ms_avg: Mapped[int] = mapped_column(Integer, default=0)
    suggestions_accepted: Mapped[int] = mapped_column(Integer, default=0)
    errors_count: Mapped[int] = mapped_column(Integer, default=0)
