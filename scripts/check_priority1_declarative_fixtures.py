#!/usr/bin/env python3
"""Replay all nine declarative fixtures for six built-in website packs."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("m2_gate", ROOT / "scripts/check_milestone2_complete.py")
assert SPEC and SPEC.loader
M2 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M2)
PACKS = ("truyenfull", "truyencv", "truyencom", "truyenyy", "wikidich", "sangtacviet")
ACTION_KEYS = {
    "HOME": "home", "GENRE": "genre", "SEARCH": "search", "SUGGESTIONS": "suggestions",
    "DETAIL": "detail", "LATEST_CHAPTER": "latest_chapter", "TOC": "toc",
    "TOC_PAGES": "tocPages", "CHAPTER": "chapter",
}

def main() -> None:
    count = 0
    for name in PACKS:
        source_dir = ROOT / "examples/sourcepacks" / name
        manifest = json.loads((source_dir / "source.json").read_text(encoding="utf-8"))
        seen = set()
        for fixture in manifest["fixtures"]:
            action = fixture["action"]
            key = ACTION_KEYS[action]
            actual = M2.evaluate_fixture(
                source_dir,
                key,
                input_path=fixture["input"],
                fixture_path=fixture["fixture"],
                program_path=manifest["actions"][key]["entry"],
            )
            expected = json.loads((source_dir / fixture["expected"]).read_text(encoding="utf-8"))
            assert actual == expected, (
                f"fixture mismatch {name}/{action}\n"
                f"actual={json.dumps(actual, ensure_ascii=False, sort_keys=True)}\n"
                f"expected={json.dumps(expected, ensure_ascii=False, sort_keys=True)}"
            )
            seen.add(action)
            count += 1
        assert seen == set(ACTION_KEYS), f"{name}: thiếu action fixture {set(ACTION_KEYS)-seen}"
        print(f"PRIORITY1_DECLARATIVE_PACK_OK {name} cases={len(seen)}")
    assert count == 54
    print(f"PRIORITY1_DECLARATIVE_FIXTURES_OK cases={count}")

if __name__ == "__main__":
    main()
