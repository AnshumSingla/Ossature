"""H4: the backend->agent shared secret must be checked in constant time and be mandatory in prod."""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app import security
from app.config import Settings, get_settings


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _use(monkeypatch, **env):
    for key in ("INTERNAL_AUTH_TOKEN", "APP_ENV"):
        monkeypatch.delenv(key, raising=False)
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    get_settings.cache_clear()


def test_dev_without_a_secret_stays_open(monkeypatch):
    _use(monkeypatch)
    security.require_internal_token(None)  # no exception


def test_correct_secret_is_accepted(monkeypatch):
    _use(monkeypatch, INTERNAL_AUTH_TOKEN="s3cret-value")
    security.require_internal_token("s3cret-value")


@pytest.mark.parametrize("supplied", [None, "", "s3cret-valuX", "s3cret", "s3cret-value-and-more", "ünïcode"])
def test_wrong_or_missing_secret_is_rejected(monkeypatch, supplied):
    _use(monkeypatch, INTERNAL_AUTH_TOKEN="s3cret-value")
    with pytest.raises(HTTPException) as exc:
        security.require_internal_token(supplied)
    assert exc.value.status_code == 401


def test_comparison_is_constant_time(monkeypatch):
    """Guard against someone reverting to `!=`: the check must go through hmac.compare_digest."""
    calls = []
    real = security.hmac.compare_digest
    monkeypatch.setattr(security.hmac, "compare_digest", lambda a, b: calls.append(1) or real(a, b))
    _use(monkeypatch, INTERNAL_AUTH_TOKEN="s3cret-value")
    security.require_internal_token("s3cret-value")
    assert calls


def test_prod_without_a_secret_refuses_to_start(monkeypatch):
    _use(monkeypatch, APP_ENV="prod")
    with pytest.raises(RuntimeError, match="INTERNAL_AUTH_TOKEN"):
        security.ensure_production_safe()


def test_prod_with_a_secret_starts(monkeypatch):
    _use(monkeypatch, APP_ENV="prod", INTERNAL_AUTH_TOKEN="s3cret-value")
    security.ensure_production_safe()


def test_dev_without_a_secret_starts(monkeypatch):
    _use(monkeypatch)
    security.ensure_production_safe()


def test_production_flag_accepts_both_spellings():
    assert Settings(app_env="production", _env_file=None).is_production
    assert Settings(app_env=" PROD ", _env_file=None).is_production
    assert not Settings(app_env="dev", _env_file=None).is_production
