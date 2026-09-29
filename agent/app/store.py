"""Durable session-state cache, backed by Postgres.

This is NOT a second system of record - the backend's Postgres already holds the durable
transcript/diagram/debrief history (see docs/architecture.md). This table exists purely so
`InterviewState` (transcript, clarifying Q&A, reference solution, council flags, interim
summaries) survives an agent restart mid-session and can be shared across future multiple
agent instances - and it's deleted once the backend confirms the session has ended, since
at that point the backend's own record is complete and this working copy is no longer needed.

Every call here is best-effort: if Postgres is unreachable, callers fall back to the
in-memory-only behavior the agent already had before this existed (see app/turns.py). This
keeps `pytest` runnable with no database, exactly as before.

A short-lived circuit breaker backs that fallback: once a call fails, further calls skip
the network attempt entirely for `_UNAVAILABLE_COOLDOWN_S` instead of each separately
paying the connect timeout. Without this, an outage would inflate the latency of *every*
subsequent agent request for as long as Postgres stayed down, not just the first one.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from sqlalchemy import JSON, Column, MetaData, String, Table, create_engine, delete, select
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.engine import Engine

from app.config import get_settings

log = logging.getLogger(__name__)

_metadata = MetaData()
_sessions_table = Table(
    "agent_sessions",
    _metadata,
    Column("session_id", String, primary_key=True),
    Column("state", JSON().with_variant(JSONB, "postgresql"), nullable=False),
)

_engine: Engine | None = None

_UNAVAILABLE_COOLDOWN_S = 30.0
_unavailable_until = 0.0


def _breaker_open() -> bool:
    return time.monotonic() < _unavailable_until


def _trip_breaker() -> None:
    global _unavailable_until
    _unavailable_until = time.monotonic() + _UNAVAILABLE_COOLDOWN_S


def _reset_breaker() -> None:
    global _unavailable_until
    _unavailable_until = 0.0


def _get_engine() -> Engine:
    global _engine
    if _engine is None:
        # Short connect_timeout: "falls back gracefully when Postgres is unreachable" only
        # holds if that fallback is fast - the OS default TCP timeout is tens of seconds.
        # (Point this at a literal IP, not "localhost" - a hostname resolving to both an
        # IPv4 and IPv6 address makes libpq try each in turn, silently doubling this.)
        _engine = create_engine(
            get_settings().database_url,
            pool_pre_ping=True,
            connect_args={"connect_timeout": 1},  # seconds; psycopg's minimum granularity
        )
    return _engine


def ensure_schema() -> None:
    """Create the table if missing. Called once at startup; failure is non-fatal."""
    try:
        _metadata.create_all(_get_engine(), tables=[_sessions_table])
        _reset_breaker()
    except Exception:
        _trip_breaker()
        log.warning(
            "Could not reach Postgres for session-state durability; "
            "falling back to in-memory-only state for now.",
            exc_info=True,
        )


def load(session_id: str) -> dict[str, Any] | None:
    if _breaker_open():
        return None
    try:
        with _get_engine().connect() as conn:
            row = conn.execute(
                select(_sessions_table.c.state).where(_sessions_table.c.session_id == session_id)
            ).first()
        _reset_breaker()
        return row[0] if row else None
    except Exception:
        _trip_breaker()
        log.warning(
            "Session-state load failed for %s; continuing without it.", session_id, exc_info=True
        )
        return None


def save(session_id: str, state: dict[str, Any]) -> None:
    if _breaker_open():
        return
    try:
        with _get_engine().begin() as conn:
            dialect = conn.dialect.name
            if dialect == "postgresql":
                from sqlalchemy.dialects.postgresql import insert as pg_insert

                stmt = pg_insert(_sessions_table).values(session_id=session_id, state=state)
                stmt = stmt.on_conflict_do_update(
                    index_elements=[_sessions_table.c.session_id], set_={"state": state}
                )
                conn.execute(stmt)
            else:
                conn.execute(
                    delete(_sessions_table).where(_sessions_table.c.session_id == session_id)
                )
                conn.execute(_sessions_table.insert().values(session_id=session_id, state=state))
        _reset_breaker()
    except Exception:
        _trip_breaker()
        log.warning(
            "Session-state save failed for %s; state stays in-memory only.",
            session_id,
            exc_info=True,
        )


def delete_session(session_id: str) -> None:
    """Purge the durable copy once the backend confirms the session has ended."""
    if _breaker_open():
        return
    try:
        with _get_engine().begin() as conn:
            conn.execute(delete(_sessions_table).where(_sessions_table.c.session_id == session_id))
        _reset_breaker()
    except Exception:
        _trip_breaker()
        log.warning(
            "Session-state delete failed for %s (harmless - it'll just linger).",
            session_id,
            exc_info=True,
        )
