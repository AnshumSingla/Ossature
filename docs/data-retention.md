# Personal data, retention and deletion

What the application stores about people, for how long, and how it is removed. Written for H21 in
`remaining-work-and-security-plan.md`; update it whenever a new kind of personal data is added.

## What is stored

| Data | Where | Why | Kept for |
|------|-------|-----|----------|
| Email, display name, bcrypt password hash | `users` | Sign-in | Until the account is deleted |
| Login sessions (hash of the refresh token) | `refresh_tokens` | Staying signed in | Until logout, expiry (30 days / 12 h) or account deletion; expired rows purged after a day |
| Interviews: what you said (transcript events), diagram snapshots, interim summaries, the debrief | `interview_sessions`, `session_events`, `interim_summaries` | The product | **Retention period (default 365 days after the interview started)**, or until you delete it |
| The interviewer's working copy of a session | `agent_sessions` | Lets the agent survive restarts / several instances | Removed when the interview ends, when the session or account is deleted, or (leftovers) by the retention job's agent cleanup |
| Questions you submitted | `questions` | Community question bank | Unpublished ones: until reviewed or you delete your account. Approved ones are part of the public bank and stay, **unlinked from you** when you delete your account |
| Rate-limit counters | `rate_limits` | Abuse protection | A day at most; keys are SHA-256 hashes, not emails or IP addresses |
| Used WebSocket ticket ids | `ws_ticket_redemptions` | Single-use tickets | Minutes |
| Logs | application logs | Operations | Per your log infrastructure; tokens, ticket values and emails are masked, transcripts are not logged (H15) |

## What leaves the system

While an interview runs, the question text and **what the candidate says** are sent to the language-model
provider configured for the agent (OpenAI by default) to produce the interviewer's replies, the reference
solution, the review and the debrief. The provider's own retention and training terms apply to that copy;
they are **not** controlled by this application. Before launch: choose the provider's zero-retention / no-training
option if available and say so in the privacy notice. Speech-to-text runs on the person's own device, so audio
never leaves it.

## Deleting data

- **One interview:** History -> Delete (`DELETE /api/sessions/{id}`). Removes the session, its events,
  summaries and debrief, and the agent's working copy. Owner only; another user's id is "unknown".
- **The whole account:** Your data -> Delete my account (`POST /api/account/delete`, password required).
  One transaction deletes every interview, unpublished submissions and login sessions and the account row, and
  anonymises approved submissions; then the agent's copies are dropped and the cookie is cleared. If any step
  fails nothing is deleted. A stolen access token alone cannot do this (it needs the password).
- **Download first:** Your data -> Download (`GET /api/account/export`) returns everything stored about the
  person as JSON (portability / right of access).
- **Automatic:** `RetentionJob` runs daily (03:30) on one backend instance, deleting interviews older than
  `ossature.retention.session-days` (default 365; `0` turns it off; minimum 30). The database enforces a hard
  floor of 30 days for this job regardless of the setting (migration V11), so a bad value cannot delete recent
  interviews.

## Known limits (accepted, to be stated in the privacy notice)

- **Backups** keep deleted data until they rotate out. Decide the backup retention (suggested: 30 days) and
  document it; restoring a backup must re-apply deletions made since (keep a deletion log or re-run the purge).
- **Logs** are outside the application's control (see above).
- **The language-model provider's copy** (see above).
- Deleting an account does not delete content already published in the question bank, only the link to the person.
