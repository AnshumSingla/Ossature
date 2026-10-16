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

### Local STT implementation (2026-10-12) - on-device only, works in Brave, Firefox, Safari

**Speech input is on-device only.** The browser's Web Speech API (which sends audio to the
vendor's cloud and fails in Brave) is no longer used for input - the section above is kept as
history. The interview room uses `useLocalSpeechRecognition`
(frontend/src/hooks/useLocalSpeech.ts); if the browser cannot run the local engine, or the model
cannot be downloaded, the room shows a typing box instead. Text-to-speech for the interviewer's
replies still uses the browser's local `speechSynthesis`.

**The model starts downloading as soon as the site opens.** `App.tsx` calls
`preloadVoiceModel()` (features/voice/shared.ts) on first load of *any* page - landing, login,
anywhere - when the browser is idle. It never touches the microphone (no permission prompt),
failures are silent (pressing Start mic retries), and it is skipped when the visitor has Data
Saver on or a 2G connection, or when `VITE_STT_PRELOAD=false`. The engine is a single shared
instance that outlives page navigation, so by the time an interview starts the model is already
in memory and "Start mic" is instant. A one-line status shows on the question-bank page.
Cost to be aware of: every first-time visitor downloads ~41 MB (cached afterwards) whether or
not they ever do an interview, and with the default settings the request goes to Hugging Face,
so it exposes the visitor's IP to them - self-host the files (`VITE_STT_MODEL_HOST`) to avoid
both the third-party request and its availability dependency.

```
mic -> AudioWorklet (public/stt/capture-worklet.js) -> Resampler (native rate -> 16 kHz)
    -> UtteranceSegmenter (energy VAD, pauses end a phrase) -> Web Worker (Moonshine) -> final text
```

- `features/voice/engine.ts` - the `LocalSpeechEngine` contract and `createLocalEngine`, the one
  place that chooses an adapter. A future Whisper/Vosk engine = one new class + one `case`.
- `features/voice/transformers/` - `TransformersEngine` (main thread) and `worker.ts` (model,
  off-thread). Generic: any speech model transformers.js can run, selected by config.
- `features/voice/shared.ts` - the single shared engine, load-state store, and the preload.
- `features/voice/segmenter.ts`, `resample.ts` - pure logic, unit-tested (`npm test`).
- Mic is requested **before** the model download, so a permission denial fails instantly.
- Resampling is done in our code at the mic's native rate (not a 16 kHz AudioContext), because
  Firefox has historically refused mixed-rate connections.
- The ONNX runtime's WebAssembly files are served from our own origin (`/ort/`, Vite plugin in
  `vite.config.ts`), single-threaded, so no COOP/COEP headers are needed.
- Model files come from the Hugging Face Hub at a **pinned commit**; set `VITE_STT_MODEL_HOST`
  to self-host (see `frontend/.env.example`). Cached by the browser after the first load.
  Download progress is measured against the expected size and never goes backwards.
- No word-by-word partials: the models transcribe whole phrases, so text appears after the
  speaker pauses (about 0.7 s of silence ends a phrase) plus the model's decode time.

Verified: segmenter/resampler unit tests (15); a continuous 80 s, 48 kHz stream of 12 phrases
cut into exactly 12 utterances and transcribed in order through the real worker and model
(14.8 % word error rate, same jargon misses as the spike); production build bundles the worker and
emits `/ort/`; in-room UI states (mic denied message, voice setting, typing box).
**Not verified:** a real microphone (the preview browser blocks capture), Brave itself, Firefox
and Safari. Known cost: the production build also emits an unused 21 MB duplicate of the ONNX
runtime under `assets/` (the `/ort/` copy is the one used) - harmless but worth trimming.

### STT model bake-off - why Whisper tiny.en (2026-10-12)

