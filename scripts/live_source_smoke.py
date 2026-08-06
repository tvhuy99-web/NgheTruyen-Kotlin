#!/usr/bin/env python3
"""Opt-in live smoke monitor for integrated story sources.

It intentionally does not bypass CAPTCHA or anti-bot systems. The script checks
DNS/TLS/redirects, final-host policy, response size and whether a page resembles
a story listing. Use --strict in CI to fail on degraded sources.
"""
from __future__ import annotations

import argparse
import json
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
USER_AGENT = "NgheTruyen-SourceMonitor/2.6 (+manual-compatible-check)"
MAX_BYTES = 4 * 1024 * 1024
CHALLENGE_MARKERS = (
    "checking your browser", "verify you are human", "cloudflare", "captcha",
    "just a moment", "attention required", "access denied",
)
STORY_MARKERS = ("truyện", "chương", "chapter", "novel", "story")

SOURCES = {
    "truyenfull": ("https://truyenfull.vision/danh-sach/truyen-moi/", {"truyenfull.vision", "truyenfull.io", "truyenfull.bio", "truyenfull.live"}),
    "truyencv": ("https://truyencv.io/moi-cap-nhat/", {"truyencv.io"}),
    "truyencom": ("https://truyencom.com/truyen-moi-cap-nhat/", {"truyencom.com", "dtruyen.com"}),
    "truyenyy": ("https://truyenyy.co/truyen-moi-cap-nhat", {"truyenyy.co", "r.jina.ai"}),
    "wikidich": ("https://wikidichvn.com", {"wikidichvn.com", "wikidich.vn"}),
    "sangtacviet": ("https://sangtacviet.vip/search/?find=&minc=0&sort=update&tag=", {"sangtacviet.vip", "sangtacviet.com", "sangtacviet.app", "sangtacviet.xyz"}),
    "wattpad": ("https://www.wattpad.com", {"wattpad.com"}),
}

@dataclass
class Result:
    source: str
    status: str
    url: str
    final_url: str = ""
    http_status: int | None = None
    bytes_read: int = 0
    elapsed_ms: int = 0
    detail: str = ""


def host_allowed(host: str, allowed: set[str]) -> bool:
    host = host.lower().strip(".")
    return any(host == item or host.endswith("." + item) for item in allowed)


def check_source(source: str, timeout: float) -> Result:
    url, allowed = SOURCES[source]
    started = time.monotonic()
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml", "Accept-Language": "vi-VN,vi;q=0.9,en;q=0.6"},
    )
    try:
        context = ssl.create_default_context()
        with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
            final_url = response.geturl()
            parsed = urlparse(final_url)
            if parsed.scheme != "https" or not parsed.hostname or not host_allowed(parsed.hostname, allowed):
                return Result(source, "FAIL", url, final_url, getattr(response, "status", None), elapsed_ms=int((time.monotonic()-started)*1000), detail="Redirect ra ngoài HTTPS/allowlist")
            declared = response.headers.get("Content-Length")
            if declared and int(declared) > MAX_BYTES:
                return Result(source, "FAIL", url, final_url, getattr(response, "status", None), elapsed_ms=int((time.monotonic()-started)*1000), detail="Response lớn hơn giới hạn")
            body = response.read(MAX_BYTES + 1)
            if len(body) > MAX_BYTES:
                return Result(source, "FAIL", url, final_url, getattr(response, "status", None), len(body), int((time.monotonic()-started)*1000), "Response vượt 4 MiB")
            text = body.decode(response.headers.get_content_charset() or "utf-8", errors="replace")
            lowered = re.sub(r"\s+", " ", text.lower())
            if any(marker in lowered for marker in CHALLENGE_MARKERS):
                status, detail = "DEGRADED", "Trang yêu cầu xác minh trình duyệt/CAPTCHA"
            elif not any(marker in lowered for marker in STORY_MARKERS):
                status, detail = "DEGRADED", "Không thấy dấu hiệu trang truyện"
            else:
                status, detail = "PASS", "HTTPS và nội dung trang có vẻ hợp lệ"
            return Result(source, status, url, final_url, getattr(response, "status", None), len(body), int((time.monotonic()-started)*1000), detail)
    except urllib.error.HTTPError as error:
        status = "DEGRADED" if error.code in {401, 403, 429, 503} else "FAIL"
        return Result(source, status, url, error.geturl(), error.code, elapsed_ms=int((time.monotonic()-started)*1000), detail=f"HTTP {error.code}")
    except (urllib.error.URLError, socket.timeout, ssl.SSLError, OSError) as error:
        return Result(source, "FAIL", url, elapsed_ms=int((time.monotonic()-started)*1000), detail=str(error))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", action="append", choices=sorted(SOURCES), help="Có thể lặp lại; mặc định là tất cả")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--strict", action="store_true", help="FAIL/DEGRADED làm lệnh trả mã lỗi")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()
    if args.list:
        for source, (url, _) in SOURCES.items():
            print(f"{source}\t{url}")
        return 0
    selected = args.source or list(SOURCES)
    results = [check_source(source, args.timeout) for source in selected]
    payload = {"schema": 1, "generatedAtEpochMs": int(time.time()*1000), "results": [asdict(item) for item in results]}
    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    if args.strict and any(item.status != "PASS" for item in results):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
