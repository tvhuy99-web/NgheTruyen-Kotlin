#!/usr/bin/env python3
from pathlib import Path

path = Path("scripts/validate_release.py")
text = path.read_text(encoding="utf-8")
replacements = [
    (
        '        "CHỈ PHÂN VAI",\n        "CHỈ NHẠC CẢNH",\n',
        '        "PHÂN VAI AI",\n        "NHẠC CẢNH",\n',
    ),
    (
        '        "TẢI KHOẢNG",\n',
        '        "CHỌN PHẠM VI TẢI",\n        "CHỌN NHIỀU CHƯƠNG",\n        "TẢI TOÀN BỘ TRUYỆN",\n',
    ),
]
for old, new in replacements:
    if new in text:
        continue
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"validator marker mismatch: expected 1, found {count}: {old!r}")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("REFERENCE_VALIDATOR_V41_APPLIED")
