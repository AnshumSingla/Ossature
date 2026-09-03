"""Review node - fires once on submit (PRD 6.3 node 3).

Diffs the candidate's locked diagram against the reference diagram and returns a prioritized
list of concrete flaw-based question prompts for the Interview AI node to voice.
"""

from __future__ import annotations

import json

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.llm import review_model

SYSTEM = """Compare the candidate's system design diagram against the reference design.
Identify concrete gaps: missing components, unaddressed failure modes, scale mismatches,
weak trade-offs. For each, return an object with:
  "summary": the gap, one line
  "question_prompt": how the interviewer should raise it, phrased as a spoken question
  "priority": 1 (most important) .. 5
Return a JSON array, most important first, at most 6 items."""


def review_node(state: InterviewState) -> InterviewState:
    final_diagram = state.get("final_diagram", {})
    reference = state.get("reference_solution", {})

    model = review_model()
    raw = model.invoke(
        [
            SystemMessage(content=SYSTEM),
            HumanMessage(
                content=(
                    f"Candidate diagram:\n{json.dumps(final_diagram)[:6000]}\n\n"
                    f"Reference:\n{json.dumps(reference)[:6000]}"
                )
            ),
        ]
    ).content

    return {"flaws": _parse_flaws(raw)}


def _parse_flaws(raw: str) -> list[dict]:
    try:
        data = json.loads(raw)
        if isinstance(data, list):
            return data[:6]
    except (json.JSONDecodeError, TypeError):
        pass
    # Stub mode / unparseable: one generic follow-up so the flow still progresses.
    return [
        {
            "summary": "Reference comparison unavailable",
            "question_prompt": "Walk me through how your design handles its single biggest "
            "point of failure.",
            "priority": 1,
        }
    ]
