#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

browser_path = ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt"
value = browser_path.read_text(encoding="utf-8")
old = '''        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (logLevel >= 1) {
                record("HTTP", "HTTP_${errorResponse.statusCode}", "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())}")
            }
        }
'''
new = '''        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            record("HTTP", "HTTP_${errorResponse.statusCode}", "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())}")
        }
'''
if old in value:
    value = value.replace(old, new, 1)
elif new not in value:
    raise SystemExit("HTTP diagnostic browser anchor not found")
browser_path.write_text(value, encoding="utf-8")

gate_path = ROOT / "scripts/check_lua_diagnostics_ui_parity.py"
gate = gate_path.read_text(encoding="utf-8")
old_gate = '''        'if (logLevel >= 1) record("PAGE"',
        'if (logLevel >= 2) {\\n                record(\\n                    "REQUEST"',
'''
new_gate = '''        'if (logLevel >= 1) record("PAGE"',
        'if (logLevel >= 1) {\\n                record("HTTP"',
        'if (logLevel >= 2) {\\n                record(\\n                    "REQUEST"',
'''
if old_gate in gate:
    gate = gate.replace(old_gate, new_gate, 1)
elif new_gate not in gate:
    raise SystemExit("diagnostic browser parity anchor not found")
gate_path.write_text(gate, encoding="utf-8")

print("DIAGNOSTICS_FINAL_HOTFIX=APPLIED")
