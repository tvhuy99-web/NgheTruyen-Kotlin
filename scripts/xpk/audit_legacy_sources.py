#!/usr/bin/env python3
"""Audit legacy Lua/XPK story sources without executing untrusted code.

The tool inventories capabilities, maps known legacy source files to existing
SourcePack examples, and emits a deterministic JSON/Markdown compatibility
report. It never loads Lua, DEX, or native libraries.
"""
from __future__ import annotations

import argparse
import json
import re
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path

SOURCE_PATTERN = re.compile(r"^nguon_(.+?)_(native|vbook)\.lua$", re.I)
ACTION_PATTERNS = {
    "SEARCH": (r"\bsearch\b", r"timkiem", r"searchBooks"),
    "DETAIL": (r"\bdetail\b", r"bookDetail", r"storyDetail"),
    "TOC": (r"\btoc\b", r"chapterList", r"listChapter"),
    "CHAPTER": (r"\bchapter\b", r"chapterContent", r"getContent"),
    "COMMENTS": (r"\bcomments?\s*=", r"function\s+[\w.:]*comments?", r"[\"\']comments?[\"\']\s*=", r"binhluan\s*="),
    "HOME": (r"\bhome\b", r"homecontent"),
    "GENRE": (r"\bgenre\b", r"category"),
}
CAPABILITY_PATTERNS = {
    "cookies": (r"cookie", r"session"),
    "browser": (r"webview", r"evaluatejavascript", r"dom"),
    "login": (r"login", r"dangnhap"),
    "storage": (r"storage", r"sqlite", r"sharedpreferences"),
    "websocket": (r"websocket", r"\bwss://"),
    "crypto": (r"sha256", r"hmac", r"aes", r"md5"),
    "native_bridge": (r"luajava", r"importclass", r"bindclass", r"loadlib"),
    "dynamic_code": (r"dexclassloader", r"load dex", r"require\s*\("),
}
SOURCE_MAP = {
    "sangtacviet": "sangtacviet",
    "truyencom": "truyencom",
    "truyencv": "truyencv",
    "truyenfull": "truyenfull",
    "truyenyy": "truyenyy",
    "wattpad": "wattpad",
    "wikidich": "wikidich",
}

@dataclass(frozen=True)
class AuditItem:
    file: str
    source_key: str
    legacy_runtime: str
    detected_actions: list[str]
    detected_capabilities: list[str]
    existing_port: str | None
    status: str
    notes: list[str]


def _read_sources(path: Path) -> dict[str, str]:
    if path.is_dir():
        return {
            p.name: p.read_text(encoding="utf-8", errors="replace")
            for p in sorted(path.rglob("nguon_*_*.lua"))
            if SOURCE_PATTERN.match(p.name)
        }
    if not zipfile.is_zipfile(path):
        raise SystemExit(f"Không phải thư mục hoặc XPK/ZIP hợp lệ: {path}")
    result: dict[str, str] = {}
    with zipfile.ZipFile(path) as zf:
        for name in sorted(zf.namelist()):
            base = Path(name).name
            if SOURCE_PATTERN.match(base):
                result[base] = zf.read(name).decode("utf-8", errors="replace")
    return result


def _detect(text: str, patterns: dict[str, tuple[str, ...]]) -> list[str]:
    lowered = text.lower()
    return sorted(
        name for name, probes in patterns.items()
        if any(re.search(probe, lowered, re.I) for probe in probes)
    )


def audit(input_path: Path, project_root: Path) -> list[AuditItem]:
    sources = _read_sources(input_path)
    items: list[AuditItem] = []
    for file_name, text in sources.items():
        match = SOURCE_PATTERN.match(file_name)
        assert match
        source_key, runtime = match.groups()
        mapped = SOURCE_MAP.get(source_key.lower())
        existing = None
        if mapped and (project_root / "examples/sourcepacks" / mapped / "source.json").is_file():
            existing = f"examples/sourcepacks/{mapped}"
        actions = _detect(text, ACTION_PATTERNS)
        capabilities = _detect(text, CAPABILITY_PATTERNS)
        notes: list[str] = []
        if existing:
            status = "PORTED_SOURCE_PRESENT"
            notes.append("Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.")
        elif runtime.lower() == "vbook":
            status = "VBOOK_COMPATIBILITY_REVIEW"
            notes.append("Có thể dùng runtime VBOOK_JS_COMPAT sau khi chuyển Lua sang JavaScript an toàn.")
        else:
            status = "MANUAL_PORT_REQUIRED"
            notes.append("Không tự dịch Lua sang mã chạy; cần port selector/API theo fixture để tránh thực thi mã cũ.")
        if "native_bridge" in capabilities or "dynamic_code" in capabilities:
            notes.append("Cầu nối native/dynamic code không được phép chuyển nguyên trạng sang SourcePack.")
        if "COMMENTS" in actions:
            notes.append("Nguồn cũ có dấu hiệu hỗ trợ bình luận; cần fixture COMMENTS trước khi bật capability trong bản Kotlin.")
        items.append(AuditItem(
            file=file_name,
            source_key=source_key,
            legacy_runtime=runtime.upper(),
            detected_actions=actions,
            detected_capabilities=capabilities,
            existing_port=existing,
            status=status,
            notes=notes,
        ))
    return items


def markdown(items: list[AuditItem], input_path: Path) -> str:
    lines = [
        "# Ma trận tương thích nguồn XPK cũ",
        "",
        f"Nguồn kiểm kê: `{input_path.name}`",
        "",
        "> Đây là phân tích tĩnh. Công cụ không thực thi Lua, DEX hoặc thư viện native.",
        "",
        "| Tệp | Runtime cũ | Actions phát hiện | Port hiện có | Trạng thái |",
        "|---|---|---|---|---|",
    ]
    for item in items:
        port = f"`{item.existing_port}`" if item.existing_port else "Chưa có"
        actions = ", ".join(item.detected_actions) or "Không xác định"
        lines.append(f"| `{item.file}` | {item.legacy_runtime} | {actions} | {port} | {item.status} |")
    lines += ["", "## Ghi chú theo nguồn", ""]
    for item in items:
        lines.append(f"### {item.file}")
        lines.append("")
        lines.append(f"- Capability phát hiện: {', '.join(item.detected_capabilities) or 'không có dấu hiệu đặc biệt'}. ")
        for note in item.notes:
            lines.append(f"- {note}")
        lines.append("")
    lines += [
        "## Quy tắc port",
        "",
        "1. Giữ nguyên SourcePack đã có nếu fixture và gate vẫn PASS.",
        "2. Không nhúng Lua/Dex/native bridge cũ vào ứng dụng mới.",
        "3. Chỉ bật capability mới sau khi có fixture đầu vào, đầu ra mong đợi và giới hạn tài nguyên.",
        "4. Khác biệt với XPK phải được ghi là thiếu, khác biệt chủ ý hoặc cải tiến.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--json", type=Path)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    items = audit(args.input, args.project_root)
    payload = {
        "schemaVersion": 1,
        "input": args.input.name,
        "sourceCount": len(items),
        "items": [asdict(item) for item in items],
    }
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(markdown(items, args.input), encoding="utf-8")
    print(json.dumps({"sourceCount": len(items), "statuses": sorted({i.status for i in items})}, ensure_ascii=False))


if __name__ == "__main__":
    main()
