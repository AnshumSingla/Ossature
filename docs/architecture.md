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
6. `session.end` → backend reads its own `interim_summaries` rows (see Summary scheduler
   below) and sends their content to the agent's `/debrief`; result pushed as `debrief.ready`.

## Summary scheduler (`backend/.../service/SummaryScheduler.java`)

A `@Scheduled` sweep (every 30s) drives the once-per-~6-min Summary node for every session
in `ACTIVE` state (PRD §6.3 node 4 / FR9): for each session it compares elapsed time since
`startedAt` against `ossature.session.summary-interval` to compute which summary ordinal is
due, builds that window's `chat.message` transcript slice plus the latest `diagram.snapshot`
from the session's event log, calls the agent's `/summary`, and persists the result as an
`InterimSummary` row. `InterviewFlowService.handleSessionEnd` reads those rows back and sends
their content to `/debrief` — the backend's Postgres log is the source of truth for interim
summaries, not the agent's in-process state (see below).

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
  once it's available, without a hard mode switch (PRD §6.3 "Resolved"). When it classifies
  its own reply as `clarifying`, it also emits a `clarifying_qa` entry (candidate question +
  its answer) so the Council node picks up constraints the candidate actually established
  (PRD §5 step 5) — no separate relay call from the backend is needed for this.
- **council** (`nodes/council.py`) — fires **at most once**. Short-circuits if `council_done`
  or a vetted reference is already attached; otherwise runs a generator→critic→reconcile pair.
- **review / summary / debrief** (`nodes/*.py`) — straightforward triggers, invoked directly
  from `app/turns.py` rather than wired as graph edges (PRD §6.3: these "are straightforward
  sequential/timed triggers").

### Why Council runs on a thread, not through the compiled graph

`app/graph/build.py` documents the fan-out/fan-in *shape* PRD §6.3 asks for (interview ∥
council → join) as a single `graph.invoke()` — correct for one turn in isolation, but not
usable as the live path: a real fan-out here has to span multiple HTTP requests, since the
candidate keeps talking while Council is still generating. One synchronous `invoke()` would
just block the candidate's very first turn until Council finished.

So `app/turns.py` calls `interview_node` and `council_node` directly instead:
`start_session` returns the opening turn immediately and kicks Council off on a daemon
background thread (skipped entirely if a vetted reference is already attached). That thread
reads session state *at call time*, not a snapshot captured at kickoff, so by the time its
generator→critic→reconcile calls finish it naturally picks up whatever clarifying Q&A
happened in the meantime — real concurrency, not simulated. `council_done`/`council_running`
flip once it merges its result back into the shared per-session state dict (guarded by a
lock).

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
- **Community question submission** — `QuestionBankService.submitForReview` throws; wire up
  `Question` creation + the owner review workflow. PRD §9 / Phase 4.
- **Agent session state is in-process** — swap `app/turns.py`'s dict for a LangGraph
  checkpointer (Postgres/Redis) before running more than one agent instance. This also caps
  the Council background thread: if the agent restarts mid-generation, that thread and its
  result are lost silently.
- **No rate limiting / abuse protection** on session creation — since Council generation costs
  real money per dynamic/community prompt, this is a real exposure until auth + rate limiting
  land (see PRD §8 cost notes).
- Backend build needs JDK 21 (see README) — resolved for this dev box, but worth stating for
  anyone else cloning the repo.
