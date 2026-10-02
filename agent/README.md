# agent — LangGraph interview service

Owns the interview state graph and every LLM call (PRD §6.3). The Spring Boot backend calls
this over plain HTTP.

## Run

```bash
python -m venv .venv
./.venv/Scripts/pip install -r requirements-dev.txt
cp .env.example .env            # set OPENAI_API_KEY for real LLM calls; blank = stub mode
docker compose up -d postgres   # from the repo root - optional, see "Session-state durability"
./.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000
```

## Endpoints

| Method + path | Node(s) | Purpose |
|---|---|---|
| `GET    /health` | — | liveness + whether stub mode is active |
| `POST   /agent/sessions/{id}/start` | interview + council (background thread) | open the session, return the opening turn |
| `POST   /agent/sessions/{id}/transcript` | interview | feed a finalized transcript segment, return an AI turn |
| `POST   /agent/sessions/{id}/submit` | review | diff the locked diagram vs. the reference, return prioritized flaws |
| `POST   /agent/sessions/{id}/summary` | summary | produce one interim rolling summary |
| `POST   /agent/sessions/{id}/debrief` | debrief | final debrief from the interim summaries |
| `DELETE /agent/sessions/{id}` | — | purge this session's durable working-state row (called by the backend after it holds the debrief) |

## Layout

```
app/
  main.py        FastAPI routes
  config.py      env-driven settings (pydantic-settings)
  schemas.py     request/response models (aliased to the backend's camelCase)
  llm.py         model factory; FakeListChatModel when no API key
  store.py       Postgres-backed session-state durability (best-effort; see docs/architecture.md)
  security.py    optional shared-secret check on the backend->agent bridge (see docs/architecture.md)
  turns.py       per-session state + review/summary/debrief triggers
  graph/
    state.py     InterviewState (TypedDict with reducers)
    build.py     fan-out/fan-in graph shape (interview ∥ council → join) - docs/tests only,
                 not the live per-request path; see docs/architecture.md
    nodes/       interview, council, review, summary, debrief
tests/
  test_graph.py  end-to-end smoke tests in stub mode + a Postgres-gated durability test
```

## Session-state durability

Session state (transcript, clarifying Q&A, reference solution, interim summaries) is
cached in-process but write-through persisted to Postgres, so it survives an agent
restart. This is entirely optional for local dev: if Postgres isn't running, the agent
falls back to today's in-memory-only behavior with no crash (see `app/store.py`'s
docstring, and `docs/architecture.md` "Agent session-state durability" for the full
design). `docker compose up -d postgres` from the repo root is enough - the agent creates
its own table on startup.

## Tests

```bash
./.venv/Scripts/python -m pytest
```

One test (`test_session_state_survives_a_simulated_restart`) needs Postgres reachable and
skips cleanly otherwise - `docker compose up -d postgres` first to include it.
