"""Per-session state + the non-conversational triggers (review, summary, debrief).

State is held in-process. Fine for the MVP (one agent instance); a durable
LangGraph checkpointer (Postgres/Redis) is the Phase-2+ upgrade path.
"""

from __future__ import annotations

from app.graph.build import interview_graph
from app.graph.nodes.debrief import debrief_node
from app.graph.nodes.review import review_node
from app.graph.nodes.summary import summary_node
from app.graph.state import InterviewState

_SESSIONS: dict[str, InterviewState] = {}


def get_state(session_id: str) -> InterviewState:
    return _SESSIONS.setdefault(session_id, {"session_id": session_id, "transcript": []})


def start_session(
    session_id: str, prompt: str, has_vetted_reference: bool, reference_solution: dict
) -> InterviewState:
    state: InterviewState = {
        "session_id": session_id,
        "prompt": prompt,
        "transcript": [],
        "clarifying_qa": [],
        "has_vetted_reference": has_vetted_reference,
        "reference_solution": reference_solution or {},
        "council_running": not has_vetted_reference,
        "council_done": False,
        "interim_summaries": [],
    }
    state = interview_graph.invoke(state)  # type: ignore[assignment]
    _SESSIONS[session_id] = state
    return state


def add_candidate_turn(session_id: str, text: str) -> InterviewState:
    state = get_state(session_id)
    state = {
        **state,
        "transcript": state.get("transcript", [])
        + [{"sender": "candidate", "text": text, "kind": ""}],
    }
    state = interview_graph.invoke(state)  # type: ignore[assignment]
    _SESSIONS[session_id] = state
    return state


def run_review(session_id: str, final_diagram: dict) -> list[dict]:
    state = get_state(session_id)
    state = {**state, "final_diagram": final_diagram}
    result = review_node(state)
    _SESSIONS[session_id] = {**state, **result}
    return result.get("flaws", [])


def run_summary(session_id: str, ordinal: int) -> str:
    state = get_state(session_id)
    result = summary_node(state)
    _SESSIONS[session_id] = {**state, **result}
    content = (result.get("interim_summaries") or [""])[0]
    return content


def run_debrief(session_id: str) -> dict:
    state = get_state(session_id)
    result = debrief_node(state)
    _SESSIONS[session_id] = {**state, **result}
    return result.get("debrief", {})


def last_ai_turn(state: InterviewState) -> dict | None:
    for turn in reversed(state.get("transcript", [])):
        if turn["sender"] == "ai":
            return turn
    return None
