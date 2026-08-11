#!/usr/bin/env python3
from pathlib import Path

script = Path(__file__).with_name("apply_lua_diagnostics_parity.py")
source = script.read_text(encoding="utf-8")
old = '''    if new in source:\n        return\n    if old not in source:\n        raise SystemExit(f"LUA_DIAGNOSTICS_PATCH missing anchor in {path}: {old[:160]!r}")\n    target.write_text(source.replace(old, new, 1), encoding="utf-8")\n'''
new = '''    if old not in source:\n        if not new or new in source:\n            return\n        raise SystemExit(f"LUA_DIAGNOSTICS_PATCH missing anchor in {path}: {old[:160]!r}")\n    target.write_text(source.replace(old, new, 1), encoding="utf-8")\n'''
if old not in source:
    raise SystemExit("LUA_DIAGNOSTICS_PATCH runner could not repair edit() helper")
source = source.replace(old, new, 1)
exec(compile(source, str(script), "exec"), {"__name__": "__main__", "__file__": str(script)})
