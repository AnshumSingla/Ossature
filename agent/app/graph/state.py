"""Shared state for the interview graph."""

from __future__ import annotations

import operator
from typing import Annotated, Any, TypedDict


class ChatTurn(TypedDict):
    sender: str  # "candidate" | "ai"
    text: str
    kind: str  # clarifying | probing | filler | flaw  (ai turns only)


class InterviewState(TypedDict, total=False):
    session_id: str
    prompt: str

    # Conversation so far. Reducer appends, so concurrent branches merge cleanly.
    transcript: Annotated[list[ChatTurn], operator.add]

    # Clarifying Q&A relayed to the Council node so the reference reflects the
    # constraints the candidate actually set (PRD 5, step 5).
    clarifying_qa: Annotated[list[dict[str, str]], operator.add]

    # Reference solution: loaded from the bank, or produced by the Council node.
    has_vetted_reference: bool
    reference_solution: dict[str, Any]
    council_running: bool
    council_done: bool
    # Council job lifecycle (A2). status: pending (needs a runner) | running (claimed) | done | failed.
    # The claim is a lease {"owner": instance id, "until": epoch seconds}: whoever holds an unexpired one
    # is running the job; if it dies the lease lapses and any agent may take the job over. `attempts`
    # caps how often the (paid) job may be started for one session.
    council_status: str
    council_claim: dict[str, Any] | None
    council_attempts: int

    # Submit / review (PRD 6.3 node 3).
    final_diagram: dict[str, Any]
    flaws: list[dict[str, Any]]

    # Rolling summaries (PRD 6.3 node 4).
    interim_summaries: Annotated[list[str], operator.add]

    # Final debrief (PRD 6.3 node 5).
    debrief: dict[str, Any]
