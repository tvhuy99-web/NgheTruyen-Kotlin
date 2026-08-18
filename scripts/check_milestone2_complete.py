#!/usr/bin/env python3
"""Completion gate for Milestone 2: signed packs, fixtures, vBook and Android wiring."""
from __future__ import annotations

import base64
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from copy import deepcopy
from pathlib import Path
from urllib.parse import quote, urljoin

from bs4 import BeautifulSoup
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

ROOT = Path(__file__).resolve().parents[1]
PACK_NAMES = ["truyenfull", "truyencv", "truyencom", "truyenyy", "wikidich", "sangtacviet", "wattpad"]
DECLARATIVE = ["truyenfull", "truyencv", "truyencom", "truyenyy", "wikidich", "sangtacviet"]


def run_script(name: str) -> None:


    with tempfile.TemporaryFile(mode="w+t", encoding="utf-8") as log:
        completed = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / name)],
            cwd=ROOT,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        log.seek(0)
        output = log.read()
    if output:
        print(output, end="" if output.endswith("\n") else "\n")
    if completed.returncode:
        raise SystemExit(completed.returncode)


def sha16(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).digest()[:16].hex()


def get_path(root, path: str):
    if not path:
        return root
    current = root
    for segment in path.split("."):
        if isinstance(current, dict):
            current = current.get(segment)
        elif isinstance(current, list) and segment.isdigit():
            index = int(segment)
            current = current[index] if index < len(current) else None
        else:
            return None
    return current


def scalar(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value)


TEMPLATE = re.compile(r"\{\{\s*([^{}]+?)\s*}}")


def render(template: str, input_data: dict, variables: dict) -> str:
    def replace(match: re.Match[str]) -> str:
        parts = [part.strip() for part in match.group(1).split("|")]
        root_name, *tail = parts[0].split(".")
        root = input_data if root_name == "input" else variables.get(root_name)
        value = get_path(root, ".".join(tail)) if tail else root
        text = scalar(value)
        for transform in parts[1:]:
            transform = transform.lower()
            if transform == "urlencode":
                text = quote(text, safe="")
            elif transform == "lower":
                text = text.lower()
            elif transform == "upper":
                text = text.upper()
            elif transform == "trim":
                text = text.strip()
            else:
                raise AssertionError(f"Unsupported template transform {transform}")
        return text
    return TEMPLATE.sub(replace, template)


def select_one(element, selector: str | None):
    if not selector:
        return element
    return element.select_one(selector)


def projection(element, fields: dict, index: int, base_url: str, input_data: dict, variables: dict) -> dict:
    output = {}
    for name, raw in fields.items():
        if isinstance(raw, str):
            selected = element.select_one(raw)
            value = selected.get_text(" ", strip=True) if selected else ""
        elif not isinstance(raw, dict):
            value = deepcopy(raw)
        elif "value" in raw:
            value = deepcopy(raw["value"])
        elif "template" in raw:
            value = render(raw["template"], input_data, variables)
            if raw.get("sha256"):
                value = sha16(value)
        elif raw.get("index") is True:
            value = index
        elif raw.get("texts") is True:
            selected = element.select(raw.get("select")) if raw.get("select") else [element]
            value = [node.get_text(" ", strip=True) for node in selected]
            value = [item for item in value if item]
        else:
            selected = select_one(element, raw.get("select"))
            if selected is None:
                extracted = ""
            elif raw.get("attr") is not None:
                extracted = selected.get(raw["attr"], "")
                if raw.get("absolute"):
                    extracted = urljoin(base_url, extracted)
            elif raw.get("html"):
                extracted = "".join(str(child) for child in selected.contents)
            elif raw.get("outerHtml"):
                extracted = str(selected)
            else:
                extracted = selected.get_text(" ", strip=True)
            value = sha16(extracted) if raw.get("sha256") else extracted.strip()
            if not value and "default" in raw:
                value = deepcopy(raw["default"])
        output[name] = value
    return output


