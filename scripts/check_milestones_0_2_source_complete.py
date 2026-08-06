#!/usr/bin/env python3
"""Verify the immutable source-acceptance evidence for Milestones 0 through 2.

This checker does not rerun Kotlin compilers. It proves that the exact files
accepted by the independently executed gates have not changed since the
acceptance manifest was generated. Any M3 edit touching an accepted M0-M2 file
must rerun the affected gate and regenerate the evidence intentionally.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/MILESTONES_0_2_SOURCE_EVIDENCE.json"
REQUIRED_PASS_GATES = {
    "check_milestone1_foundation.py",
    "check_milestone1_reader_core.py",
    "check_p1_ui_static.py",
    "check_p1_features.py",
    "check_milestone2_comments.py",
    "check_comment_fixtures.py",
    "check_legacy_source_audit.py",
    "check_p2_sources.py",
    "check_source_platform_android_static.py",
    "check_source_platform_foundation.py",
    "check_milestone2_source_platform.py",
    "check_milestone2_complete.py",
    "check_vbook_static.py",
    "validate_release.py",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    payload = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    assert payload["schemaVersion"] == 1
    gates = {item["gate"]: item["result"] for item in payload["gates"]}
    missing = REQUIRED_PASS_GATES - gates.keys()
    assert not missing, f"Thiếu bằng chứng gate: {sorted(missing)}"
    failed = {gate: gates[gate] for gate in REQUIRED_PASS_GATES if gates[gate] != "PASS"}
    assert not failed, f"Gate chưa PASS: {failed}"
    tracked = payload["trackedFiles"]
    assert tracked, "Danh sách tệp nghiệm thu rỗng"
    for relative, expected in tracked.items():
        path = ROOT / relative
        assert path.is_file(), f"Thiếu tệp đã nghiệm thu: {relative}"
        actual = sha256(path)
        assert actual == expected, f"Tệp đã thay đổi sau nghiệm thu: {relative}"
    print(f"MILESTONES_0_2_SOURCE_EVIDENCE_OK files={len(tracked)} gates={len(REQUIRED_PASS_GATES)}")


if __name__ == "__main__":
    main()
