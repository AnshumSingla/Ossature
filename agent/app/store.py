"""Durable session-state store, backed by Postgres.

This is NOT a second system of record - the backend's Postgres already holds the durable
transcript/diagram/debrief history (see docs/architecture.md). This table exists so
`InterviewState` (transcript, clarifying Q&A, reference solution, council flags, interim
summaries) survives an agent restart mid-session and is shared by several agent instances - and
it's deleted once the backend confirms the session has ended, since at that point the backend's
own record is complete and this working copy is no longer needed.

The table is created by the backend's Flyway migration (V7), not here: the agent connects as the
restricted `ossature_agent` role, which can read and write this one table and nothing else, and
cannot create or alter anything.

Concurrency across instances is optimistic. Every row carries a `version`; a write names the
version it was based on and succeeds only if that is still the stored version (`write_if`), so two
instances can never silently overwrite each other - the loser gets `CONFLICT` and redoes its change
on the fresh state (see app/turns.py).

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
from datetime import timedelta
from typing import Any, NamedTuple

from sqlalchemy import (
    JSON,
    BigInteger,
    Column,
    DateTime,
    MetaData,
    String,
    Table,
    create_engine,
    delete,
    func,
    select,
    text,
    update,
)
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
    Column("version", BigInteger, nullable=False, server_default="0"),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
)


class Loaded(NamedTuple):
    state: dict[str, Any]
    version: int


class _Conflict:
    def __repr__(self) -> str:
        return "CONFLICT"


#: Returned by `write_if` when the stored version is not the one the caller based its change on.
CONFLICT = _Conflict()

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
            # On a failed write SQLAlchemy would otherwise append the statement's parameters to the
            # error text - here that is the whole interview state (prompt + transcript) - and the
            # warnings below log that error with a traceback.
            hide_parameters=True,
            connect_args={"connect_timeout": 1},  # seconds; psycopg's minimum granularity
        )
    return _engine


def _insert(conn):
    """The dialect's INSERT (both have ON CONFLICT ... RETURNING; sqlite is for unit tests only)."""
    if conn.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    return insert(_sessions_table)


def check_connection() -> None:
    """Probe the table once at startup; failure is non-fatal (the agent runs in-memory then).

    The agent role cannot create tables, so a missing one means the backend's migrations have not
    run yet - say so, because the symptom (state not surviving a restart) is otherwise silent.
    """
    try:
        with _get_engine().connect() as conn:
            conn.execute(text("SELECT version FROM agent_sessions LIMIT 1"))
        _reset_breaker()
    except Exception:
        _trip_breaker()
        log.warning(
            "Could not use the agent_sessions table in Postgres (unreachable, wrong credentials, or the "
            "backend's migrations have not run); falling back to in-memory-only state for now.",
            exc_info=True,
        )


def load(session_id: str) -> Loaded | None:
    if _breaker_open():
        return None
    try:
        with _get_engine().connect() as conn:
            row = conn.execute(
                select(_sessions_table.c.state, _sessions_table.c.version).where(
                    _sessions_table.c.session_id == session_id
                )
            ).first()
        _reset_breaker()
        return Loaded(row[0], int(row[1])) if row else None
    except Exception:
        _trip_breaker()
        log.warning(
            "Session-state load failed for %s; continuing without it.", session_id, exc_info=True
        )
        return None


def version_of(session_id: str) -> int | None:
    """The stored version, or None if there is no row or the store is unavailable. Cheap - used to
    notice that another instance has written since this one last looked."""
    if _breaker_open():
        return None
    try:
        with _get_engine().connect() as conn:
            row = conn.execute(
                select(_sessions_table.c.version).where(_sessions_table.c.session_id == session_id)
            ).first()
        _reset_breaker()
        return int(row[0]) if row else None
    except Exception:
        _trip_breaker()
        log.warning("Session-state version check failed for %s.", session_id, exc_info=True)
        return None


def find_running_councils(max_age_s: float) -> list[str]:
    """Sessions whose Council job is unfinished and that were touched recently. The age cut-off keeps
    abandoned sessions (never ended, never purged) from having a paid job started for them forever."""
    if _breaker_open():
        return []
    try:
        with _get_engine().connect() as conn:
            rows = conn.execute(
                select(_sessions_table.c.session_id).where(
                    _sessions_table.c.state["council_running"].as_string() == "true",
                    _sessions_table.c.updated_at >= func.now() - timedelta(seconds=max_age_s),
                )
            ).all()
        _reset_breaker()
        return [r[0] for r in rows]
    except Exception:
        _trip_breaker()
        log.warning("Looking for unfinished Council jobs failed.", exc_info=True)
        return []


def save(session_id: str, state: dict[str, Any]) -> int | None:
    """Unconditional write, for creating (or restarting) a session. Returns the new version, or
    None if the store is unavailable."""
    if _breaker_open():
        return None
    try:
        with _get_engine().begin() as conn:
            stmt = (
                _insert(conn)
                .values(session_id=session_id, state=state, version=0)
                .on_conflict_do_update(
                    index_elements=[_sessions_table.c.session_id],
                    set_={
                        "state": state,
                        "version": _sessions_table.c.version + 1,
                        "updated_at": func.now(),
                    },
                )
                .returning(_sessions_table.c.version)
            )
            version = conn.execute(stmt).scalar_one()
        _reset_breaker()
        return int(version)
    except Exception:
        _trip_breaker()
        log.warning(
            "Session-state save failed for %s; state stays in-memory only.",
            session_id,
            exc_info=True,
        )
        return None


def write_if(
    session_id: str, state: dict[str, Any], expected_version: int | None
) -> int | None | _Conflict:
    """Write `state` only if the stored version is still `expected_version` (None = "no row yet").

    Returns the new version on success, `CONFLICT` if someone else wrote first (the caller must
    reload and redo its change), or None if the store is unavailable (the caller keeps the state in
    memory only, as before).
    """
    if _breaker_open():
        return None
    try:
        with _get_engine().begin() as conn:
            if expected_version is None:
                stmt = (
                    _insert(conn)
                    .values(session_id=session_id, state=state, version=0)
                    .on_conflict_do_nothing(index_elements=[_sessions_table.c.session_id])
                    .returning(_sessions_table.c.version)
                )
            else:
                stmt = (
                    update(_sessions_table)
                    .where(
                        _sessions_table.c.session_id == session_id,
                        _sessions_table.c.version == expected_version,
                    )
                    .values(
                        state=state, version=_sessions_table.c.version + 1, updated_at=func.now()
                    )
                    .returning(_sessions_table.c.version)
                )
            row = conn.execute(stmt).first()
        _reset_breaker()
        return int(row[0]) if row else CONFLICT
    except Exception:
        _trip_breaker()
        log.warning(
            "Session-state write failed for %s; state stays in-memory only.",
            session_id,
            exc_info=True,
        )
        return None


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
