"""A2: the Council job survives the agent dying, runs once, and the Review copes without its result.

Runs against the real Postgres as the real `ossature_agent` role (skips when it is not reachable),
with unique session ids. The kill test starts a second Python process, lets it claim the job and be
mid-run, kills it, and checks that this process takes the job over.
"""

from __future__ import annotations

import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import pytest

from app import store, turns
from app.graph.nodes import review as review_module
from app.graph.nodes.review import NO_REFERENCE_FLAW, review_node

AGENT_DIR = Path(__file__).resolve().parent.parent


def _usable() -> bool:
    store._reset_breaker()
    store.check_connection()
    return not store._breaker_open()


pytestmark = pytest.mark.skipif(not _usable(), reason="Postgres / agent_sessions not available")

RESULT = {
    "reference_solution": {"summary": "generated reference", "diagram": {"nodes": [], "edges": []}},
    "council_running": False,
    "council_done": True,
}


@pytest.fixture
def sid():
    session_id = f"test-council-{uuid.uuid4().hex[:12]}"
    yield session_id
    store._reset_breaker()
    store.delete_session(session_id)
    for cache in (turns._SESSIONS, turns._VERSIONS, turns._SESSION_LOCKS):
        cache.pop(session_id, None)
    turns._COUNCIL_THREADS.discard(session_id)


@pytest.fixture(autouse=True)
def fast_timings(monkeypatch):
    monkeypatch.setattr(turns, "_COUNCIL_LEASE_S", 1.5)
    monkeypatch.setattr(turns, "_COUNCIL_HEARTBEAT_S", 0.4)
    monkeypatch.setattr(turns, "_REVIEW_WAIT_FOR_COUNCIL_S", 0.3)


def _wait(predicate, seconds: float = 10.0) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.1)
    return False


def _stored(sid: str) -> dict:
    return store.load(sid).state


def _start_without_running(sid: str, monkeypatch) -> None:
    """Create a session whose Council needs a runner, without letting the real thread race the test."""
    monkeypatch.setattr(turns, "_spawn_council", lambda _sid: None)
    turns.start_session(sid, "Design a URL shortener", False, {})


# ---------------------------------------------------------------- lifecycle


def test_a_session_without_a_reference_runs_the_council_to_done(sid, monkeypatch):
    monkeypatch.setattr(turns, "council_node", lambda state: RESULT)
    turns.start_session(sid, "Design a URL shortener", False, {})
    assert _wait(lambda: _stored(sid).get("council_status") == "done")
    state = _stored(sid)
    assert state["reference_solution"]["summary"] == "generated reference"
    assert state["council_running"] is False and state["council_done"] is True
    assert state["council_claim"] is None
    assert state["council_attempts"] == 1


def test_the_status_is_pending_until_someone_runs_it(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)
    state = _stored(sid)
    assert (state["council_status"], state["council_running"], state["council_attempts"]) == ("pending", True, 0)


def test_a_vetted_reference_needs_no_council(sid):
    turns.start_session(sid, "Design X", True, {"summary": "vetted"})
    assert _stored(sid)["council_status"] == "done"
    assert not turns._claimable(turns.get_state(sid), time.time())


# ---------------------------------------------------------------- runs once (A2.3)


