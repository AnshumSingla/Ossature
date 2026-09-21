"""Per-session state + node orchestration for triggers outside the plain conversation.

Council runs on a background thread rather than inline in a graph invoke, because a real
fan-out here has to span multiple HTTP requests - the candidate keeps talking while it
works - and a single `graph.invoke()` call can't do that on its own (PRD 6.3
"Concurrency note"). `interview_node` is called directly per turn for the same reason;
`app.graph.build` is kept for the graph *shape* (fan-out/fan-in wiring, tests) rather
than as the live per-request path.

State is held in-process. Fine for the MVP (one agent instance); a durable LangGraph
checkpointer (Postgres/Redis) is the upgrade path before running more than one.
"""

from __future__ import annotations

import threading
from typing import Any

from app.graph.nodes.council import council_node
from app.graph.nodes.debrief import debrief_node
from app.graph.nodes.interview import interview_node
from app.graph.nodes.review import review_node
from app.graph.nodes.summary import summary_node
from app.graph.state import InterviewState

_SESSIONS: dict[str, InterviewState] = {}
_LOCK = threading.Lock()


def get_state(session_id: str) -> InterviewState:
    with _LOCK:
        state = _SESSIONS.setdefault(session_id, {"session_id": session_id, "transcript": []})
        return dict(state)


def _merge(session_id: str, update: dict[str, Any]) -> InterviewState:
    """Apply `update` the way the state's reducers would: list fields concatenate,
    everything else is last-write-wins (mirrors app.graph.state.InterviewState)."""
    with _LOCK:
        current = _SESSIONS.get(session_id, {"session_id": session_id})
        merged = dict(current)
        for key, value in update.items():
            prior = merged.get(key)
            if isinstance(value, list) and isinstance(prior, list):
                merged[key] = prior + value
            else:
                merged[key] = value
        _SESSIONS[session_id] = merged
        return dict(merged)


def start_session(
    session_id: str, prompt: str, has_vetted_reference: bool, reference_solution: dict
) -> InterviewState:
    with _LOCK:
        _SESSIONS[session_id] = {
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

    state = _merge(session_id, interview_node(get_state(session_id)))

    if state.get("council_running"):
        _spawn_council(session_id)
    return state


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
