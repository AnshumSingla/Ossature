-- H10: login sessions that can be ended on the server.
--
-- The access token the browser holds is now short-lived (15 minutes) and lives only in memory. What
-- keeps a user signed in is a refresh token: an opaque random value in an httpOnly cookie, stored here
-- ONLY as a SHA-256 hash (a database leak yields nothing usable). Each use rotates it: the presented
-- token is marked used and a new one in the same "family" replaces it. Presenting a token that was
-- already used (outside a short grace window for two tabs refreshing at once) means a copy has leaked,
-- so the whole family is revoked. Logout revokes the family too.
--
-- All access happens during login, signup, refresh and logout, i.e. in the 'auth' scope (see V7): the
-- caller has no signed-in identity yet when presenting a cookie, so the policy is scope-based, and
-- outside that scope the table is invisible.
CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    family_id  UUID        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE,
    persistent BOOLEAN     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The family's absolute expiry, inherited by every rotation: a stolen cookie cannot be kept alive forever.
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_refresh_tokens_family  ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens TO ossature_app;

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY refresh_tokens_auth ON refresh_tokens FOR ALL
    USING (app_scope() = 'auth') WITH CHECK (app_scope() = 'auth');
