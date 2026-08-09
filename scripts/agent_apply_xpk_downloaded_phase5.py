#!/usr/bin/env python3
from pathlib import Path


def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

# ViewModel: offline opens chapter tab; update/tải tiếp starts after last downloaded chapter.
p = Path('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt')
s = p.read_text()
s = s.replace('                    storyDetailTab = "intro",\n                    storyAdvancedOptionsRequested = false,\n                    storyDetail = StoryDetail(\n', '                    storyDetailTab = "chapters",\n                    storyAdvancedOptionsRequested = false,\n                    storyDetail = StoryDetail(\n', 1)
anchor = '    fun openOfflineStory(entity: StoryEntity) {\n'
method = '''    fun updateDownloadedStory(entity: StoryEntity) {
        if (entity.sourceId == "offline" || entity.remoteUrl.isBlank()) {
            showMessage("Truyện nhập từ tệp không có nguồn trực tuyến để cập nhật.")
            return
        }
        val source = container.sourceRegistry.get(entity.sourceId)
        if (source == null) {
            showMessage("Nguồn truyện không còn khả dụng.")
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang kiểm tra chương mới…") }
            val loaded = source.story(entity.remoteUrl)
            if (loaded is AppResult.Failure) {
                mutableState.update { it.copy(loading = false, message = loaded.message) }
                return@launch
            }
            val detail = (loaded as AppResult.Success).value
            val chapters = if (detail.nextChapterPageUrl != null) {
                when (val all = StoryDownloadPlanner().collectChapters(source, detail)) {
                    is AppResult.Success -> all.value
                    is AppResult.Failure -> {
                        mutableState.update { it.copy(loading = false, message = all.message) }
                        return@launch
                    }
                }
            } else detail.chapters
            val lastDownloadedIndex = container.libraryRepository.listOfflineChapters(entity.id)
                .maxOfOrNull { it.chapterIndex } ?: -1
            val latestIndex = chapters.maxOfOrNull { it.index } ?: -1
            if (latestIndex <= lastDownloadedIndex) {
                mutableState.update { it.copy(loading = false, message = "Không có chương mới để tải tiếp.") }
                return@launch
            }
            val firstNewIndex = lastDownloadedIndex + 1
            val count = latestIndex - firstNewIndex + 1
            val request = DownloadRequest.create(
                sourceId = entity.sourceId,
                storyId = entity.id,
                selectionMode = DownloadSelectionMode.RANGE,
                startChapterIndex = firstNewIndex,
                endChapterIndex = latestIndex,
            )
            mutableState.update { it.copy(loading = false) }
            queueDownload(
                request = request,
                estimatedTotal = count,
                successMessage = "Đã thêm $count chương mới vào hàng đợi tải tiếp.",
            )
        }
    }

'''
s = rep(s, anchor, method + anchor, 'downloaded update viewmodel')
p.write_text(s)

# Library screen signature + real jobs/failures + secondary action menu.
p = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt')
s = p.read_text()
s = rep(s, '    onRemoveOffline: (String) -> Unit,\n', '    onRemoveOffline: (String) -> Unit,\n    onUpdateDownloaded: (StoryEntity) -> Unit,\n', 'library downloaded callback')
s = rep(s, '                    jobs = emptyList(),\n                    failures = emptyList(),\n', '                    jobs = state.downloadJobs,\n                    failures = state.downloadFailures,\n', 'real downloaded jobs')
s = rep(s, '                    onRemoveOffline = onRemoveOffline,\n', '                    onRemoveOffline = onRemoveOffline,\n                    onUpdateDownloaded = onUpdateDownloaded,\n', 'downloaded callback pass')
s = rep(s, '    onRemoveOffline: (String) -> Unit,\n) {\n    var pendingRemoval by remember { mutableStateOf<StoryEntity?>(null) }\n', '    onRemoveOffline: (String) -> Unit,\n    onUpdateDownloaded: (StoryEntity) -> Unit,\n) {\n    var pendingRemoval by remember { mutableStateOf<StoryEntity?>(null) }\n    var selectedActions by remember { mutableStateOf<StoryEntity?>(null) }\n', 'downloaded section signature')

