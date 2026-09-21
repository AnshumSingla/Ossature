"""Smoke tests. Run in stub mode (no OPENAI_API_KEY needed)."""

from __future__ import annotations

import time

from fastapi.testclient import TestClient

from app import turns
from app.graph.nodes.interview import interview_node
from app.main import app

client = TestClient(app)


def test_health_reports_stub_mode():
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["stubMode"] is True


def test_start_then_transcript_then_submit_flow():
    sid = "test-session-1"
    start = client.post(
        f"/agent/sessions/{sid}/start",
        json={"sessionId": sid, "prompt": "Design a URL shortener", "hasVettedReference": False},
    ).json()
    assert start["text"]

    turn = client.post(
        f"/agent/sessions/{sid}/transcript",
        json={"sessionId": sid, "text": "What's the expected read/write ratio?"},
    ).json()
    assert "text" in turn

    review = client.post(
        f"/agent/sessions/{sid}/submit",
        json={"sessionId": sid, "finalDiagram": {"nodes": [], "edges": []}},
    ).json()
    assert isinstance(review["flaws"], list) and review["flaws"]

    debrief = client.post(
        f"/agent/sessions/{sid}/debrief",
        json={"sessionId": sid, "interimSummaries": []},
    ).json()
    assert set(debrief) >= {"strengths", "gaps", "unresolved", "referenceComparison"}


def test_interview_node_relays_clarifying_qa_when_no_reference_yet():
    # Unit-level: isolates the relay logic from the background-council race below,
    # since in stub mode Council can finish before the next HTTP call lands.
    state = {
        "prompt": "Design a rate limiter",
        "transcript": [{"sender": "candidate", "text": "What's the expected RPS?", "kind": ""}],
        "reference_solution": {},
    }
    update = interview_node(state)
    assert update["transcript"][0]["kind"] == "clarifying"
    assert update["clarifying_qa"] == [
        {
            "candidateQuestion": "What's the expected RPS?",
            "aiAnswer": update["transcript"][0]["text"],
        }
    ]


def test_council_runs_in_background_and_produces_reference():
    sid = "test-session-council-bg"
    client.post(
        f"/agent/sessions/{sid}/start",
        json={"sessionId": sid, "prompt": "Design a rate limiter", "hasVettedReference": False},
    )

    # Council runs on a background thread (PRD 6.3 concurrency note); give it a moment.
    for _ in range(20):
        if turns.get_state(sid).get("council_done"):
            break
        time.sleep(0.05)

    state = turns.get_state(sid)
    assert state["council_done"] is True
    assert state["council_running"] is False
    assert state["reference_solution"]


def test_debrief_prefers_caller_supplied_interim_summaries():
    sid = "test-session-debrief-summaries"
    client.post(
        f"/agent/sessions/{sid}/start",
        json={"sessionId": sid, "prompt": "Design a chat system", "hasVettedReference": False},
    )
    result = turns.run_debrief(sid, ["candidate proposed a websocket gateway"])
    assert set(result) >= {"strengths", "gaps", "unresolved", "reference_comparison"}
