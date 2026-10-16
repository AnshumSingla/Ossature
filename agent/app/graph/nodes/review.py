"""Review node - fires once on submit (PRD 6.3 node 3).

Diffs the candidate's locked diagram against the reference diagram and returns a prioritized
list of concrete flaw-based question prompts for the Interview AI node to voice.
"""

from __future__ import annotations

import json

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.guard import parse_json, sanitize_flaws
from app.llm import review_model
from app.prompting import untrusted, with_rules

SYSTEM = """Compare the candidate's system design diagram against the reference design.
Identify concrete gaps: missing components, unaddressed failure modes, scale mismatches,
weak trade-offs. For each, return an object with:
  "summary": the gap, one line
  "question_prompt": how the interviewer should raise it, phrased as a spoken question
  "priority": 1 (most important) .. 5
Return a JSON array, most important first, at most 6 items. Output only the JSON array."""


def review_node(state: InterviewState) -> InterviewState:
    final_diagram = state.get("final_diagram", {})
    reference = state.get("reference_solution", {})

    # Node labels in the diagram are typed by the candidate: untrusted.
    model = review_model()
    raw = model.invoke(
        [
            SystemMessage(content=with_rules(SYSTEM)),
            HumanMessage(
                content=(
                    f"{untrusted('candidate_diagram', json.dumps(final_diagram), 6000)}\n\n"
                    f"{untrusted('reference_solution', json.dumps(reference), 6000)}"
                )
            ),
        ]
    ).content

    return {"flaws": _parse_flaws(raw)}


def _parse_flaws(raw: str) -> list[dict]:
    # Whatever the model returned is forced into a known, bounded shape before anything downstream
    # (the backend, the UI, text-to-speech) sees it.
    flaws = sanitize_flaws(parse_json(raw))
    if flaws:
        return flaws
    # Stub mode / unparseable / nothing usable: one generic follow-up so the flow still progresses.
    return [
        {
            "summary": "Reference comparison unavailable",
            "question_prompt": "Walk me through how your design handles its single biggest "
            "point of failure.",
            "priority": 1,
        }
    ]
