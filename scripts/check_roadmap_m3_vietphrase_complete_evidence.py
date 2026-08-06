#!/usr/bin/env python3
"""Verify immutable source-side acceptance evidence for roadmap Milestone 3."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/ROADMAP_M3_VIETPHRASE_COMPLETE_EVIDENCE.json"
REQUIRED_GATES = {
    "check_roadmap_milestone3_vietphrase_complete.py",
    "check_roadmap_m3_persistence_static.py",
    "check_p4_transfer_static.py",
    "check_p2_ui_static.py",
    "check_p4_features.py",
    "check_roadmap_milestone3_source_complete.py",
    "check_milestone3_foundation.py",
    "check_milestone3_ui_static.py",
    "check_milestone4_complete.py",
    "check_milestones_0_2_source_complete.py",
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
    assert payload["sourceStatus"] == "COMPLETE"
    gates = {entry["gate"]: entry["result"] for entry in payload["gates"]}
    missing = REQUIRED_GATES - gates.keys()
    assert not missing, f"Thiếu gate M3: {sorted(missing)}"
    failed = {gate: gates[gate] for gate in REQUIRED_GATES if gates[gate] != "PASS"}
    assert not failed, f"Gate M3 chưa PASS: {failed}"
    tracked = payload["trackedFiles"]
    assert tracked, "Danh sách tệp M3 nghiệm thu rỗng"
    for relative, expected in tracked.items():
        path = ROOT / relative
        assert path.is_file(), f"Thiếu tệp M3 đã nghiệm thu: {relative}"
        actual = sha256(path)
        assert actual == expected, f"Tệp M3 đã thay đổi sau nghiệm thu: {relative}"
    deferred = payload.get("deferredChecks", [])
    assert deferred, "Phải ghi rõ các kiểm tra Android đã hoãn"
    print(
        f"ROADMAP_M3_VIETPHRASE_COMPLETE_EVIDENCE_OK "
        f"files={len(tracked)} gates={len(REQUIRED_GATES)}"
    )


if __name__ == "__main__":
    main()