def test_of_many_simultaneous_claims_exactly_one_wins(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)
    results: list[bool] = []
    barrier = threading.Barrier(8)

    def claim() -> None:
        barrier.wait()
        results.append(turns._claim_council(sid))

    threads = [threading.Thread(target=claim) for _ in range(8)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert results.count(True) == 1
    assert _stored(sid)["council_attempts"] == 1


def test_a_second_trigger_while_the_job_runs_is_a_no_op(sid, monkeypatch):
    calls: list[int] = []
    release = threading.Event()

    def slow(state):
        calls.append(1)
        release.wait(5)
        return RESULT

    monkeypatch.setattr(turns, "council_node", slow)
    turns.start_session(sid, "Design X", False, {})
    assert _wait(lambda: len(calls) == 1)
    assert turns._claim_council(sid) is False  # the live lease holds it
    turns._run_council(sid)  # as if resumed by a second trigger: returns at once, runs nothing
    assert len(calls) == 1
    release.set()
    assert _wait(lambda: _stored(sid).get("council_status") == "done")
    assert len(calls) == 1
    assert _stored(sid)["council_attempts"] == 1


def test_a_finished_job_is_never_claimed_again(sid, monkeypatch):
    monkeypatch.setattr(turns, "council_node", lambda state: RESULT)
    turns.start_session(sid, "Design X", False, {})
    assert _wait(lambda: _stored(sid).get("council_status") == "done")
    assert turns._claim_council(sid) is False
    assert turns.resume_stalled_councils() >= 0
    assert _stored(sid)["council_attempts"] == 1


# ---------------------------------------------------------------- failure, retries, cap


def test_a_failed_attempt_is_retried_then_given_up_on(sid, monkeypatch):
    attempts: list[int] = []

    def boom(state):
        attempts.append(1)
        raise RuntimeError("model unavailable: the prompt was 'secret question text'")

    monkeypatch.setattr(turns, "council_node", boom)
    _start_without_running(sid, monkeypatch)

    turns._run_council(sid)
    state = _stored(sid)
    assert (state["council_status"], state["council_running"], state["council_claim"]) == ("pending", True, None)
    turns._run_council(sid)
    turns._run_council(sid)
    state = _stored(sid)
    assert len(attempts) == 3
    assert (state["council_status"], state["council_running"]) == ("failed", False)

    turns._run_council(sid)  # nothing left to try, and nothing is billed
    assert len(attempts) == 3
    assert not turns._claimable(turns.get_state(sid), time.time())


def test_a_failure_is_logged_by_type_not_message(sid, monkeypatch, caplog):
    monkeypatch.setattr(turns, "council_node", lambda s: (_ for _ in ()).throw(RuntimeError("secret question text")))
    _start_without_running(sid, monkeypatch)
    with caplog.at_level("WARNING"):
        turns._run_council(sid)
    assert "RuntimeError" in caplog.text
    assert "secret question text" not in caplog.text


def test_a_job_that_keeps_crashing_the_agent_is_not_restarted_forever(sid, monkeypatch):
    """Crashes never reach the failure handler, so the attempt count (bumped on every claim) is what stops it."""
    _start_without_running(sid, monkeypatch)
    turns._merge(sid, {"council_status": "running", "council_attempts": turns._COUNCIL_MAX_ATTEMPTS,
                       "council_claim": {"owner": "dead-instance", "until": time.time() - 1}})
    assert turns._claimable(turns.get_state(sid), time.time()) is False
    assert turns._claim_council(sid) is False


# ---------------------------------------------------------------- lease takeover


def test_a_lapsed_lease_is_taken_over_and_completed(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)
    # The previous runner claimed the job and died: its lease ran out a moment ago.
    turns._merge(sid, {"council_status": "running", "council_attempts": 1,
                       "council_claim": {"owner": "dead-instance", "until": time.time() - 1}})
    monkeypatch.setattr(turns, "council_node", lambda state: RESULT)
    turns._run_council(sid)
    state = _stored(sid)
    assert state["council_status"] == "done" and state["reference_solution"]["summary"] == "generated reference"
    assert state["council_attempts"] == 2


def test_a_live_lease_is_respected(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)
    turns._merge(sid, {"council_status": "running", "council_attempts": 1,
                       "council_claim": {"owner": "other-instance", "until": time.time() + 60}})
    assert turns._claim_council(sid) is False


def test_a_runner_that_lost_its_lease_cannot_overwrite_the_new_owners_result(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)

    def slow_then_stale(state):
        # While this (stale) run is in flight, its lease lapses and another agent takes over and finishes.
        turns._merge(sid, {**RESULT, "reference_solution": {"summary": "the new owner's reference"},
                           "council_status": "done", "council_claim": None})
        return {**RESULT, "reference_solution": {"summary": "stale result"}}

    monkeypatch.setattr(turns, "council_node", slow_then_stale)
    turns._run_council(sid)
    assert _stored(sid)["reference_solution"]["summary"] == "the new owner's reference"


def test_the_heartbeat_keeps_a_long_job_from_being_taken_over(sid, monkeypatch):
    release = threading.Event()
    monkeypatch.setattr(turns, "council_node", lambda s: (release.wait(10), RESULT)[1])
    turns.start_session(sid, "Design X", False, {})
    assert _wait(lambda: _stored(sid).get("council_status") == "running")
    time.sleep(turns._COUNCIL_LEASE_S + 1.0)  # longer than the lease: only renewals keep it alive
    assert turns._claim_council(sid) is False
    release.set()
    assert _wait(lambda: _stored(sid).get("council_status") == "done")


def test_sessions_idle_beyond_the_window_are_not_resumed(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)
    monkeypatch.setattr(turns, "_COUNCIL_RESUME_WINDOW_S", 0.0)
    assert sid not in store.find_running_councils(turns._COUNCIL_RESUME_WINDOW_S)
    assert sid in store.find_running_councils(3600)


# ---------------------------------------------------------------- A2.4 review without a reference


def test_review_without_a_reference_says_so_and_does_not_call_a_model(monkeypatch):
    def no_model():
        raise AssertionError("the review model must not be asked to compare against nothing")

    monkeypatch.setattr(review_module, "review_model", no_model)
    flaws = review_node({"final_diagram": {"nodes": [{"id": "a"}], "edges": []}, "reference_solution": {}})
    assert flaws == {"flaws": [NO_REFERENCE_FLAW]}
    assert "reference" in NO_REFERENCE_FLAW["question_prompt"].lower()


def test_run_review_waits_briefly_for_the_council_then_degrades(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)  # council pending, no reference
    started = time.monotonic()
    flaws = turns.run_review(sid, {"nodes": [], "edges": []})
    assert time.monotonic() - started >= 0.3  # it did wait
    assert flaws == [NO_REFERENCE_FLAW]


def test_run_review_uses_the_reference_when_it_arrives_while_waiting(sid, monkeypatch):
    _start_without_running(sid, monkeypatch)
    monkeypatch.setattr(turns, "_REVIEW_WAIT_FOR_COUNCIL_S", 5.0)
    threading.Timer(0.6, lambda: turns._merge(sid, RESULT)).start()
    flaws = turns.run_review(sid, {"nodes": [], "edges": []})
    assert flaws and flaws != [NO_REFERENCE_FLAW]


# ---------------------------------------------------------------- A2.5 kill and restart


CHILD = """
import sys, time
sys.path.insert(0, {agent_dir!r})
from app import turns
turns._COUNCIL_LEASE_S = 1.5
turns._COUNCIL_HEARTBEAT_S = 0.4
turns.council_node = lambda state: time.sleep(120)   # a Council that never finishes: we kill it mid-run
turns.start_session({sid!r}, "Design a URL shortener", False, {{}})
print("started", flush=True)
time.sleep(120)
"""


def test_kill_the_agent_mid_council_and_a_restarted_agent_finishes_the_job(sid, monkeypatch, tmp_path):
    script = tmp_path / "child.py"
    script.write_text(CHILD.format(agent_dir=str(AGENT_DIR), sid=sid))
    child = subprocess.Popen([sys.executable, str(script)], cwd=AGENT_DIR, stdout=subprocess.PIPE, text=True)
    try:
        assert _wait(lambda: (store.load(sid) or store.Loaded({}, 0)).state.get("council_status") == "running", 30)
        claim = _stored(sid)["council_claim"]
        assert claim["owner"] != turns.INSTANCE_ID  # held by the other process, not by us
    finally:
        child.kill()  # the "crash": no cleanup code runs
        child.wait()

    # Nobody renews the lease any more, so it lapses; until then the job is protected from a takeover.
    assert turns._claim_council(sid) is False
    calls: list[int] = []
    monkeypatch.setattr(turns, "council_node", lambda state: calls.append(1) or RESULT)
    assert _wait(lambda: turns._claimable(turns.get_state(sid), time.time()), 10)

    # "Restart": the new process's startup sweep finds the unfinished job and takes it over.
    assert turns.resume_stalled_councils() >= 1
    assert _wait(lambda: _stored(sid).get("council_status") == "done")
    state = _stored(sid)
    assert state["reference_solution"]["summary"] == "generated reference"
    assert state["council_attempts"] == 2  # the killed run, then the takeover
    assert calls == [1]  # run exactly once after the crash
