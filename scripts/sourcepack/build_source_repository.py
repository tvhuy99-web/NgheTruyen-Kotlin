#!/usr/bin/env python3
"""Canonicalize and sign a Source Repository v1 index with an external P-256 key."""
from __future__ import annotations

import argparse
import base64
import json
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

ROOT_KEYS = [
    "schemaVersion", "repositoryId", "name", "generatedAtEpochMs", "expiresAtEpochMs",
    "signerKeyId", "signatureAlgorithm", "packages",
]
PACKAGE_KEYS = [
    "sourceId", "name", "version", "description", "packageUrl", "packageSha256", "packageBytes",
    "minAppVersion", "maxAppVersion", "adult", "changelog",
]
OPTIONAL_PACKAGE_KEYS = {"description", "minAppVersion", "maxAppVersion", "adult", "changelog"}


def https_url(raw: str) -> bool:
    parsed = urlsplit(raw)
    return parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password and not parsed.fragment


def canonicalize(raw: dict) -> dict:
    unknown = set(raw) - set(ROOT_KEYS) - {"signature"}
    if unknown:
        raise ValueError(f"Field repository không hỗ trợ: {sorted(unknown)}")
    if raw.get("schemaVersion") != 1:
        raise ValueError("schemaVersion phải bằng 1")
    if raw.get("signatureAlgorithm") != "ECDSA_P256_SHA256":
        raise ValueError("Builder hiện chỉ ký ECDSA_P256_SHA256")
    signer_key_id = raw.get("signerKeyId")
    if not isinstance(signer_key_id, str) or not signer_key_id.strip() or len(signer_key_id) > 200:
        raise ValueError("signerKeyId phải có 1..200 ký tự")
    for time_key in ("generatedAtEpochMs", "expiresAtEpochMs"):
        if not isinstance(raw.get(time_key), int) or raw[time_key] < 0:
            raise ValueError(f"{time_key} phải là số nguyên không âm")
    if raw["expiresAtEpochMs"] <= raw["generatedAtEpochMs"]:
        raise ValueError("expiresAtEpochMs phải lớn hơn generatedAtEpochMs")
    packages = raw.get("packages")
    if not isinstance(packages, list) or not packages or len(packages) > 500:
        raise ValueError("packages phải có 1..500 phần tử")
    output_packages = []
    source_ids = set()
    for item in packages:
        if not isinstance(item, dict):
            raise ValueError("Mỗi package phải là object")
        unknown_package = set(item) - set(PACKAGE_KEYS)
        if unknown_package:
            raise ValueError(f"Field package không hỗ trợ: {sorted(unknown_package)}")
        missing = [key for key in PACKAGE_KEYS if key not in item and key not in OPTIONAL_PACKAGE_KEYS]
        if missing:
            raise ValueError(f"Package thiếu field: {missing}")
        if not isinstance(item["sourceId"], str) or not item["sourceId"].strip():
            raise ValueError("sourceId không hợp lệ")
        if item["sourceId"] in source_ids:
            raise ValueError(f"sourceId trùng: {item['sourceId']}")
        source_ids.add(item["sourceId"])
        if not https_url(item["packageUrl"]):
            raise ValueError(f"packageUrl không phải HTTPS an toàn: {item['packageUrl']}")
        package = {key: item[key] for key in PACKAGE_KEYS if key in item}
        output_packages.append(package)
    return {key: (output_packages if key == "packages" else raw[key]) for key in ROOT_KEYS}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path, help="JSON index chưa ký")
    parser.add_argument("--private-key", type=Path, required=True, help="PEM P-256 nằm ngoài repository")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raw = json.loads(args.input.read_text(encoding="utf-8"))
    canonical = canonicalize(raw)
    payload = json.dumps(canonical, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with tempfile.TemporaryDirectory(prefix="ntsource-repository-sign-") as name:
        payload_path = Path(name) / "payload.json"
        signature_path = Path(name) / "signature.der"
        payload_path.write_bytes(payload)
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(args.private_key), "-out", str(signature_path), str(payload_path)],
            check=True,
        )
        signed = dict(canonical)
        signed["signature"] = base64.b64encode(signature_path.read_bytes()).decode("ascii")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(signed, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
