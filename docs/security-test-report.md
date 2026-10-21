# Security test report (working draft, 2026-10-21)

What was run against this codebase, with what result, what it found, and what has **not** been done. This is the
document Part D of `remaining-work-and-security-plan.md` asks for; it is a draft until the "not yet run" items below
are done against a staging deployment.

Versions: Spring Boot 4.1.1 (Tomcat 11.0.25, Jackson 3.1.7), PostgreSQL 16.15, Python 3.11 with FastAPI 0.157 /
LangGraph 1.2 / langchain-core 1.6, React 19 / Vite 8, Java 21.

## Automated regression suites (all run on every build)

| Suite | Tests | Covers |
|-------|-------|--------|
| Backend (`./mvnw test`) | 199 | see below |
| Agent (`pytest`) | 159 (+15 live-model probes, skipped without `OPENAI_API_KEY`) | injection corpus, secret handling, locking, council recovery, least-privilege role |
| Frontend (`npm test`) | 40 | session handling, XSS sinks, CSP |

Backend highlights, by plan item:

- **C1 authentication** - `JwtServiceTest`, `HostileInputFullStackTest`: `alg=none`, wrong key, tampered payload, expired,
  not-yet-valid, wrong type/issuer/audience, missing or non-UUID subject, junk and oversized headers, a token for a
  deleted account - all refused with no stack trace. Login rate limits, constant-work login, case/whitespace email
  variants (earlier suites). Token lifetime 15 min plus server-side logout (H10).
- **C2 authorization** - `RoleMatrixWebTest`: anonymous / user / owner against every endpoint (26 rows), look-alike owner
  emails, a WebSocket ticket used as a bearer token, and a **completeness guard** that fails the build if an endpoint
  exists without a row. Mutation-checked in both directions.
- **C3 tenant isolation** - `TenantIsolationFullStackTest` (real server, real database, restricted role): another user's
  session/events/summaries/debrief/diagram/ticket/delete all 404 and indistinguishable from a nonexistent id; list
  endpoints own-only; pending questions invisible; the owner role gives no access to others' interviews; export is
  own-only; mass assignment (`isOwner`, `userId`, `status`, `source`, `submittedBy`, `state`) ignored on signup,
  questions and sessions; WebSocket: foreign ticket, login token as ticket, no ticket, wrong origin, ticket reuse and
  frames naming another session all refused; a **connection-pool leak** test (3 connections, 8 threads alternating users
  and anonymous). **Layers independently:** with the controller's ownership check disabled, row-level security alone
  still blocks all of it (12/12); with RLS opened up, the controller check alone still denies. Plus 23 policy tests and
  7 service-level RLS tests (`RlsPolicyTest`, `RlsApplicationTest`) and the multi-instance script
  (`scripts/two_instance_check.py`).
- **C4 injection** - malformed, deeply nested, duplicate-key, oversized and control-character bodies never produce a
  5xx or leak internals; SQL-injection strings are stored as plain text; path-parameter payloads; CRLF in a header
  value; stored-XSS sinks are absent from the frontend source (test); prompt-injection corpus (87 agent tests, H11).
- **C4b local speech** - CSP test: no inline or eval scripts, `wasm-unsafe-eval` only, connections only to our own
  origins with a self-hosted model.
- **C5 WebSocket** - garbage, `null`, oversized frames, NUL bytes, forged `sender: ai`, locking the diagram twice, any
  frame after the session ended, a failed debrief: all handled; the paid agent is called the expected number of times.
- **C7 CORS** - only the exact configured origin is echoed; `null`, look-alike and wildcard origins are not.
- **C8 secrets** - production refuses to start on default secrets and passwords (`ProductionSafetyCheckTest`, agent
  tests); **gitleaks over the full history: no leaks** (one reviewed false positive, the public jwt.io sample token in a
  log-masking test, allowlisted in `.gitleaks.toml`).
- **C10 dependencies** - `scripts/audit_dependencies.py` (OSV; 429 packages): clean apart from one dated, reasoned
  exception (`sharp`, never shipped to the browser).
- **C12 privacy** - deletion, export and retention tests (H21); log masking tests (H15).

## What this pass found and fixed

| Finding | Severity (my judgement) | Fix |
|---------|-------------------------|-----|
| Tomcat (3 Critical), Jackson (High), vite, and a year-old Python AI stack with Critical/High advisories | High | upgraded / overridden (H20) |
| A `null` JSON frame, or any frame with a missing field, killed the candidate's WebSocket (NPE) | Medium | frames are validated; one bad frame no longer closes the connection |
| A NUL byte in a question title/prompt/display name returned **500** (Postgres refuses it) | Medium | rejected with 400 (`@SafeText`); transcripts cleaned; event log strips NUL escapes |
| The diagram could be locked and reviewed repeatedly - **each a paid model call** | Medium (cost) | state guard: locked once |
| Frames (each possibly a paid call) were still accepted after a session ended | Medium (cost) | ended sessions accept nothing |
| A client could write chat messages as the interviewer (`sender: ai`) | Low | only `candidate` accepted from clients |
| A session could not be ended while the model was unreachable (stayed open, accepting paid frames) | Low | ending no longer depends on the debrief |
| A still-valid access token for a deleted account made writes return **500** (FK violation) | Low | answers 401 "sign in again"; DB integrity errors map to 409 |
| A signed token with no subject caused a 500 in token parsing | Low | any malformed token is "not logged in" |
| The old WebSocket's close notification unregistered a reconnected socket (silent loss of all pushes) | Medium | fixed (multi-instance work) |
| Writer starvation under cross-instance contention lost a candidate turn | Medium | backoff plus a larger retry budget |
| `RlsApplicationIT` was never run (surefire ignores `*IT`) | Process | renamed; now part of the suite |

## Not yet run (so this report does not claim them)

- **C6 cost controls:** a per-user daily LLM budget and a global circuit breaker are **not built**; the per-session
  transcript rate (30/min), session creation (10/h) and the new state guards are the current limits. No k6 load test.
- **C7 transport:** TLS, HSTS behaviour, HTTP to HTTPS redirect - needs a deployment (`testssl.sh`).
- **C9 infrastructure:** backup restore test, network exposure of Postgres and the agent - needs staging.
- **C10:** container image scan (`trivy`) - there are no application images yet; `semgrep` static analysis not run.
- **C11:** OWASP ZAP baseline/active scan against staging; manual review of the owner flow and prompt assembly.
- **Prompt injection against a real model** (needs `OPENAI_API_KEY`); langchain-core 1.x content-format check.
- **Speech-to-text:** Brave/Firefox/Safari with a real microphone, real-speaker accuracy, slow-laptop latency (A4.10-A4.12).
- **H1 / H16** (email verification, password reset) are deferred by decision; H1's interim mitigation is in place.
- The CI workflow is written but has not run on GitHub.

## Accepted risks

- An access token stays valid up to 15 minutes after logout or account deletion (stateless verification).
- Backups retain deleted data until rotation (policy to be set, see `data-retention.md`).
- Candidate speech goes to the LLM provider while an interview runs (see `data-retention.md`).
- `sharp` advisories (not shipped to the browser), until 2027-01-31.
