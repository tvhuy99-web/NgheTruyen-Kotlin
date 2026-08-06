#!/usr/bin/env python3
"""Completion gate for Priority 1 source fidelity.

Validates effective hybrid source ownership, complete action/fixture coverage,
Wattpad JavaScript replay, signed built-in archives and archive/source parity.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

ROOT = Path(__file__).resolve().parents[1]
HYBRID = {
    "truyenfull": "truyenfull",
    "truyencv": "truyencv",
    "truyencom": "truyencom",
    "truyenyy": "truyenyy",
    "wikidich": "wikidich",
    "sangtacviet": "sangtacviet",
}
PACKS = [*HYBRID, "wattpad"]
REQUIRED_ACTIONS = {
    "home", "genre", "search", "suggestions", "detail",
    "latest_chapter", "toc", "tocPages", "chapter",
}
REQUIRED_FIXTURE_ACTIONS = {
    "HOME", "GENRE", "SEARCH", "SUGGESTIONS", "DETAIL",
    "LATEST_CHAPTER", "TOC", "TOC_PAGES", "CHAPTER",
}


def load_trust_keys() -> list[tuple[str, object]]:
    text = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformTrustRoots.kt").read_text(encoding="utf-8")
    pairs = re.findall(r'keyId = "([^"]+)".*?base64 = "([^"]+)"', text, re.S)
    return [(key_id, load_der_public_key(base64.b64decode(value))) for key_id, value in pairs]


def verify_signature(archive: zipfile.ZipFile, keys: list[tuple[str, object]]) -> str:
    hashes_raw = archive.read("FILES.sha256")
    signature = archive.read("SIGNATURE.es256")
    for key_id, key in keys:
        try:
            key.verify(signature, hashes_raw, ec.ECDSA(hashes.SHA256()))
            return key_id
        except Exception:
            pass
    raise AssertionError("Không có trust root nào xác minh được chữ ký")


def verify_archive(name: str, keys: list[tuple[str, object]]) -> None:
    source_dir = ROOT / "examples/sourcepacks" / name
    path = ROOT / "app/src/main/assets/sourcepacks" / f"{name}.ntsource"
    assert path.is_file(), f"Thiếu asset {path.name}"
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len({item.casefold() for item in names}), f"Tên tệp va chạm: {name}"
        signer = verify_signature(archive, keys)
        assert signer == "nghe-truyen-priority1-p256-v2", f"{name} ký bởi {signer}"
        manifest_lines = archive.read("FILES.sha256").decode("ascii").splitlines()
        expected_hashes = {}
        for line in manifest_lines:
            digest, relative = line.split("  ", 1)
            expected_hashes[relative] = digest
        payload = set(names) - {"FILES.sha256", "SIGNATURE.es256"}
        assert payload == set(expected_hashes), f"Hash coverage không khớp: {name}"
        for relative, digest in expected_hashes.items():
            data = archive.read(relative)
            assert hashlib.sha256(data).hexdigest() == digest, f"Sai hash {name}:{relative}"
            disk = source_dir / relative
            assert disk.is_file(), f"Asset có tệp không nằm trong source: {name}:{relative}"
            assert disk.read_bytes() == data, f"Asset chưa đồng bộ source: {name}:{relative}"
        disk_payload = {
            item.relative_to(source_dir).as_posix()
            for item in source_dir.rglob("*") if item.is_file()
            and item.name not in {"FILES.sha256", "SIGNATURE.es256", "SIGNATURE.ed25519"}
        }
        assert payload == disk_payload, f"Asset thiếu/thừa payload so với source: {name}"


def verify_manifest(name: str) -> None:
    root = ROOT / "examples/sourcepacks" / name
    manifest = json.loads((root / "source.json").read_text(encoding="utf-8"))
    info = json.loads((root / "data/source-info.json").read_text(encoding="utf-8"))
    assert set(manifest["actions"]) == REQUIRED_ACTIONS, f"{name}: action chưa đủ"
    fixture_actions = {item["action"] for item in manifest.get("fixtures", [])}
    assert fixture_actions == REQUIRED_FIXTURE_ACTIONS, f"{name}: fixture chưa đủ"
    assert len(manifest["fixtures"]) == 9, f"{name}: cần đúng 9 fixture"
    for fixture in manifest["fixtures"]:
        for field in ("input", "fixture", "expected"):
            assert (root / fixture[field]).is_file(), f"{name}: thiếu {fixture[field]}"
        action_key = {
            "HOME": "home", "GENRE": "genre", "SEARCH": "search",
            "SUGGESTIONS": "suggestions", "DETAIL": "detail",
            "LATEST_CHAPTER": "latest_chapter", "TOC": "toc",
            "TOC_PAGES": "tocPages", "CHAPTER": "chapter",
        }[fixture["action"]]
        assert manifest["actions"][action_key]["entry"]
    if name in HYBRID:
        assert info["compatibilityTier"] == "FULL_BUILTIN_BRIDGE"
        assert info["preferSourcePack"] is True
        assert int(info["selectionPriority"]) > 100
        assert info["delegateBuiltInId"] == HYBRID[name]
        assert len(info.get("categories", [])) >= 5
    else:
        assert manifest["runtime"]["mode"] == "VBOOK_JS_COMPAT"
        assert info["compatibilityTier"] == "FULL"
        assert info["preferSourcePack"] is True
        assert int(info["selectionPriority"]) > 100


def main() -> None:
    build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionName = "2.8.0-ai-narration-priority2-complete"' in build
    assert "versionCode = 28" in build
    action_enum = (ROOT / "source-api/src/main/kotlin/vn/nghetruyen/source/api/SourceManifest.kt").read_text(encoding="utf-8")
    assert "LATEST_CHAPTER" in action_enum
    registry = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt").read_text(encoding="utf-8")
    source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt").read_text(encoding="utf-8")
    assert "BuiltInSourcePackBridge" in registry and "attachBuiltInDelegate" in registry
    assert "SourceImplementationKind.HYBRID_PACK" in source and "delegateBuiltInId" in source
    fixture_runner = (ROOT / "source-runtime/src/main/kotlin/vn/nghetruyen/source/runtime/SourceFixtureRunner.kt").read_text(encoding="utf-8")
    assert "SourceFixtureExecutor" in fixture_runner
    assert "fixture.input" in fixture_runner and "resources.read(fixture.input" in fixture_runner
    manager = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt").read_text(encoding="utf-8")
    assert "SourceFixtureExecutor" in manager and "VBookJsRuntime" in manager
    assert "SemanticVersion(2, 7, 0)" in manager

    keys = load_trust_keys()
    assert any(key_id == "nghe-truyen-priority1-p256-v2" for key_id, _ in keys)
    for name in PACKS:
        verify_manifest(name)
        verify_archive(name, keys)

    declarative = subprocess.run(
        [sys.executable, str(ROOT / "scripts/check_priority1_declarative_fixtures.py")],
        cwd=ROOT, text=True, capture_output=True,
    )
    if declarative.stdout:
        print(declarative.stdout, end="")
    if declarative.stderr:
        print(declarative.stderr, end="", file=sys.stderr)
    assert declarative.returncode == 0, "Declarative fixture replay thất bại"

    completed = subprocess.run(
        [sys.executable, str(ROOT / "scripts/check_priority1_wattpad_fixtures.py")],
        cwd=ROOT,
        text=True,
        capture_output=True,
    )
    if completed.stdout:
        print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    assert completed.returncode == 0, "Wattpad JS fixture replay thất bại"

    print("PRIORITY1_HYBRID_PACKS_OK count=6")
    print("PRIORITY1_SIGNED_ASSETS_OK count=7 signer=nghe-truyen-priority1-p256-v2")
    print("PRIORITY1_ACTION_FIXTURE_COVERAGE_OK actions=9 fixtures=9 sources=7")
    print("PRIORITY1_COMPLETE_OK")


if __name__ == "__main__":
    main()
