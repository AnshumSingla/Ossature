"""H15: secrets and personal data must not reach the agent's logs."""

from __future__ import annotations

import io
import logging

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

from app import logging_safe
from app.logging_safe import MaskingFormatter, mask

JWT = (
    "eyJhbGciOiJIUzI1NiJ9."
    "eyJzdWIiOiIxMjM0IiwiZW1haWwiOiJhQGIuY28ifQ."
    "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
)


def test_masks_jwts_bearer_and_secret_pairs():
    assert JWT not in mask(f"issued {JWT}")
    assert "abc.def" not in mask("Authorization: Bearer abc.def-123")
    out = mask("GET /x?ticket=SECRETVALUE&lastSeq=0 password: hunter2hunter2 x-internal-token=zzz")
    assert "SECRETVALUE" not in out and "hunter2hunter2" not in out and "zzz" not in out
    assert "lastSeq=0" in out


def test_masks_emails_keeping_the_domain():
    out = mask("account alice.smith@example.com exists")
    assert "alice.smith" not in out and "a***@example.com" in out


def test_masks_api_keys():
    assert "sk-abcdefghijklmnopqrstuvwx" not in mask("auth failed for sk-abcdefghijklmnopqrstuvwx")


def test_masks_sql_parameters():
    err = '(psycopg.Error) boom\n[SQL: INSERT INTO t (state) VALUES (%(state)s)]\n[parameters: {\'state\': {\'prompt\': \'secret question\'}}]\n(Background on this error at: https://sqlalche.me/e/20/xx)'
    out = mask(err)
    assert "secret question" not in out
    assert "[parameters: redacted]" in out


def test_leaves_ordinary_lines_alone():
    line = "Session-state save failed for 3343ac9b-4783-42e6-babf-67dae8f47c0a; state stays in-memory only."
    assert mask(line) == line
    assert mask("") == ""


def test_is_idempotent():
    once = mask(f"Bearer {JWT} ticket=zzz bob@example.com")
    assert mask(once) == once


def _logger_with_masking():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(MaskingFormatter("%(levelname)s %(message)s"))
    log = logging.getLogger("test.h15")
    log.handlers = [handler]
    log.propagate = False
    log.setLevel(logging.DEBUG)
    return log, stream


def test_formatter_masks_message_and_traceback():
    log, stream = _logger_with_masking()
    try:
        raise RuntimeError(f"upstream said transcript=my-secret-words for carol@example.com with Bearer {JWT}")
    except RuntimeError:
        log.warning("call failed for ticket=ONETIME", exc_info=True)
    out = stream.getvalue()
    assert "ONETIME" not in out and JWT not in out and "carol@example.com" not in out
    assert "RuntimeError" in out  # the useful part survives


def test_a_failed_database_write_does_not_echo_the_interview_state():
    """End to end with a real SQLAlchemy error: hide_parameters must keep the data out of the message."""
    engine = create_engine("sqlite://", hide_parameters=True)
    with engine.connect() as conn:
        with pytest.raises(SQLAlchemyError) as exc:
            conn.execute(text("INSERT INTO no_such_table (state) VALUES (:state)"), {"state": "the candidate's whole transcript"})
    assert "the candidate's whole transcript" not in str(exc.value)


def test_the_store_engine_is_created_with_hidden_parameters():
    """Guard against someone dropping hide_parameters from store._get_engine()."""
    import inspect

    from app import store

    assert "hide_parameters=True" in inspect.getsource(store._get_engine)


def test_configure_logging_is_idempotent_and_masks_root_handlers():
    root = logging.getLogger()
    before = list(root.handlers)
    try:
        logging_safe.configure_logging()
        logging_safe.configure_logging()
        assert root.handlers
        assert all(isinstance(h.formatter, MaskingFormatter) for h in root.handlers)
    finally:
        root.handlers = before
