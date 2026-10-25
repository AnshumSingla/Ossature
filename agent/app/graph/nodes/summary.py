"""Summary node - timed rolling summaries (~every 6 min; PRD 6.3 node 4 / FR9)."""

from __future__ import annotations

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.llm import fast_model
from app.prompting import plain, untrusted, with_rules

SYSTEM = """Summarize this slice of a system design interview in 4-6 bullet points:
what the candidate proposed, decisions made, open threads, and any concerns raised.
This feeds the final debrief, so keep it factual and compact."""

MAX_SUMMARY_CHARS = 2000


def summary_node(state: InterviewState) -> InterviewState:
    # The conversation includes everything the candidate said: untrusted. A summary is stored and
    # later fed to the debrief model, so an injection that survives here would persist.
    window = untrusted("conversation", _format_transcript(state.get("transcript", [])), 20000)
    model = fast_model()
    content = model.invoke(
        [SystemMessage(content=with_rules(SYSTEM)), HumanMessage(content=window)]
    ).content
    return {"interim_summaries": [plain(content, MAX_SUMMARY_CHARS)]}


def _format_transcript(turns: list[dict]) -> str:
    return "\n".join(f"{t['sender']}: {t['text']}" for t in turns) or "(no conversation yet)"
