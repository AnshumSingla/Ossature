"""R8 / A1.3: the agent's database access - restricted role, optimistic locking, multi-instance safety.

These run against the real Postgres (docker-compose) as the real `ossature_agent` role, using unique
session ids and cleaning up after themselves; they skip when Postgres is not reachable. The table is
created by the backend's migration V7, so start the backend once first (see agent/.env.example).
"""

from __future__ import annotations

import threading
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError

from app import store, turns
from app.config import get_settings


def _usable() -> bool:
    store._reset_breaker()
    store.check_connection()
    return not store._breaker_open()


pytestmark = pytest.mark.skipif(
    not _usable(), reason="Postgres / agent_sessions not available (see agent/.env.example)"
)


@pytest.fixture
def sid():
    session_id = f"test-lock-{uuid.uuid4().hex[:12]}"
    yield session_id
    store._reset_breaker()
    store.delete_session(session_id)
    for cache in (turns._SESSIONS, turns._VERSIONS, turns._SESSION_LOCKS):
        cache.pop(session_id, None)


def _forget_cache(session_id: str) -> None:
    """Pretend to be a different agent instance: it has never seen this session."""
    for cache in (turns._SESSIONS, turns._VERSIONS):
        cache.pop(session_id, None)


# ---------------------------------------------------------------- the store's conditional write


def test_a_write_succeeds_only_from_the_current_version(sid):
    assert store.write_if(sid, {"n": 1}, None) == 0  # first write creates the row
    assert store.write_if(sid, {"n": 2}, 0) == 1
    assert store.write_if(sid, {"n": 3}, 0) is store.CONFLICT  # based on a stale version
    assert store.load(sid) == ({"n": 2}, 1)  # ...and changed nothing
    assert store.write_if(sid, {"n": 4}, 1) == 2


def test_creating_a_row_that_already_exists_is_a_conflict(sid):
    store.save(sid, {"n": 1})
    assert store.write_if(sid, {"n": 2}, None) is store.CONFLICT
    assert store.load(sid).state == {"n": 1}


def test_writing_to_a_deleted_row_is_a_conflict_not_a_silent_recreate(sid):
    store.save(sid, {"n": 1})
    store.delete_session(sid)
    assert store.write_if(sid, {"n": 2}, 0) is store.CONFLICT
    assert store.load(sid) is None


def test_save_restarts_a_session_and_bumps_the_version(sid):
    assert store.save(sid, {"n": 1}) == 0
    assert store.save(sid, {"n": 2}) == 1
    assert store.version_of(sid) == 1


# ---------------------------------------------------------------- two instances, one session


def test_a_stale_instance_picks_up_the_other_instances_writes(sid):
    turns.start_session(sid, "Design X", True, {"summary": "ref"})
    instance_a = (dict(turns._SESSIONS), dict(turns._VERSIONS))

    # Instance B appends a turn. (Same module, so emulate B by clearing A's cache first.)
    _forget_cache(sid)
    turns.add_candidate_turn(sid, "typed on instance B")

    # Back on instance A, whose cache is now stale.
    turns._SESSIONS.update(instance_a[0])
    turns._VERSIONS.update(instance_a[1])
    state = turns.get_state(sid)
    assert any(t["text"] == "typed on instance B" for t in state["transcript"])


def test_a_stale_instance_does_not_overwrite_the_other_instances_work(sid):
    turns.start_session(sid, "Design X", True, {"summary": "ref"})
    stale = (dict(turns._SESSIONS), dict(turns._VERSIONS))

    _forget_cache(sid)
    turns._merge(sid, {"transcript": [{"sender": "candidate", "text": "from B", "kind": ""}]})

    # Instance A still believes the old version and does not re-read before writing.
    turns._SESSIONS.update(stale[0])
    turns._VERSIONS.update(stale[1])
    turns._merge(sid, {"transcript": [{"sender": "candidate", "text": "from A", "kind": ""}]})

    stored = store.load(sid).state["transcript"]
    texts = [t["text"] for t in stored]
    assert texts.count("from A") == 1 and texts.count("from B") == 1  # neither lost, neither duplicated


def test_concurrent_writers_never_lose_an_update(sid, monkeypatch):
    """Many writers racing on one session with NO in-process serialisation: only the database's version
    check stands between them and lost updates (what several agent instances look like)."""
    turns.start_session(sid, "Design X", True, {"summary": "ref"})
    monkeypatch.setattr(turns, "_session_lock", lambda _sid: threading.RLock())  # a private lock per call
    monkeypatch.setattr(turns, "_MAX_WRITE_ATTEMPTS", 200)
    writers = 12
    errors: list[BaseException] = []

    def write(i: int) -> None:
        try:
            turns._merge(sid, {"transcript": [{"sender": "candidate", "text": f"w{i}", "kind": ""}]})
        except BaseException as e:  # noqa: BLE001 - surfaced below
            errors.append(e)

    threads = [threading.Thread(target=write, args=(i,)) for i in range(writers)]
    [t.start() for t in threads]
    [t.join() for t in threads]

    assert not errors
    texts = [t["text"] for t in store.load(sid).state["transcript"] if t["text"].startswith("w")]
    assert sorted(texts) == sorted(f"w{i}" for i in range(writers))


def test_gives_up_with_a_clear_error_under_endless_contention(sid, monkeypatch):
    turns.start_session(sid, "Design X", True, {"summary": "ref"})
    monkeypatch.setattr(store, "write_if", lambda *a, **k: store.CONFLICT)
    with pytest.raises(turns.StateConflictError):
        turns._merge(sid, {"transcript": [{"sender": "candidate", "text": "x", "kind": ""}]})


def test_without_a_database_state_stays_in_memory(sid, monkeypatch):
    turns.start_session(sid, "Design X", True, {"summary": "ref"})
    monkeypatch.setattr(store, "write_if", lambda *a, **k: None)  # store unavailable
    monkeypatch.setattr(store, "version_of", lambda *a, **k: None)
    turns._merge(sid, {"transcript": [{"sender": "candidate", "text": "offline", "kind": ""}]})
    assert any(t["text"] == "offline" for t in turns.get_state(sid)["transcript"])


# ---------------------------------------------------------------- least privilege


@pytest.fixture(scope="module")
def agent_connection():
    engine = create_engine(get_settings().database_url, connect_args={"connect_timeout": 2})
    with engine.connect() as conn:
        yield conn
    engine.dispose()


def test_the_agent_connects_as_its_restricted_role(agent_connection):
    row = agent_connection.execute(
        text("SELECT current_user, (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)")
    ).one()
    assert row[0] == "ossature_agent"
    assert row[1] is False


@pytest.mark.parametrize(
    "statement",
    [
        "SELECT * FROM users",
        "SELECT * FROM interview_sessions",
        "SELECT * FROM session_events",
        "SELECT * FROM questions",
        "CREATE TABLE agent_scratch (id int)",
        "ALTER TABLE agent_sessions ADD COLUMN sneaky text",
        "DROP TABLE agent_sessions",
        "TRUNCATE agent_sessions",
        "CREATE ROLE sneaky LOGIN",
    ],
)
def test_the_agent_role_can_do_nothing_beyond_its_own_table(agent_connection, statement):
    with pytest.raises(DBAPIError):
        agent_connection.execute(text(statement))
    agent_connection.rollback()
