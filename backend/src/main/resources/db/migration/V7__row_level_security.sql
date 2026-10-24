-- Row-level security and least-privilege database roles (H17 / H18, tasks R1-R8).
--
-- Until now the application connected as the Postgres superuser that owns the schema. A superuser
-- bypasses row-level security entirely, so isolation between users rested on application code alone,
-- and a bug (or injection) in any query could read or change anyone's data - and the agent service
-- connected with the same all-powerful account.
--
-- After this migration:
--   ossature        - unchanged: owns the tables, used ONLY by Flyway (migrations).
--   ossature_app    - what the backend connects as. Not a superuser, owns nothing, BYPASSRLS off, and
--                     holds only the table privileges listed below.
--   ossature_agent  - what the agent connects as. Can touch agent_sessions and nothing else.
--
-- WHO IS ACTING is passed per transaction by the backend as three settings (see db/DbContext.java):
--   app.user_id   uuid of the signed-in user (unset for anonymous requests)
--   app.is_owner  'true' for the project owner
--   app.scope     'auth' (login/signup), 'scheduler' (summary sweep) or unset
-- They are set with set_config(..., is_local => true), so they vanish at commit/rollback and can never
-- leak into the next transaction on a pooled connection. If they are missing, the policies below
-- match nothing: the default is "no rows", not "all rows".
--
-- What this does and does not protect against: it stops one user's data being reached through a
-- mistake in application code (a missing ownership check, a wrong query). It cannot stop someone who
-- can run arbitrary SQL as ossature_app from setting these settings themselves - that needs the
-- SQL-injection defences in the application, which remain.
--
-- Future migrations that add tables MUST grant privileges to ossature_app explicitly and add policies;
-- there are deliberately no default privileges.

-- ---------------------------------------------------------------------------------------------
-- 1. Roles (cluster-wide, so created only if missing). Passwords come from Flyway placeholders
--    (APP_DB_PASSWORD / AGENT_DB_PASSWORD); production refuses to start with the dev defaults.
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ossature_app') THEN
        CREATE ROLE ossature_app LOGIN PASSWORD '${appDbPassword}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ossature_agent') THEN
        CREATE ROLE ossature_agent LOGIN PASSWORD '${agentDbPassword}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;

-- Nobody but the owner creates objects in the schema.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ossature_app, ossature_agent;

-- ---------------------------------------------------------------------------------------------
-- 2. The agent's own table, now managed here (so the agent needs no CREATE rights), plus a version
--    column for optimistic locking when more than one agent instance runs.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_sessions (
    session_id VARCHAR PRIMARY KEY,
    state      JSONB NOT NULL
);
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS version    BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

GRANT SELECT, INSERT, UPDATE, DELETE ON agent_sessions TO ossature_agent;

-- ---------------------------------------------------------------------------------------------
-- 3. Helpers that read the acting-user settings. missing_ok = true -> NULL when unset, so every
--    comparison below is false and nothing matches (fail closed).
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION app_user_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION app_is_owner() RETURNS boolean
    LANGUAGE sql STABLE
AS $$ SELECT COALESCE(current_setting('app.is_owner', true), '') = 'true' $$;

CREATE FUNCTION app_scope() RETURNS text
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.scope', true), '') $$;

-- ---------------------------------------------------------------------------------------------
-- 4. Privileges for the backend role: only what the code actually does (there are no deletes, and
--    users are never updated).
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT         ON users             TO ossature_app;
GRANT SELECT, INSERT, UPDATE ON questions         TO ossature_app;
GRANT SELECT, INSERT, UPDATE ON interview_sessions TO ossature_app;
GRANT SELECT, INSERT         ON session_events    TO ossature_app;
GRANT SELECT, INSERT         ON interim_summaries TO ossature_app;

-- ---------------------------------------------------------------------------------------------
-- 5. Policies. FORCE makes even the table owner obey them (the app role is not the owner anyway).
-- ---------------------------------------------------------------------------------------------

-- users: a signed-in user sees only their own row. Login and signup run in scope 'auth', the only
-- place that may look an account up by email or create one.
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
CREATE POLICY users_self        ON users FOR SELECT USING (id = app_user_id());
CREATE POLICY users_auth_read   ON users FOR SELECT USING (app_scope() = 'auth');
CREATE POLICY users_auth_insert ON users FOR INSERT WITH CHECK (app_scope() = 'auth');

-- questions: the approved bank is public (anonymous too). A submitter also sees their own pending or
-- rejected ones; the owner sees everything and is the only one who may approve or reject. A community
-- submission can only be created as PENDING, in the submitter's own name.
ALTER TABLE questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE questions FORCE ROW LEVEL SECURITY;
CREATE POLICY questions_read ON questions FOR SELECT
    USING (status = 'APPROVED' OR submitted_by = app_user_id() OR app_is_owner());
CREATE POLICY questions_submit ON questions FOR INSERT
    WITH CHECK (submitted_by = app_user_id() AND status = 'PENDING' AND source = 'COMMUNITY');
CREATE POLICY questions_moderate ON questions FOR UPDATE
    USING (app_is_owner()) WITH CHECK (app_is_owner());

-- interview_sessions: strictly your own. The summary scheduler may LIST active sessions (to learn who
-- owns them) but cannot change anything; it then acts as each owner for the real work.
ALTER TABLE interview_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY sessions_own ON interview_sessions FOR ALL
    USING (user_id = app_user_id()) WITH CHECK (user_id = app_user_id());
CREATE POLICY sessions_scheduler_list ON interview_sessions FOR SELECT
    USING (app_scope() = 'scheduler' AND state = 'ACTIVE');

-- session_events / interim_summaries: only for sessions you own. Checked against the session's owner
-- directly (not "any visible session"), so the scheduler's list-only view does not extend to them.
ALTER TABLE session_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_events FORCE ROW LEVEL SECURITY;
CREATE POLICY events_own_session ON session_events FOR ALL
    USING (EXISTS (SELECT 1 FROM interview_sessions s WHERE s.id = session_events.session_id AND s.user_id = app_user_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM interview_sessions s WHERE s.id = session_events.session_id AND s.user_id = app_user_id()));

ALTER TABLE interim_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE interim_summaries FORCE ROW LEVEL SECURITY;
CREATE POLICY summaries_own_session ON interim_summaries FOR ALL
    USING (EXISTS (SELECT 1 FROM interview_sessions s WHERE s.id = interim_summaries.session_id AND s.user_id = app_user_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM interview_sessions s WHERE s.id = interim_summaries.session_id AND s.user_id = app_user_id()));
