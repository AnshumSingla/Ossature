# TODO

Tracks progress against `whiteboard-interviewer-prd.md`'s phasing (§10). See
`docs/architecture.md` for the technical detail behind each item.

## Phase 1 — Core loop

- [x] Whiteboard — structured shapes (boxes, arrows, labels) via React Flow
- [x] Voice input — mic capture + Web Speech STT streaming to the backend
      (Chrome/Edge only — see "Known limitations" below)
- [x] WebSocket transcript streaming, with resume-on-reconnect via a replayed event log
- [x] Interview AI node answering clarifying questions live
- [x] Council node generating a reference solution in the background — real concurrency
      (background thread reading live state), not simulated

## Phase 2 — Submit & review

- [x] Diagram lock on submit
- [x] Review node diffing the diagram against the reference, returning ranked flaws
- [x] Targeted flaw-based questions voiced by the Interview node
- [x] `ai.thinking` filler push while Review is running (no dead air)

## Phase 3 — Debrief

- [x] Timed Summary node, wired up via a backend `@Scheduled` sweep
      (`SummaryScheduler` — was a real gap, closed)
- [x] Debrief node, built from persisted interim summaries (not the raw transcript,
      per the PRD's explicit cost choice)

## Phase 4 — Community & polish

- [ ] Community question submission — `QuestionBankService.submitForReview` currently
      just throws `UnsupportedOperationException`
- [ ] Owner review workflow for submitted questions
- [ ] Session history / replay UI
- [ ] TTS for AI responses, if cost allows (already done — `speechSynthesis` is free
      and local; nothing further needed here)

## Infrastructure / correctness

- [x] All three services (Spring Boot backend, LangGraph agent, React frontend) run and
      verified **live, end-to-end, in a real browser** — not just unit tests
- [x] Agent session-state durability — write-through Postgres cache
      (`agent/app/store.py`), survives an agent restart, purged once the backend holds
      the debrief durably
- [ ] **Auth** — `HomePage` still uses a hard-coded demo user id
      (`00000000-0000-0000-0000-000000000001`)
- [ ] **Rate limiting / abuse protection** on session creation — real cost exposure once
      Council is calling a paid LLM; nothing currently stops scripted session creation
- [ ] **Multi-instance agent support** — each agent instance's in-memory `_SESSIONS` is
      still authoritative between requests, so two instances would race. Durability fix
      (above) solves *restarts*, not *horizontal scaling*
- [ ] A background Council thread's in-flight work is still lost if the agent restarts
      mid-generation specifically (the thread dies with the process)

## Known limitations (deliberate, not bugs)

- **STT only works in Chrome/Edge**, not "any browser" — Web Speech API's
  `SpeechRecognition` streams audio to Google's backend over the network; Brave (and
  likely other privacy-hardened Chromium forks) strips that wiring and fails with a
  `network` error every time, Firefox doesn't implement it at all. TTS
  (`speechSynthesis`) is genuinely local everywhere. See docs/architecture.md "Voice
  input reality check". PRD §6.5's "both legs local/on-device" claim is therefore
  accurate for TTS only — true fix would be a local engine (e.g. WASM Whisper), not
  yet built; currently just surfaced clearly in the UI instead.

## Bugs found and fixed along the way

(Kept here as a record — not actionable, but useful context for why some code looks
the way it does.)

- Spring Boot 4's Jackson 3 migration (`com.fasterxml.jackson.databind` →
  `tools.jackson.databind`, checked `JsonProcessingException` → unchecked
  `JacksonException`)
- `registry.push(sessionId, ...)` passing a `UUID` where `String` was expected
- Missing `RestClient.Builder` autoconfiguration bean in Boot 4 (webmvc starter alone
  doesn't pull it in)
- `RestClient.builder().build()`'s default request factory silently dropped POST
  bodies talking to uvicorn — fixed via explicit `SimpleClientHttpRequestFactory`
- Flyway seed data used lowercase enum values (`'seed'`/`'approved'`) against
  `@Enumerated(EnumType.STRING)`, which persists/queries uppercase — question bank
  silently returned empty
- Missing demo-user row caused a FK violation on every session creation
- Frontend discarded every replayed WebSocket event (`if ('seq' in msg) return`),
  which meant the session-opening AI turn — only ever delivered via replay — never
  reached the UI
- `useSpeechRecognition` swallowed recognition errors silently and didn't restart on
  Chrome's own silence-timeout, making "mic on, no input, stops after ~5s" look
  identical whether the cause was normal or a real failure
