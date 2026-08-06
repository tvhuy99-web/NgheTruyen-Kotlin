#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BANNED_EXTENSIONS = {".lua", ".dex", ".so", ".xpk", ".alp"}
BANNED_RUNTIME_TOKENS = ("com.androlua", "LuaActivity", "LuaService")
ALLOWED_LUA_RESOURCES = {
    Path("source-lua/src/main/resources/vn/nghetruyen/source/lua/native_api.lua"),
    Path("source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua"),
}

errors: list[str] = []
for path in ROOT.rglob("*"):
    if not path.is_file() or ".gradle" in path.parts or "build" in path.parts:
        continue
    if path.suffix.lower() in BANNED_EXTENSIONS:
        relative = path.relative_to(ROOT)
        if not (path.suffix.lower() == ".lua" and relative in ALLOWED_LUA_RESOURCES):
            errors.append(f"Banned artifact: {relative}")
    if path.suffix.lower() in {".kt", ".kts", ".java", ".xml"}:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for token in BANNED_RUNTIME_TOKENS:
            if token in text:
                errors.append(f"Runtime token {token!r}: {path.relative_to(ROOT)}")
    if path.suffix.lower() == ".xml":
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append(f"Invalid XML {path.relative_to(ROOT)}: {exc}")

if errors:
    print("CLEAN_REWRITE_CHECK_FAILED")
    print("\n".join(errors))
    sys.exit(1)
print("CLEAN_REWRITE_CHECK_OK")