def evaluate_fixture(
    source_dir: Path,
    action_name: str,
    *,
    input_path: str | None = None,
    fixture_path: str | None = None,
    program_path: str | None = None,
) -> object:
    input_file = source_dir / (input_path or f"fixtures/{action_name}.input.json")
    fixture_file = source_dir / (fixture_path or f"fixtures/{action_name}.http.json")
    program_file = source_dir / (program_path or f"actions/{action_name}.json")
    input_data = json.loads(input_file.read_text(encoding="utf-8"))
    replay = json.loads(fixture_file.read_text(encoding="utf-8"))
    responses = {(item.get("method", "GET").upper(), item["url"]): item for item in replay["responses"]}
    program = json.loads(program_file.read_text(encoding="utf-8"))
    variables: dict[str, object] = {"input": input_data}
    output = None

    for step in program["steps"]:
        op = step["op"]
        if op == "template":
            variables[step["as"]] = render(step["value"], input_data, variables)
        elif op == "fetch":
            url = render(step["url"], input_data, variables) if "url" in step else scalar(variables[step["urlFrom"]])
            method = step.get("method", "GET").upper()
            assert (method, url) in responses, f"{source_dir.name}/{action_name}: fixture missing {method} {url}"
            item = responses[(method, url)]
            variables[step["as"]] = {
                "status": item["status"],
                "url": item.get("finalUrl", item["url"]),
                "headers": item.get("headers", {}),
                "body": item.get("bodyText", ""),
                "redirectCount": 0,
                "fromReplay": True,
            }
        elif op in {"selectHtmlArray", "selectHtmlObject", "htmlParagraphs"}:
            source = variables[step["from"]]
            html = get_path(source, step.get("path", ""))
            if "baseUrl" in step:
                base_url = render(step["baseUrl"], input_data, variables)
            elif "baseUrlFrom" in step:
                base_url = scalar(get_path(variables[step["baseUrlFrom"]], step.get("baseUrlPath", "")))
            else:
                base_url = json.loads((source_dir / "source.json").read_text(encoding="utf-8"))["origins"][0]
            soup = BeautifulSoup(scalar(html), "html.parser")
            if op == "selectHtmlArray":
                nodes = soup.select(step["selector"])[: step.get("limit", 2000)]
                variables[step["as"]] = [projection(node, step["fields"], index, base_url, input_data, variables) for index, node in enumerate(nodes)]
            elif op == "selectHtmlObject":
                node = soup.select_one(step["selector"])
                variables[step["as"]] = projection(node, step["fields"], 0, base_url, input_data, variables) if node else None
            else:
                node = soup.select_one(step["selector"])
                assert node is not None, f"{source_dir.name}/{action_name}: paragraph selector not found"
                node = BeautifulSoup(str(node), "html.parser")
                for remove in node.select(step.get("remove", "")) if step.get("remove") else []:
                    remove.decompose()
                paragraphs = [part.strip() for part in node.get_text("\n").splitlines() if part.strip()]
                variables[step["as"]] = paragraphs[: step.get("limit", 10000)]
        elif op == "composeObject":
            result = {}
            for key, raw in step["fields"].items():
                if not isinstance(raw, dict):
                    result[key] = deepcopy(raw)
                elif "value" in raw:
                    result[key] = deepcopy(raw["value"])
                elif "template" in raw:
                    result[key] = render(raw["template"], input_data, variables)
                elif "from" in raw:
                    result[key] = deepcopy(get_path(variables[raw["from"]], raw.get("path", "")))
                else:
                    result[key] = None
            variables[step["as"]] = result
        elif op == "paginate":
            items = variables[step["from"]]
            page = max(1, int(input_data.get(step["pageInput"], 1)))
            size = step.get("pageSize", 20)
            start = (page - 1) * size
            page_items = items[start : start + size]
            variables[step["as"]] = {"items": page_items, "nextPage": page + 1 if start + size < len(items) else None}
        elif op == "emit":
            output = deepcopy(variables[step["from"]])
        else:
            raise AssertionError(f"Unsupported fixture operation {op} in {source_dir.name}/{action_name}")
    return output


def trust_keys():
    text = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformTrustRoots.kt").read_text(encoding="utf-8")
    pairs = re.findall(r'keyId = "([^"]+)".*?base64 = "([^"]+)"', text, re.S)
    return [(key_id, load_der_public_key(base64.b64decode(value))) for key_id, value in pairs]


