#!/usr/bin/env python3
"""Compatibility verifier for the historical first M3 VietPhrase slice.

Slice-1 hashes became obsolete when persistence, migration, UI and lossless
archives were added. The authoritative acceptance record is now the complete
M3 evidence manifest. This delegate avoids treating deliberate completion work
as a regression while preserving the old command name for callers.
"""
from __future__ import annotations
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "scripts/check_roadmap_m3_vietphrase_complete_evidence.py"
if not TARGET.is_file():
    raise SystemExit("M3_COMPLETE_EVIDENCE_NOT_CREATED")
print("M3_SLICE1_EVIDENCE_SUPERSEDED_BY_COMPLETE_EVIDENCE")
runpy.run_path(str(TARGET), run_name="__main__")
