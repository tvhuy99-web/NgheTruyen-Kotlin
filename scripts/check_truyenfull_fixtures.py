#!/usr/bin/env python3
"""Fast offline contract checks for the Truyện Full HTML fixtures.

This does not replace the Kotlin/JUnit parser tests. It gives contributors a
zero-network smoke test that catches accidental fixture or selector drift even
when an Android SDK is unavailable.
"""

from __future__ import annotations

from pathlib import Path
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "app" / "src" / "test" / "resources" / "truyenfull"
BASE = "https://truyenfull.live"
ALLOWED_HOSTS = {
    "truyenfull.live",
    "truyenfull.today",
    "truyenfull.vn",
    "truyenfull.net",
    "truyenfull.io",
    "truyenfull.bio",
    "truyenfull.vision",
}


def soup(name: str) -> BeautifulSoup:
    return BeautifulSoup((FIXTURES / name).read_text(encoding="utf-8"), "html.parser")


def absolute(base_url: str, href: str) -> str:
    url = urljoin(base_url, href)
    parsed = urlparse(url)
    assert parsed.scheme == "https", url
    assert parsed.hostname in ALLOWED_HOSTS, url
    return url


def check_list() -> None:
    doc = soup("list.html")
    rows = doc.select(".list-truyen div[itemscope]")
    assert len(rows) == 2
    first = rows[0].select_one(".truyen-title > a")
    assert first is not None
    assert first.get_text(" ", strip=True) == "Thần Đạo Đan Tôn"
    assert absolute(f"{BASE}/danh-sach/truyen-moi/", first["href"]) == f"{BASE}/than-dao-dan-ton/"


def check_detail_pages() -> None:
    first = soup("detail-page-1.html")
    assert first.select_one("h3.title").get_text(" ", strip=True) == "Thần Đạo Đan Tôn"
    assert [node.get_text(" ", strip=True) for node in first.select(".info a[itemprop=genre]")] == [
        "Tiên Hiệp",
        "Huyền Huyễn",
    ]
    chapter_links = first.select("#list-chapter li a, ul.list-chapter li a, .list-chapter li a")
    chapter_links = [a for a in chapter_links if "chương" in a.get_text(" ", strip=True).lower()]
    assert len(chapter_links) == 2
    next_page = first.select_one(".pagination li.active + li a")
    assert next_page is not None
    assert absolute(f"{BASE}/than-dao-dan-ton/", next_page["href"]) == f"{BASE}/than-dao-dan-ton/trang-2/"

    second = soup("detail-page-2.html")
    chapter_links = second.select("#list-chapter li a, ul.list-chapter li a, .list-chapter li a")
    chapter_titles = [a.get_text(" ", strip=True) for a in chapter_links if "chương" in a.get_text(" ", strip=True).lower()]
    assert chapter_titles == ["Chương 3: Gặp gỡ", "Chương 4: Thử thách"]
    assert second.select_one(".pagination li.active + li a") is None


def check_chapter() -> None:
    doc = soup("chapter.html")
    content = doc.select_one("div.chapter-c")
    assert content is not None
    for node in content.select("noscript, script, iframe, style, div.ads-responsive, .ads, .advertisement, a"):
        node.decompose()
    text = content.get_text("\n", strip=True)
    assert "Quảng cáo" not in text
    assert "alert" not in text
    assert "Đoạn văn thứ nhất." in text
    assert "Đoạn văn thứ hai" in text
    assert "vẫn thuộc nội dung." in text
    assert absolute(f"{BASE}/than-dao-dan-ton/chuong-2/", doc.select_one("a#prev_chap")["href"]).endswith("/chuong-1/")
    assert absolute(f"{BASE}/than-dao-dan-ton/chuong-2/", doc.select_one("a#next_chap")["href"]).endswith("/chuong-3/")


def main() -> None:
    required = {"list.html", "detail-page-1.html", "detail-page-2.html", "chapter.html"}
    present = {path.name for path in FIXTURES.glob("*.html")}
    assert required <= present, f"Thiếu fixture: {sorted(required - present)}"
    check_list()
    check_detail_pages()
    check_chapter()
    print("TRUYENFULL_FIXTURE_CHECK_OK")


if __name__ == "__main__":
    main()
