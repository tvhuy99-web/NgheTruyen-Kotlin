#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
build_gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
versioning = (ROOT / "VERSIONING.md").read_text(encoding="utf-8")
main_strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
debug_strings = (ROOT / "app/src/debug/res/values/strings.xml").read_text(encoding="utf-8")

version_name_match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', build_gradle, re.MULTILINE)
version_code_match = re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', build_gradle, re.MULTILINE)
assert version_name_match, "missing Android versionName"
assert version_code_match, "missing Android versionCode"

version_name = version_name_match.group(1)
version_code = int(version_code_match.group(1))
assert re.fullmatch(r"\d+\.\d+\.\d+", version_name), (
    f"versionName must use MAJOR.MINOR.PATCH only, got {version_name!r}"
)
assert version_code > 0, "versionCode must be a positive increasing integer"
assert f"`versionName = {version_name}`" in versioning, "VERSIONING.md current versionName is out of sync"
assert f"`versionCode = {version_code}`" in versioning, "VERSIONING.md current versionCode is out of sync"

for required in (
    "MAJOR.MINOR.PATCH",
    "Cập nhật lớn — MAJOR",
    "Cập nhật nhỏ — MINOR",
    "Sửa lỗi nhỏ — PATCH",
):
    assert required in versioning, f"VERSIONING.md missing policy section: {required}"

for strings in (main_strings, debug_strings):
    assert '<string name="app_name">Nghe Truyện</string>' in strings, "app_name must remain Nghe Truyện"

print(f"VERSION_POLICY=PASS versionName={version_name} versionCode={version_code}")
