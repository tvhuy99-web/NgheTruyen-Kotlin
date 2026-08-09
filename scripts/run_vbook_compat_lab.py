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
    parser.add_argument("--skip-tests", action="store_true", help="Skip Gradle tests for diagnostics only")
    parser.add_argument("--allow-upstream-errors", action="store_true", help="Record incomplete corpus without treating acquisition as fatal")
    parser.add_argument("--allow-uncovered", action="append", default=[], help="Temporary reference coverage waiver")
    args = parser.parse_args()

    BUILD.mkdir(parents=True, exist_ok=True)
    try:
        if not args.skip_fetch:
            cmd = [sys.executable, "scripts/fetch_vbook_corpus.py", "--out", str(CORPUS)]
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

        # The Kotlin analyzer is the source of truth for detector/feature semantics.
        run([
            "bash", "./gradlew",
            ":source-vbook:classes",
            "--no-daemon",
        ])
        classpath_file = BUILD / "runtime-classpath.txt"
        # Gradle task is added only if/when a dedicated application plugin is introduced. Until
        # then the normal source-vbook tests execute the same analyzer and fixtures. Keep the
        # explicit report requirement so a lab cannot claim proof from acquisition alone.
        if not AUDIT.is_file():
            print(
                "VBOOK_AUDIT_REPORT_REQUIRED: generate corpus-audit.json with "
                "VBookCorpusAuditMain from source-vbook runtime classpath before certification",
                file=sys.stderr,
            )

        if args.reference_server:
            env = os.environ.copy()
            env["VBOOK_REFERENCE_SERVER"] = args.reference_server
            run([
                sys.executable,
                "scripts/capture_vbook_reference.py",
                "--plan", "scripts/vbook-reference-plan.json",
                "--server", args.reference_server,
                "--out", str(REFERENCE),
            ], env=env)

        if AUDIT.is_file() and REFERENCE.is_file():
            coverage = [
                sys.executable,
                "scripts/check_vbook_differential_coverage.py",
                str(AUDIT),
                str(REFERENCE),
            ]
            for feature in args.allow_uncovered:
                coverage.extend(["--allow-uncovered", feature])
            # 2 = uncovered implementation proof, 3 = implementation blocker, 4 = reference errors.
            run(coverage)

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
            "auditPresent": AUDIT.is_file(),
            "referencePresent": REFERENCE.is_file(),
            "status": "LAB_EXECUTED",
        }, indent=2))
        return 0
    except Exception as exc:
        print(f"VBOOK_COMPAT_LAB_FAILED:{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
