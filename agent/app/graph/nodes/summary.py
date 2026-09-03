"""Summary node - timed rolling summaries (~every 6 min; PRD 6.3 node 4 / FR9)."""

from __future__ import annotations

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.llm import fast_model

SYSTEM = """Summarize this slice of a system design interview in 4-6 bullet points:
what the candidate proposed, decisions made, open threads, and any concerns raised.
This feeds the final debrief, so keep it factual and compact."""


def summary_node(state: InterviewState) -> InterviewState:
    window = _format_transcript(state.get("transcript", []))
    model = fast_model()
    content = model.invoke([SystemMessage(content=SYSTEM), HumanMessage(content=window)]).content
    return {"interim_summaries": [content]}


def _format_transcript(turns: list[dict]) -> str:
    return "\n".join(f"{t['sender']}: {t['text']}" for t in turns) or "(no conversation yet)"
