#!/usr/bin/env python3
"""Collect deterministic Milestone 0 evidence and SHA-256 hashes."""
from __future__ import annotations
import hashlib
import json
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/reports/m0"
OUT.mkdir(parents=True, exist_ok=True)

patterns = [
    "app/build/outputs/apk/debug/*.apk",
    "app/build/outputs/apk/androidTest/debug/*.apk",
    "app/build/outputs/bundle/release/*.aab",
    "app/build/reports/**/*.html",
    "app/build/reports/**/*.xml",
    "app/build/reports/**/*.txt",
    "app/schemas/**/*.json",
]

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def command(*args: str) -> str:
    try:
        return subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=20, check=False).stdout.strip()
    except Exception as exc:
        return f"{type(exc).__name__}: {exc}"

files: dict[str, Path] = {}
for pattern in patterns:
    for path in ROOT.glob(pattern):
        if path.is_file():
            files[str(path.relative_to(ROOT))] = path

payload = {
    "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
    "gitCommit": command("git", "rev-parse", "HEAD"),
    "gitStatus": command("git", "status", "--short"),
    "java": command("java", "-version"),
    "python": platform.python_version(),
    "os": platform.platform(),
    "ci": os.environ.get("CI", "false"),
    "artifacts": [
        {"path": rel, "bytes": path.stat().st_size, "sha256": sha256(path)}
        for rel, path in sorted(files.items())
    ],
}
(OUT / "evidence.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
with (OUT / "SHA256SUMS").open("w", encoding="utf-8") as f:
    for item in payload["artifacts"]:
        f.write(f"{item['sha256']}  {item['path']}\n")
print(f"M0_EVIDENCE_ARTIFACTS={len(payload['artifacts'])}")
