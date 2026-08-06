#!/usr/bin/env python3
"""Lightweight TruyenYY Markdown fixture gate without Android or Gradle."""
from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "app/src/test/resources/truyenyy"
STORY = re.compile(r"^#{2,4}\s+\[([^]\n]+)]\((https?://(?:www\.)?truyenyy\.co/truyen/[^)\s]+)\)", re.M)
CHAPTER = re.compile(r"^\s*\*\s+\[([^]\n]+)]\((https?://(?:www\.)?truyenyy\.co/truyen/[^/\s]+/[^)\s]+)\)", re.M)


def read(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def main() -> None:
    required = {"list.md", "detail.md", "toc-1.md", "toc-2.md", "chapter.md"}
    present = {path.name for path in FIXTURES.glob("*.md")}
    assert required <= present, f"Thiếu fixture: {sorted(required - present)}"

    stories = STORY.findall(read("list.md"))
    assert [name for name, _ in stories] == ["Kiếm Đạo Độc Tôn", "Thư Viện Thiên Đạo"]
    assert all(urlparse(url).hostname in {"truyenyy.co", "www.truyenyy.co"} for _, url in stories)

    detail = read("detail.md")
    assert re.search(r"^#\s+Kiếm Đạo Độc Tôn$", detail, re.M)
    assert "Tác giả: Kiếm Du Thái Hư" in detail
    assert "## Giới Thiệu Truyện" in detail

    first = CHAPTER.findall(read("toc-1.md"))
    second = CHAPTER.findall(read("toc-2.md"))
    assert len(first) == 3 and len(second) == 3
    assert "Truyện có 103 chương" in read("toc-1.md")
    assert second[-1][0].startswith("103 ")

    chapter = read("chapter.md")
    assert "Phiên bản 2000 chữ" in chapter
    assert "[Trước](https://truyenyy.co/" in chapter
    assert "[Tiếp](https://truyenyy.co/" in chapter

    source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenYySource.kt").read_text(encoding="utf-8")
    for token in (
        "health = SourceHealth.DEGRADED",
        'JINA_HOST = "r.jina.ai"',
        "X-Max-Tokens",
        "parseChapterList",
        "SOURCE_BROWSER_VERIFICATION_REQUIRED",
        "CHAPTER_PAGE_SIZE = 100",
    ):
        assert token in source, token

    registry = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt").read_text(encoding="utf-8")
    assert "TruyenYySource()" in registry
    assert 'NotPortedSource("truyenyy"' not in registry

    print("TRUYENYY_FIXTURE_CHECK_OK")


if __name__ == "__main__":
    main()
