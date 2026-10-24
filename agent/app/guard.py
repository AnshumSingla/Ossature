"""Output-side checks for model responses (H11).

Input framing (``prompting.py``) lowers the chance a model is talked into misbehaving; these checks
make the worst outcomes impossible regardless: the hidden reference solution cannot be echoed back
to the candidate, and structured model output is forced into a known, bounded shape before anything
downstream (the backend, the UI, the text-to-speech) sees it.
"""

from __future__ import annotations

import json
import re
from typing import Any

# What the interviewer says instead when its reply would have revealed the reference.
SAFE_PROBE = (
    "Let's stay with your own design. Walk me through how it behaves when its most important "
    "component fails."
)

MAX_REPLY_CHARS = 600
LEAK_WINDOW_WORDS = 8  # this many consecutive words copied from the reference counts as revealing it

_TAG = re.compile(r"</?[A-Za-z][^>]{0,80}>")
_FENCE = re.compile(r"```[a-zA-Z]*")
_WORD = re.compile(r"[a-z0-9]+")


def _words(text: str) -> list[str]:
    return _WORD.findall(text.lower())


def _flatten(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return " ".join(_flatten(v) for v in value.values())
    if isinstance(value, (list, tuple)):
        return " ".join(_flatten(v) for v in value)
    return ""


def reveals_reference(reply: str, reference: dict[str, Any] | None) -> bool:
    """True if ``reply`` copies a run of ``LEAK_WINDOW_WORDS`` or more consecutive words from the
    reference solution. A paraphrase passes; a dump or a long quote does not."""
    if not reference:
        return False
    ref_words = _words(_flatten(reference))
    reply_words = _words(reply)
    n = LEAK_WINDOW_WORDS
    if len(ref_words) < n or len(reply_words) < n:
        return False
    ref_windows = {tuple(ref_words[i : i + n]) for i in range(len(ref_words) - n + 1)}
    return any(tuple(reply_words[i : i + n]) in ref_windows for i in range(len(reply_words) - n + 1))


def safe_reply(reply: object, reference: dict[str, Any] | None) -> tuple[str, bool]:
    """Normalise a spoken reply and enforce the no-leak rule.

    Returns ``(text, leaked)``. Markup and code fences are stripped (this is read aloud), the length
    is capped, and a reply that reveals the reference is replaced with ``SAFE_PROBE``.
    """
    text = reply if isinstance(reply, str) else str(reply or "")
    text = _FENCE.sub("", _TAG.sub("", text))
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) > MAX_REPLY_CHARS:
        cut = text[:MAX_REPLY_CHARS]
        end = max(cut.rfind(". "), cut.rfind("? "), cut.rfind("! "))
        text = cut[: end + 1] if end > 200 else cut.rstrip() + "..."
    if reveals_reference(text, reference):
        return SAFE_PROBE, True
    return (text or SAFE_PROBE), False


# ---- structured output ----


def parse_json(raw: object) -> Any:
    """json.loads that also accepts the ```json fenced blocks models like to add. None on failure."""
    if not isinstance(raw, str):
        return None
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*|\s*```$", "", text).strip()
    try:
        return json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return None


def _clip(value: Any, limit: int) -> str:
    s = value if isinstance(value, str) else ""
    s = re.sub(r"\s+", " ", _TAG.sub("", s)).strip()
    return s[:limit]


def sanitize_flaws(data: Any, max_items: int = 6) -> list[dict[str, Any]]:
    """Force the review model's output into ``[{summary, question_prompt, priority}]``: right types,
    bounded lengths, priority 1-5, markup stripped, most important first. Unusable items are dropped."""
    if not isinstance(data, list):
        return []
    flaws: list[dict[str, Any]] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        question = _clip(item.get("question_prompt"), 300)
        if not question:
            continue
        try:
            priority = max(1, min(5, int(item.get("priority", 3))))
        except (TypeError, ValueError):
            priority = 3
        flaws.append(
            {"summary": _clip(item.get("summary"), 200) or question[:200], "question_prompt": question, "priority": priority}
        )
    flaws.sort(key=lambda f: f["priority"])
    return flaws[:max_items]


def _str_list(value: Any, max_items: int = 8, limit: int = 300) -> list[str]:
    if not isinstance(value, list):
        return []
    return [c for c in (_clip(v, limit) for v in value if isinstance(v, str)) if c][:max_items]


def sanitize_debrief(data: Any) -> dict[str, Any] | None:
    """Force the debrief into exactly ``strengths/gaps/unresolved: string[]`` and
    ``reference_comparison: string``, bounded. None if the model returned something else entirely."""
    if not isinstance(data, dict):
        return None
    return {
        "strengths": _str_list(data.get("strengths")),
        "gaps": _str_list(data.get("gaps")),
        "unresolved": _str_list(data.get("unresolved")),
        "reference_comparison": _clip(data.get("reference_comparison"), 1000),
    }