# Insert action dialog before removal dialog.
marker = '    pendingRemoval?.let { story ->\n'
actions = '''    selectedActions?.let { story ->
        AlertDialog(
            onDismissRequest = { selectedActions = null },
            title = { Text(story.title) },
            text = { Column {
                ReferenceActionButton(
                    "MỞ DANH SÁCH CHƯƠNG ĐÃ TẢI",
                    { selectedActions = null; onStoryClick(story) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (story.sourceId != "offline" && story.remoteUrl.isNotBlank()) {
                    ReferenceActionButton(
                        "CẬP NHẬT / TẢI TIẾP",
                        { selectedActions = null; onUpdateDownloaded(story) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
                ReferenceActionButton(
                    "XÓA DỮ LIỆU ĐÃ TẢI",
                    { selectedActions = null; pendingRemoval = story },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            } },
            confirmButton = { TextButton(onClick = { selectedActions = null }) { Text("ĐÓNG") } },
        )
    }
'''
s = rep(s, marker, actions + marker, 'downloaded actions dialog')

# XPK language in delete confirmation and card secondary action.
s = s.replace('title = { Text("Xóa bản ngoại tuyến?") }', 'title = { Text("XÓA DỮ LIỆU ĐÃ TẢI") }', 1)
s = s.replace('"Nội dung chương đã tải của ${story.title} sẽ bị xóa; lịch sử đọc vẫn được giữ."', '"Toàn bộ dữ liệu chương đã tải của ${story.title} sẽ bị xóa. Tiến độ đọc, dấu trang và lịch sử đọc vẫn được giữ."', 1)
s = s.replace('Text("XÓA BẢN NGOẠI TUYẾN")', 'Text("TÙY CHỌN")', 1)
s = s.replace('Button(onClick = { pendingRemoval = story }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {\n                        Text("TÙY CHỌN")\n                    }', 'Button(onClick = { selectedActions = story }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {\n                        Text("TÙY CHỌN")\n                    }', 1)
p.write_text(s)

# Primary + reference app wiring.
for path in ['app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt', 'app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt']:
    p = Path(path)
    s = p.read_text()
    s = rep(s, '                        onRemoveOffline = viewModel::removeOfflineStory,\n', '                        onRemoveOffline = viewModel::removeOfflineStory,\n                        onUpdateDownloaded = viewModel::updateDownloadedStory,\n', f'{path} downloaded wiring')
    p.write_text(s)

Path('scripts/check_downloaded_library_parity.py').write_text('''#!/usr/bin/env python3
from pathlib import Path
vm = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text()
ui = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt").read_text()
for token in ["fun updateDownloadedStory(entity: StoryEntity)", "lastDownloadedIndex + 1", "selectionMode = DownloadSelectionMode.RANGE", "startChapterIndex = firstNewIndex", "endChapterIndex = latestIndex", 'storyDetailTab = "chapters"']:
    if token not in vm: raise SystemExit("DOWNLOADED_PARITY VM missing: " + token)
for token in ["MỞ DANH SÁCH CHƯƠNG ĐÃ TẢI", "CẬP NHẬT / TẢI TIẾP", "XÓA DỮ LIỆU ĐÃ TẢI", "state.downloadJobs", "state.downloadFailures", "Tiến độ đọc, dấu trang và lịch sử đọc vẫn được giữ"]:
    if token not in ui: raise SystemExit("DOWNLOADED_PARITY UI missing: " + token)
print("DOWNLOADED_LIBRARY_PARITY=PASS")
''')

p = Path('scripts/m0_gate.sh')
s = p.read_text()
marker = '  scripts/check_music_normalization_flow_parity.py\n'
if marker not in s:
    marker = '  scripts/check_music_playback_parity.py\n'
if 'check_downloaded_library_parity.py' not in s:
    s = rep(s, marker, marker + '  scripts/check_downloaded_library_parity.py\n', 'm0 downloaded gate')
p.write_text(s)
