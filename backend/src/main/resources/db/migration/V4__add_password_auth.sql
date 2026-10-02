-- Real auth (PRD 6.2 follow-up): bcrypt hash, set at signup.
-- Nullable because V3's seeded demo user predates auth and never had a password -
-- it simply can't log in until someone signs up fresh with their own email.
ALTER TABLE users ADD COLUMN password_hash TEXT;
