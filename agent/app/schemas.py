"""Request/response models for the HTTP surface the Spring Boot backend calls."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

Diagram = dict[str, Any]  # { "nodes": [...], "edges": [...] }


class StartRequest(BaseModel):
    session_id: str = Field(alias="sessionId")
    prompt: str
    has_vetted_reference: bool = Field(default=False, alias="hasVettedReference")
    reference_solution: dict[str, Any] = Field(default_factory=dict, alias="referenceSolution")

    model_config = {"populate_by_name": True}


class TurnResponse(BaseModel):
    text: str
    kind: Literal["clarifying", "probing", "filler", "flaw"] = "clarifying"
    council_running: bool = Field(default=False, serialization_alias="councilRunning")


class TranscriptRequest(BaseModel):
    session_id: str = Field(alias="sessionId")
    text: str

    model_config = {"populate_by_name": True}


class SubmitRequest(BaseModel):
    session_id: str = Field(alias="sessionId")
    final_diagram: Diagram = Field(default_factory=dict, alias="finalDiagram")

    model_config = {"populate_by_name": True}


class Flaw(BaseModel):
    summary: str
    question_prompt: str = Field(serialization_alias="questionPrompt")
    priority: int = 1


class ReviewResponse(BaseModel):
    flaws: list[Flaw]


class SummaryRequest(BaseModel):
    session_id: str = Field(alias="sessionId")
    ordinal: int
    transcript_window: str = Field(default="", alias="transcriptWindow")
    diagram_state: Diagram = Field(default_factory=dict, alias="diagramState")

    model_config = {"populate_by_name": True}


class SummaryResponse(BaseModel):
    ordinal: int
    content: str


class DebriefRequest(BaseModel):
    session_id: str = Field(alias="sessionId")
    interim_summaries: list[str] = Field(default_factory=list, alias="interimSummaries")
    reference_solution: dict[str, Any] = Field(default_factory=dict, alias="referenceSolution")

    model_config = {"populate_by_name": True}


class DebriefResponse(BaseModel):
    strengths: list[str]
    gaps: list[str]
    unresolved: list[str]
    reference_comparison: str = Field(serialization_alias="referenceComparison")
