# Remaining work, hardening backlog and pre-production security plan

Source: `TODO.md` (progress against the PRD phasing) plus a **static read of the code** in
`backend/`, `agent/` and `docker-compose.yml`. Nothing here has been executed against a running
system yet - the "Findings" in Part B are code-review observations that Part C must
confirm or refute with real tests.

## Decisions taken (2026-10-12)

| Topic | Decision | Consequence |
|-------|----------|-------------|
| Email verification + password reset | **Deferred to a later release.** Logged in "Future work" below. | H1 and H16 stay open. See the interim mitigation under H1 - it must be in place before go-live. |
| Deployment | **Multiple instances.** | All of A1 is required, not optional. |
| Voice input | **On-device speech-to-text only (no browser/cloud fallback). Model: Whisper tiny.en (changed from Moonshine tiny after a bake-off, 2026-10-12). Model preloads when the site opens.** The engine interface still allows other models/engines. | Track A4 (below). Typing is the only fallback. |
| Database isolation | **Everything: app-level checks + Postgres RLS + automated cross-user tests.** | H17 becomes the R-series in Part B2. |

## Progress

**2026-10-20 - Row-level security done on the backend (H17 / H18 / H19; R1-R7).** R8 (agent) is still open.

| ID | Status | What changed | Proof |
|----|--------|--------------|-------|
| R1 | Done | Ownership per table is encoded in the policies (V7). `interview_sessions.user_id` is now `NOT NULL` (V8). Events and summaries belong through their session (policy joins on the PK, no denormalised column). Approved questions are public; pending/rejected are visible only to the submitter and the owner | Policy tests |
| R2/H18 | Done (backend), agent pending | Roles `ossature_app` (DML only, not the owner, no `BYPASSRLS`, no `CREATE` on `public`) and `ossature_agent` (only `agent_sessions`) created by V7; all five tables `ENABLE` + `FORCE ROW LEVEL SECURITY`. The backend connects as `ossature_app`; Flyway runs as the schema owner (`POSTGRES_USER`). Production refuses to start if the app connects as the owner or any DB password is a development default | `ProductionSafetyCheckTest`, `RlsApplicationIT` (`current_user` is `ossature_app`, not superuser) |
| R3 | Done | `RlsJpaTransactionManager` sets `app.user_id`, `app.is_owner`, `app.scope` with `set_config(..., true)` (transaction-local) at the start of every transaction, from `DbContext` or the Spring Security principal. Nothing set means nothing visible | Test with a pool of one connection: identity never leaks to the next transaction |
| R4 | Done | Policies per table and command in V7 (users: self / auth-scope read+insert; questions: read, submit, moderate; sessions: own + scheduler list of ACTIVE rows; events and summaries: through own session) | 23 `RlsPolicyTest` cases, mutation-checked |
| R5 | Done | `SummaryScheduler` lists ACTIVE sessions in the narrow `scheduler` scope (sees session rows only, never their content) and then acts as each owner; login and signup run in the `auth` scope; the WebSocket handler runs as the ticket's user. No path uses a bypass role | `RlsApplicationIT`; live: scheduler found every active session and acted per owner |
| R6 | Done | Real-Postgres tests (scratch database per run, skipped if Postgres is not reachable; Testcontainers is the CI equivalent) for unset context, cross-user read/write on every table, owner-only paths, and the application's own services | 23 + 7 tests |
| R7 | Done | Policy predicates use `idx_sessions_user` and the primary key; the events policy is evaluated as a hashed subplan once per query, not per row. Replay query still uses `idx_events_session_seq` | `EXPLAIN (ANALYZE)` as `ossature_app` on the dev database |
| H19 | Done as a side effect | Another user's session, events, summaries, debrief, diagram and ticket request all return 404 (they simply do not exist for that user). `SessionController.owned()` stays as a second layer | Live script: every cross-user request returned 404 |

Found while doing it: Spring Data runs derived query methods outside any transaction unless the repository is `@Transactional`. A query with no transaction has no identity and silently saw nothing under RLS (login and the scheduler broke). All repositories are now `@Transactional(readOnly = true)` and `RepositoryTransactionTest` fails if one is added without it.

**What RLS does and does not give you.** It stops mistakes in application code from leaking another user's rows (a missing ownership check, a wrong query). It does not stop someone who can run arbitrary SQL as `ossature_app`: that role can set its own `app.user_id`. So the SQL-injection defences, parameterised queries and the app-level checks all stay. Do not give the application account more rights than it has, and keep its password separate from the owner's.

