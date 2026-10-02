"""Shared-secret check for the backend -> agent bridge (optional in dev).

The agent sits behind the backend and never faces the candidate's browser directly, so this
isn't full auth - it just stops anything other than the backend (which now itself requires a
signed-in user, see backend/src/main/java/.../security) from hitting these endpoints for free.
"""

from __future__ import annotations

from fastapi import Header, HTTPException, status

from app.config import get_settings


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    expected = get_settings().internal_auth_token
    if not expected:
        return  # no shared secret configured - open, as before this existed
    if x_internal_token != expected:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or missing internal token")
