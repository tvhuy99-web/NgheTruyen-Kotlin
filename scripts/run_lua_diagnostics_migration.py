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

# The old release gate encoded the bug by requiring an always-visible Reader-local log button.
# Move that contract to the global Lua-style chrome and run the dedicated lifecycle gate.
release_gate = Path(__file__).with_name("validate_release.py")
release = release_gate.read_text(encoding="utf-8")
old_gate = '    run_script("check_xpk_parity_v290.py")\n'
new_gate = '    run_script("check_xpk_parity_v290.py")\n    run_script("check_lua_diagnostics_ui_parity.py")\n'
if new_gate not in release:
    if old_gate not in release:
        raise SystemExit("missing validate_release gate anchor")
    release = release.replace(old_gate, new_gate, 1)
old_reader = '''        "VIETPHRASE",
        "DỊCH AI",
        "PHÂN VAI AI",
        "XEM NHẬT KÝ",
    )
'''
new_reader = '''        "VIETPHRASE",
        "DỊCH AI",
        "PHÂN VAI AI",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/components/ReferenceDiagnosticsChrome.kt",
        'if (state.diagnosticsMode == "off") return',
        "ĐANG GHI NHẬT KÝ...",
        "XEM NHẬT KÝ",
        "CHƯA CÓ NHẬT KÝ",
        "XUẤT HỘP ĐEN",
        "NHẬT KÝ CHẨN ĐOÁN",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt",
        "ReferenceDiagnosticsChrome(",
        "ReferencePrimaryBottomBar(",
    )
'''
if new_reader not in release:
    if old_reader not in release:
        raise SystemExit("missing Reader diagnostics release-gate anchor")
    release = release.replace(old_reader, new_reader, 1)
release_gate.write_text(release, encoding="utf-8")
print("DIAGNOSTICS_RELEASE_GATE=ALIGNED")
