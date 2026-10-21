"""Per-session state + node orchestration for triggers outside the plain conversation.

Council runs on a background thread rather than inline in a graph invoke, because a real
fan-out here has to span multiple HTTP requests - the candidate keeps talking while it
works - and a single `graph.invoke()` call can't do that on its own (PRD 6.3
"Concurrency note"). `interview_node` is called directly per turn for the same reason;
`app.graph.build` is kept for the graph *shape* (fan-out/fan-in wiring, tests) rather
than as the live per-request path.

State is cached in-process and persisted to Postgres (app/store.py), which is authoritative: with
several agent instances any of them may serve any turn of a session, so every read checks that the
cached copy is still the stored version, and every write is conditional on the version it was based
on (optimistic locking) - on a conflict the change is redone on the fresh state.
"""

from __future__ import annotations

import logging
import random
import threading
import time
from typing import Any

from app import store
from app.graph.nodes.council import council_node
from app.graph.nodes.debrief import debrief_node
from app.graph.nodes.interview import interview_node
from app.graph.nodes.review import review_node
from app.graph.nodes.summary import summary_node
from app.graph.state import InterviewState

log = logging.getLogger(__name__)

# In-memory fast path. `store` (Postgres, best-effort) is what makes this survive a
# restart or a cold cache - see app/store.py for why and app/main.py for the startup hook.
_SESSIONS: dict[str, InterviewState] = {}
# The store version each cached state was last read from / written as (None = not stored).
_VERSIONS: dict[str, int | None] = {}
_LOCK = threading.Lock()
# One writer per session inside this process (the Council thread and a request thread both merge);
# across processes the version check does the same job.
_SESSION_LOCKS: dict[str, threading.RLock] = {}

# Attempts at a conditional write before giving up. Each retry means another instance wrote in
# between, so exhausting this means sustained contention on one session, not a transient blip.
_MAX_WRITE_ATTEMPTS = 25
# Pause before retrying (up to attempt * this, randomised) so that writers which collided do not
# keep colliding in lock-step, and so a busy writer cannot starve the loser of a conflict forever.
_RETRY_BACKOFF_S = 0.004


class StateConflictError(RuntimeError):
    """The session's state kept changing under us (another instance writing the same session)."""


def _session_lock(session_id: str) -> threading.RLock:
    with _LOCK:
        return _SESSION_LOCKS.setdefault(session_id, threading.RLock())


def _adopt(session_id: str, loaded: store.Loaded) -> InterviewState:
    with _LOCK:
        _SESSIONS[session_id] = loaded.state
        _VERSIONS[session_id] = loaded.version
        return dict(loaded.state)


def get_state(session_id: str) -> InterviewState:
    with _LOCK:
        cached = _SESSIONS.get(session_id)
        cached_version = _VERSIONS.get(session_id)
    if cached is not None:
        stored = store.version_of(session_id)  # None: no row, or store unavailable -> trust the cache
        if stored is None or stored == cached_version:
            return dict(cached)
        # Another instance has written since we last looked: the stored copy is the truth.
        loaded = store.load(session_id)
        return _adopt(session_id, loaded) if loaded else dict(cached)

    # Cold cache (fresh process, or first time this instance has seen the session):
    # recover from Postgres before falling back to an empty session.
    loaded = store.load(session_id)
    if loaded:
        return _adopt(session_id, loaded)
    with _LOCK:
        state = _SESSIONS.setdefault(session_id, {"session_id": session_id, "transcript": []})
        return dict(state)


def _apply(current: dict[str, Any], update: dict[str, Any]) -> dict[str, Any]:
    """Apply `update` the way the state's reducers would: list fields concatenate,
    everything else is last-write-wins (mirrors app.graph.state.InterviewState)."""
    merged = dict(current)
    for key, value in update.items():
        prior = merged.get(key)
        if isinstance(value, list) and isinstance(prior, list):
            merged[key] = prior + value
        else:
            merged[key] = value
    return merged


