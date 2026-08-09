#!/usr/bin/env python3
"""Capture and merge all deterministic vBook reference plans into one immutable snapshot."""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_PLANS = [
    ROOT / "scripts" / "vbook-reference-plan.json",
    ROOT / "scripts" / "vbook-reference-plan-providers.json",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--plan", action="append", type=pathlib.Path, default=[])
    args = parser.parse_args()

    plans = args.plan or DEFAULT_PLANS
    merged_cases: list[dict] = []
    ids: set[str] = set()
    try:
        with tempfile.TemporaryDirectory(prefix="vbook-reference-") as temp_dir:
            temp = pathlib.Path(temp_dir)
            for index, plan in enumerate(plans):
                output = temp / f"capture-{index}.json"
                completed = subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / "scripts" / "capture_vbook_reference.py"),
                        "--plan", str(plan),
                        "--server", args.server,
                        "--out", str(output),
                    ],
                    cwd=ROOT,
                )
                if completed.returncode != 0:
                    raise RuntimeError(f"REFERENCE_PLAN_FAILED:{plan}:{completed.returncode}")
                capture = json.loads(output.read_text(encoding="utf-8"))
                if capture.get("schema") != 1 or not isinstance(capture.get("cases"), list):
                    raise RuntimeError(f"REFERENCE_CAPTURE_SCHEMA_INVALID:{plan}")
                for case in capture["cases"]:
                    case_id = str(case.get("id") or "")
                    if not case_id or case_id in ids:
                        raise RuntimeError(f"REFERENCE_CASE_ID_DUPLICATE:{case_id}")
                    ids.add(case_id)
                    merged_cases.append(case)

        payload = {
            "schema": 1,
            "reference": "vbook-local-rest",
            "server": args.server,
            "plans": [str(plan.relative_to(ROOT) if plan.is_relative_to(ROOT) else plan) for plan in plans],
            "cases": merged_cases,
        }
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"caseCount": len(merged_cases), "output": str(args.out)}, ensure_ascii=False))
        return 0
    except Exception as exc:
        print(f"VBOOK_REFERENCE_MATRIX_FAILED:{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
