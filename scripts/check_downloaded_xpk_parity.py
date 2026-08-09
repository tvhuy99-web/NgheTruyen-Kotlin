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
    '"CẬP NHẬT / TẢI TIẾP"',
    '"XÓA DỮ LIỆU ĐÃ TẢI"',
    'onUpdateDownloadedStory(story)',
    'DownloadedLibraryCallbacks.chapters(app, story)',
    '"CHƯƠNG: ${story.title.ifBlank { "Truyện" }}',
    '"TÌM CHƯƠNG ĐÃ TẢI"',
    '"Nhập tên, số chương hoặc vài ký tự liên quan"',
    '"HIỆN TẤT CẢ"',
    '"Không tìm thấy chương phù hợp với “${chapterQuery.trim()}”."',
    'DownloadedLibraryCallbacks.selectChapter(chapter)',
    'onStoryClick(story)',
    '"Chạm để mở danh sách chương đã tải"',
]:
    assert marker in downloaded, f"Downloaded XPK flow missing: {marker}"
assert "XÓA BẢN NGOẠI TUYẾN" not in downloaded, "Legacy direct-delete button returned"

for marker in [
    "object DownloadedLibraryCallbacks",
    "suspend fun chapters(app: NgheTruyenApplication, story: StoryEntity)",
    "fun selectChapter(chapter: ChapterEntity)",
    "consumeSelectedChapter(storyId: String)",
    "viewModel.openDownloadedStoryFromLibrary(story)",
    "viewModel.updateDownloadedStoryFromLibrary(story)",
    "fun AppViewModel.openDownloadedStoryFromLibrary(entity: StoryEntity)",
    "openChapter(",
    "ChapterSummary(",
    "fun AppViewModel.updateDownloadedStoryFromLibrary(entity: StoryEntity)",
    "StoryDownloadPlanner().collectChapters(source, detail)",
    ".filter { it.downloadedAt != null && !it.content.isNullOrBlank() }",
    "selectionMode = DownloadSelectionMode.RANGE",
    "startChapterIndex = nextIndex",
    "endChapterIndex = chapters.lastIndex",
    "container.downloadScheduler.resume(request)",
]:
    assert marker in actions, f"Downloaded action parity missing: {marker}"

assert "onStoryClick = { story -> DownloadedLibraryCallbacks.open(viewModel, story) }" in app, "app missing downloaded open adapter wiring"
assert "onUpdateDownloadedStory = { story -> DownloadedLibraryCallbacks.update(viewModel, story) }" in app, "app missing downloaded update adapter wiring"
assert "onStoryClick = viewModel::openDownloadedStoryFromLibrary" in reference, "reference missing direct downloaded open wiring"
assert "onUpdateDownloadedStory = viewModel::updateDownloadedStoryFromLibrary" in reference, "reference missing direct downloaded update wiring"

print("DOWNLOADED_XPK_PARITY=PASS")
