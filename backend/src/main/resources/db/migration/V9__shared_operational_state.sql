-- Multi-instance support (A1.5 - A1.7): state that used to live in one backend's memory and so was
-- invisible to the other instances. All three tables hold operational data only - no user content -
-- so, unlike the data tables in V7, they have no row-level-security policies; the backend role gets
-- exactly the privileges it needs and the agent role gets none.

-- A1.5: rate-limit counters. A fixed window per key; the key is stored as a SHA-256 hash so the table
-- never holds email addresses or IP addresses in the clear.
CREATE TABLE rate_limits (
    key_hash     TEXT        PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    hits         INTEGER     NOT NULL
);
CREATE INDEX idx_rate_limits_window ON rate_limits (window_start);

-- A1.6: a lease so that only one backend instance runs a scheduled job at a time.
CREATE TABLE scheduler_locks (
    name         TEXT        PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    locked_by    TEXT        NOT NULL
);

-- A1.7: WebSocket tickets that have been used, so a ticket is single-use across all instances
-- (it is a signed token and verifying it is stateless; only "already used" needs shared memory).
CREATE TABLE ws_ticket_redemptions (
    jti        TEXT        PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ws_ticket_redemptions_expiry ON ws_ticket_redemptions (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON rate_limits, scheduler_locks, ws_ticket_redemptions TO ossature_app;
