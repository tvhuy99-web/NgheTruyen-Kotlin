#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).with_name("reference_ui_parity_deep.py")
text = path.read_text(encoding="utf-8")
old = r'append("\n").append(pack.version)'
new = r'append("\\n").append(pack.version)'
if old not in text:
    raise SystemExit("missing extension newline escape marker")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("deep parity newline escape normalized")
