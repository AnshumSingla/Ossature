-- Remove the pre-auth placeholder identity seeded by V3.
--
-- V3 inserted 'demo@ossature.local' so the app had *some* user before real accounts existed.
-- Nothing in the application references it any more, it has no password (so nobody can log in as
-- it), and a seeded, well-known account has no place in a production database.
--
-- It is only deleted when nothing depends on it: if it owns sessions or submitted questions from
-- that era it stays, rather than cascading a delete through real data.

DELETE FROM users
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND password_hash IS NULL
  AND NOT EXISTS (SELECT 1 FROM interview_sessions s WHERE s.user_id = users.id)
  AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.submitted_by = users.id);
