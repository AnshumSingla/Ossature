"""Interview AI node - the only node that speaks to the candidate (PRD 6.3 node 1)."""

from __future__ import annotations

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.llm import fast_model

SYSTEM = """You are a senior engineer running a system design interview.
You speak with one consistent voice. Depending on what the conversation calls for you:
- answer the candidate's clarifying questions about the prompt directly;
- once a reference solution is available, ask probing questions that draw on it WITHOUT
  revealing it;
- while the review is running, ask generic wrap-up questions to avoid dead air;
- after submit, ask targeted questions about each flagged flaw.
Keep turns short and conversational - this is spoken aloud."""


def interview_node(state: InterviewState) -> InterviewState:
    last_candidate = _last_candidate_turn(state)
    if not last_candidate:
        # Opening turn.
        opening = (
            f"Let's get started. Here's your prompt: {state['prompt']}\n\n"
            "Take a moment, then walk me through your thinking. Ask me anything you need."
        )
        return {"transcript": [{"sender": "ai", "text": opening, "kind": "clarifying"}]}

    ref = state.get("reference_solution") or {}
    context = ""
    if ref:
        context = f"\n\nReference solution (do not reveal): {ref.get('summary', ref)}"

    model = fast_model()
    reply = model.invoke(
        [
            SystemMessage(content=SYSTEM + context),
            HumanMessage(content=f"Candidate just said: {last_candidate}"),
        ]
    )
    kind = "probing" if ref else "clarifying"
    update: InterviewState = {"transcript": [{"sender": "ai", "text": reply.content, "kind": kind}]}
    if kind == "clarifying":
        # Relayed to the Council node so the reference reflects constraints the
        # candidate actually established, not generic assumptions (PRD 5 step 5).
        update["clarifying_qa"] = [{"candidateQuestion": last_candidate, "aiAnswer": reply.content}]
    return update


def _last_candidate_turn(state: InterviewState) -> str | None:
    for turn in reversed(state.get("transcript", [])):
        if turn["sender"] == "candidate":
            return turn["text"]
    return None
