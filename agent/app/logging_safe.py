"""Keeps secrets and personal data out of the agent's logs (H15).

Same idea as the backend's Masking: the first defence is not logging bodies at all (see
``hide_parameters`` in store.py - SQLAlchemy would otherwise echo the whole interview state into
the error text when a save fails), and this formatter is the last line of defence for anything a
library puts in its own messages or tracebacks.
"""

from __future__ import annotations

import logging
import re

_JWT = re.compile(r"\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*")
_BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_SECRET_PAIR = re.compile(
    r"(?i)\b(token|ticket|password|passwd|secret|authorization|api[_-]?key|x-internal-token)"
    r"([\"']?\s*[:=]\s*[\"']?)([^\s&\"',;}\]]+)"
)
_EMAIL = re.compile(r"([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\.[A-Za-z]{2,})")
# SQLAlchemy appends "[SQL: ...]" and "[parameters: ...]" to database errors. The parameters are
# the data being written - for this service, the entire interview state.
_SQL_PARAMS = re.compile(r"\[parameters:.*?\](?=\s*\(Background on this|\s*$)", re.DOTALL)
# OpenAI-style keys, in case one ever ends up in an exception message.
_API_KEY = re.compile(r"\bsk-[A-Za-z0-9_-]{16,}")


def mask(text: str) -> str:
    if not text:
        return text
    out = _JWT.sub("[jwt]", text)
    out = _BEARER.sub("Bearer [redacted]", out)
    out = _API_KEY.sub("[api-key]", out)
    out = _SECRET_PAIR.sub(
        lambda m: f"{m.group(1)}{m.group(2)}{m.group(3) if m.group(3).startswith('[') else '[redacted]'}",
        out,
    )
    out = _SQL_PARAMS.sub("[parameters: redacted]", out)
    return _EMAIL.sub(r"\1***@\2", out)


class MaskingFormatter(logging.Formatter):
    """Formats a record normally, then masks the whole result (message and traceback)."""

    def format(self, record: logging.LogRecord) -> str:
        return mask(super().format(record))


_FORMAT = "%(asctime)s %(levelname)-5s [%(name)s] %(message)s"


def configure_logging(level: int = logging.INFO) -> None:
    """Idempotent. Masks every handler on the root and uvicorn loggers; adds a console handler if none."""
    root = logging.getLogger()
    if not root.handlers:
        handler = logging.StreamHandler()
        root.addHandler(handler)
    root.setLevel(level)

    handlers = list(root.handlers)
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        handlers.extend(logging.getLogger(name).handlers)
    for handler in handlers:
        if not isinstance(handler.formatter, MaskingFormatter):
            handler.setFormatter(MaskingFormatter(_FORMAT))
