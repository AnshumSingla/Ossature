"""Shared-secret check for the backend -> agent bridge (optional in dev).

The agent sits behind the backend and never faces the candidate's browser directly, so this
isn't full auth - it just stops anything other than the backend (which now itself requires a
signed-in user, see backend/src/main/java/.../security) from hitting these endpoints for free.
"""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from app.config import get_settings


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    expected = get_settings().internal_auth_token
    if not expected:
        # Open only in local development. In production this is refused at startup
        # (see ensure_production_safe), so reaching here with no secret means dev mode.
        return
    # hmac.compare_digest is constant-time (a plain != leaks how many leading characters were
    # right via response timing). It needs bytes to accept non-ASCII input without raising.
    supplied = (x_internal_token or "").encode("utf-8")
    if not hmac.compare_digest(supplied, expected.encode("utf-8")):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or missing internal token")


_DEV_DB_PASSWORDS = {"", "ossature", "ossature_agent", "ossature_app"}


def ensure_production_safe() -> None:
    """Refuse to start in production with the shared secret unset (H4) or a default DB password.

    A blank INTERNAL_AUTH_TOKEN means every endpoint is callable by anything that can reach the
    agent's port. Fine on a developer laptop, never fine in production, so it is a startup
    failure rather than a quiet warning.
    """
    s = get_settings()
    if not s.is_production:
        return
    problems: list[str] = []
    if not s.internal_auth_token:
        problems.append(
            "INTERNAL_AUTH_TOKEN is empty, so the agent would accept calls from anyone "
            "(set the same secret here and in the backend's AGENT_SHARED_SECRET)"
        )
    if s.agent_db_password in _DEV_DB_PASSWORDS:
        problems.append("AGENT_DB_PASSWORD is unset or still a development default")
    if s.agent_db_user != "ossature_agent":
        problems.append(
            "AGENT_DB_USER must be the restricted ossature_agent role (the schema owner's account "
            "bypasses the database's least-privilege grants)"
        )
    if problems:
        raise RuntimeError("Refusing to start with APP_ENV=prod: " + "; ".join(problems) + ".")
