# agent — LangGraph interview service

Owns the interview state graph and every LLM call (PRD §6.3). The Spring Boot backend calls
this over plain HTTP.

## Run

```bash
python -m venv .venv
./.venv/Scripts/pip install -r requirements-dev.txt
cp .env.example .env            # set OPENAI_API_KEY for real LLM calls; blank = stub mode
./.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000
```

## Endpoints

| Method + path | Node(s) | Purpose |
|---|---|---|
| `GET  /health` | — | liveness + whether stub mode is active |
| `POST /agent/sessions/{id}/start` | interview + council (fan-out) | open the session, return the opening turn |
| `POST /agent/sessions/{id}/transcript` | interview | feed a finalized transcript segment, return an AI turn |
| `POST /agent/sessions/{id}/submit` | review | diff the locked diagram vs. the reference, return prioritized flaws |
| `POST /agent/sessions/{id}/summary` | summary | produce one interim rolling summary |
| `POST /agent/sessions/{id}/debrief` | debrief | final debrief from the interim summaries |

## Layout

```
app/
  main.py        FastAPI routes
  config.py      env-driven settings (pydantic-settings)
  schemas.py     request/response models (aliased to the backend's camelCase)
  llm.py         model factory; FakeListChatModel when no API key
  turns.py       per-session state + review/summary/debrief triggers
  graph/
    state.py     InterviewState (TypedDict with reducers)
    build.py     fan-out/fan-in graph (interview ∥ council → join)
    nodes/       interview, council, review, summary, debrief
tests/
  test_graph.py  end-to-end smoke test in stub mode
```

## Tests

```bash
./.venv/Scripts/python -m pytest
```
