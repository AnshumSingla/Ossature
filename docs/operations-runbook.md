# Operations runbook: rollback, secrets, incidents

For whoever is on call. It describes what the code actually does today; where something has to be decided or filled in by
a person, it says so in **bold**. Update it when the behaviour changes.

## 0. Fill in before launch (not something the code can decide)

| What | Value |
|------|-------|
| **Incident contact (primary / backup), and how to reach them out of hours** | **TO BE FILLED IN** |
| **Who may approve a rollback or an emergency shutdown** | **TO BE FILLED IN** |
| **Where logs, metrics and alerts live** | **TO BE FILLED IN** (the app logs to stdout; masking is built in, see `data-retention.md`) |
| **Backup schedule, retention and the last tested restore date** | **TO BE FILLED IN** (a restore has not been tested yet - Part C9) |
| **Who is told if personal data may have been exposed, and by when** (legal / regulator / users) | **TO BE FILLED IN** |

## 1. What runs where

| Component | Needs | Health |
|-----------|-------|--------|
| Backend (Spring Boot), any number of instances | Postgres, the agent, `JWT_SECRET`, `AGENT_SHARED_SECRET`, `APP_DB_PASSWORD`, `POSTGRES_PASSWORD` (migrations), `AGENT_DB_PASSWORD`, `OWNER_EMAILS`, `SPRING_PROFILES_ACTIVE=prod`, `COOKIE_SECURE=true`, allowed CORS origin(s) | `GET /actuator/health` (public); metrics need the owner login |
| Agent (FastAPI), any number of instances | Postgres, `INTERNAL_AUTH_TOKEN` (= backend `AGENT_SHARED_SECRET`), `AGENT_DB_PASSWORD`, `OPENAI_API_KEY`, `APP_ENV=prod` | `GET /health` |
| Frontend (static files) | a host that sends the headers in `dist/_headers` / `dist/nginx-security-headers.conf`; must be **same-site** with the API (e.g. `app.` and `api.` subdomains) | page loads; voice indicator reaches "ready" |
| Postgres 16 | the only stateful part | `pg_isready` |

Both services **refuse to start in production** with default secrets, default DB passwords, an insecure cookie, or the
schema-owner account as the application account. A start-up failure naming one of those is the safety check working, not
a bug.

State that matters lives in Postgres only. Backend and agent processes can be killed and restarted at any time: sessions
continue on another instance, an interrupted Council job is taken over after its lease lapses (~90 s), the summary sweep
runs on whichever instance holds the lease.

## 2. Deploying and rolling back

**Migrations run automatically when a backend instance starts** (Flyway, as the schema-owner account) and are
**forward-only**: there is no "down". Consequences:

1. **Take a backup before any release that contains a new migration.** That backup is the only real rollback for data.
2. During a rolling deploy old and new instances run together against the new schema, so a migration must work for the
   old code too: **add** columns/tables first, switch the code, **remove** old things in a later release (expand, then
   contract). Review each new migration against that rule.
3. **Rolling back the application** (the previous image/jar) is safe as long as the schema change was additive: the old
   code ignores new tables and columns. Check the migration list between the two versions; if one is not additive
   (drops, renames, new NOT NULL without default), rolling back needs a database restore instead.
4. **Rolling back a bad migration:** stop the new instances, restore the pre-release backup, deploy the old version. Do
   not edit an applied migration file (Flyway will refuse to start on a checksum mismatch); fix forward with a new one.
5. The **agent** can be rolled back independently of the backend: its table (`agent_sessions`) is only added to.

After any deploy: `/actuator/health` on every backend, `/health` on every agent, sign in, start an interview, send a
message, end it.

Pre-release check: `./mvnw test` (backend), `pytest` (agent), `npm test && npm run build` (frontend),
`python scripts/audit_dependencies.py`, and `python scripts/two_instance_check.py` when anything shared changed.

## 3. Rotating secrets

| Secret | How to rotate | Effect on users |
|--------|---------------|-----------------|
| `JWT_SECRET` | set the new value on **all** backend instances, restart | every access token and WebSocket ticket becomes invalid immediately. Users are **not** signed out: the refresh cookie (stored in the database) still works, so clients silently get a new token within seconds |
| Sign **everyone** out | as the schema owner: `UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL;` (plus rotate `JWT_SECRET` so live access tokens die too) | everyone must log in again |
| Sign one person out | revoke their refresh tokens (same UPDATE with `WHERE user_id = ...`); their access token lasts up to 15 more minutes | that person |
| `AGENT_SHARED_SECRET` / `INTERNAL_AUTH_TOKEN` | change **both** sides together (a brief window of failed agent calls is normal during the switch) | interviews pause briefly |
| `APP_DB_PASSWORD`, `AGENT_DB_PASSWORD` | **the roles are created once with the password at the time; changing the environment value does not change the role.** Run `ALTER ROLE ossature_app PASSWORD '...'` (and `ossature_agent`) as the owner, then update the services' environment and restart | none if done in that order on a quiet moment |
| `POSTGRES_PASSWORD` (schema owner, used only for migrations) | `ALTER ROLE ossature PASSWORD '...'`, update the deploy environment | none |
| `OPENAI_API_KEY` | replace on the agents, restart | none |
| `OWNER_EMAILS` | change and restart. **Register the owner account first**, then set the variable (email verification is not built, H1) | the old owner loses review rights |

