# Architecture notes

How the three services map onto the PRD, and what is implemented vs. stubbed in the scaffold.

## Service boundaries

- **`backend/` (Spring Boot)** owns sessions, the WebSocket connection, persistence and the
  bridge to the agent. It never calls an LLM. PRD §6.2.
- **`agent/` (LangGraph + FastAPI)** owns the state graph and every LLM call. It is stateless
  over HTTP but keeps per-session graph state in-process (`app/turns.py`). PRD §6.3.
- **`frontend/`** owns the whiteboard, on-device STT/TTS (Web Speech API) and the transcript
  UI. Audio never leaves the browser. PRD §6.5.

## Request flow

1. `POST /api/sessions` → `SessionService.create` persists the session, copies a vetted
   reference solution from the question if present, and calls the agent's `/start`.
2. Client opens `ws://…/ws/interview?sessionId=…&lastSeq=…`. `InterviewWebSocketHandler`
   registers the socket and replays any events after `lastSeq` (resume; PRD FR12 / NFR
   reliability).
3. `transcript.partial` frames are logged only. `transcript.final` frames are logged **and**
   forwarded to the agent's `/transcript`; the returned AI turn is pushed back as a
   `chat.message`.
4. `diagram.snapshot` frames (client sends every ~90s) are logged only — persistence/replay,
   **no AI reasoning** (PRD §6.2 / FR5).
5. `diagram.locked` → session marked submitted, diagram locked, agent `/submit` called; each
   returned flaw is pushed as a `chat.message` with `kind: "flaw"`. An `ai.thinking`
   (`node: "review"`) frame is pushed first to cover the wait (PRD FR8).
6. `session.end` → agent `/debrief` called; result pushed as `debrief.ready`.

## LangGraph graph (`agent/app/graph`)

```
        ┌─────────────┐
START ─▶│  interview   │─┐
        └─────────────┘ │
        ┌─────────────┐ ├─▶ join ─▶ END
START ─▶│  council     │─┘
        └─────────────┘
```

- **interview** (`nodes/interview.py`) — the only candidate-facing node. Produces the opening
  turn, then replies to each candidate turn; phrasing shifts toward the reference solution
  once it's available, without a hard mode switch (PRD §6.3 "Resolved").
- **council** (`nodes/council.py`) — fan-out branch, fires **at most once**. Short-circuits if
  `council_done` or a vetted reference is already attached; otherwise runs a
  generator→critic→reconcile pair.
- **review / summary / debrief** (`nodes/*.py`) — straightforward triggers, invoked directly
  from `app/turns.py` rather than wired as graph edges (PRD §6.3: these "are straightforward
  sequential/timed triggers").

### Stub mode

With no `OPENAI_API_KEY`, `app/llm.py` returns a `FakeListChatModel`, so the whole graph and
every endpoint run offline with deterministic placeholder text. The agent test suite runs in
this mode.

## Persistence (`backend/src/main/resources/db/migration`)

- `V1__init.sql` — `users`, `questions`, `interview_sessions`, `session_events` (append-only
  replay log with a monotonic per-session `seq`), `interim_summaries`.
- `V2__seed_questions.sql` — four well-known prompts, no reference solutions attached yet.
- Diagram/transcript/debrief payloads are stored as `jsonb`.

## Known gaps / TODO

- **Auth** is not implemented; `HomePage` uses a hard-coded demo user id. PRD §6.2.
- **Timed Summary node** — the backend has `ossature.session.summary-interval` config and the
  agent has the endpoint, but nothing schedules the periodic call yet (add a
  `@Scheduled` driver or client-side timer). PRD FR9.
- **Clarifying-Q&A relay** to the Council node (`clarifying_qa` state) is modelled but the
  backend does not yet extract Q&A pairs from the chat stream to forward. PRD §5 step 5.
- **Community question submission** — `QuestionBankService.submitForReview` throws; wire up
  `Question` creation + the owner review workflow. PRD §9 / Phase 4.
- **Agent session state is in-process** — swap `app/turns.py`'s dict for a LangGraph
  checkpointer (Postgres/Redis) before running more than one agent instance.
- **Backend build needs JDK 21** (see README).