Live end-to-end check (dev database migrated to V8, backend running as `ossature_app`): signup, login, session create/list/read, events, summaries, debrief and diagram for the owner (200) versus another user (404), the pending-question workflow, non-owner moderation (403), WebSocket ticket flow (ticket reuse and another user's ticket refused), and a message sent over the socket is stored under the right user. Full backend suite: 95 passing, plus `RlsApplicationIT` (7).

Still open for the data layer: **R8** (agent connects as `ossature_agent`, stops creating tables, optimistic locking, A1.2/A1.3).

**2026-10-12 - A4.1 done.** Moonshine spike: conditional go with `moonshine-tiny` q8; see
`docs/architecture.md` ("Local STT spike"). New follow-ups A4.11 (domain-term correction) and
A4.12 (real-speech validation) added.

**2026-10-16 - P1 batch 2 done: H9, H11, H15.**

| ID | Status | What changed | Proof |
|----|--------|--------------|-------|
| H9 | Done | The WebSocket no longer accepts the login token in the URL. The client calls `POST /api/sessions/{id}/ws-ticket` (owner only) for a 30-second, single-use ticket bound to that session and user, and sends only that; reconnects fetch a new one. Tickets carry `typ=ws` and are rejected as login tokens (and login tokens as tickets). Residual: single-use is tracked in memory per instance, so with several backend instances a replay inside the 30 s window against a *different* instance is possible (A1.7). | 14 backend tests; live (scratch DB): reuse, old `?token=`, token-as-ticket, wrong session, another user's session, anonymous, ticket-as-Bearer and expiry all refused; real UI: room connects ("socket open"), WebSocket URL has `ticket=` only, no login token, re-entering the room reconnects |
| H15 | Done | Every log line and stack trace is masked (JWTs, Bearer, token/ticket/password pairs, emails) by logback converters in `logback-spring.xml`; failures are logged by type + HTTP status, never by message or body (a JSON parse error quotes the transcript; an agent HTTP error can echo it). Agent: same masking, and SQLAlchemy no longer appends the statement parameters - the whole interview state - to failed-write errors (`hide_parameters`). | 11 + 1 backend tests incl. a full-app console check (`ticket=[redacted] by d***@example.com with Bearer [jwt]`), 10 agent tests incl. a real SQLAlchemy error |
| H11 | Done (structural); behavioural probe opt-in | **Framing:** user-controlled text (question prompt, candidate speech, diagram labels, and model output derived from them) only enters prompts as escaped, tagged data in the human message; system messages hold fixed text + a standing untrusted-data rule. **Leak guard:** the interviewer's reply cannot contain a run of 8+ consecutive words from the reference solution - it is replaced by a safe probe even if the model obeys an injection. **Output validation:** review flaws and the debrief are forced into a bounded shape (types, lengths, priority 1-5, 6 items, markup stripped); fenced JSON is accepted. **Caps:** agent rejects prompts/transcripts over 4000 chars. Also fixed: the interviewer was never shown the question prompt. | 87 injection tests over a 15-payload corpus (`agent/tests/injection_corpus.json`); mutation check: removing the escaping, the leak guard, or putting candidate text in the system message makes 9 / 16 / 15 tests fail |

What H11 does **not** prove: there is no API key in this environment, so how a real model behaves
against the corpus is untested (`test_live_model_does_not_leak_the_canary` is ready and runs when
`OPENAI_API_KEY` is set - run it before release, C4). The leak guard catches copied text, not a
model describing the reference in its own words. A candidate can still inject into their *own*
summary/debrief (self-affecting only). The cross-user path - a community question prompt shaping the
Council's reference for other users - is reduced by owner review, tagging, escaping and the length cap,
not eliminated.

**2026-10-16 - P1 batch done: H8, H12, H13, H14** (branch `feat/security-hardening-and-local-stt`).

| ID | Status | What changed | Proof |
|----|--------|--------------|-------|
| H8 | Done | Login budgets are now per (account, source IP) = 5 / 5 min, plus a high account-wide cap (100 / h) so guessing spread over many addresses is still bounded, plus the existing per-IP cap. Signup has its own per-IP budget (10 / h) and shares nothing with login. A stranger can no longer exhaust the real user's login attempts, from their own address or by signing up with the victim's email. | 7 MockMvc tests incl. the attacker scenarios; mutation check (re-adding the shared key) makes the lockout test fail with 429 |
| H12 | Done (login); signup answer accepted | Login does exactly one bcrypt comparison whether or not the account exists (dummy hash of the same cost), so response time no longer reveals registered emails. Signup still says "already exists" (usability); it is rate-limited per IP. | 4 unit tests; live timing: before 9.6 ms (unknown) vs 107.5 ms (real account) = 98 ms oracle; after 89.9 vs 98.5 ms = 8.6 ms |
| H13 | Done (code); host config to apply | API: CSP `default-src 'none'; frame-ancestors 'none'`, X-Frame-Options DENY, nosniff, Referrer-Policy no-referrer, Permissions-Policy, HSTS (HTTPS only). Frontend: one source (`frontend/security-headers.mjs`) feeds `vite preview` and is emitted into every build as `dist/_headers` (Netlify/Cloudflare) and `dist/nginx-security-headers.conf`. CSP allows only self, the inline theme script by sha256, `wasm-unsafe-eval`, Google Fonts, the API/WS origins and Hugging Face (unless the model is self-hosted); the build fails if index.html gains another inline script. **Whoever serves `dist/` must apply the emitted headers.** | MockMvc header tests; built site served with the headers: page, fonts, voice model download + WebAssembly all work with no violations; injected inline script, inline handler, external script, string-eval and a request to an unlisted host are all blocked |
| H14 | Done | Migration V6 removes the seeded `demo@ossature.local` user when it has no password and owns no data (kept otherwise). Nothing in the code referenced it. | Scratch-DB run of the full chain (v1-v6): removed on a fresh DB; kept when it owns a session or has a password; real users untouched |

Also: `npm run build` now passes (removed an ignored `className` prop that caused a type error in
`LabeledEdge.tsx`); the TODO item for TTS is ticked. Tests: backend 43, agent 19, frontend 20.

Notes for the next steps: the per-IP limits still use the socket address (X-Forwarded-For is
deliberately ignored), so behind a reverse proxy every user would share one budget until a trusted
proxy header is configured - revisit when deploying (C6). The login rate limiter is in-memory
(A1.5 moves it to shared storage for multiple instances).

**2026-10-12 - A4: model changed to Whisper tiny.en, on-device only, preload on site open.**
Bake-off of 5 models (table in `docs/architecture.md`): Whisper tiny.en best accuracy per MB/s
(7.5 % WER, 86 % jargon vs Moonshine tiny 10.9 % / 77 %), at ~4x the latency (1.7-4 s per
phrase on a fast desktop). Browser Web Speech engine and the voice-input setting removed;
shared engine + background preload added (`features/voice/shared.ts`). New open items:
**A4.13** measure on a typical/older laptop and decide whether 2-4 s phrase latency is acceptable
(options: WebGPU where available, Moonshine via one env var, a smaller utterance window);
**A4.14** self-host the model files (avoids the third-party request on every first visit and the
IP exposure) and confirm the model licence; **A4.15** decide whether preloading ~41 MB for
every first-time visitor (including those who never interview) is acceptable on metered data.

**2026-10-12 - A4.2-A4.7 built (Moonshine local speech-to-text in the app).** Capture + VAD,
worker, engine interface + Moonshine adapter, `useLocalSpeechRecognition`, engine selection with
fallback and typing, a user setting, production build check. Details and what is *not* verified
(real mic, Brave, Firefox, Safari) are in `docs/architecture.md` ("Local STT implementation").
Deviation from the plan text: only Moonshine is behind the `LocalSpeechEngine` interface; the
browser Web Speech engine stays as the existing hook and is selected by `useVoiceInput`, rather
than being rewritten as a second adapter class. Still open: A4.4 integrity check beyond the
pinned model revision, A4.8 CSP, A4.9 wording, A4.10 browser matrix, A4.11, A4.12.

**2026-10-12 - P0 hardening H2-H7 done, H1 interim mitigation in place** (all uncommitted in
the working tree; backend must be restarted to pick them up, which also runs migration V5).

| ID | Status | What changed | Proof |
|----|--------|--------------|-------|
| H1 | Interim only | Owner check normalised; case variants can't create an account (see H2). Verification still deferred. **Still required operationally:** register the owner *before* setting `OWNER_EMAILS`. | Live: `OWNER@X.COM` / `Owner@x.com` signup -> 409; token with differently-cased email still resolves to the one owner |
| H2 | Done | `Emails.normalize` used by signup, login, owner policy and the login rate-limit key; migration `V5__normalize_email_case.sql` (fails loudly if case-duplicates already exist, lower-cases the rest, adds `UNIQUE (lower(email))` and `CHECK (email = lower(email))`); signup race returns 409, not 500 | Unit tests; live; DB rejects `Owner@X.com` and case-duplicates on a fresh migrated DB |
| H3 | Done (code) | `ProductionSafetyCheck` (profile `prod`) refuses to start on default JWT secret, short secret, default DB password, blank agent secret, non-https/localhost CORS origins; `application-prod.yml` (INFO logging, no health details); compose publishes Postgres/pgAdmin on `127.0.0.1` only | Unit tests; live: `SPRING_PROFILES_ACTIVE=prod` with defaults exits with the list of problems |
| H4 | Done | Agent compares the shared secret with `hmac.compare_digest`; `APP_ENV=prod` refuses to start without `INTERNAL_AUTH_TOKEN` | 13 new agent tests |
| H5 | Done | Sessions can only start on APPROVED questions; `GET /api/questions/{id}` hides pending/rejected from everyone except the submitter and the owner (404, same as unknown) | Unit + MockMvc + live (anonymous and other logged-in user get 404; start-session on a pending question gets 404) |
| H6 | Done | `/actuator/**` other than health/info is `ROLE_OWNER` only | MockMvc + live (anonymous/user 403, owner 200) |
| H7 | Done | `@Size` on every text field (email 254, display name 80, password 128, title 140, prompt 4000); 64 KB cap on REST bodies (413, filter also counts chunked bodies); WebSocket frame cap 256 KB (Tomcat 1009); transcript frames over 4000 chars dropped; `transcript.final` capped at 30/min/session | Unit + live (70 KB body -> 413, 200-char title -> 400 with a readable message, oversized WS frame -> close 1009, oversized transcript dropped and logged) |

Mutation check: removing the H5 and H6 fixes makes the corresponding web tests fail, so the
tests do guard the behaviour. Test totals: backend 30 passing, agent 19 passing.

Side findings while doing this:
- `JwtAuthFilter` was also being registered as a plain servlet filter (double registration). It now
  runs only inside the security chain (`FilterRegistrationBean` disabled).
- **H8 observed live:** signup attempts consume the same per-email budget as login, so signup
  traffic alone can lock a victim out of login. Still open (P1).
- Inbound WebSocket `chat.message` frames let a client write messages with `sender: "ai"` into its
  own replay log. Low impact (own session only) but should be validated in the C5 pass.

Sizes: **S** < half a day, **M** ~1-2 days, **L** > 2 days. IDs are stable so tasks can be
referenced from commits and PRs.

Sequencing in one line: **Part A (finish features) -> Part B (fix known issues) -> feature
freeze -> Part C (security test pass) -> Part D (release gate)**. Part B items marked P0 are
cheap and should not wait for the freeze.

---

## Part A - Finish the open TODO items

Three items are unchecked in `TODO.md`. Each is split below into steps that can each be a
single PR.

### A1. Multi-instance agent support  (open in TODO)

Problem: each agent instance's in-memory `_SESSIONS` is authoritative between requests, so two
instances race. The Postgres write-through only fixes restarts.

| ID | Task | Size | Done when |
|----|------|------|-----------|
| A1.1 | **Decide**: sticky routing by `session_id` (simplest, keeps in-memory authority) vs. making Postgres authoritative with row locking. Record the choice in `docs/architecture.md`. | S | Decision written down |
| A1.2 | If DB-authoritative: add a `version` column to the agent's session table and do load -> modify -> save with optimistic locking (or `SELECT ... FOR UPDATE`) in `agent/app/store.py` | M | Concurrent writers can't silently overwrite each other |
| A1.3 | Demote `_SESSIONS` to a cache that is validated against the DB version on each request | M | A turn handled by instance B sees instance A's latest state |
| A1.4 | Concurrency test: two agent instances, interleaved `/transcript` calls on one session | M | Test passes reliably; state is consistent |
| A1.5 | Move `RateLimiter` (backend, in-memory) to shared storage (Postgres or Redis) | M | Limits hold across 2 backend instances |
| A1.6 | Make `SummaryScheduler` single-runner across backend instances (e.g. ShedLock) | S | Only one instance fires each sweep |
| A1.7 | `SessionSocketRegistry` is per-instance: either sticky WS routing or cross-instance push | M | A reconnect landing on another instance still gets pushes |

A1.5-A1.7 are not listed in `TODO.md` but follow directly from "multi-instance", which is the
chosen deployment - so they are required. Add A1.8: a two-backend + two-agent compose profile
used by the security and load tests (Part C) so they run against the real topology.

### A2. Council work lost if the agent restarts mid-generation  (open in TODO)

| ID | Task | Size | Done when |
|----|------|------|-----------|
| A2.1 | Persist Council job state per session (`pending / running / done / failed`) in the agent store | S | State survives restart |
| A2.2 | On agent startup, find sessions with a `running` Council job and resume or restart them | M | Killed-mid-run job completes after restart |
| A2.3 | Idempotency guard so a resumed job can't run twice or double-bill | S | Second trigger is a no-op |
| A2.4 | Define the fallback when no reference exists yet at Review time (clear message, no crash) | S | Review degrades gracefully |
| A2.5 | Kill-and-restart test mid-generation | M | Automated or documented manual test |

### A3. Housekeeping and optional track

| ID | Task | Size |
|----|------|------|
| A3.1 | Tick the "TTS for AI responses" box in `TODO.md` (its own note says it is done) | S |
| A3.2 | Superseded by A4 (local speech-to-text is now in scope) | - |

### A4. Local speech-to-text  (decided: Moonshine now, other engines later)

Goal: input works in Firefox, Safari and privacy browsers, and audio never leaves the device,
so the PRD's "both legs on-device" claim becomes true. Web Speech stays as an automatic
fallback, and typing as the last resort.

**Engine decision:** ship **Moonshine** only. It is small, built for live on-device streaming and
runs in the browser, which fits a talk-while-you-draw interview. **Whisper and other engines are
not built now**, but the design must make adding one a small change (A4.5): every engine sits
behind one interface, selected by configuration, with no Moonshine-specific code outside its
own adapter.

Spike result (A4.1): `moonshine-tiny` q8 is ~28 MB, runs ~14x faster than real time on a desktop
and scores ~13 % word error rate on clean synthetic speech, but it garbles key jargon (Redis ->
"readies", shard -> "charge", idempotent -> "imposed"). The English models are English-only;
Moonshine publishes separate models for other languages, so that is a model swap. If real-speech
validation (A4.12) fails, the fallback plan is a Whisper adapter (see "Future engines").

| ID | Task | Size | Done when |
|----|------|------|-----------|
| A4.1 | **DONE 2026-10-12 - conditional GO with `moonshine-tiny` q8** (see `docs/architecture.md`, "Local STT spike"). **Spike on Moonshine:** run the available model sizes in the browser (`transformers.js`/ONNX, WASM path; WebGPU only as an optional speed-up) in Chrome, Firefox and Safari. Record model size, load time, latency per chunk, and word-error-rate on a recorded set of system-design phrases. Set the pass/fail targets here. | M | Numbers and a go/no-go written in `docs/architecture.md` |
| A4.2 | Audio capture: `AudioWorklet` producing 16 kHz mono chunks, simple voice-activity detection to cut utterances | M | Clean utterance chunks from the mic |
| A4.3 | Run inference in a Web Worker so the whiteboard UI never freezes | M | UI stays responsive during transcription |
| A4.4 | Model delivery: self-hosted files (no third-party CDN), download progress UI, cached in Cache API/IndexedDB, integrity check | M | Second visit starts instantly; tampered file is rejected |
| A4.5 | **Engine interface + Moonshine adapter.** Define one `SpeechEngine` contract (`load()`, `start()`, `stop()`, events for partial and final text, `dispose()`), and implement `MoonshineEngine` against it. The existing Web Speech code becomes a second adapter. | M | Interview room runs unchanged with either engine; no engine-specific code outside its adapter |
| A4.6 | New `useLocalSpeechRecognition` hook with the **same interface** as the existing `useSpeechRecognition`, built on `SpeechEngine`. Engine choice comes from one config value, so swapping engines needs no UI change. | S | Hook works with both adapters |
| A4.7 | Engine selection and fallback: Moonshine when supported, Web Speech if it isn't, typing for weak devices, with a user-visible setting | S | Behaviour on Chrome, Firefox, Safari, Brave documented and tested |
| A4.8 | Hosting/CSP: WASM needs `wasm-unsafe-eval` in the CSP - agree it with H13. The spike showed threads give no benefit at this model size, so **COOP/COEP are not required** (leave them off unless a later engine needs threads). | S | CSP set; no console errors |
| A4.9 | Update README/PRD wording and the in-UI notice; remove the Chrome/Edge-only warning where no longer true; state that local input is **English-only** | S | Docs match reality |
| A4.10 | Tests: word-error-rate on the recorded phrase set, latency budget, low-end laptop check, and a Firefox + Safari manual pass (the spike only ran in Chromium) | M | Meets the targets set in A4.1 |
| A4.11 | **Domain-term correction:** fuzzy/phonetic match each utterance against a system-design glossary plus the labels currently on the whiteboard (so "readies" becomes Redis when a Redis node exists). Runs on-device. | M | Jargon recall >= 80 % on the A4.12 set |
| A4.12 | **Real-speech validation:** 5+ speakers, mixed accents, laptop microphones, with a pass threshold (suggested <= 15 % word error rate after A4.11). The spike used synthetic speech only. | M | Report committed; go/no-go on staying with Moonshine |

**Future engines (not scheduled).** Each is one adapter plus a config entry:

| Engine | When to consider it |
|--------|---------------------|
| Whisper (in-browser, `transformers.js`) | The A4.1 accuracy is not good enough on technical terms, or non-English input is needed |
| Vosk (in-browser) | A smaller or faster model is needed for very weak devices |
| Our own server (`faster-whisper`) | Low-end devices can't run any local model; costs the on-device privacy claim for those users |
| Paid streaming API | Only if the no-paid-API goal is dropped |

---

## Part B - Hardening backlog (fix before the security pass)

Each row is a concrete observation from the code with the file to look at. Severity is my
estimate pending confirmation in Part C.

### P0 - fix now, small and high impact

| ID | Finding | Where | Fix | Size |
|----|---------|-------|-----|------|
| H1 | **(Verification deferred - interim mitigation required.)** **Owner role can be claimed by registering the owner's email.** Owner is decided purely by the JWT `email` claim matching `OWNER_EMAILS`, and emails are never verified. If the owner hasn't signed up yet, anyone can register that address and get `ROLE_OWNER`. | `OwnerPolicy`, `JwtAuthFilter`, `AuthService.signup` | Verify email ownership before issuing a token with owner rights, or bind owner to a user id set at deploy time rather than an email string | M |
| H2 | *(also the main interim mitigation for H1)* **Case-variant owner spoof.** `users.email` is `UNIQUE` but case-sensitive, while `OwnerPolicy` matches with `equalsIgnoreCase`. `Owner@x.com` and `owner@x.com` are different rows, but both count as the owner. | `V1__init.sql`, `OwnerPolicy` | Normalise emails to lowercase on write; unique index on `lower(email)`; migration to merge existing duplicates | S |
| H3 | **Default JWT secret and DB credentials work in any environment.** `JWT_SECRET` falls back to a public string; Postgres defaults to `ossature/ossature`; pgAdmin to `admin/admin`; DB port published to the host. | `application.yml`, `docker-compose.yml` | Fail startup under a `prod` profile if the secret is the default or short; no default DB password; don't publish 5432 in prod; drop pgAdmin | S |
| H4 | **Agent shared secret fails open.** Blank `INTERNAL_AUTH_TOKEN` means no check, and the compare uses `!=` (not constant-time). | `agent/app/security.py` | Refuse to start in prod without it; use `hmac.compare_digest` | S |
| H5 | **Sessions can be started on non-approved questions.** `SessionService.create` doesn't check `status`, so a PENDING or REJECTED community prompt can be run by UUID, bypassing the review workflow. `GET /api/questions/{id}` is also public and returns any status. | `SessionService.create`, `SecurityConfig`, `QuestionController.get` | Require `APPROVED` (or owner/submitter) for both | S |
| H6 | **`/actuator/metrics` is readable by any logged-in user.** It is exposed in config but only `health`/`info` are permitted; everything else just needs "authenticated". | `application.yml`, `SecurityConfig` | Restrict actuator (other than health) to `ROLE_OWNER` or a separate management port | S |
| H7 | **No size limits.** `SubmitQuestionRequest` has no `@Size`; no max request body; no WebSocket max message size; transcript frames go to a paid LLM. | `ApiDtos`, `WebSocketConfig`, `InterviewFlowService` | `@Size` on all text fields, server-wide body limit, WS `maxTextMessageSize`, per-session message rate cap | M |

### P1 - fix before launch

| ID | Finding | Where | Fix | Size |
|----|---------|-------|-----|------|
| H8 | **Login lockout of other users.** Signup and login share the rate-limit key `login:email:<email>`, so anyone can burn a victim's attempts and lock them out. | `AuthController.guardLoginAttempt` | Key on (email + IP) or use a backoff that doesn't hard-lock; separate signup bucket | S |
| H9 | **WebSocket token in the URL** (`?token=`) ends up in access logs, proxies and history. `/ws/**` is `permitAll` and relies on hand-rolled checks. | `InterviewWebSocketHandler` | Short-lived single-use WS ticket from an authenticated REST call, or subprotocol header | M |
| H10 | **JWT hygiene.** 24 h expiry, no revocation or logout on the server, no `iss`/`aud`, token kept in `localStorage`/`sessionStorage` (readable by any XSS). | `JwtService`, `frontend/src/api/auth.tsx` | Shorter access token + refresh, `iss`/`aud` checks, strict CSP; consider httpOnly cookie + CSRF protection | L |
| H11 | **Stored prompt injection.** Approved community prompts and candidate speech are inserted into LLM prompts; one user's content shapes another user's session. | `agent/app/graph/nodes/*` | Delimit untrusted text, never let it set instructions or tool use, validate structured LLM output, test with an injection corpus | M |
| H12 | **Account enumeration.** Signup says an email exists (the UI now shows that text). Login correctly says "invalid email or password". | `GlobalExceptionHandler`, `SignupPage` | Accept the trade-off for usability, or move to email-confirmation signup; at least rate-limit signup per IP | S |
| H13 | **No security headers** on API responses; none defined for the frontend host. | backend config, deploy | HSTS, `X-Content-Type-Options`, `Frame-Options`/frame-ancestors, CSP, `Referrer-Policy` | S |
| H14 | **Remove the seeded demo user** from production data. | `V3__seed_demo_user.sql` | Make it dev-only (profile-scoped migration) | S |
| H15 | **Debug logging and PII.** `com.ossature.interviewer: DEBUG` plus transcripts and emails flowing through logs. | `application.yml` | INFO in prod; scrub tokens and transcript text from logs | S |
| H16 | **Deferred.** Password reset does not exist (the login page says so). Locked-out users have no recovery. | `LoginPage` | Needs email delivery - see "Future work" | L |

### P2 - defence in depth

| ID | Item | Size |
|----|------|------|
| H17 | **Postgres Row-Level Security - decided: build it, on top of app checks and CI tests.** Isolation is application-level only today (`owned()` in `SessionController`, plus the WS handler). Broken into R1-R8 below. | L |
| H18 | **Least-privilege DB users** - delivered as part of R2: separate roles for Flyway (DDL), backend (DML) and agent (its own table only). | (in R2) |
| H19 | **404 vs 403 on sessions** reveals whether a session ID exists. Return 404 for both. **Done via RLS (see Progress).** | S |
| H20 | **Dependency and image hygiene:** pin versions, enable Dependabot or Renovate, scan images, produce an SBOM. | M |
| H21 | **Data retention and deletion:** transcripts and debriefs are personal data; define retention, add account/session deletion. | M |

### B2. Row-Level Security (H17 / H18) - task series

Three layers, all kept: app checks (already there), RLS (new), and CI tests that try to cross
tenants (C3). RLS must **fail closed**: if the user context is not set, queries return nothing.

| ID | Task | Size | Done when |
|----|------|------|-----------|
| R1 | Ownership inventory per table. `interview_sessions.user_id` -> `NOT NULL` (after removing the demo user, H14); `session_events` and `interim_summaries` belong via their session (decide: join in the policy, or denormalise a `user_id`); `questions`: approved rows public, pending/rejected visible only to the submitter and the owner | S | Written table of who may read/write each row |
| R2 | Database roles (replaces H18): `ossature_migrator` (Flyway, owns tables), `ossature_app` (DML only, **not** table owner, no `BYPASSRLS`), `ossature_agent` (only its own session-state table). Use `FORCE ROW LEVEL SECURITY` so even owners obey policies | M | App can't `DROP`/`ALTER`; app role bypass attempts fail |
| R3 | Per-transaction user context: `set_config('app.user_id', <uuid>, true)` (transaction-local, **never** session-level, because connections are pooled) plus `app.is_owner`. Hook it in at transaction start from the authenticated principal | M | Context is set on every request and cleared after commit |
| R4 | Policies per table and per command (`SELECT`/`INSERT`/`UPDATE`/`DELETE`), written as a Flyway migration | M | Policies exist for all five tables |
| R5 | Non-request code paths with no logged-in user: `SummaryScheduler`, background Council/Review callbacks, WebSocket handler after its own token check. Give each an explicit context (the session owner's id, or a narrowly-scoped system policy) rather than a blanket bypass | M | Scheduler and background work still function; none runs with a bypass role |
| R6 | Testcontainers Postgres tests for every policy: unset context -> zero rows; user A -> user B rows denied for all four commands; owner-only paths; `SET ROLE`/`RESET` escape attempts | M | Green in CI |
| R7 | Performance check: indexes supporting the policy predicates, `EXPLAIN` on history/replay queries | S | No regression on list/replay endpoints |
| R8 | Agent database access moved to `ossature_agent`; update `agent/.env.example`, compose and `docs/architecture.md` | S | Agent works with its restricted role only |

---

## Future work (explicitly deferred)

| Item | Why it matters | Needs |
|------|----------------|-------|
| **Email verification at signup** | Closes H1 for good: without it, owner rights rest on an unverified string. | Email sender (SMTP/provider), verification-token table, resend + expiry handling, UI states |
| **Password reset** (H16) | Users who forget a password are locked out; the login page already says "not available yet". | Same email sender, single-use expiring tokens, rate limits, anti-enumeration wording |

**H1 interim mitigation (required before go-live while verification is deferred):**
1. Ship the H2 fix (lower-cased emails + `lower(email)` unique index) first.
2. The real owner registers **before** `OWNER_EMAILS` is set in production.
3. Add a test proving a second account cannot be created for any case variant of an owner email.
4. Preferably move to owner-by-user-ID (set at deploy time) so email text alone never grants a role.
Until 1-3 are done, H1 counts as an open Critical in the release gate.

---

## Part C - Security test plan (run after feature freeze)

Run against a **staging copy** with production-like config (`prod` profile, real secrets,
Postgres on its own network). Never against production data. Every test below should end up as
an automated test where practical (Spring `MockMvc`/Testcontainers, `pytest`, or a script in
`security/`), so the pass is repeatable on every release.

### C1. Authentication
- Signup/login: weak and over-long passwords, unicode and case variants of an email, whitespace padding.
- JWT: `alg=none`, wrong key, tampered payload, expired, missing `sub`, non-UUID `sub`, token for a deleted user (**expect 401, no stack traces**). Tool: `jwt_tool`.
- Brute force: confirm per-email and per-IP limits, and that H8 (lockout of others) is gone.
- Token lifetime and logout behaviour match the design chosen in H10.
- Timing: login for known vs unknown email should be indistinguishable.

### C2. Authorization / RBAC
- Build a **role x endpoint matrix** (anonymous, user A, user B, owner) covering every route in `SecurityConfig`, `SessionController`, `QuestionController`, actuator and WS. Assert allow/deny for each cell.
- Owner endpoints (`/pending`, approve, reject) as anonymous, normal user, and a user with a **case-variant** of the owner email (H1, H2).
- Verify H5 (sessions on pending/rejected questions) and H6 (metrics).
- Matcher order regressions: confirm `/api/questions/pending` still beats the public `GET /api/questions/**`.

### C3. Tenant isolation / IDOR / RLS
- With two users A and B: B requests A's session, debrief, summaries, diagram, events (REST) - **expect 403/404 everywhere**. Repeat with guessed and sequential-looking IDs.
- WebSocket: B connects to A's `sessionId`; B sends frames to a session it doesn't own; reconnect with a stale `lastSeq` after another user's activity.
- Mass assignment: extra JSON fields (`userId`, `status`, `source`, `isOwner`) on every POST body are ignored.
- **RLS (decided, R1-R8):** connect to Postgres as the app role, run `SELECT` on other users' rows with the context variable unset, set to a different user, and set to the right user; run `UPDATE`/`DELETE` across tenants; try `RESET`/`SET ROLE`/`SET app.user_id` (session-level) escapes; confirm the app role has no `BYPASSRLS` and is not a table owner. **Expect zero cross-tenant rows and zero rows when the context is missing.**
- **Pooling leak:** hammer the API with alternating users on a small connection pool and assert no request ever sees the previous user's context.
- **Layers tested independently:** disable the app-level `owned()` check in a test build and confirm RLS alone still blocks the access (and vice versa).
- Keep all C3 REST/WS tests in CI.
- **Multi-instance (A1):** repeat the isolation and rate-limit tests against the two-backend/two-agent topology (A1.8), and verify a WebSocket reconnect that lands on a different instance still enforces ownership.

### C4. Injection
- **SQL:** all data access is JPA derived queries and SQLAlchemy Core with bound parameters (no raw string SQL found). Still fuzz every string and UUID parameter (`sqlmap`, plus manual payloads in `title`, `prompt`, `displayName`, `email`, path IDs) and check the Flyway-managed `jsonb` columns.
- **JSON/deserialization:** malformed, deeply nested, huge and duplicate-key bodies against REST and WS frames; unknown `type` values on WS frames.
- **Stored XSS:** submit `<script>`/attribute payloads as question title/prompt and display name; confirm React escapes them on Home, Review, History and Replay, and that no `dangerouslySetInnerHTML` exists.
- **Header/log injection:** CRLF in headers and in values that reach logs.
- **Prompt injection (H11):** a corpus of payloads in question prompts and in spoken/transcribed text - "ignore previous instructions", "print the reference solution", attempts to change grading or reveal other sessions. Expect no leakage of the hidden reference solution, no behaviour change in scoring, valid structured output.
- **SSRF / agent surface:** confirm nothing user-controlled becomes a URL fetched by backend or agent.

### C4b. Local speech-to-text (A4)
- Model and WASM files are served from our own origin only; a tampered model file fails the integrity check.
- No audio or transcript leaves the browser when the local engine is active (check the network tab / a proxy during a full interview).
- CSP and COOP/COEP headers don't open new holes (`wasm-unsafe-eval` scoped as narrowly as possible).
- Transcript text from the local engine goes through the same validation, size limits and prompt-injection handling as any other `transcript.final` frame.

### C5. WebSocket
- Missing/expired/forged token, wrong origin, origin spoofing, token in URL (H9) appearing in logs.
- Oversized frames, frame floods, slow-loris, thousands of idle connections.
- Sending `diagram.locked`/`session.end`/transcript frames in an invalid state (after end, before start, duplicate lock).

### C6. Abuse, cost and availability
- Session-creation cap (10/h) under parallel requests and across multiple tokens/IPs.
- LLM cost exposure: transcript flooding, very long transcripts, repeated submit/summary triggers. Define a per-user daily budget and a global circuit-breaker and test they trip.
- Rate limiter eviction/memory growth under many keys; behaviour with `X-Forwarded-For` behind the real proxy (code deliberately ignores it - re-check once a proxy exists).
- Load test with `k6` at expected peak x3; confirm graceful 429/503, not crashes.

### C7. Transport, headers, CORS
- TLS only, HSTS, modern cipher suites (`testssl.sh`). HTTP -> HTTPS redirect.
- CORS: allowed origin list exact-match only (no wildcard with credentials), preflight behaviour, `Origin: null`.
- Security headers from H13 present on API and frontend (`securityheaders`-style check, or ZAP baseline).

### C8. Secrets and configuration
- Run the app under the `prod` profile with defaults: it must **refuse to start** (H3, H4).
- Secret scan of the repo and history (`gitleaks`, `trufflehog`). Confirm `.env` files are ignored.
- No secrets in container images, logs, error pages or `/actuator/*`.
- Actuator: only health publicly; nothing sensitive exposed (H6).

### C9. Database and infrastructure
- Postgres not reachable from outside the private network; no default credentials; pgAdmin absent.
- Roles from H18 exist with least privilege; the app role cannot `DROP`/`ALTER`.
- Backups exist **and a restore has been tested**.
- Agent port not reachable from the internet; agent rejects calls without the internal token (and compares in constant time).

### C10. Dependencies and supply chain
- `OWASP Dependency-Check` / `mvn dependency-check` / `npm audit` / `pip-audit` / `osv-scanner`: no unaccepted High/Critical.
- Container image scan (`trivy`), base images pinned, run as non-root.
- Static analysis: `semgrep` (Java, Python, TypeScript, React rulesets), `oxlint` already in the frontend.

### C11. Dynamic scan and review
- OWASP ZAP baseline then authenticated active scan against staging (with two users loaded).
- Manual pass of anything the scanners can't reason about: the owner flow, the review/approve flow, WS lifecycle, LLM prompt assembly.

### C12. Privacy and logging
- Verify what is stored (transcripts, debriefs, emails), that logs don't contain tokens/transcripts (H15), and that deletion works (H21).

---

## Part D - Release gate

Ship only when **all** are true:

- [ ] Part A tasks done (A1, A2, A4 are all in scope), or each explicitly descoped in the release notes.
- [ ] All P0 and P1 items in Part B fixed or formally risk-accepted with an owner and a date. H1 needs its interim mitigation (see "Future work") at minimum.
- [ ] RLS (R1-R8) in place and its tests green, with the app-level checks still enabled.
- [ ] Every cell of the C2 role matrix and every C3 isolation test passes, and runs in CI.
- [ ] No High/Critical findings open from C4, C6, C8, C10, C11.
- [ ] Production startup refuses default secrets (C8), and TLS/headers pass (C7).
- [ ] Backup restore tested (C9). Incident contact and a rollback path documented.
- [ ] A short **security test report** is committed (what was run, versions, results, accepted risks).

## Suggested order of work

1. **First:** H2, H3, H4, H5, H6, H7 (all small) and the H1 interim mitigation - they remove the most serious issues cheaply.
2. **Data layer, together:** R1-R8 (RLS) and A1.2/A1.3 (agent DB-authoritative state). Both change how services use Postgres, so do them in one coordinated pass.
3. A2 (restart-safe Council), then the rest of A1 (shared rate limiter, scheduler, WebSocket routing, A1.8 test topology).
4. A4 (local speech-to-text) in parallel by whoever owns the frontend; it only touches the voice hook and hosting headers.
5. P1 items H8-H15.
6. Feature freeze -> Part C -> fix -> re-test -> Part D.
