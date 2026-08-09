#!/usr/bin/env python3
"""Capture immutable differential fixtures from the official vBook /extension/test API.

Input is a JSON plan. Each case points at an extracted extension directory containing
plugin.json and src/*.js. The tool never installs the extension; it submits the exact
source tree to /extension/test and records the raw response plus provenance hashes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_PLUGIN_BYTES = 1024 * 1024
MAX_SCRIPT_BYTES = 4 * 1024 * 1024
MAX_SOURCE_BYTES = 32 * 1024 * 1024
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
TIMEOUT_SECONDS = 60
USER_AGENT = "NgheTruyen-VBook-Differential/1"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strict_text(path: pathlib.Path, limit: int) -> str:
    data = path.read_bytes()
    if len(data) > limit:
        raise RuntimeError(f"FILE_TOO_LARGE:{path}:{len(data)}>{limit}")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise RuntimeError(f"FILE_NOT_UTF8:{path}:{exc}") from exc


def load_extension(root: pathlib.Path) -> tuple[str, dict[str, str], dict[str, str]]:
    plugin_path = root / "plugin.json"
    src_root = root / "src"
    if not plugin_path.is_file() or not src_root.is_dir():
        raise RuntimeError(f"EXTENSION_TREE_INVALID:{root}")
    plugin = strict_text(plugin_path, MAX_PLUGIN_BYTES)
    scripts: dict[str, str] = {}
    hashes: dict[str, str] = {"plugin.json": sha256(plugin.encode("utf-8"))}
    total = len(plugin.encode("utf-8"))
    for path in sorted(src_root.rglob("*.js")):
        if not path.is_file():
            continue
        relative = path.relative_to(src_root).as_posix()
        source = strict_text(path, MAX_SCRIPT_BYTES)
        encoded = source.encode("utf-8")
        total += len(encoded)
        if total > MAX_SOURCE_BYTES:
            raise RuntimeError(f"EXTENSION_SOURCE_TOO_LARGE:{root}")
        scripts[relative] = source
        hashes[f"src/{relative}"] = sha256(encoded)
    if not scripts:
        raise RuntimeError(f"EXTENSION_SCRIPTS_MISSING:{root}")
    return plugin, scripts, hashes


def post_test(server: str, plugin: str, scripts: dict[str, str], script: str, args: list[str]) -> dict:
    endpoint = server.rstrip("/") + "/extension/test"
    body = json.dumps(
        {
            "plugin": plugin,
            "icon": "",
            "src": json.dumps(scripts, ensure_ascii=False, separators=(",", ":")),
            "input": json.dumps({"script": script, "vararg": [str(value) for value in args]}, ensure_ascii=False, separators=(",", ":")),
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
            if len(payload) > MAX_RESPONSE_BYTES:
                raise RuntimeError("REFERENCE_RESPONSE_TOO_LARGE")
    except urllib.error.HTTPError as exc:
        payload = exc.read(MAX_RESPONSE_BYTES + 1)
        raise RuntimeError(f"REFERENCE_HTTP_ERROR:{exc.code}:{payload[:1000].decode('utf-8', 'replace')}") from exc
    try:
        value = json.loads(payload.decode("utf-8"))
    except Exception as exc:
        raise RuntimeError(f"REFERENCE_RESPONSE_INVALID_JSON:{exc}") from exc
    if not isinstance(value, dict):
        raise RuntimeError("REFERENCE_RESPONSE_OBJECT_REQUIRED")
    return value


def run(plan_path: pathlib.Path, output_path: pathlib.Path, server_override: str | None) -> dict:
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    if not isinstance(plan, dict) or plan.get("schema") != 1 or not isinstance(plan.get("cases"), list):
        raise RuntimeError("DIFFERENTIAL_PLAN_INVALID")
    server = server_override or str(plan.get("server") or "").strip()
    if not server:
        raise RuntimeError("REFERENCE_SERVER_REQUIRED")
    parsed = urllib.parse.urlparse(server)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise RuntimeError("REFERENCE_SERVER_INVALID")

    plan_root = plan_path.parent
    output = {
        "schema": 1,
        "referenceServer": server,
        "capturedAtEpochMs": int(time.time() * 1000),
        "planSha256": sha256(plan_path.read_bytes()),
        "cases": [],
    }
    seen = set()
    for raw_case in plan["cases"]:
        if not isinstance(raw_case, dict):
            raise RuntimeError("DIFFERENTIAL_CASE_INVALID")
        case_id = str(raw_case.get("id") or "").strip()
        if not case_id or case_id in seen:
            raise RuntimeError(f"DIFFERENTIAL_CASE_ID_INVALID:{case_id}")
        seen.add(case_id)
        extension_dir = (plan_root / str(raw_case.get("extension") or "")).resolve()
        script = str(raw_case.get("script") or "").strip()
        args = raw_case.get("args") or []
        if not script or not isinstance(args, list) or len(args) > 16:
            raise RuntimeError(f"DIFFERENTIAL_CASE_INPUT_INVALID:{case_id}")
        plugin, scripts, hashes = load_extension(extension_dir)
        if script not in scripts:
            raise RuntimeError(f"DIFFERENTIAL_SCRIPT_MISSING:{case_id}:{script}")
        response = post_test(server, plugin, scripts, script, [str(value) for value in args])
        output["cases"].append(
            {
                "id": case_id,
                "artifactId": str(raw_case.get("artifactId") or extension_dir.name),
                "profile": str(raw_case.get("profile") or "UNKNOWN"),
                "features": list(raw_case.get("features") or []),
                "script": script,
                "args": [str(value) for value in args],
                "sourceHashes": hashes,
                "response": response,
            }
        )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", nargs="?", type=pathlib.Path, help="Reference plan JSON")
    parser.add_argument("--plan", dest="plan_option", type=pathlib.Path, help="Reference plan JSON (named form)")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--server", default=None, help="Override plan.reference server URL")
    args = parser.parse_args()
    plan_path = args.plan_option or args.plan
    if plan_path is None:
        parser.error("a plan path is required")
    try:
        output = run(plan_path, args.out, args.server)
    except Exception as exc:
        print(f"VBOOK_REFERENCE_CAPTURE_FAILED:{exc}", file=sys.stderr)
        return 2
    failed = [row for row in output["cases"] if row["response"].get("code") != 200]
    print(json.dumps({"captured": len(output["cases"]), "referenceErrors": len(failed)}, sort_keys=True))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
