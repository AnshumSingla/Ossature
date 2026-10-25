"""C6: a small load check - does the API degrade gracefully (fast 2xx/4xx/429, never 5xx or hangs) under bursts?

    python scripts/load_check.py [base_url] [seconds] [workers]

Run it against a THROWAWAY backend (it creates accounts and sessions), ideally with the production rate limits so the
429s are real. It is not a substitute for k6 against staging with production-like hardware, but it catches the failures
that matter early: 5xx under concurrency, unbounded latency, and limits that do not trip.

Scenarios, all concurrent: anonymous question-list reads, authenticated session listing, repeated bad logins (must be
throttled with 429, not slow or crash), signup floods (must be throttled), and session creation beyond its cap.
"""

from __future__ import annotations

import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8095"
SECONDS = float(sys.argv[2]) if len(sys.argv) > 2 else 20
WORKERS = int(sys.argv[3]) if len(sys.argv) > 3 else 24

results: dict[str, list[tuple[int, float]]] = {}
lock = threading.Lock()


def call(label: str, method: str, path: str, body=None, token=None) -> int:
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:  # nosemgrep: dynamic-urllib-use-detected -- URL is the base given on the command line
            r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        e.read()
        status = e.code
    except Exception:
        status = 0  # connection error / timeout
    with lock:
        results.setdefault(label, []).append((status, (time.perf_counter() - t0) * 1000))
    return status


def signup() -> tuple[str, str]:
    email = f"load-{uuid.uuid4().hex[:10]}@example.com"
    req = urllib.request.Request(BASE + "/api/auth/signup", method="POST",
                                 data=json.dumps({"email": email, "password": "Passw0rd1x", "displayName": "l"}).encode())
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as r:  # nosemgrep: dynamic-urllib-use-detected -- URL is the base given on the command line
        return email, json.loads(r.read())["token"]


def question_id(token: str) -> str:
    req = urllib.request.Request(BASE + "/api/questions")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as r:  # nosemgrep: dynamic-urllib-use-detected -- URL is the base given on the command line
        return json.loads(r.read())[0]["id"]


def worker(kind: int, token: str, qid: str, stop: float) -> None:
    victim = f"victim-{uuid.uuid4().hex[:8]}@example.com"
    while time.time() < stop:
        if kind == 0:
            call("anonymous GET /api/questions", "GET", "/api/questions")
        elif kind == 1:
            call("authenticated GET /api/sessions", "GET", "/api/sessions", token=token)
        elif kind == 2:
            call("bad login (one account)", "POST", "/api/auth/login", {"email": victim, "password": "wrong-Pass1"})
        elif kind == 3:
            call("signup flood", "POST", "/api/auth/signup",
                 {"email": f"flood-{uuid.uuid4().hex[:10]}@example.com", "password": "Passw0rd1x", "displayName": "f"})
        else:
            call("create session", "POST", "/api/sessions", {"questionId": qid}, token=token)


def main() -> int:
    _, token = signup()
    qid = question_id(token)
    stop = time.time() + SECONDS
    threads = [threading.Thread(target=worker, args=(i % 5, token, qid, stop)) for i in range(WORKERS)]
    [t.start() for t in threads]
    [t.join() for t in threads]

    bad = False
    print(f"{WORKERS} workers for {SECONDS:.0f}s against {BASE}\n")
    print(f"{'scenario':34} {'requests':>8} {'p50 ms':>8} {'p95 ms':>8} {'max ms':>8}  statuses")
    for label, rows in sorted(results.items()):
        lat = sorted(ms for _, ms in rows)
        codes = Counter(s for s, _ in rows)
        p95 = lat[int(len(lat) * 0.95) - 1] if len(lat) > 1 else lat[0]
        print(f"{label:34} {len(rows):8} {statistics.median(lat):8.0f} {p95:8.0f} {lat[-1]:8.0f}  {dict(sorted(codes.items()))}")
        if any(c == 0 or c >= 500 for c in codes):
            bad = True
    print()
    for label in ("bad login (one account)", "signup flood", "create session"):
        codes = {s for s, _ in results.get(label, [])}
        print(f"{label}: throttled with 429 -> {'yes' if 429 in codes else 'NO (limit never tripped)'}")
    print("\nFAILED: server errors or connection failures under load" if bad else "\nOK: no 5xx and no connection failures")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
