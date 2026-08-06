#!/usr/bin/env python3
"""Build a deterministic ECDSA-P256 signed .ntsource archive.

The private key is supplied by path and is never copied into the project or
archive. Requires the `openssl` executable for signing.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import tempfile
import unicodedata
import zipfile
from pathlib import Path, PurePosixPath

HASH_FILE = "FILES.sha256"
SIGNATURE_FILE = "SIGNATURE.es256"
FORBIDDEN_INPUT = {HASH_FILE, "SIGNATURE.ed25519", SIGNATURE_FILE}
MAX_FILES = 1024
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024


def canonical_path(raw: str) -> str:
    if not raw or len(raw) > 512 or "\\" in raw or "\x00" in raw:
        raise ValueError(f"Đường dẫn không hợp lệ: {raw!r}")
    normalized = unicodedata.normalize("NFC", raw)
    if normalized != raw:
        raise ValueError(f"Đường dẫn chưa chuẩn hóa NFC: {raw}")
    path = PurePosixPath(normalized)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError(f"Đường dẫn có nguy cơ traversal: {raw}")
    for part in path.parts:
        if part.endswith((" ", ".")) or ":" in part or any(ord(ch) < 0x20 for ch in part):
            raise ValueError(f"Đường dẫn không an toàn: {raw}")
    return path.as_posix()


def collect(source: Path) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    collision_keys: set[str] = set()
    total = 0
    for file in sorted(source.rglob("*")):
        if not file.is_file():
            continue
        relative = canonical_path(file.relative_to(source).as_posix())
        if relative in FORBIDDEN_INPUT:
            raise ValueError(f"Không đặt {relative} trong thư mục nguồn; script sẽ tự tạo")
        collision = relative.casefold()
        if collision in collision_keys:
            raise ValueError(f"Tên tệp va chạm không phân biệt hoa thường: {relative}")
        collision_keys.add(collision)
        data = file.read_bytes()
        if len(data) > MAX_FILE_BYTES:
            raise ValueError(f"Tệp vượt 8 MiB: {relative}")
        total += len(data)
        if total > MAX_TOTAL_BYTES:
            raise ValueError("Tổng payload vượt 64 MiB")
        files[relative] = data
    if len(files) > MAX_FILES:
        raise ValueError("Gói vượt 1024 tệp")
    if "source.json" not in files:
        raise ValueError("Thiếu source.json")
    return files


def build(source: Path, key: Path, output: Path) -> None:
    files = collect(source)
    hash_manifest = "".join(
        f"{hashlib.sha256(files[name]).hexdigest()}  {name}\n" for name in sorted(files)
    ).encode("ascii")
    with tempfile.TemporaryDirectory(prefix="ntsource-sign-") as temp_name:
        hash_path = Path(temp_name) / HASH_FILE
        signature_path = Path(temp_name) / SIGNATURE_FILE
        hash_path.write_bytes(hash_manifest)
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key), "-out", str(signature_path), str(hash_path)],
            check=True,
        )
        files[HASH_FILE] = hash_manifest
        files[SIGNATURE_FILE] = signature_path.read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_output = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    try:
        with zipfile.ZipFile(temp_output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(files):
                info = zipfile.ZipInfo(name)
                info.date_time = (2026, 1, 1, 0, 0, 0)
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, files[name])
        temp_output.replace(output)
    finally:
        temp_output.unlink(missing_ok=True)
    print(f"Đã tạo {output}")
    print(f"SHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Thư mục payload SourcePack")
    parser.add_argument("--private-key", required=True, type=Path, help="Khóa EC P-256 PEM bên ngoài project")
    parser.add_argument("--output", required=True, type=Path, help="Tệp .ntsource đầu ra")
    args = parser.parse_args()
    if not args.source.is_dir():
        parser.error("source phải là thư mục")
    if not args.private_key.is_file():
        parser.error("không tìm thấy private key")
    build(args.source.resolve(), args.private_key.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
