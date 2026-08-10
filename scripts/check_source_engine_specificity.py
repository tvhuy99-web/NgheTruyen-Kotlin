#!/usr/bin/env python3
"""Fail when a compatibility engine contains a concrete website URL.

Compatibility fixes must be driven by an ecosystem ABI or a reproducible corpus case.
Concrete provider hosts belong in packages/catalog data, never in the vBook engine.
"""
from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENGINE_ROOTS = (
    ROOT / "source-vbook/src/main",
    ROOT / "source-js-sandbox/src/main",
)
URL = re.compile(r"https?://([A-Za-z0-9.-]+)", re.IGNORECASE)
ALLOWED_SUFFIXES = (".invalid", ".example")


def main() -> None:
    violations: list[str] = []
    for engine_root in ENGINE_ROOTS:
        for path in sorted(engine_root.rglob("*.kt")):
            text = path.read_text(encoding="utf-8", errors="replace")
            for line_number, line in enumerate(text.splitlines(), start=1):
                for match in URL.finditer(line):
                    host = match.group(1).lower().rstrip(".")
                    if host.endswith(ALLOWED_SUFFIXES):
                        continue
                    relative = path.relative_to(ROOT)
                    violations.append(f"{relative}:{line_number}: {host}")

    if violations:
        details = "\n".join(f"  - {item}" for item in violations)
        raise SystemExit(
            "SOURCE_ENGINE_SPECIFICITY_FAILED\n"
            "Concrete provider URLs are forbidden in compatibility-engine production code:\n"
            f"{details}"
        )

    print("SOURCE_ENGINE_SPECIFICITY_OK")


if __name__ == "__main__":
    main()
