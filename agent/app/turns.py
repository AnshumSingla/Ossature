"""Per-session state + node orchestration for triggers outside the plain conversation.

Council runs on a background thread rather than inline in a graph invoke, because a real
fan-out here has to span multiple HTTP requests - the candidate keeps talking while it
works - and a single `graph.invoke()` call can't do that on its own (PRD 6.3
"Concurrency note"). `interview_node` is called directly per turn for the same reason;
`app.graph.build` is kept for the graph *shape* (fan-out/fan-in wiring, tests) rather
than as the live per-request path.

The Council job is durable too (A2): its state (pending / running / done / failed, a lease claimed by
whichever agent runs it, an attempt count) lives in the persisted session state, so if the agent running
it dies, another agent - or the same one after a restart - takes it over once the lease lapses
(`resume_stalled_councils`), and the claim is itself a version-checked write, so a job can never run
twice at once.

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
import uuid
from collections.abc import Callable
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


# Identifies this process in Council claims.
INSTANCE_ID = uuid.uuid4().hex

# Council job lease (A2). The runner renews the lease every heartbeat; if it dies the lease lapses within
# `_COUNCIL_LEASE_S` and the job is taken over. A job is started at most `_COUNCIL_MAX_ATTEMPTS` times
# per session (it is a paid, multi-call job), and only sessions touched within the resume window are
# resumed at all.
_COUNCIL_LEASE_S = 90.0
_COUNCIL_HEARTBEAT_S = 25.0
_COUNCIL_MAX_ATTEMPTS = 3
_COUNCIL_RESUME_WINDOW_S = 1800.0
_COUNCIL_SWEEP_INTERVAL_S = 30.0
# How long a Review that arrives before the reference exists waits for the Council to finish.
_REVIEW_WAIT_FOR_COUNCIL_S = 20.0

_COUNCIL_THREADS: set[str] = set()  # sessions with a Council thread alive in THIS process


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
    return _merge_with(session_id, lambda _current: update)  # type: ignore[return-value]


def _merge_with(
    session_id: str,
    decide: Callable[[InterviewState], dict[str, Any] | None],
    *,
    fresh: bool = False,
) -> InterviewState | None:
    """Apply the change `decide(current_state)` returns, as one version-checked write.

    `decide` runs against the latest state on every attempt, so a decision that depends on the state
    (claim this job only if nobody holds it) is re-made after losing a conflict, not replayed on stale
    data. It returns the update to apply, or None to change nothing (the result is then None).
    `fresh=True` first refreshes the cache from the store; a plain append does not need it, because the
    conditional write detects a stale cache by itself.
    """
    with _session_lock(session_id):
        for attempt in range(_MAX_WRITE_ATTEMPTS):
            if fresh:
                get_state(session_id)
            with _LOCK:
                current = dict(_SESSIONS.get(session_id, {"session_id": session_id}))
                version = _VERSIONS.get(session_id)
            update = decide(current)
            if update is None:
                return None
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
        "council_status": "done" if has_vetted_reference else "pending",
        "council_claim": None,
        "council_attempts": 0,
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
    with _LOCK:
        if session_id in _COUNCIL_THREADS:
            return  # already running in this process
        _COUNCIL_THREADS.add(session_id)

    def run() -> None:
        try:
            _run_council(session_id)
        finally:
            with _LOCK:
                _COUNCIL_THREADS.discard(session_id)

    threading.Thread(target=run, daemon=True, name=f"council-{session_id}").start()


def _claimable(state: InterviewState, now: float) -> bool:
    if not state.get("council_running") or state.get("council_done"):
        return False
    if ((state.get("council_claim") or {}).get("until") or 0) > now:
        return False  # someone holds a live lease
    return int(state.get("council_attempts") or 0) < _COUNCIL_MAX_ATTEMPTS


def _holds_claim(state: InterviewState) -> bool:
    return (
        bool(state.get("council_running"))
        and not state.get("council_done")
        and (state.get("council_claim") or {}).get("owner") == INSTANCE_ID
    )


def _claim_council(session_id: str) -> bool:
    """Take the job if nobody holds a live lease. The write is version-checked, so of several agents
    that try at once exactly one succeeds - a job is never run twice concurrently (A2.3)."""

    def decide(state: InterviewState) -> dict[str, Any] | None:
        now = time.time()
        if not _claimable(state, now):
            return None
        return {
            "council_claim": {"owner": INSTANCE_ID, "until": now + _COUNCIL_LEASE_S},
            "council_status": "running",
            "council_attempts": int(state.get("council_attempts") or 0) + 1,
        }

    return _merge_with(session_id, decide, fresh=True) is not None


def _renew_council_lease(session_id: str) -> bool:
    def decide(state: InterviewState) -> dict[str, Any] | None:
        if not _holds_claim(state):
            return None  # lost the job (lease lapsed and another agent took over)
        return {"council_claim": {"owner": INSTANCE_ID, "until": time.time() + _COUNCIL_LEASE_S}}

    return _merge_with(session_id, decide, fresh=True) is not None


def _heartbeat(session_id: str, stop: threading.Event) -> None:
    while not stop.wait(_COUNCIL_HEARTBEAT_S):
        try:
            if not _renew_council_lease(session_id):
                return
        except Exception as exc:  # noqa: BLE001 - keep the job alive; the lease merely lapses sooner
            log.warning("Council lease renewal failed for %s: %s", session_id, type(exc).__name__)


def _run_council(session_id: str) -> None:
    if not _claim_council(session_id):
        return  # finished, exhausted, or another agent is on it
    stop = threading.Event()
    threading.Thread(target=_heartbeat, args=(session_id, stop), daemon=True, name=f"council-hb-{session_id}").start()
    try:
        # Reads live state at call time - not a snapshot captured at kickoff - so it
        # picks up whatever clarifying Q&A the candidate raised while the
        # generator/critic/reconcile calls were in flight (PRD 5 step 5).
        result = council_node(get_state(session_id))
    except Exception as exc:  # noqa: BLE001 - recorded, then retried by the sweeper up to the attempt cap
        # Type only: a model/HTTP error message can quote the prompt (H15).
        log.warning("Council attempt failed for session %s: %s", session_id, type(exc).__name__)
        _merge_with(session_id, _after_failed_attempt, fresh=True)
        return
    finally:
        stop.set()

    def finish(state: InterviewState) -> dict[str, Any] | None:
        if not _holds_claim(state):
            # Our lease lapsed and another agent took the job over: its result stands, this one is dropped
            # (and must not overwrite a reference that is already in use).
            return None
        return {**result, "council_status": "done", "council_claim": None}

    _merge_with(session_id, finish, fresh=True)


def _after_failed_attempt(state: InterviewState) -> dict[str, Any] | None:
    if not _holds_claim(state):
        return None
    if int(state.get("council_attempts") or 0) >= _COUNCIL_MAX_ATTEMPTS:
        # Out of attempts: stop trying (and paying). Review degrades gracefully without a reference.
        return {"council_running": False, "council_status": "failed", "council_claim": None}
    return {"council_status": "pending", "council_claim": None}  # the sweeper retries it


def resume_stalled_councils() -> int:
    """Start (or take over) every unfinished Council job nobody is running. Called at startup, so a
    restarted agent picks up what its previous incarnation was doing, and then periodically, so a job
    whose runner died on ANOTHER agent is also taken over. Returns how many were started."""
    started = 0
    for session_id in store.find_running_councils(_COUNCIL_RESUME_WINDOW_S):
        if _claimable(get_state(session_id), time.time()):
            _spawn_council(session_id)
            started += 1
    return started


def start_council_sweeper() -> threading.Event:
    """Background loop running `resume_stalled_councils`; set the returned event to stop it."""
    stop = threading.Event()

    def loop() -> None:
        while not stop.is_set():
            try:
                resume_stalled_councils()
            except Exception as exc:  # noqa: BLE001 - never let the sweeper die
                log.warning("Council sweep failed: %s", type(exc).__name__)
            stop.wait(_COUNCIL_SWEEP_INTERVAL_S)

    threading.Thread(target=loop, daemon=True, name="council-sweeper").start()
    return stop


def add_candidate_turn(session_id: str, text: str) -> InterviewState:
    state = _merge(session_id, {"transcript": [{"sender": "candidate", "text": text, "kind": ""}]})
    return _merge(session_id, interview_node(state))


def run_review(session_id: str, final_diagram: dict) -> list[dict]:
    state = _merge(session_id, {"final_diagram": final_diagram})
    # The reference may still be being generated (the candidate submitted early, or the Council was
    # taken over after a crash): give it a moment rather than reviewing against nothing.
    deadline = time.monotonic() + _REVIEW_WAIT_FOR_COUNCIL_S
    while state.get("council_running") and not state.get("reference_solution") and time.monotonic() < deadline:
        time.sleep(0.5)
        state = get_state(session_id)
    if not state.get("reference_solution"):
        log.warning("Reviewing session %s without a reference solution (council %s)", session_id, state.get("council_status"))
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
