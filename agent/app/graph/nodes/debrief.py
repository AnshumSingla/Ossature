"""Debrief node - fires at session end (PRD 6.3 node 5 / FR10).

Works from the interim summaries (cheaper than re-processing the full transcript) plus the
reference solution, and produces strengths / gaps / unresolved / reference comparison.
"""

from __future__ import annotations

import json

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.guard import parse_json, sanitize_debrief
from app.llm import fast_model
from app.prompting import untrusted, with_rules

SYSTEM = """You are writing the debrief for a system design interview. Using the interim
summaries and the reference solution, return a JSON object with keys:
  "strengths": string[]
  "gaps": string[]
  "unresolved": string[]
  "reference_comparison": string  (how the candidate's design compared to the reference)
Be specific and fair. No numeric score. Output only the JSON object."""


def debrief_node(state: InterviewState) -> InterviewState:
    summaries = "\n\n".join(state.get("interim_summaries", [])) or "(no interim summaries)"
    reference = json.dumps(state.get("reference_solution", {}))

    # Summaries were produced from the candidate's own words, so they stay untrusted data here.
    model = fast_model()
    raw = model.invoke(
        [
            SystemMessage(content=with_rules(SYSTEM)),
            HumanMessage(
                content=f"{untrusted('summaries', summaries, 12000)}\n\n{untrusted('reference_solution', reference, 6000)}"
            ),
        ]
    ).content

    return {"debrief": _parse(raw)}


def _parse(raw: str) -> dict:
    # Forced into exactly the four expected fields, bounded, before it is shown to anyone.
    debrief = sanitize_debrief(parse_json(raw))
    if debrief is not None:
        return debrief
    return {
        "strengths": [],
        "gaps": [],
        "unresolved": ["Debrief generation unavailable in stub mode."],
        "reference_comparison": "",
    }
