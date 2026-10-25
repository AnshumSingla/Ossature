-- H21: users can delete their data, and old data is purged on a schedule.
--
-- Until now the backend role could not delete anything. These grants and policies add exactly the deletions
-- the application performs, each still limited by row-level security:
--
--   * a session (and, through ON DELETE CASCADE, its events and summaries): only your own (the existing
--     sessions_own policy already covers DELETE; this migration adds the privilege);
--   * your account: only in the 'auth' scope (where the password has just been re-checked), only your own row;
--   * questions you submitted: unpublished ones (pending / rejected) are deleted outright; ones the owner
--     approved are now part of the public bank, so they stay but lose the link to you (submitted_by -> NULL);
--   * retention: the 'retention' scope may list and delete sessions, but ONLY ones older than 30 days. That
--     floor is enforced here, in the database, whatever the configured retention period says, so a bug or a
--     bad setting in the purge job can never delete recent interviews.
GRANT DELETE ON users, questions, interview_sessions TO ossature_app;

CREATE POLICY users_auth_delete ON users FOR DELETE
    USING (app_scope() = 'auth' AND id = app_user_id());

CREATE POLICY questions_delete_own_unpublished ON questions FOR DELETE
    USING (submitted_by = app_user_id() AND status <> 'APPROVED');
CREATE POLICY questions_anonymise ON questions FOR UPDATE
    USING (submitted_by = app_user_id() AND status = 'APPROVED')
    WITH CHECK (submitted_by IS NULL);

CREATE POLICY sessions_retention_read ON interview_sessions FOR SELECT
    USING (app_scope() = 'retention' AND started_at < now() - interval '30 days');
CREATE POLICY sessions_retention_delete ON interview_sessions FOR DELETE
    USING (app_scope() = 'retention' AND started_at < now() - interval '30 days');
