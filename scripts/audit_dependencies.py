"""H20: check every dependency (Maven, npm, PyPI) against the public OSV vulnerability database.

    python scripts/audit_dependencies.py              # fail on HIGH / CRITICAL (or unrated) findings
    python scripts/audit_dependencies.py --all        # also list lower severities
    python scripts/audit_dependencies.py --offline-list   # just print what would be checked

No installs needed (standard library only). It looks at what is actually resolved: Maven's runtime
dependency list, the npm lockfile (runtime and dev), and the agent venv's installed packages. Only package
names and versions are sent to api.osv.dev - nothing from this project's code or data.

Accepted risks go in scripts/audit-allowlist.json as {"id": "GHSA-...", "reason": "...", "until": "YYYY-MM-DD"};
an entry stops counting once its date passes, so an exception cannot be forgotten.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OSV = "https://api.osv.dev/v1"
FAIL_AT = {"HIGH", "CRITICAL", "UNRATED"}
ORDER = ["CRITICAL", "HIGH", "MODERATE", "LOW", "UNRATED"]


def maven_packages() -> list[tuple[str, str, str, str]]:
    out = ROOT / "backend" / "target" / "audit-deps.txt"
    mvnw = "mvnw.cmd" if sys.platform == "win32" else "./mvnw"
    subprocess.run(
        [str(ROOT / "backend" / mvnw), "-q", "dependency:list", f"-DoutputFile={out}", "-DappendOutput=false"],
        cwd=ROOT / "backend", check=True, capture_output=True,
    )
    pkgs = []
    for line in out.read_text().splitlines():
        parts = line.strip().split(":")
        if len(parts) >= 5 and parts[0] and not line.startswith("The following"):
            group, artifact, _type, version, scope = parts[0], parts[1], parts[2], parts[-2], parts[-1].split(" ")[0]
            pkgs.append(("Maven", f"{group}:{artifact}", version, scope))
    return pkgs


def npm_packages() -> list[tuple[str, str, str, str]]:
    lock = json.loads((ROOT / "frontend" / "package-lock.json").read_text())
    pkgs = []
    for path, meta in lock.get("packages", {}).items():
        if not path or "version" not in meta:
            continue
        name = path.split("node_modules/")[-1]
        pkgs.append(("npm", name, meta["version"], "dev" if meta.get("dev") else "runtime"))
    return pkgs


def pypi_packages() -> list[tuple[str, str, str, str]]:
    py = ROOT / "agent" / ".venv" / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
    out = subprocess.run([str(py), "-m", "pip", "list", "--format=json"], capture_output=True, text=True, check=True).stdout
    return [("PyPI", p["name"], p["version"], "runtime") for p in json.loads(out)]


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(OSV + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:  # nosemgrep: dynamic-urllib-use-detected -- fixed https://api.osv.dev base
        return json.loads(r.read())


def get(path: str) -> dict:
    with urllib.request.urlopen(OSV + path, timeout=60) as r:  # nosemgrep: dynamic-urllib-use-detected -- fixed https://api.osv.dev base
        return json.loads(r.read())


def severity(vuln: dict) -> str:
    """GitHub advisories carry a plain rating. PYSEC/CVE records often have none, so use the rating of a
    GHSA record that is an alias of the same issue; otherwise it stays UNRATED (and fails, to be triaged)."""
    rating = (vuln.get("database_specific") or {}).get("severity")
    if isinstance(rating, str) and rating.upper() in ORDER:
        return rating.upper()
    for alias in vuln.get("aliases", []):
        if alias.startswith("GHSA-"):
            try:
                r = (get(f"/vulns/{alias}").get("database_specific") or {}).get("severity")
            except Exception:
                continue
            if isinstance(r, str) and r.upper() in ORDER:
                return r.upper()
    return "UNRATED"


def allowlisted() -> dict[str, dict]:
    path = ROOT / "scripts" / "audit-allowlist.json"
    if not path.exists():
        return {}
    today = dt.date.today().isoformat()
    return {e["id"]: e for e in json.loads(path.read_text()) if e.get("until", "9999-12-31") >= today}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="also list findings below the failure threshold")
    ap.add_argument("--offline-list", action="store_true")
    ap.add_argument("--by-package", action="store_true", help="summarise findings per package")
    args = ap.parse_args()

    packages = maven_packages() + npm_packages() + pypi_packages()
    print(f"{len(packages)} packages: " + ", ".join(
        f"{eco} {sum(1 for p in packages if p[0] == eco)}" for eco in ("Maven", "npm", "PyPI")))
    if args.offline_list:
        for p in packages:
            print(*p)
        return 0

    # Batch lookups (OSV caps a batch at 1000 queries).
    hits: dict[tuple, list[str]] = {}
    for i in range(0, len(packages), 500):
        chunk = packages[i : i + 500]
        result = post("/querybatch", {"queries": [
            {"package": {"name": name, "ecosystem": eco}, "version": version} for eco, name, version, _scope in chunk]})
        for pkg, res in zip(chunk, result["results"]):
            ids = [v["id"] for v in res.get("vulns", [])]
            if ids:
                hits[pkg] = ids

    details: dict[str, dict] = {}
    for ids in hits.values():
        for vid in ids:
            if vid not in details:
                details[vid] = get(f"/vulns/{vid}")

    allow = allowlisted()
    rows = []
    for (eco, name, version, scope), ids in hits.items():
        for vid in ids:
            v = details[vid]
            rows.append((ORDER.index(severity(v)), eco, name, version, scope, vid, (v.get("summary") or "")[:90]))
    if args.by_package:
        from collections import Counter
        counts = Counter((r[1], r[2], r[3], ORDER[r[0]]) for r in rows)
        for (eco, name, version, sev), n in sorted(counts.items()):
            print(f"{eco:5} {name}@{version}  {sev}  x{n}")
        return 0
    rows.sort()

    failing = []
    for sev_i, eco, name, version, scope, vid, summary in rows:
        sev = ORDER[sev_i]
        accepted = vid in allow or any(a in allow for a in details[vid].get("aliases", []))
        failing_row = sev in FAIL_AT and not accepted
        if failing_row:
            failing.append(vid)
        if failing_row or args.all:
            tag = "ACCEPTED " if accepted else ""
            print(f"{tag}{sev:9} {eco:5} {name}@{version} ({scope})  {vid}  {summary}")

    print()
    lower = len(rows) - len(failing)
    if failing:
        print(f"FAILED: {len(failing)} finding(s) at HIGH/CRITICAL/unrated that are not accepted "
              f"({lower} lower or accepted; use --all to list them).")
        return 1
    print(f"OK: no unaccepted HIGH/CRITICAL findings ({len(rows)} lower or accepted; use --all to list them).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