def _merge(session_id: str, update: dict[str, Any]) -> InterviewState:
    with _session_lock(session_id):
        for attempt in range(_MAX_WRITE_ATTEMPTS):
            with _LOCK:
                current = _SESSIONS.get(session_id, {"session_id": session_id})
                version = _VERSIONS.get(session_id)
            merged = _apply(current, update)

            outcome = store.write_if(session_id, merged, version)
            if outcome is store.CONFLICT:
                # Someone else wrote first. Take their state and redo this change on top of it.
                loaded = store.load(session_id)
                if loaded:
                    _adopt(session_id, loaded)
                else:  # the row vanished (session ended elsewhere): start again from our copy
                    with _LOCK:
                        _VERSIONS[session_id] = None
                time.sleep(random.uniform(0, _RETRY_BACKOFF_S * (attempt + 1)))
                continue

            with _LOCK:
                _SESSIONS[session_id] = merged
                _VERSIONS[session_id] = outcome  # None when the store is unavailable
            return dict(merged)

    log.warning("Gave up merging into session %s after %d conflicting writes.", session_id, _MAX_WRITE_ATTEMPTS)
    raise StateConflictError(f"session {session_id} is being updated concurrently; try again")


def start_session(
    session_id: str, prompt: str, has_vetted_reference: bool, reference_solution: dict
) -> InterviewState:
    initial: InterviewState = {
        "session_id": session_id,
        "prompt": prompt,
        "transcript": [],
        "clarifying_qa": [],
        "has_vetted_reference": has_vetted_reference,
        "reference_solution": reference_solution or {},
        "council_running": not has_vetted_reference,
        "council_done": has_vetted_reference,
        "interim_summaries": [],
    }
    with _session_lock(session_id):
        version = store.save(session_id, initial)
        with _LOCK:
            _SESSIONS[session_id] = initial
            _VERSIONS[session_id] = version

    state = _merge(session_id, interview_node(get_state(session_id)))

    if state.get("council_running"):
        _spawn_council(session_id)
    return state


def end_session(session_id: str) -> None:
    """Called once the backend has confirmed the session ended and holds the durable
    record itself - this working copy is no longer needed (see app/store.py)."""
    with _LOCK:
        _SESSIONS.pop(session_id, None)
        _VERSIONS.pop(session_id, None)
        _SESSION_LOCKS.pop(session_id, None)
    store.delete_session(session_id)


def _spawn_council(session_id: str) -> None:
    def run() -> None:
        # Reads live state at call time - not a snapshot captured at kickoff - so it
        # picks up whatever clarifying Q&A the candidate raised while the
        # generator/critic/reconcile calls were in flight (PRD 5 step 5).
        result = council_node(get_state(session_id))
        _merge(session_id, result)

    threading.Thread(target=run, daemon=True, name=f"council-{session_id}").start()


def add_candidate_turn(session_id: str, text: str) -> InterviewState:
    state = _merge(session_id, {"transcript": [{"sender": "candidate", "text": text, "kind": ""}]})
    return _merge(session_id, interview_node(state))


def run_review(session_id: str, final_diagram: dict) -> list[dict]:
    state = _merge(session_id, {"final_diagram": final_diagram})
    result = review_node(state)
    _merge(session_id, result)
    return result.get("flaws", [])


def run_summary(session_id: str, ordinal: int) -> str:
    state = get_state(session_id)
    result = summary_node(state)
    _merge(session_id, result)
    content = (result.get("interim_summaries") or [""])[-1]
    return content


def run_debrief(session_id: str, interim_summaries: list[str] | None = None) -> dict:
    state = get_state(session_id)
    # The backend's Postgres log is authoritative once the Summary scheduler has run;
    # fall back to this service's own accumulated list only when the caller doesn't
    # supply one (e.g. driving the agent directly, as in the tests).
    if interim_summaries:
        state = {**state, "interim_summaries": interim_summaries}
    result = debrief_node(state)
    _merge(session_id, result)
    return result.get("debrief", {})


def last_ai_turn(state: InterviewState) -> dict | None:
    for turn in reversed(state.get("transcript", [])):
        if turn["sender"] == "ai":
            return turn
    return None
