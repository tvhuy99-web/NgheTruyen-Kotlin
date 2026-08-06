#!/usr/bin/env python3
"""Lightweight TruyenCV fixture gate without Android or Gradle."""
from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "app/src/test/resources/truyencv"
ALLOWED = {"truyencv.io", "www.truyencv.io"}

STORY_SELECTOR = (
    "h1 a[href*='/truyen/'], h2 a[href*='/truyen/'], h3 a[href*='/truyen/'], "
    "h4 a[href*='/truyen/'], .post-title a[href*='/truyen/'], "
    ".manga-title a[href*='/truyen/'], .item-title a[href*='/truyen/']"
)
CHAPTER_SELECTOR = "#chapter-list a[href*='/chuong-'], .chapter-list a[href*='/chuong-']"


def soup(name: str) -> BeautifulSoup:
    return BeautifulSoup((FIXTURES / name).read_text(encoding="utf-8"), "html.parser")


def absolute(base: str, href: str) -> str:
    target = urljoin(base, href)
    parsed = urlparse(target)
    assert parsed.scheme == "https", target
    assert parsed.hostname in ALLOWED, target
    return target


def main() -> None:
    listing = soup("list.html")
    links = listing.select(STORY_SELECTOR)
    items: list[tuple[str, str]] = []
    seen: set[str] = set()
    for link in links:
        url = absolute("https://truyencv.io/moi-cap-nhat/", link.get("href", ""))
        title = " ".join(link.get_text(" ", strip=True).split())
        if title and url not in seen:
            seen.add(url)
            items.append((title, url))
    assert items == [
        ("Uyên Thiên Tôn", "https://truyencv.io/truyen/uyen-thien-ton/"),
        ("Phi Thiên", "https://truyencv.io/truyen/phi-thien/"),
    ]

    detail = soup("detail.html")
    assert detail.select_one("h1").get_text(strip=True) == "Uyên Thiên Tôn"
    pages = [
        int(match.group(1))
        for link in detail.select("a[href*='/chuong/page/']")
        if (match := re.search(r"/chuong/page/(\d+)/", link.get("href", "")))
    ]
    assert max(pages) == 3

    chapter_page = soup("chapter-page-3.html")
    chapters = [
        (" ".join(link.get_text(" ", strip=True).split()), absolute(
            "https://truyencv.io/truyen/uyen-thien-ton/chuong/page/3/",
            link.get("href", ""),
        ))
        for link in chapter_page.select(CHAPTER_SELECTOR)
    ]
    chapters.reverse()
    assert "Chương 1" in chapters[0][0]
    assert "Chương 2" in chapters[1][0]

    chapter = soup("chapter.html")
    content = chapter.select_one(".chapter-content")
    assert content is not None
    for node in content.select("script, style, iframe, noscript, form, nav, .ads, .advertisement"):
        node.decompose()
    lines = [" ".join(line.split()) for line in content.get_text("\n").splitlines()]
    lines = [line for line in lines if line and "truyencv.io" not in line.lower()]
    assert lines == ["Đoạn thứ nhất.", "Đoạn thứ hai.", "Vẫn là nội dung."]

    source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenCvSource.kt").read_text(encoding="utf-8")
    for token in (
        'health = SourceHealth.DEGRADED',
        '"truyencv.io", "www.truyencv.io"',
        'findHighestChapterPage',
        'parseChapterContent',
        'SOURCE_BROWSER_VERIFICATION_REQUIRED',
    ):
        assert token in source, token

    print("TRUYENCV_FIXTURE_CHECK_OK")


if __name__ == "__main__":
    main()