def verify_pack(path: Path, keys) -> str:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(name.casefold() for name in names)), f"case-insensitive collision in {path.name}"
        assert "source.json" in names and "FILES.sha256" in names
        signature_name = "SIGNATURE.es256"
        assert signature_name in names, f"{path.name} missing ECDSA signature"
        hashes_raw = archive.read("FILES.sha256")
        assert hashes_raw.endswith(b"\n")
        expected: dict[str, str] = {}
        for line in hashes_raw.decode("ascii").splitlines():
            digest, name = line.split("  ", 1)
            assert name not in expected
            expected[name] = digest
        payload_names = set(names) - {"FILES.sha256", signature_name}
        assert set(expected) == payload_names, f"hash coverage mismatch in {path.name}"
        for name, digest in expected.items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == digest, f"hash mismatch {path.name}:{name}"
        signer = None
        signature = archive.read(signature_name)
        for key_id, key in keys:
            try:
                key.verify(signature, hashes_raw, ec.ECDSA(hashes.SHA256()))
                signer = key_id
                break
            except Exception:
                continue
        assert signer, f"untrusted signature: {path.name}"
        source = json.loads(archive.read("source.json"))
        for action in source.get("actions", {}).values():
            assert action["entry"] in names, f"{path.name} action entry missing: {action['entry']}"
        runtime_entry = source.get("runtime", {}).get("entry")
        if runtime_entry:
            assert runtime_entry in names, f"{path.name} runtime entry missing"
        return signer


def main() -> None:
    build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionName = "2.8.0-ai-narration-priority2-complete"' in build
    assert 'implementation(project(":source-vbook"))' in build
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    assert '":source-vbook"' in settings

    keys = trust_keys()
    assets = ROOT / "app/src/main/assets/sourcepacks"
    signers = {}
    for name in PACK_NAMES:
        pack = assets / f"{name}.ntsource"
        assert pack.is_file(), f"missing built-in pack {pack.name}"
        signers[name] = verify_pack(pack, keys)
    for name in PACK_NAMES:
        assert signers[name] == "nghe-truyen-priority1-p256-v2", f"unexpected signer for {name}"

    fixture_count = 0
    for name in DECLARATIVE:
        source_dir = ROOT / "examples/sourcepacks" / name
        manifest = json.loads((source_dir / "source.json").read_text(encoding="utf-8"))
        assert manifest["runtime"]["mode"] == "DECLARATIVE"
        for action in ("search", "detail", "toc", "chapter"):
            actual = evaluate_fixture(source_dir, action)
            expected = json.loads((source_dir / f"fixtures/{action}.expected.json").read_text(encoding="utf-8"))
            assert actual == expected, (
                f"fixture mismatch {name}/{action}\n"
                f"actual={json.dumps(actual, ensure_ascii=False, sort_keys=True)}\n"
                f"expected={json.dumps(expected, ensure_ascii=False, sort_keys=True)}"
            )
            fixture_count += 1

    run_script("check_milestone2_comments.py")

    wattpad = json.loads((ROOT / "examples/sourcepacks/wattpad/source.json").read_text(encoding="utf-8"))
    assert wattpad["runtime"]["mode"] == "VBOOK_JS_COMPAT"
    assert wattpad["actions"]["home"]["entry"] == "src/homecontent.js"
    assert wattpad["actions"]["genre"]["entry"] == "src/genrecontent.js"
    assert (ROOT / "examples/sourcepacks/wattpad/plugin.json").is_file()

    if "--with-kotlinc" in sys.argv:
        run_script("check_milestone2_source_platform.py")
        run_script("check_vbook_static.py")
        run_script("check_source_platform_android_static.py")
    else:
        print("MILESTONE2_COMPILER_GATES_RUN_SEPARATELY")
    print(f"MILESTONE2_BUILTIN_PACKS_OK count={len(PACK_NAMES)}")
    print(f"MILESTONE2_FIXTURE_REPLAY_OK count={fixture_count}")
    print("MILESTONE2_COMPLETE_CHECK_OK")


if __name__ == "__main__":
    main()
