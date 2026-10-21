"""H20: write a CycloneDX software bill of materials for each part of the project into sbom/.

    python scripts/generate_sbom.py

Needs `pip install cyclonedx-bom` for the agent part; the Maven plugin and `npm sbom` are fetched/built in.
The sbom/ folder is not committed - CI uploads it as a build artifact per release.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "sbom"
WIN = sys.platform == "win32"


def run(cmd: list[str], cwd: Path, stdout=None) -> None:
    subprocess.run(cmd, cwd=cwd, check=True, stdout=stdout)


def main() -> None:
    OUT.mkdir(exist_ok=True)

    mvnw = str(ROOT / "backend" / ("mvnw.cmd" if WIN else "mvnw"))
    run([mvnw, "-q", "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom",
         "-DoutputFormat=json", "-DoutputName=backend.cdx"], ROOT / "backend")
    (ROOT / "backend" / "target" / "backend.cdx.json").replace(OUT / "backend.cdx.json")

    with open(OUT / "frontend.cdx.json", "w") as f:
        run(["npm", "sbom", "--sbom-format", "cyclonedx", "--omit", "dev"], ROOT / "frontend", stdout=f)

    venv_py = ROOT / "agent" / ".venv" / ("Scripts/python.exe" if WIN else "bin/python")
    freeze = subprocess.run([str(venv_py), "-m", "pip", "list", "--format=freeze"], capture_output=True, text=True, check=True).stdout
    freeze_file = OUT / "agent-requirements.txt"
    freeze_file.write_text(freeze)
    run([sys.executable, "-m", "cyclonedx_py", "requirements", str(freeze_file), "--of", "JSON",
         "-o", str(OUT / "agent.cdx.json")], ROOT)
    freeze_file.unlink()
    print("wrote", ", ".join(sorted(p.name for p in OUT.glob("*.cdx.json"))), "to", OUT)


if __name__ == "__main__":
    main()
