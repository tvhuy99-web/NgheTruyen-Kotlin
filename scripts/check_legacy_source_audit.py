#!/usr/bin/env python3
"""Gate the checked-in XPK compatibility inventory and safe audit tool."""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {"sangtacviet", "truyencom", "truyencv", "truyenfull", "truyenyy", "wattpad", "wikidich"}


def main() -> None:
    tool = (ROOT / "scripts/xpk/audit_legacy_sources.py").read_text(encoding="utf-8")
    for token in ("zipfile.is_zipfile", "never loads Lua", "MANUAL_PORT_REQUIRED", "PORTED_SOURCE_PRESENT"):
        assert token.lower() in tool.lower(), token
    assert "exec(" not in tool and "eval(" not in tool and "subprocess" not in tool
    report = json.loads((ROOT / "docs/XPK_V34_SOURCE_COMPATIBILITY.json").read_text(encoding="utf-8"))
    assert report["schemaVersion"] == 1 and report["sourceCount"] == 7
    keys = {item["source_key"] for item in report["items"]}
    assert keys == EXPECTED, keys
    for item in report["items"]:
        assert item["status"] == "PORTED_SOURCE_PRESENT"
        assert item["existing_port"]
        assert (ROOT / item["existing_port"] / "source.json").is_file()
    print("LEGACY_SOURCE_AUDIT_OK")


if __name__ == "__main__":
    main()
