#!/usr/bin/env python3
"""Offline coverage gate for Priority 1 source fidelity.

This gate distinguishes rich built-in adapters from compatibility SourcePacks,
checks that every integrated website has parser fixtures, and prevents a small
selector pack from claiming precedence without the required parity metadata.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SOURCES = {
    "truyenfull": ("TruyenFullSource.kt", "TruyenFullSourceTest.kt", "truyenfull", 10),
    "truyencv": ("TruyenCvSource.kt", "TruyenCvSourceTest.kt", "truyencv", 8),
    "truyencom": ("TruyenComSource.kt", "TruyenComSourceTest.kt", "truyencom", 10),
    "truyenyy": ("TruyenYySource.kt", "TruyenYySourceTest.kt", "truyenyy", 8),
    "wikidich": ("WikidichSource.kt", "WikidichSourceTest.kt", "wikidich", 10),
    "sangtacviet": ("SangTacVietSource.kt", "SangTacVietSourceTest.kt", "sangtacviet", 6),
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    source_dir = ROOT / "app/src/main/java/vn/nghetruyen/app/sources"
    test_dir = ROOT / "app/src/test/java/vn/nghetruyen/app/sources"
    resource_root = ROOT / "app/src/test/resources"

    for source_id, (source_name, test_name, fixture_dir, minimum_categories) in SOURCES.items():
        source_path = source_dir / source_name
        test_path = test_dir / test_name
        fixture_path = resource_root / fixture_dir
        require(source_path.is_file(), f"Thiếu adapter Kotlin {source_id}: {source_path}")
        require(test_path.is_file(), f"Thiếu unit test {source_id}: {test_path}")
        require(fixture_path.is_dir(), f"Thiếu fixture parser {source_id}: {fixture_path}")

        source = source_path.read_text(encoding="utf-8")
        test = test_path.read_text(encoding="utf-8")
        require("override suspend fun home(page: Int)" in source, f"{source_id} chưa có route home tường minh")
        require("override suspend fun chapterPage" in source, f"{source_id} chưa có phân trang TOC thật")
        require("override suspend fun latestChapter" in source, f"{source_id} chưa có latestChapter riêng")
        require(test.count("@Test") >= 4, f"{source_id} có quá ít unit test")
        require(len([p for p in fixture_path.rglob("*") if p.is_file()]) >= 3, f"{source_id} có quá ít fixture")

        category_block = source.split("CATEGORY_URLS = linkedMapOf(", 1)
        require(len(category_block) == 2, f"{source_id} thiếu CATEGORY_URLS")
        category_count = category_block[1].split(")", 1)[0].count(" to ")
        require(category_count >= minimum_categories, f"{source_id} chỉ có {category_count} category, cần >= {minimum_categories}")

        info_path = ROOT / "examples/sourcepacks" / source_id / "data/source-info.json"
        info = json.loads(info_path.read_text(encoding="utf-8"))
        require(info.get("legacyId") == source_id, f"{source_id} SourcePack alias sai")
        require(info.get("compatibilityTier") == "FULL_BUILTIN_BRIDGE", f"{source_id} pack chưa bật hybrid full-fidelity")
        require(info.get("preferSourcePack") is True, f"{source_id} pack chưa được ưu tiên sau khi gắn adapter")
        require(info.get("delegateBuiltInId") == source_id, f"{source_id} bridge trỏ sai adapter")
        require(int(info.get("selectionPriority", 0)) > 100, f"{source_id} hybrid pack chưa thắng adapter sau khi attach")
        require(len(info.get("categories", [])) >= minimum_categories, f"{source_id} source-info thiếu danh mục đầy đủ")

    wattpad_info = json.loads((ROOT / "examples/sourcepacks/wattpad/data/source-info.json").read_text(encoding="utf-8"))
    wattpad_manifest = json.loads((ROOT / "examples/sourcepacks/wattpad/source.json").read_text(encoding="utf-8"))
    require(wattpad_info.get("compatibilityTier") == "FULL", "Wattpad chưa được chứng nhận full parity")
    require(wattpad_info.get("preferSourcePack") is True, "Wattpad chưa được ưu tiên")
    require(int(wattpad_info.get("selectionPriority", 0)) > 100, "Wattpad full parity phải thắng placeholder")
    required_actions = {"home", "genre", "search", "suggestions", "detail", "latest_chapter", "toc", "tocPages", "chapter"}
    require(required_actions <= set(wattpad_manifest.get("actions", {})), "Wattpad thiếu action full parity")
    fixture_actions = {item["action"] for item in wattpad_manifest.get("fixtures", [])}
    require({"HOME", "GENRE", "SEARCH", "SUGGESTIONS", "DETAIL", "LATEST_CHAPTER", "TOC", "TOC_PAGES", "CHAPTER"} <= fixture_actions, "Wattpad thiếu fixture action")

    registry = (source_dir / "SourceRegistry.kt").read_text(encoding="utf-8")
    require("candidate.selectionPriority > current.selectionPriority" in registry, "Registry chưa chọn implementation theo parity")
    pack_runtime = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt").read_text(encoding="utf-8")
    require("isFullParityCertified" in pack_runtime, "Pack có thể tự nhận full parity mà không có fixture")
    require("FULL_PARITY_REQUIRED_FIXTURES" in pack_runtime, "Thiếu chuẩn fixture cho full parity")
    require("BuiltInSourcePackBridge" in registry and "attachBuiltInDelegate" in registry, "Registry chưa gắn hybrid SourcePack vào adapter")

    view_model = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text(encoding="utf-8")
    require("mergeExploreStories" in view_model, "UI chưa bảo toàn thứ tự trang home/category")
    require("mode != ExploreMode.SEARCH" in view_model, "Home/category vẫn có nguy cơ bị sort lại")
    require("source.home(nextPage)" in view_model, "Load-more home chưa gọi route home")

    health = (source_dir / "SourceHealthChecker.kt").read_text(encoding="utf-8")
    require('runStep("Trang chủ / danh sách"' in health, "Health check chưa kiểm tra home")
    require('runStep("Gợi ý tìm kiếm"' in health, "Health check chưa kiểm tra suggestions")
    require("detail.chapters.size" in health, "Health check TOC chưa nối startIndex thật")

    print("PRIORITY1_SOURCE_COVERAGE_OK")


if __name__ == "__main__":
    main()
