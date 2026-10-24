"""A1.4 / A1.8: run TWO backends and TWO agents against one scratch Postgres and check that they behave
like one system. Not part of the unit-test run (it starts real processes); run it before a release
and after touching anything that holds state in memory.

    python scripts/two_instance_check.py

Needs: the compose Postgres up (`docker compose up -d postgres`), `./mvnw package -DskipTests` done in
backend/, the agent venv set up (agent/.venv), and `pip install websockets` for this script.
Uses its own ports (backends 8091/8092, agents 8011/8012) and its own throwaway database, so it can run
next to the normal dev stack; everything is stopped and the database dropped at the end.

Topology (each backend talks to its OWN agent, so a session that moves between backends also moves
between agents - which only works if state lives in Postgres, not in either process):

    client -> backend A (8091) -> agent A (8011) --\
    client -> backend B (8092) -> agent B (8012) ---+-> one Postgres
"""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import websockets

ROOT = Path(__file__).resolve().parent.parent
DB_NAME = "ossature_topo_" + uuid.uuid4().hex[:10]
BACKEND_PORTS = {"A": 8091, "B": 8092}
AGENT_PORTS = {"A": 8011, "B": 8012}
SUMMARY_INTERVAL = "8s"

procs: dict[str, subprocess.Popen] = {}
failures: list[str] = []


# ------------------------------------------------------------------ helpers


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok or not detail else f"   ({detail})"))
    if not ok:
        failures.append(name)


