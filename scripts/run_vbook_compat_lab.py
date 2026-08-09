#!/usr/bin/env python3
"""Run the complete vBook S2-S4 compatibility lab in one command.

Designed for the final consolidated validation pass, not for gating every edit. It keeps corpus
completeness, implementation coverage and reference certification as separate failure classes.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = ROOT / "build" / "vbook-compat-lab"
CORPUS = BUILD / "corpus"
AUDIT = BUILD / "corpus-audit.json"
REFERENCE = BUILD / "reference-capture.json"


def run(cmd: list[str], *, env: dict[str, str] | None = None, allow: set[int] = {0}) -> int:
    print("+", " ".join(cmd), flush=True)
    completed = subprocess.run(cmd, cwd=ROOT, env=env)
    if completed.returncode not in allow:
        raise RuntimeError(f"COMMAND_FAILED:{completed.returncode}:{' '.join(cmd)}")
    return completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-server", help="vBook local REST server, e.g. http://127.0.0.1:8080")
    parser.add_argument("--skip-fetch", action="store_true", help="Reuse an existing strict corpus")
    parser.add_argument("--resume-fetch", action="store_true", help="Reuse validated downloads while completing an interrupted corpus fetch")
    parser.add_argument("--skip-tests", action="store_true", help="Skip Gradle tests for diagnostics only")
    parser.add_argument("--allow-upstream-errors", action="store_true", help="Record incomplete corpus without treating acquisition as fatal")
    parser.add_argument("--allow-uncovered", action="append", default=[], help="Temporary reference coverage waiver")
    args = parser.parse_args()

    BUILD.mkdir(parents=True, exist_ok=True)
    try:
        if not args.skip_fetch:
            cmd = [sys.executable, "scripts/fetch_vbook_corpus.py", "--out", str(CORPUS)]
            if args.resume_fetch:
                cmd.append("--resume")
            if args.allow_upstream_errors:
                cmd.append("--allow-errors")
            run(cmd)

        corpus_index = CORPUS / "corpus-index.json"
        if not corpus_index.is_file():
            raise RuntimeError(f"CORPUS_INDEX_MISSING:{corpus_index}")
        index = json.loads(corpus_index.read_text(encoding="utf-8"))
        complete = bool(index.get("summary", {}).get("complete"))
        if not complete and not args.allow_upstream_errors:
            raise RuntimeError("VBOOK_CORPUS_INCOMPLETE")

        run([
            "bash", "./gradlew",
            ":source-vbook:auditCorpus",
            f"-PvbookCorpusDir={CORPUS / 'packages'}",
            f"-PvbookAuditOut={AUDIT}",
            "--no-daemon",
        ])
        if not AUDIT.is_file():
            raise RuntimeError(f"VBOOK_AUDIT_REPORT_MISSING:{AUDIT}")
        audit = json.loads(AUDIT.read_text(encoding="utf-8"))
        if audit.get("schema") != 2:
            raise RuntimeError("VBOOK_AUDIT_SCHEMA_2_REQUIRED")

        if args.reference_server:
            env = os.environ.copy()
            env["VBOOK_REFERENCE_SERVER"] = args.reference_server
            run([
                sys.executable,
                "scripts/capture_vbook_reference_matrix.py",
                "--server", args.reference_server,
                "--out", str(REFERENCE),
            ], env=env)

        coverage_state = "NOT_RUN"
        if REFERENCE.is_file():
            coverage = [
                sys.executable,
                "scripts/check_vbook_differential_coverage.py",
                str(AUDIT),
                str(REFERENCE),
            ]
            for feature in args.allow_uncovered:
                coverage.extend(["--allow-uncovered", feature])
            run(coverage)
            coverage_state = "PASS"
        elif args.reference_server:
            raise RuntimeError(f"VBOOK_REFERENCE_CAPTURE_MISSING:{REFERENCE}")

        if not args.skip_tests:
            run([
                "bash", "./gradlew",
                ":source-js-sandbox:test",
                ":source-store:test",
                ":source-repository:test",
                ":source-vbook:test",
                ":app:testDebugUnitTest",
                "--no-daemon",
            ])

        print(json.dumps({
            "corpusComplete": complete,
            "extensionCount": audit.get("extensionCount"),
            "blockingFeatures": audit.get("blockingFeatures", []),
            "auditPresent": True,
            "referencePresent": REFERENCE.is_file(),
            "referenceCoverage": coverage_state,
            "status": "LAB_EXECUTED",
        }, ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f"VBOOK_COMPAT_LAB_FAILED:{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
