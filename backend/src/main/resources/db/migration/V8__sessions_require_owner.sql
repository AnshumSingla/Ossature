-- R1: every interview session belongs to a user. The demo user (the only source of owner-less
-- sessions) was removed in V6; any leftover owner-less rows are unreachable under row-level
-- security anyway, so drop them before tightening the column.
DELETE FROM interview_sessions WHERE user_id IS NULL;
ALTER TABLE interview_sessions ALTER COLUMN user_id SET NOT NULL;
