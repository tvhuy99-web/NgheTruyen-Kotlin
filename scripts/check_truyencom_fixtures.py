#!/usr/bin/env python3
"""Lightweight Truyện Com fixture gate without Android or Gradle."""
from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "app/src/test/resources/truyencom"
ALLOWED = {"truyencom.com", "www.truyencom.com", "dtruyen.com", "www.dtruyen.com"}
STORY_PATH = re.compile(r"^/[a-z0-9-]+\.\d+/?$", re.I)


def soup(name: str) -> BeautifulSoup:
    return BeautifulSoup((FIXTURES / name).read_text(encoding="utf-8"), "html.parser")


def absolute(base: str, href: str) -> str:
    target = urljoin(base, href)
    parsed = urlparse(target)
    assert parsed.scheme == "https", target
    assert parsed.hostname in ALLOWED, target
    return target


def main() -> None:
    required = {"list.html", "detail.html", "detail-page-2.html", "chapter.html"}
    present = {path.name for path in FIXTURES.glob("*.html")}
    assert required <= present, f"Thiếu fixture: {sorted(required - present)}"

    listing = soup("list.html")
    items = []
    for link in listing.select(
        ".list-truyen .truyen-title a, .list-truyen h3 a, .list-truyen h2 a, "
        "h3.truyen-title a, .story-title a, main h3 a[href]"
    ):
        url = absolute("https://truyencom.com/truyen-moi-cap-nhat/", link.get("href", ""))
        if STORY_PATH.match(urlparse(url).path):
            items.append((link.get_text(" ", strip=True), url))
    assert items == [
        ("Truyền Kiếm", "https://truyencom.com/truyen-kiem.6795/"),
        ("Cổ Chân Nhân", "https://truyencom.com/co-chan-nhan.1234/"),
    ]

    detail = soup("detail.html")
    assert detail.select_one("h1.title").get_text(" ", strip=True) == "Truyền Kiếm"
    chapters = detail.select(
        ".list-chapter a[href*='/chuong-'], ul.list-chapter a[href*='/chuong-'], "
        "#list-chapter a[href*='/chuong-']"
    )
    assert [node.get_text(" ", strip=True) for node in chapters] == [
        "Chương 1: Tàn mạch bẩm sinh",
        "Chương 2: Phi tiên",
    ]
    next_page = detail.select_one(".pagination li.active + li a")
    assert next_page is not None
    assert absolute("https://truyencom.com/truyen-kiem.6795/", next_page["href"]) == (
        "https://truyencom.com/truyen-kiem.6795/trang-2/"
    )

    second = soup("detail-page-2.html")
    assert second.select_one(".pagination li.active + li a") is None

    chapter = soup("chapter.html")
    content = chapter.select_one("#chapter-c")
    assert content is not None
    for node in content.select("script, style, iframe, noscript, .ads, .chapter-nav, .navigation"):
        node.decompose()
    lines = [" ".join(line.split()) for line in content.get_text("\n").splitlines()]
    lines = [line for line in lines if line and "truyencom" not in line.lower()]
    assert lines == [
        "Mạc Vấn bước vào kiếm cốc.",
        "Vạn kiếm đồng thời rung động.",
        "Thiếu niên ngẩng đầu.",
    ]

    source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenComSource.kt").read_text(encoding="utf-8")
    for token in (
        'health = SourceHealth.DEGRADED',
        '"truyencom.com"',
        'searchSlug',
        'parseChapterContent',
        'SOURCE_BROWSER_VERIFICATION_REQUIRED',
    ):
        assert token in source, token

    print("TRUYENCOM_FIXTURE_CHECK_OK")


if __name__ == "__main__":
    main()
