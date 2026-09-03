"""Smoke tests. Run in stub mode (no OPENAI_API_KEY needed)."""

from __future__ import annotations

from fastapi.testclient import TestClient

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