def http(method: str, url: str, body=None, token: str | None = None, timeout: float = 30):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:  # nosemgrep: dynamic-urllib-use-detected -- localhost test URLs built in this script
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def psql(sql: str, db: str = DB_NAME) -> str:
    out = subprocess.run(
        ["docker", "exec", "ossature-postgres", "psql", "-U", "ossature", "-d", db, "-tA", "-c", sql],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def wait_for(url: str, what: str, seconds: int = 120) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:  # nosemgrep: dynamic-urllib-use-detected -- localhost test URLs built in this script
                if r.status == 200:
                    return
        except Exception:
            time.sleep(1)
    raise RuntimeError(f"{what} did not come up at {url}")


def start_backend(name: str) -> None:
    env = {
        **os.environ,
        "BACKEND_PORT": str(BACKEND_PORTS[name]),
        "AGENT_BASE_URL": f"http://127.0.0.1:{AGENT_PORTS[name]}",
        "POSTGRES_DB": DB_NAME,
        "OSSATURE_SESSION_SUMMARYINTERVAL": SUMMARY_INTERVAL,
    }
    log = open(ROOT / f".topology-backend-{name}.log", "w")
    procs[f"backend-{name}"] = subprocess.Popen(
        ["java", "-jar", str(next((ROOT / "backend" / "target").glob("backend-*.jar")))],
        env=env, stdout=log, stderr=subprocess.STDOUT,
    )
    wait_for(f"http://127.0.0.1:{BACKEND_PORTS[name]}/actuator/health", f"backend {name}")


def start_agent(name: str) -> None:
    env = {**os.environ, "POSTGRES_DB": DB_NAME, "DB_HOST": "127.0.0.1", "OPENAI_API_KEY": ""}
    log = open(ROOT / f".topology-agent-{name}.log", "w")
    procs[f"agent-{name}"] = subprocess.Popen(
        [str(ROOT / "agent" / ".venv" / "Scripts" / "python.exe"), "-m", "uvicorn", "app.main:app",
         "--port", str(AGENT_PORTS[name])],
        cwd=ROOT / "agent", env=env, stdout=log, stderr=subprocess.STDOUT,
    )
    wait_for(f"http://127.0.0.1:{AGENT_PORTS[name]}/health", f"agent {name}")


def stop(name: str) -> None:
    p = procs.pop(name, None)
    if p and p.poll() is None:
        subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"], capture_output=True)


def api(inst: str) -> str:
    return f"http://127.0.0.1:{BACKEND_PORTS[inst]}"


def agent(inst: str) -> str:
    return f"http://127.0.0.1:{AGENT_PORTS[inst]}"


def log_text(path: str) -> str:
    return (ROOT / path).read_text(errors="replace") if (ROOT / path).exists() else ""


# ------------------------------------------------------------------ scenarios


def scenario_agents_interleave() -> None:
    """A1.4: many /transcript calls on ONE session, alternating between two agent processes at once."""
    print("\n-- A1.4: two agents, one session, interleaved calls")
    sid = "topo-" + uuid.uuid4().hex[:8]
    st, _ = http("POST", f"{agent('A')}/agent/sessions/{sid}/start",
                 {"sessionId": sid, "prompt": "Design a URL shortener", "hasVettedReference": True,
                  "referenceSolution": {"summary": "ref"}})
    check("session started on agent A", st == 200, str(st))

    errors: list[str] = []
    texts = [f"turn-{i:02d}" for i in range(24)]

    def send(i: int) -> None:
        inst = "A" if i % 2 == 0 else "B"
        status, body = http("POST", f"{agent(inst)}/agent/sessions/{sid}/transcript", {"sessionId": sid, "text": texts[i]})
        if status != 200:
            errors.append(f"{texts[i]} -> {status} {body}")

    threads = [threading.Thread(target=send, args=(i,)) for i in range(len(texts))]
    [t.start() for t in threads]
    [t.join() for t in threads]
    check("every concurrent call succeeded", not errors, "; ".join(errors[:3]))

    state = json.loads(psql(f"SELECT state FROM agent_sessions WHERE session_id='{sid}'"))
    candidate = [t["text"] for t in state["transcript"] if t["sender"] == "candidate"]
    check("all 24 candidate turns are stored, none lost", sorted(candidate) == sorted(texts),
          f"{len(candidate)} stored")
    check("none duplicated", len(candidate) == len(set(candidate)))
    version = int(psql(f"SELECT version FROM agent_sessions WHERE session_id='{sid}'"))
    check("version advanced once per write (>= 48 writes)", version >= 48, f"version {version}")

    # A fresh read on either agent shows the same, complete state.
    for inst in "AB":
        status, _ = http("POST", f"{agent(inst)}/agent/sessions/{sid}/transcript", {"sessionId": sid, "text": "after"})
        check(f"agent {inst} still serves the session", status == 200)
    state = json.loads(psql(f"SELECT state FROM agent_sessions WHERE session_id='{sid}'"))
    check("both agents' later turns were added on top", sum(1 for t in state["transcript"] if t["text"] == "after") == 2)


async def ws_exchange(inst: str, session_id: str, ticket: str, last_seq: int, send: str | None) -> list[dict]:
    url = f"ws://127.0.0.1:{BACKEND_PORTS[inst]}/ws/interview?sessionId={session_id}&lastSeq={last_seq}&ticket={ticket}"
    frames: list[dict] = []
    async with websockets.connect(url, origin="http://localhost:5173") as ws:
        if send is not None:
            await ws.send(json.dumps({"type": "transcript.final", "sessionId": session_id, "text": send, "ts": 1}))
        try:
            while True:
                frames.append(json.loads(await asyncio.wait_for(ws.recv(), 3)))
        except Exception:
            pass
    return frames


def texts_in(frames: list[dict]) -> list[str]:
    out = []
    for f in frames:
        p = f.get("payload") or {}
        out.append(f.get("text") or p.get("text") or "")
    return out


def scenario_session_moves_between_instances() -> tuple[str, str]:
    print("\n-- A1: one interview, served by both backends and both agents")
    email = f"topo-{uuid.uuid4().hex[:8]}@example.com"
    st, body = http("POST", f"{api('A')}/api/auth/signup", {"email": email, "password": "Passw0rd1x", "displayName": "t"})
    check("signup on backend A", st in (200, 201), str(st))
    token_a = body["token"]
    st, body = http("POST", f"{api('B')}/api/auth/login", {"email": email, "password": "Passw0rd1x"})
    check("the account is visible on backend B (login)", st == 200, str(st))
    token_b = body["token"]
    st, qs = http("GET", f"{api('A')}/api/questions", token=token_a)
    st, sess = http("POST", f"{api('A')}/api/sessions", {"questionId": qs[0]["id"]}, token_a)
    check("session created on backend A", st == 201, str(st))
    sid = sess["id"]
    st, mine = http("GET", f"{api('B')}/api/sessions", token=token_b)
    check("backend B lists the session", st == 200 and sid in [s["id"] for s in mine])

    st, t1 = http("POST", f"{api('A')}/api/sessions/{sid}/ws-ticket", token=token_a)
    frames_a = asyncio.run(ws_exchange("A", sid, t1["ticket"], 0, "hello from instance A"))
    check("instance A: the interviewer answered over the socket",
          any(f.get("type") == "chat.message" and (f.get("sender") == "ai") for f in frames_a), str(texts_in(frames_a)))

    # Reconnect lands on the OTHER backend (and so the other agent): history replays, work continues.
    st, t2 = http("POST", f"{api('B')}/api/sessions/{sid}/ws-ticket", token=token_b)
    frames_b = asyncio.run(ws_exchange("B", sid, t2["ticket"], 0, "hello from instance B"))
    seen = texts_in(frames_b)
    check("instance B replays what happened on A", "hello from instance A" in seen, str(seen))
    check("instance B's interviewer answered too",
          sum(1 for f in frames_b if f.get("sender") == "ai") >= 1, str(texts_in(frames_b)))

    state = json.loads(psql(f"SELECT state FROM agent_sessions WHERE session_id='{sid}'"))
    candidate = [t["text"] for t in state["transcript"] if t["sender"] == "candidate"]
    check("agent B continued from agent A's state (both turns, in order)",
          candidate == ["hello from instance A", "hello from instance B"], str(candidate))

    # A ticket from one instance is single-use across both.
    st, t3 = http("POST", f"{api('A')}/api/sessions/{sid}/ws-ticket", token=token_a)
    ok_first = True
    try:
        asyncio.run(ws_exchange("B", sid, t3["ticket"], 999999, None))
    except Exception:
        ok_first = False
    check("a ticket issued by A is accepted by B", ok_first)
    reused = True
    try:
        frames = asyncio.run(ws_exchange("A", sid, t3["ticket"], 999999, None))
        reused = bool(frames)  # a refused ticket closes the socket without frames
    except Exception:
        reused = False
    check("the same ticket is refused on A afterwards", not reused)
    return sid, email


def scenario_shared_rate_limit() -> None:
    print("\n-- A1.5: one rate-limit budget across both backends")
    email = f"victim-{uuid.uuid4().hex[:8]}@example.com"
    codes = []
    for inst in ["A", "A", "A", "B", "B", "A", "B"]:
        st, _ = http("POST", f"{api(inst)}/api/auth/login", {"email": email, "password": "wrong-Pass1"})
        codes.append(st)
    # 5 attempts per (account, address) per window: attempts 1-5 are answered, 6 and 7 are throttled.
    check("first five attempts (split across A and B) get an answer, the rest are throttled",
          codes[:5] == [401] * 5 and codes[5:] == [429, 429], str(codes))


def scenario_summary_sweep_runs_once(session_id: str) -> None:
    print("\n-- A1.6: the summary sweep runs once, not once per backend")
    deadline = time.time() + 75
    n = 0
    while time.time() < deadline:
        n = int(psql(f"SELECT count(*) FROM interim_summaries WHERE session_id='{session_id}'"))
        if n >= 3:
            break
        time.sleep(3)
    ordinals = [int(x) for x in psql(
        f"SELECT ordinal FROM interim_summaries WHERE session_id='{session_id}' ORDER BY ordinal").split()]
    check("summaries were produced", len(ordinals) >= 2, f"ordinals {ordinals}")
    check("ordinals are 1..n with no gap or repeat", ordinals == list(range(1, len(ordinals) + 1)), str(ordinals))
    holders = int(psql("SELECT count(*) FROM scheduler_locks WHERE name='summary-sweep'"))
    check("one lease row for the sweep", holders == 1)
    both = log_text(".topology-backend-A.log") + log_text(".topology-backend-B.log")
    check("no failed ticks or duplicate-key errors in either backend", "Summary tick failed" not in both and "duplicate key" not in both.lower())


def scenario_instance_loss(session_id: str, email: str) -> None:
    print("\n-- A1: kill instance A (backend + agent), the interview carries on")
    stop("backend-A")
    stop("agent-A")
    st, body = http("POST", f"{api('B')}/api/auth/login", {"email": email, "password": "Passw0rd1x"})
    check("login still works on B", st == 200, str(st))
    token = body["token"]
    st, tk = http("POST", f"{api('B')}/api/sessions/{session_id}/ws-ticket", token=token)
    frames = asyncio.run(ws_exchange("B", session_id, tk["ticket"], 0, "after A died"))
    check("the session continues on B", any(f.get("sender") == "ai" for f in frames) and "hello from instance A" in texts_in(frames))


# ------------------------------------------------------------------ main


def main() -> int:
    try:
        psql("SELECT 1", db="postgres")
    except Exception as e:
        print("Postgres is not reachable (docker compose up -d postgres):", e)
        return 2
    psql(f"CREATE DATABASE {DB_NAME}", db="postgres")
    try:
        start_backend("A")  # migrates the empty database
        start_backend("B")
        start_agent("A")
        start_agent("B")
        scenario_agents_interleave()
        session_id, email = scenario_session_moves_between_instances()
        scenario_shared_rate_limit()
        scenario_summary_sweep_runs_once(session_id)
        scenario_instance_loss(session_id, email)
    finally:
        for name in list(procs):
            stop(name)
        time.sleep(2)
        try:
            psql(f"DROP DATABASE IF EXISTS {DB_NAME} WITH (FORCE)", db="postgres")
        except Exception as e:
            print("could not drop", DB_NAME, e)
    print("\nFAILED: " + ", ".join(failures) if failures else "\nALL PASSED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
