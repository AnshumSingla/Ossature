"""FastAPI surface the Spring Boot backend calls (PRD 6.1).

The backend owns sessions, WebSocket fan-out and persistence; this service owns the
LangGraph graph and every LLM call.
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import store, turns
from app.config import get_settings
from app.schemas import (
    DebriefRequest,
    DebriefResponse,
    ReviewResponse,
    StartRequest,
    SubmitRequest,
    SummaryRequest,
    SummaryResponse,
    TranscriptRequest,
    TurnResponse,
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    store.ensure_schema()
    yield


app = FastAPI(title="Ossature Interview Agent", version="0.1.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    s = get_settings()
    return {"status": "ok", "stubMode": s.stub_mode}


@app.post("/agent/sessions/{session_id}/start", response_model=TurnResponse)
def start(session_id: str, req: StartRequest) -> TurnResponse:
    state = turns.start_session(
        session_id, req.prompt, req.has_vetted_reference, req.reference_solution
    )
    ai = turns.last_ai_turn(state) or {"text": "", "kind": "clarifying"}
    return TurnResponse(
        text=ai["text"],
        kind=ai.get("kind") or "clarifying",
        council_running=bool(state.get("council_running")),
    )


@app.post("/agent/sessions/{session_id}/transcript", response_model=TurnResponse)
def transcript(session_id: str, req: TranscriptRequest) -> TurnResponse:
    state = turns.add_candidate_turn(session_id, req.text)
    ai = turns.last_ai_turn(state) or {"text": "", "kind": "clarifying"}
    return TurnResponse(
        text=ai["text"],
        kind=ai.get("kind") or "clarifying",
        council_running=bool(state.get("council_running")),
    )


@app.post("/agent/sessions/{session_id}/submit", response_model=ReviewResponse)
def submit(session_id: str, req: SubmitRequest) -> ReviewResponse:
    flaws = turns.run_review(session_id, req.final_diagram)
    return ReviewResponse(
        flaws=[
            {
                "summary": f.get("summary", ""),
                "question_prompt": f.get("question_prompt", ""),
                "priority": int(f.get("priority", 1)),
            }
            for f in flaws
        ]
    )


@app.post("/agent/sessions/{session_id}/summary", response_model=SummaryResponse)
def summary(session_id: str, req: SummaryRequest) -> SummaryResponse:
    content = turns.run_summary(session_id, req.ordinal)
    return SummaryResponse(ordinal=req.ordinal, content=content)


@app.post("/agent/sessions/{session_id}/debrief", response_model=DebriefResponse)
def debrief(session_id: str, req: DebriefRequest) -> DebriefResponse:
    d = turns.run_debrief(session_id, req.interim_summaries)
    return DebriefResponse(
        strengths=d.get("strengths", []),
        gaps=d.get("gaps", []),
        unresolved=d.get("unresolved", []),
        reference_comparison=d.get("reference_comparison", ""),
    )


@app.delete("/agent/sessions/{session_id}", status_code=204, response_model=None)
def end_session(session_id: str):
    """Called by the backend once it holds the debrief durably - this session's working
    copy (transcript, clarifying Q&A, reference solution) is no longer needed."""
    turns.end_session(session_id)
