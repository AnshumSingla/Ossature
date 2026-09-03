"""Debrief node - fires at session end (PRD 6.3 node 5 / FR10).

Works from the interim summaries (cheaper than re-processing the full transcript) plus the
reference solution, and produces strengths / gaps / unresolved / reference comparison.
"""

from __future__ import annotations

import json

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.llm import fast_model

SYSTEM = """You are writing the debrief for a system design interview. Using the interim
summaries and the reference solution, return a JSON object with keys:
  "strengths": string[]
  "gaps": string[]
  "unresolved": string[]
  "reference_comparison": string  (how the candidate's design compared to the reference)
Be specific and fair. No numeric score."""


def debrief_node(state: InterviewState) -> InterviewState:
    summaries = "\n\n".join(state.get("interim_summaries", [])) or "(no interim summaries)"
    reference = json.dumps(state.get("reference_solution", {}))[:6000]

    model = fast_model()
    raw = model.invoke(
        [
            SystemMessage(content=SYSTEM),
            HumanMessage(content=f"Interim summaries:\n{summaries}\n\nReference:\n{reference}"),
        ]
    ).content

    return {"debrief": _parse(raw)}


def _parse(raw: str) -> dict:
    try:
        data = json.loads(raw)
        if isinstance(data, dict):
            return data
    except (json.JSONDecodeError, TypeError):
        pass
    return {
        "strengths": [],
        "gaps": [],
        "unresolved": ["Debrief generation unavailable in stub mode."],
        "reference_comparison": "",
    }
