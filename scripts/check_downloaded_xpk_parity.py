#!/usr/bin/env python3
from pathlib import Path

library = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt").read_text()
actions = Path("app/src/main/java/vn/nghetruyen/app/ui/DownloadedLibraryActions.kt").read_text()
app = Path("app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt").read_text()
reference = Path("app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt").read_text()

start = library.index("private fun DownloadedSection(")
end = library.index("private fun StoryEntityList", start)
downloaded = library[start:end]

for marker in [
    '"TÙY CHỌN"',
    '"CẬP NHẬT / TẢI TIẾP"',
    '"XÓA DỮ LIỆU ĐÃ TẢI"',
    'onUpdateDownloadedStory(story)',
    '.clickable { onStoryClick(story) }',
    '"Chạm để mở danh sách chương đã tải"',
]:
    assert marker in downloaded, f"Downloaded XPK flow missing: {marker}"
assert "XÓA BẢN NGOẠI TUYẾN" not in downloaded, "Legacy direct-delete button returned"

for marker in [
    "fun AppViewModel.openDownloadedStoryFromLibrary(entity: StoryEntity)",
    'setStoryDetailTab("chapters")',
    "fun AppViewModel.updateDownloadedStoryFromLibrary(entity: StoryEntity)",
    "StoryDownloadPlanner().collectChapters(source, detail)",
    ".filter { it.downloadedAt != null && !it.content.isNullOrBlank() }",
    "selectionMode = DownloadSelectionMode.RANGE",
    "startChapterIndex = nextIndex",
    "endChapterIndex = chapters.lastIndex",
    "container.downloadScheduler.resume(request)",
]:
    assert marker in actions, f"Downloaded action parity missing: {marker}"

for source_name, source in [("app", app), ("reference", reference)]:
    assert "onStoryClick = viewModel::openDownloadedStoryFromLibrary" in source, f"{source_name} missing downloaded open wiring"
    assert "onUpdateDownloadedStory = viewModel::updateDownloadedStoryFromLibrary" in source, f"{source_name} missing downloaded update wiring"

print("DOWNLOADED_XPK_PARITY=PASS")
