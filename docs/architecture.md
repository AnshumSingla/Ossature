# Architecture notes

How the three services map onto the PRD, and what is implemented vs. stubbed in the scaffold.

## Service boundaries

- **`backend/` (Spring Boot)** owns sessions, the WebSocket connection, persistence and the
  bridge to the agent. It never calls an LLM. PRD §6.2.
- **`agent/` (LangGraph + FastAPI)** owns the state graph and every LLM call. It is stateless
  over HTTP but keeps per-session graph state in-process (`app/turns.py`). PRD §6.3.
- **`frontend/`** owns the whiteboard, STT/TTS (Web Speech API) and the transcript UI. TTS
  (`speechSynthesis`) is genuinely local. STT (`SpeechRecognition`) is **not** - see below;
  the PRD §6.5 claim that both legs are "local, on-device" is incorrect and should be fixed.

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

## Voice input reality check (frontend/src/hooks/useSpeech.ts)

Confirmed by hand on 2026-09-22: **`SpeechRecognition` is not on-device.** Chrome ships a
working implementation only because it calls Google's own speech-recognition backend over
the network — audio genuinely leaves the machine. Two concrete consequences, both now
handled in the hook:

- **Chrome's recognizer silently stops after ~5s of no results** even with
  `continuous: true`. If the browser can't reach Google's backend, this looks exactly like
  "the mic turned on, took no input, and stopped by itself" — no error was ever surfaced to
  the user before this fix. `useSpeechRecognition` now surfaces `error` (the DOM error code:
  `'no-speech' | 'not-allowed' | 'audio-capture' | 'network' | ...`) and auto-restarts the
  recognizer on a silent `onend` so continuous dictation doesn't require the candidate to
  keep re-clicking "Start mic".
- **Brave (and likely other privacy-hardened Chromium forks) strips this backend wiring
  entirely** — `SpeechRecognition` exists in Brave but has nowhere to send audio, so it
  fails with a `'network'` error every time, regardless of the user's actual network. This
  isn't a bug to fix; it's a real product constraint. `InterviewRoom`'s mic-error message
  now tells the candidate to switch to Chrome/Edge when this happens.

**PRD impact:** §6.5's claim that both legs of voice I/O are "local, on-device" (and
therefore free and privacy-preserving) is true for TTS but false for STT. If that matters
for the cost/privacy story, the real fix is a genuinely local STT engine (e.g. a
WASM-compiled Whisper build) rather than the browser's built-in API — worth a deliberate
decision, not a silent scope change.

## Agent session-state durability (`agent/app/store.py`)

Added 2026-09-29. `app/turns.py`'s `_SESSIONS` dict is still the fast path for every read
and write, but it's now backed by a Postgres table (`agent_sessions`, one row per session,
whole `InterviewState` as JSONB) — the agent creates and owns this table itself at startup
(`store.ensure_schema()`), separate from the backend's Flyway-managed schema, on the same
physical Postgres instance (docker-compose `postgres` service) for operational simplicity
rather than running a second database.

This is a working-state cache, not a second system of record — the backend's Postgres
already holds the durable transcript/diagram/debrief history. So the lifecycle is:
- **Write-through**: every `_merge` (i.e. every turn, Council's result, Review, Summary)
  saves the full state to Postgres, not just memory. If the agent process restarts,
  `get_state` recovers from Postgres on the next request instead of silently starting a
  blank session.
- **Purged on session end**: the backend calls `DELETE /agent/sessions/{id}` (via
  `AgentClient.endSession`) right after it durably persists the debrief in
  `InterviewFlowService.handleSessionEnd` — at that point the agent's copy has no further
  purpose.
- **Best-effort, with a circuit breaker**: every `store.*` call is wrapped in try/except: if
  Postgres is unreachable, callers fall back to in-memory-only behavior exactly as before
  this existed (keeps `pytest` runnable with no database). A failed call trips a 30s
  in-process breaker so an outage costs one ~1-2s connect attempt, not one per subsequent
  call — without it, a down Postgres would inflate *every* agent request's latency for as
  long as the outage lasted, not just the first one.

This still doesn't solve running more than one agent instance (each has its own in-memory
`_SESSIONS`, and two instances would both think they "own" a session) — that needs the
in-memory dict replaced with real read-through-on-every-call behavior (or a proper LangGraph
checkpointer), not just backed by durable storage. Fine for the current single-instance
deployment.

## Auth (`backend/src/main/java/.../security`, `agent/app/security.py`)

Added 2026-10-02. Email/password, stateless JWT (HS256, `jjwt`), no sessions/cookies:

- `V4__add_password_auth.sql` adds a nullable `password_hash` on `users` (nullable because
  V3's seeded demo user predates auth and never had a password — it can't log in until
  someone signs up fresh).
- `POST /api/auth/signup` / `POST /api/auth/login` are the only unauthenticated REST routes
  (besides `GET /api/questions*`, which stays public browsing). Everything else requires
  `Authorization: Bearer <token>`, enforced by `JwtAuthFilter` + `SecurityConfig`.