Treat any of these as compromised if it appeared in a log, a ticket, a chat or a repository. The repository history was
scanned with gitleaks and is clean (`docs/security-test-report.md`).

## 4. Incident playbooks

First, in every case: **write down the time you noticed it, keep the evidence (logs, the request), and tell the
incident contact (section 0).** Do not delete data to "clean up" until it has been copied somewhere safe.

### 4.1 Model (LLM) cost spike or abuse
Signals: the provider's usage dashboard; logs containing `Over the ... budget`.
1. Limits already in force: 30 transcript turns/min/session, 10 new sessions/h/user, **1500 turns/user/day, 5000 turns/hour
   for the whole service** (`ossature.budget.*`).
2. To stop paid calls quickly without taking the site down, lower the budgets (env `OSSATURE_BUDGET_USERDAILYTURNS`,
   `OSSATURE_BUDGET_GLOBALHOURLYTURNS`, e.g. to `1`) and restart; the interview then answers "very busy". `0` means
   *unlimited*, not zero.
3. To block one person: revoke their refresh tokens (section 3; their access token lasts up to 15 more minutes) and, if
   the account is abusive, delete it in SQL as the schema owner (the in-app deletion needs the person's own password).
4. If the key itself leaked, rotate `OPENAI_API_KEY` at the provider first, then on the agents.

### 4.2 Suspected token theft / account takeover
1. Revoke that person's refresh tokens (section 3). A reused refresh token already revokes its whole login
   automatically; look for `A used refresh token was presented again` in the backend log.
2. If the signing secret may be exposed: rotate `JWT_SECRET` and sign everyone out.
3. Check the log for logins from unexpected places (login attempts are rate-limited per account and address).

### 4.3 Suspected data exposure (one user seeing another's data)
This is the case row-level security exists for, so first check whether the database layer was bypassed.
1. Confirm the backend connects as `ossature_app`, not the owner: `SELECT current_user` from the app's pool, or look
   at the datasource user in the running configuration. In production the app refuses to start as the owner.
2. Reproduce with two test accounts using the role matrix / isolation tests (`RoleMatrixWebTest`,
   `TenantIsolationFullStackTest`, `RlsPolicyTest`). A failure there points at the change that caused it.
3. If real data was exposed: stop the affected endpoint (deploy the previous version), preserve logs, and follow the
   notification duties in section 0.

### 4.4 Database trouble
- **Postgres down:** the backend returns errors and the agent falls back to in-memory state for a while (state is lost
  if the agent also restarts). Restore Postgres first; sessions resume from the event log.
- **Needs a restore:** restore the backup, then **re-apply deletions made since the backup** (people who deleted an
  interview or account must not reappear; the retention job will re-delete old interviews on its next run, user-requested
  deletions have to be replayed from the deletion log you keep - **decide where that log lives**, section 0). Then run
  the health checks in section 2.
- **A migration failed half-way:** Flyway marks it failed and the instances will not start. Inspect `flyway_schema_history`,
  restore the pre-release backup (migrations are not reliably re-runnable), fix forward with a corrected migration.

### 4.5 The agent or the model provider is down
Interviews continue to record what the candidate says; the interviewer stays quiet. Ending a session still works (it
ends without a debrief). Nothing to do except restore the agent / provider. Check `Agent ... failed` log lines.

### 4.6 A dependency vulnerability is announced
`python scripts/audit_dependencies.py` (also run weekly in CI); update the pinned version, run the three test suites,
deploy. Spring Boot property overrides for Tomcat/Jackson live at the top of `backend/pom.xml`.

## 5. Useful queries (as the schema owner; the application account cannot see most of this by design)

```sql
-- who is signed in (live login sessions) and since when
SELECT user_id, count(*) AS tokens, max(created_at) FROM refresh_tokens WHERE revoked_at IS NULL AND expires_at > now() GROUP BY user_id;
-- is the summary sweep lease healthy, and who holds it
SELECT * FROM scheduler_locks;
-- agent working state still lingering
SELECT session_id, version, updated_at FROM agent_sessions ORDER BY updated_at DESC LIMIT 20;
-- unfinished Council jobs
SELECT session_id, state->>'council_status' AS status, state->>'council_attempts' AS attempts FROM agent_sessions WHERE state->>'council_running' = 'true';
```

## 6. Known gaps (do not rely on these yet)
- A restore has never been rehearsed; no deletion log exists; no alerting is configured; the CI workflow has not run on
  GitHub; TLS/headers have not been checked on a real deployment. See `security-test-report.md` ("Not yet run").
