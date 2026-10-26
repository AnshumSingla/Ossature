# Ossature — Live Collaborative Whiteboard System Design Interviewer

A web app that simulates a voice-driven system design interview. The candidate talks through a
design out loud while drawing it on a shared whiteboard; speech is transcribed **on-device**
(Whisper tiny.en in a Web Worker, English only) and streamed to an AI interviewer, whose text
replies are spoken back **on-device**. Neither speech leg touches a paid API; audio never leaves
the browser.

How the repo is organised and what each service owns: [docs/architecture.md](docs/architecture.md).

## Repository layout

| Path        | Stack                              | Responsibility |
|-------------|------------------------------------|----------------|
| `frontend/` | React 19 + Vite + TypeScript, React Flow | Whiteboard, on-device speech-to-text and browser TTS, transcript and chat UI, login (access token kept in memory only) |
| `backend/`  | Spring Boot 4 (Java 21), Maven     | REST + WebSocket API, auth (JWT + rotating refresh cookie), Postgres persistence with row-level security, event log, bridge to the agent, retention and account deletion |
| `agent/`    | Python 3.11, FastAPI, LangGraph    | The interview state graph and every LLM call (Interview / Council / Review / Summary / Debrief nodes); working state in Postgres so any instance can serve any session |
| `scripts/`  | Python                             | Dependency audit, SBOM, two-instance check, load check |
| `docs/`     | Markdown                           | Architecture, security plan and test report, data retention, operations runbook |
| `docker-compose.yml` | Postgres 16 (+ pgAdmin)   | Local infrastructure only; not a production deployment |

```
frontend  ──WebSocket + REST──▶  backend  ──HTTP──▶  agent (LangGraph)
                                    │                    │
                                    └──── Postgres ──────┘
```

## Prerequisites

- **Node** ≥ 20 (tested on 22 and 24)
- **JDK 21**: the backend targets Java 21
- **Python** 3.11
- **Docker** (for local Postgres)

## First-time setup

```bash
cp .env.example .env                      # add OPENAI_API_KEY if you want real LLM calls
docker compose up -d postgres             # start Postgres

# frontend
cd frontend && npm install && cp .env.example .env.local && cd ..

# agent
cd agent && python -m venv .venv && ./.venv/Scripts/pip install -r requirements-dev.txt && cp .env.example .env && cd ..

# backend (needs JDK 21)
cd backend && ./mvnw dependency:go-offline && cd ..
```

On macOS/Linux use `.venv/bin/` instead of `.venv/Scripts/`.

Start the backend once before the agent: its migrations create the restricted database roles and the
agent's table.

## Running (three terminals)

```bash
# 1. backend: http://localhost:8080   (applies the database migrations on start)
cd backend && ./mvnw spring-boot:run

# 2. agent: http://localhost:8000   (stub mode with no OPENAI_API_KEY)
cd agent && ./.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000

# 3. frontend: http://localhost:5173
cd frontend && npm run dev
```

Open http://localhost:5173, sign up, pick a prompt, allow microphone access, and start talking. The
speech model (about 41 MB) downloads once and is cached in the browser.

## Tests and checks

```bash
cd backend  && ./mvnw test                         # unit, web, row-level-security and multi-instance tests (needs Postgres)
cd agent    && ./.venv/Scripts/python -m pytest    # graph, store, Council recovery, security tests
cd frontend && npm test && npm run lint && npm run build

python scripts/audit_dependencies.py               # known-vulnerability scan of all three parts
python scripts/two_instance_check.py               # two backends + two agents against one database
python scripts/load_check.py http://localhost:8080 # burst test; run it against a throwaway backend only
```

CI (`.github/workflows/ci.yml`) runs the three test suites, the dependency audit, secret scan
(gitleaks), static analysis (semgrep) and SBOM generation on every pull request.

## Configuration

Everything is configured through environment variables; `.env.example` (root), `agent/.env.example`
and `frontend/.env.example` list them with comments. In production
(`SPRING_PROFILES_ACTIVE=prod`, `APP_ENV=prod`) both services refuse to start on default secrets, default
database passwords or an insecure cookie.

## Security and operations

- [docs/remaining-work-and-security-plan.md](docs/remaining-work-and-security-plan.md): what is done and what is still open
- [docs/security-test-report.md](docs/security-test-report.md): what was tested and the results
- [docs/data-retention.md](docs/data-retention.md): what is stored, for how long, and how deletion works
- [docs/operations-runbook.md](docs/operations-runbook.md): deploy, roll back, rotate secrets, incident playbooks

## Credits

Seed questions and reference answers in the question bank are adapted from [The System Design Primer](https://github.com/donnemartin/system-design-primer) by Donne Martin (CC BY 4.0). Details in [docs/third-party-notices.md](docs/third-party-notices.md).

## Status

Core interview loop, diagram submit and review, timed summaries, debrief, community-submitted
questions with owner review, and the security hardening above are built. Still open: validation of
speech-to-text with real speakers and other browsers, TLS/header checks on a real deployment, a backup
restore rehearsal, email verification and password reset. The full list is in the security plan.
