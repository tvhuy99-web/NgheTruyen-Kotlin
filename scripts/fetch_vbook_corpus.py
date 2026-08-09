#!/usr/bin/env python3
"""Fetch the vBook repository corpus without executing extension code.

This is a developer/CI acquisition tool. Runtime compatibility decisions remain in
source-vbook's Kotlin analyzers. The downloader only preserves provenance and safely
extracts plugin.json + JavaScript files for corpus tests.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import pathlib
import sys
import time
import urllib.parse
import urllib.request
import zipfile

DEFAULT_INDEX = "https://raw.githubusercontent.com/Darkrai9x/vbook-extensions/master/repository.json"
USER_AGENT = "NgheTruyen-VBook-Corpus/1"
MAX_INDEX_BYTES = 2 * 1024 * 1024
MAX_CATALOG_BYTES = 16 * 1024 * 1024
MAX_ZIP_BYTES = 16 * 1024 * 1024
MAX_ENTRY_BYTES = 4 * 1024 * 1024
MAX_EXPANDED_BYTES = 48 * 1024 * 1024
MAX_ENTRIES = 1024
MAX_COMPRESSION_RATIO = 200
TIMEOUT_SECONDS = 30


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def slug(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:20]


def fetch(url: str, limit: int, attempts: int = 2) -> bytes:
    last = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as response:
                if response.status < 200 or response.status >= 300:
                    raise RuntimeError(f"HTTP_{response.status}")
                content_length = response.headers.get("Content-Length")
                if content_length and int(content_length) > limit:
                    raise RuntimeError(f"RESPONSE_TOO_LARGE:{content_length}>{limit}")
                data = response.read(limit + 1)
                if len(data) > limit:
                    raise RuntimeError(f"RESPONSE_TOO_LARGE:{len(data)}>{limit}")
                return data
        except Exception as exc:  # acquisition diagnostics are intentionally preserved
            last = exc
            if attempt + 1 < attempts:
                time.sleep(0.4 * (attempt + 1))
    raise RuntimeError(str(last) if last else "FETCH_FAILED")


def parse_json(data: bytes, label: str):
    try:
        return json.loads(data.decode("utf-8-sig"))
    except Exception as exc:
        raise RuntimeError(f"INVALID_JSON:{label}:{exc}") from exc


def safe_name(name: str) -> str:
    value = name.replace("\\", "/")
    while value.startswith("/"):
        value = value[1:]
    parts = value.split("/")
    if not value or any(part in ("", ".", "..") for part in parts):
        raise RuntimeError(f"ZIP_PATH_INVALID:{name}")
    if ":" in parts[0] or "\x00" in value:
        raise RuntimeError(f"ZIP_PATH_INVALID:{name}")
    return value


def extract_source_zip(payload: bytes) -> dict[str, bytes]:
    if len(payload) > MAX_ZIP_BYTES:
        raise RuntimeError("ZIP_TOO_LARGE")
    files: dict[str, bytes] = {}
    expanded = 0
    with zipfile.ZipFile(io.BytesIO(payload), "r") as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise RuntimeError("ZIP_TOO_MANY_ENTRIES")
        for info in infos:
            if info.is_dir():
                continue
            name = safe_name(info.filename)
            mode = (info.external_attr >> 16) & 0xFFFF
            if mode and (mode & 0o170000) == 0o120000:
                raise RuntimeError(f"ZIP_SYMLINK_DENIED:{name}")
            if info.file_size > MAX_ENTRY_BYTES:
                raise RuntimeError(f"ZIP_ENTRY_TOO_LARGE:{name}")
            if info.compress_size > 0 and info.file_size / info.compress_size > MAX_COMPRESSION_RATIO:
                raise RuntimeError(f"ZIP_COMPRESSION_RATIO:{name}")
            expanded += info.file_size
            if expanded > MAX_EXPANDED_BYTES:
                raise RuntimeError("ZIP_EXPANDED_TOO_LARGE")
            keep = name == "plugin.json" or (name.startswith("src/") and name.lower().endswith(".js"))
            if not keep:
                continue
            with archive.open(info, "r") as stream:
                data = stream.read(MAX_ENTRY_BYTES + 1)
            if len(data) > MAX_ENTRY_BYTES:
                raise RuntimeError(f"ZIP_ENTRY_TOO_LARGE:{name}")
            files[name] = data
    if "plugin.json" not in files:
        raise RuntimeError("PLUGIN_JSON_MISSING")
    return files


def write_bytes(path: pathlib.Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def run(index_url: str, output_dir: pathlib.Path, limit_packages: int | None) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    index_bytes = fetch(index_url, MAX_INDEX_BYTES)
    index = parse_json(index_bytes, "repository.json")
    if not isinstance(index, list):
        raise RuntimeError("REPOSITORY_INDEX_ARRAY_REQUIRED")

    report = {
        "schema": 1,
        "indexUrl": index_url,
        "indexSha256": sha256(index_bytes),
        "catalogs": [],
        "packages": [],
    }
    package_count = 0
    for repo_pos, descriptor in enumerate(index):
        if not isinstance(descriptor, dict) or not descriptor.get("link"):
            continue
        catalog_url = str(descriptor["link"])
        catalog_row = {
            "index": repo_pos,
            "url": catalog_url,
            "author": str(descriptor.get("author", "")),
            "description": str(descriptor.get("description", "")),
        }
        try:
            catalog_bytes = fetch(catalog_url, MAX_CATALOG_BYTES)
            catalog = parse_json(catalog_bytes, catalog_url)
            data = catalog.get("data", []) if isinstance(catalog, dict) else []
            if not isinstance(data, list):
                raise RuntimeError("CATALOG_DATA_ARRAY_REQUIRED")
            catalog_row.update({"sha256": sha256(catalog_bytes), "itemCount": len(data), "status": "OK"})
            write_bytes(output_dir / "catalogs" / f"{repo_pos:02d}-{slug(catalog_url)}.json", catalog_bytes)
        except Exception as exc:
            catalog_row.update({"status": "ERROR", "error": str(exc)})
            report["catalogs"].append(catalog_row)
            continue
        report["catalogs"].append(catalog_row)

        for item_pos, item in enumerate(data):
            if limit_packages is not None and package_count >= limit_packages:
                break
            if not isinstance(item, dict) or not item.get("path"):
                continue
            package_url = str(item["path"])
            identity = hashlib.sha256((catalog_url.strip() + "\n" + package_url.strip()).encode("utf-8")).hexdigest()
            row = {
                "catalogUrl": catalog_url,
                "catalogIndex": repo_pos,
                "itemIndex": item_pos,
                "identity": identity,
                "name": str(item.get("name", "")),
                "version": item.get("version"),
                "type": item.get("type"),
                "locale": item.get("locale"),
                "source": item.get("source"),
                "packageUrl": package_url,
            }
            package_count += 1
            try:
                zip_bytes = fetch(package_url, MAX_ZIP_BYTES)
                files = extract_source_zip(zip_bytes)
                package_dir = output_dir / "packages" / identity
                for name, data_bytes in files.items():
                    write_bytes(package_dir / name, data_bytes)
                row.update({
                    "status": "OK",
                    "zipSha256": sha256(zip_bytes),
                    "fileCount": len(files),
                    "pluginSha256": sha256(files["plugin.json"]),
                })
            except Exception as exc:
                row.update({"status": "ERROR", "error": str(exc)})
            report["packages"].append(row)
        if limit_packages is not None and package_count >= limit_packages:
            break

    report["summary"] = {
        "repositoryCount": len(report["catalogs"]),
        "catalogOk": sum(1 for row in report["catalogs"] if row["status"] == "OK"),
        "packageAttempted": len(report["packages"]),
        "packageOk": sum(1 for row in report["packages"] if row["status"] == "OK"),
        "packageError": sum(1 for row in report["packages"] if row["status"] != "OK"),
    }
    (output_dir / "corpus-index.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", default=DEFAULT_INDEX)
    parser.add_argument("--out", default="build/vbook-corpus")
    parser.add_argument("--limit-packages", type=int, default=None)
    args = parser.parse_args()
    if args.limit_packages is not None and args.limit_packages < 1:
        parser.error("--limit-packages must be >= 1")
    try:
        report = run(args.index, pathlib.Path(args.out), args.limit_packages)
    except Exception as exc:
        print(f"VBOOK_CORPUS_FATAL:{exc}", file=sys.stderr)
        return 2
    print(json.dumps(report["summary"], ensure_ascii=False, sort_keys=True))
    return 0 if report["summary"]["catalogOk"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
