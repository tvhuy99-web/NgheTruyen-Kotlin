#!/usr/bin/env python3
"""Compatibility entry point for the completed Milestone 3 VietPhrase gate.

The original slice-1 checker is intentionally superseded. Keeping this file as
an explicit delegate prevents CI, documentation, or local scripts from running
the obsolete entity stub and reporting a false failure after schema 14.
"""
from __future__ import annotations
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "scripts/check_roadmap_milestone3_vietphrase_complete.py"
print("M3_CORE_GATE_SUPERSEDED_BY_COMPLETE_GATE")
runpy.run_path(str(TARGET), run_name="__main__")
