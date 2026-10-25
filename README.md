# Ossature — Live Collaborative Whiteboard System Design Interviewer

A web app that simulates a voice-driven system design interview. The candidate talks through a
design out loud while drawing it on a shared whiteboard; speech is transcribed **on-device**
and streamed to an AI interviewer, whose text replies are spoken back **on-device**. Neither
STT nor TTS touches a paid API.

See [whiteboard-interviewer-prd.md](whiteboard-interviewer-prd.md) for the full product spec and
[docs/architecture.md](docs/architecture.md) for how this repo maps onto it.

## Repository layout

| Path        | Stack                              | Responsibility |
|-------------|------------------------------------|----------------|
| `frontend/` | React 19 + Vite + TypeScript, React Flow | Whiteboard, on-device speech-to-text (Whisper tiny.en in a Web Worker; **English only**) + browser TTS, transcript & chat UI |
| `backend/`  | Spring Boot 4 (Java 21), Maven     | WebSocket session mgr, REST lifecycle API, Postgres persistence / event log, bridge to the agent |
| `agent/`    | Python 3.11, FastAPI, LangGraph    | The interview state graph and every LLM call (Interview / Council / Review / Summary / Debrief nodes) |
| `docker-compose.yml` | Postgres 16 (+ pgAdmin)   | Local infra only |

```
frontend  ──WebSocket + REST──▶  backend  ──HTTP──▶  agent (LangGraph)
                                    │
                                    ▼
                                 Postgres
```

## Prerequisites

- **Node** ≥ 20 (tested on 24)
- **JDK 21** — the backend targets Java 21. Only JDK 11 was on the dev box at scaffold time;
  install Temurin 21 and point `JAVA_HOME` at it before building `backend/`.
- **Python** 3.11
- **Docker** (for local Postgres)

## First-time setup

```bash
cp .env.example .env                      # fill in OPENAI_API_KEY if you want real LLM calls
docker compose up -d postgres             # start Postgres

# frontend
cd frontend && npm install && cp .env.example .env.local && cd ..

# agent
cd agent && python -m venv .venv && ./.venv/Scripts/pip install -r requirements-dev.txt && cp .env.example .env && cd ..

# backend (needs JDK 21)
cd backend && ./mvnw dependency:go-offline && cd ..
```

## Running (three terminals)

```bash
# 1. agent  — http://localhost:8000  (runs in stub mode with no OPENAI_API_KEY)
cd agent && ./.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000

# 2. backend — http://localhost:8080
cd backend && ./mvnw spring-boot:run

# 3. frontend — http://localhost:5173
cd frontend && npm run dev
```

Open http://localhost:5173, pick a prompt, allow microphone access, and start talking.

## Tests

```bash
cd agent && ./.venv/Scripts/python -m pytest      # graph + API smoke tests (stub mode)
cd frontend && npm run build                       # typecheck + production build
cd backend && ./mvnw test                          # needs JDK 21 + Postgres
```

## Phasing (from the PRD §10)

1. **Core loop** — whiteboard + STT + WebSocket transcript streaming + Interview node + background Council node.
2. **Submit & review** — diagram lock, Review node diff, targeted flaw questions, filler questions.
3. **Debrief** — timed Summary nodes, final Debrief node.
4. **Community & polish** — candidate-submitted questions with review workflow, session replay UI.

The scaffold wires an end-to-end vertical slice of phases 1–3; see `docs/architecture.md` for
what is real vs. stubbed.