Asked to use a better model than Moonshine tiny, five candidates were run in the same
harness (`docs/spikes/moonshine/index2.html`): 40 clips = 20 jargon-heavy system-design
phrases x 2 Windows TTS voices, quantised q8, single-thread WebAssembly, Chromium on a
16-core desktop. "Jargon" = share of 70 technical terms (Kafka, idempotency, Postgres,
Elasticsearch, write-ahead log...) that came out right, ignoring spacing.

| Model | Download (q8) | Word error rate | Jargon right | Time per phrase | First load |
|-------|--------------|-----------------|--------------|-----------------|------------|
| Moonshine tiny (previous default) | ~28 MB | 10.9 % | 77 % | 0.39 s | 7 s |
| Moonshine base | ~63 MB | 9.3 % | 80 % | 0.67 s | 1 s (cached) |
| **Whisper tiny.en (chosen)** | **~41 MB** | **7.5 %** | **86 %** | 1.7 s | 32 s |
| Whisper base.en | ~77 MB | 7.9 % | 86 % | 3.4 s | 59 s |
| Whisper small.en | ~249 MB | not measured (run stopped before it finished) | - | - | - |

Findings:

- Whisper tiny.en is the most accurate per megabyte and per second spent. Larger Whisper
  (base.en) was *not* more accurate here and twice as slow. small.en was not measured (stopped
  before it finished), but at ~249 MB it is already too large to download in the background on
  every first visit, so it was dropped on size alone.
- It gets right what Moonshine garbled: idempotent / idempotency, Postgres, Elasticsearch,
  write-ahead log, web sockets. Shared remaining misses: Redis ("readies"), shard ("char"),
  rate limiter ("rate-lameter") - the domain-term correction layer (A4.11) targets these.
- **Latency is the cost.** Measured 1.7 s per phrase in the bake-off, and 3.7-4.7 s per phrase
  later the same day on the same machine under load (also with the model in the app's worker
  and on the page's main thread, so it is not the app's wiring). Expect 2-4 s on a fast
  desktop and more on a typical laptop. In the original bake-off run Moonshine tiny was ~4.5x faster than Whisper tiny.en.
- **Multi-threading made it worse** (cross-origin-isolated run: ~13 s per phrase), so the worker
  stays single-threaded and COOP/COEP headers stay off.
- **WebGPU was not usable here:** the run did not finish in over three minutes in the preview
  browser (its GPU path may be throttled while the pane is hidden), so it is unmeasured, not
  disproved. It is the obvious next lever for latency (Chrome/Edge/Brave desktop, recent Safari).
- Test audio is synthetic TTS, which flatters every model; real-speaker validation is A4.12.

Decision: default to `onnx-community/whisper-tiny.en` at revision
`2575352d61be1bf7225cf8f8b268a4678025fc58`. To trade accuracy for speed, set
`VITE_STT_MODEL_ID=onnx-community/moonshine-tiny-ONNX` and
`VITE_STT_MODEL_REVISION=a6da1241cd305dcd64eab1edbd615f2bb9aabb95` (no code change).
Whisper weights are MIT-licensed; the model card does not state it, so confirm before
redistributing the files if you self-host.

End-to-end check of the chosen setup through the app's own shared engine: a continuous 139 s,
48 kHz stream of 20 phrases was cut into exactly 20 utterances and transcribed in order with a
6.3 % word error rate. Preload verified from a cold cache: opening `/login` with no interaction
took the model from idle to ready in ~31 s (fast connection) with monotonic progress 12 -> 99 %.

### Local STT spike: Moonshine (task A4.1, run 2026-10-12)

Decision taken after the above: build local speech-to-text with **Moonshine only** for now
(Whisper/others are future adapters, see `docs/remaining-work-and-security-plan.md` A4).
This spike answers "is Moonshine good enough in a browser to ship?". Source and how to re-run:
`docs/spikes/moonshine/` (README there).

**Setup.** `@huggingface/transformers@3.8.0` from jsDelivr, ONNX Runtime Web (WASM), models
`onnx-community/moonshine-{tiny,base}-ONNX` at `q8`. 12 system-design sentences (~5-6 s each,
~65 s total) synthesised with Windows SAPI ("Microsoft David"), 16 kHz mono. Browser: the
Claude desktop app's built-in Chromium (Chrome 152), 16-core desktop CPU, no GPU path used.