- `SessionController` derives `userId` from the authenticated principal, never from the
  request body — `CreateSessionRequest` no longer even has a `userId` field. `GET
  /api/sessions/{id}` (and `/debrief`, `/summaries`) check the session's owner against the
  caller and return 403 otherwise, so one user can't read another's transcript by guessing a
  UUID.
- **WebSocket auth is separate from the REST filter chain**: browsers can't set a custom
  header on a WS handshake, so the JWT travels as a `token` query param instead, and
  `InterviewWebSocketHandler.afterConnectionEstablished` validates it by hand (signature +
  expiry) and checks the token's user owns the target session before registering the socket
  — closing the gap where the WS endpoint could be driven directly, bypassing the REST
  layer's auth entirely.
- **Backend → agent bridge**: optional shared secret (`AGENT_SHARED_SECRET` /
  `INTERNAL_AUTH_TOKEN`), sent as `X-Internal-Token` by `AgentClientConfig` and checked by
  `agent/app/security.py`'s `require_internal_token` dependency on every agent route except
  `/health`. Blank on either side (the default) means no check — same open-for-local-dev
  posture as the Postgres durability layer, not real user-facing auth (the agent never
  faces the candidate's browser directly; this just stops anything other than the backend
  from driving it for free).
- Frontend: `AuthProvider`/`useAuth` (`frontend/src/api/auth.tsx`) hold the token + user in
  `localStorage`; `rest.ts` attaches `Authorization` to every call and `socket.ts` appends
  `token` to the WS URL. `App.tsx`'s `RequireAuth` wrapper redirects to `/login` when there's
  no signed-in user.

Password rules: 8-72 chars (BCrypt silently ignores anything past 72 bytes), must contain
at least one letter and one digit (`AuthDtos.SignupRequest`) - a floor against trivially
guessable input, not full complexity scoring.

## Rate limiting (`backend/src/main/java/.../service/RateLimiter.java`)

Added 2026-10-02. In-memory, fixed-window - fine for the current single-instance
deployment (same caveat as the agent's `_SESSIONS` cache: a real multi-instance setup
needs this backed by something shared, e.g. Redis, instead). Applied to:

- **`/api/auth/login` and `/api/auth/signup`** (brute-force guard): two independent
  windows, per-email (5 / 5 min) and per-IP (20 / 5 min) - either tripping returns 429.
  Deliberately does **not** trust `X-Forwarded-For` yet (nothing in front of this to strip
  a client-supplied value), so per-IP uses `request.getRemoteAddr()` directly; revisit once
  this sits behind a real reverse proxy.
- **`POST /api/sessions`** (the original cost-exposure gap): 10 creations / hour per
  authenticated user, since each one kicks off a real, possibly paid, Council LLM call
  (PRD §8).

Limits live in `ossature.rate-limit.*` (`application.yml`). `TooManyRequestsException` ->
429 via `GlobalExceptionHandler`.

One gotcha hit and fixed along the way: Spring Security blocking `@Valid` validation
failures. `MethodArgumentNotValidException` isn't caught by the app's own
`@RestControllerAdvice`, so Spring's built-in resolver handles it via an internal forward
to `/error` - and since `/error` wasn't in `SecurityConfig`'s permitted paths, Security
blocked *that* forward, turning what should have been a `400` with a validation-detail body
into an opaque `403` with no body. Fixed by permitting `/error`.

Still open: tying these limits to something other than per-process memory once this runs
as more than one instance.

## Persistence (`backend/src/main/resources/db/migration`)

- `V1__init.sql` — `users`, `questions`, `interview_sessions`, `session_events` (append-only
  replay log with a monotonic per-session `seq`), `interim_summaries`.
- `V2__seed_questions.sql` — four well-known prompts, no reference solutions attached yet.
- Diagram/transcript/debrief payloads are stored as `jsonb`.

## Known gaps / TODO

- **Community question submission** — `QuestionBankService.submitForReview` throws; wire up
  `Question` creation + the owner review workflow. PRD §9 / Phase 4.
- **Agent still can't run more than one instance** — session state now survives a restart
  (see "Agent session-state durability" above), but each instance's in-memory `_SESSIONS`
  dict is still authoritative between requests, so two instances would race. A background
  Council thread's result is also still lost if the agent restarts *mid-generation*
  specifically (the thread itself dies with the process) — the durability fix covers
  everything already merged into state, not an in-flight thread.
- **No rate limiting / abuse protection** on session creation — since Council generation costs
  real money per dynamic/community prompt, this is a real exposure until auth + rate limiting
  land (see PRD §8 cost notes).
- Backend build needs JDK 21 (see README) — resolved for this dev box, but worth stating for
  anyone else cloning the repo.
- **STT requires Chrome/Edge, not just "a browser"** — see "Voice input reality check" above.
  Worth a UI browser-detection warning up front rather than only surfacing on mic error.