| Run | Model download | First load (download + init) | Speed (x real time) | ms per ~5.5 s clip | Word error rate | Jargon terms recovered |
|-----|---------------|------------------------------|---------------------|--------------------|-----------------|------------------------|
| tiny q8, 1 thread | ~28 MB (+4 MB WASM runtime) | 29.8 s | 0.072 (14x faster than real time) | ~395 | **13.0 %** | 10 / 16 |
| tiny q8, threaded (cross-origin isolated) | same | 21.9 s | 0.106 | ~579 | 13.0 % | 10 / 16 |
| base q8, 1 thread | ~63 MB (+4 MB) | 43.4 s | 0.129 | ~701 | 11.8 % | 9 / 16 (strict string match) |

Model sizes are from the Hugging Face file listing (encoder + merged decoder, `q8`): tiny
7.9 + 20.2 MB, base 20.5 + 42.4 MB. License: MIT. JS heap after loading: 32-40 MB (the ONNX
runtime's own WASM memory is not included in that figure).

**What the numbers say**

- **Speed is a non-issue on this machine.** Even tiny at 0.07x leaves ~14x headroom, so a
  utterance-by-utterance (not word-by-word) pipeline can feel near-instant. Low-end laptops are
  **untested** (see gaps).
- **Accuracy is decent on plain English, weak on exactly our vocabulary.** Typical misses:
  "Redis" -> "readies", "shard" -> "charge", "idempotent/idempotency" -> "imposed" / "I
  dependency", "rate limiter" -> "rape lameter", "news feed" -> "new speed", "Elasticsearch" ->
  "elastic search". Kafka, load balancer, circuit breaker, availability zones, CDN, base 62 and
  WebSockets came through.
- **Base is not worth it.** 1.2 points better word error rate for 2.3x the download and ~2x the
  latency; it did not fix the jargon misses.
- **Threads don't help at this model size**, so **cross-origin isolation (COOP/COEP) is not
  required**. A COEP `require-corp` page still loaded the Hugging Face model files fine, so it
  stays an option, but the default deployment can skip it. Only `wasm-unsafe-eval` in the CSP
  is needed for WebAssembly.
- First-load cost is the real UX issue: ~28 MB download plus ~20-30 s initialisation before the
  mic is usable. It must be a visible, one-time "preparing voice" step with caching (A4.4).

**Gaps - what this spike did NOT establish**

1. **Synthetic speech.** TTS is cleaner than real people. Real accents, mic noise and
   disfluencies ("um", restarts) will make results worse. A run on recordings of several
   real speakers is required before release.
2. **Only Chromium was tested.** Firefox and Safari were not available here. Both run
   WebAssembly, so it should work, but this is unverified - needs a manual pass.
3. **No low-end device test** (no CPU throttling available). Verify on an older laptop; a ~5x
   slower CPU would still be real time at tiny, but that is arithmetic, not a measurement.
4. **No streaming.** Clips were transcribed whole. Live use needs voice-activity cutting (A4.2)
   so each spoken phrase is transcribed when the speaker pauses.
5. **Language.** The English models are English-only; Moonshine publishes separate models for
   other languages (e.g. French, Japanese, Korean, Arabic, Vietnamese, Ukrainian, Chinese),
   so non-English input is a model swap, not a rewrite.

**Verdict: conditional GO with `moonshine-tiny` (q8).** Proceed with A4.2-A4.10, on these
conditions:

- Add a **domain-term correction layer** (new task A4.11): fuzzy/phonetic matching of each
  utterance against a glossary of system-design terms plus the labels currently on the
  whiteboard, so "readies" resolves to Redis when a Redis box is drawn. The downstream
  interviewer LLM also sees the question and diagram and can tolerate some noise, but the
  transcript the candidate reads should be right.
- Add a **real-speech validation** (new task A4.12) before release: 5+ speakers, mixed
  accents, laptop mic, with a go/no-go threshold (suggest <= 15 % word error rate and >= 80 %
  jargon recall after A4.11).
- Keep the **Web Speech adapter and typing fallback** so a failed model load or weak device
  never blocks an interview.
- If A4.12 fails, the next step is a **Whisper adapter** (the engine interface from A4.5
  exists for exactly this), not abandoning local STT.

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

## Community submission, owner review, session history/replay (Phase 4)

Added 2026-10-05.

- **Community submission**: `QuestionBankService.submitForReview` creates a
  `PENDING`/`COMMUNITY` `Question` with an auto-generated, collision-safe slug.
  `POST /api/questions` derives the submitter from the authenticated principal (never a
  client-supplied field, same pattern as session creation). Frontend: `SubmitQuestionPage.tsx`.
- **Owner review**: a config-driven role rather than a DB column, since this app has
  exactly one kind of elevated user - `ossature.auth.owner-emails` (`OWNER_EMAILS`) is
  checked against the JWT's email claim in `JwtAuthFilter`/`OwnerPolicy`, granting
  `ROLE_OWNER`. Owner-only `GET /api/questions/pending` + `POST .../approve|reject`
  (`SecurityConfig`'s owner matchers must come *before* the general `GET /api/questions/**`
  permitAll rule - matcher order decides on first match). `AuthResponse` carries `isOwner`
  so the frontend can conditionally show "Review submissions" (`ReviewQuestionsPage.tsx`) -
  the backend enforces it either way, this is just UI gating.
- **Session history/replay**: `GET /api/sessions/{id}/events` replays the full
  `session_events` log in the exact frame shape (`{type, seq, sessionId, payload, ts}`) the
  live WebSocket already uses for reconnect replay, so `SessionReplayPage.tsx` reuses
  `TranscriptPanel` unmodified - one renderer for both the live and historical path.
  `GET /api/sessions/{id}/diagram` returns the stored `final_diagram` jsonb. Both are
  nullable-String-returning endpoints (Spring writes an empty body for a null `String`,
  which breaks a naive `res.json()`) - the frontend's `jsonOrNull()` helper handles that.
  `HistoryPage.tsx` lists past sessions by cross-referencing `GET /api/questions` client-side
  for titles rather than adding a backend join.
  - **Wire format gained a field**: `DiagramNode.shape` (component/datastore/client) is new
    - added purely on the frontend/protocol side since the backend's `Diagram.nodes` is
    `List<Object>` (untyped), so an extra per-node key round-trips losslessly with zero
    backend changes. Without it, a replayed diagram couldn't know which icon to render per
    node and would fall back to a generic box.
  - `DiagramReadonly.tsx` reuses the same `ShapeNode`/`LabeledEdge` components the live
    whiteboard uses, but is fully prop-driven (not the shared `whiteboardStore` singleton)
    - rendering a past session's diagram must never touch the state a live session might
    currently be using.
  - Side fix while touching the whiteboard: `whiteboardStore` is a module-level singleton
    shared across every page in the SPA, so without a reset, navigating from one session to
    another (or back into one) would still show the previous board's shapes. `InterviewRoom`
    now calls `reset()` on mount, keyed by `sessionId`.

## Known gaps / TODO

- **Agent still can't run more than one instance** — session state now survives a restart
  (see "Agent session-state durability" above), but each instance's in-memory `_SESSIONS`
  dict is still authoritative between requests, so two instances would race. A background
  Council thread's result is also still lost if the agent restarts *mid-generation*
  specifically (the thread itself dies with the process) — the durability fix covers
  everything already merged into state, not an in-flight thread.
- Backend build needs JDK 21 (see README) — resolved for this dev box, but worth stating for
  anyone else cloning the repo.
- **STT requires Chrome/Edge, not just "a browser"** — see "Voice input reality check" above.
  Worth a UI browser-detection warning up front rather than only surfacing on mic error.
