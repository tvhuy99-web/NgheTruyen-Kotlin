#!/usr/bin/env python3
from pathlib import Path

def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if new and new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 occurrence, found {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

def remove_once(path: str, old: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        return
    if text.count(old) != 1:
        raise SystemExit(f"{path}: ambiguous remove: {old[:160]!r}")
    p.write_text(text.replace(old, "", 1), encoding="utf-8")

settings = "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt"
vm = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
story = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
app = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"

# Persist chapter sort preference like the reference tool.
replace_once(
    settings,
    "    val readerMode: ReaderMode = ReaderMode.TEXT,\n    val readerDisplay: ReaderDisplaySettings = ReaderDisplaySettings(),\n",
    "    val readerMode: ReaderMode = ReaderMode.TEXT,\n    val chapterSortDescending: Boolean = false,\n    val readerDisplay: ReaderDisplaySettings = ReaderDisplaySettings(),\n",
)
replace_once(
    settings,
    '        val readerMode = stringPreferencesKey("reader_mode")\n        val readerTheme = stringPreferencesKey("reader_theme")\n',
    '        val readerMode = stringPreferencesKey("reader_mode")\n        val chapterSortDescending = booleanPreferencesKey("chapter_sort_desc")\n        val readerTheme = stringPreferencesKey("reader_theme")\n',
)
replace_once(
    settings,
    '''            readerMode = runCatching { ReaderMode.valueOf(prefs[Keys.readerMode] ?: ReaderMode.TEXT.name) }
                .getOrDefault(ReaderMode.TEXT),
            readerDisplay = ReaderDisplaySettings(
''',
    '''            readerMode = runCatching { ReaderMode.valueOf(prefs[Keys.readerMode] ?: ReaderMode.TEXT.name) }
                .getOrDefault(ReaderMode.TEXT),
            chapterSortDescending = prefs[Keys.chapterSortDescending] ?: false,
            readerDisplay = ReaderDisplaySettings(
''',
)
replace_once(
    settings,
    '''    suspend fun setReaderMode(value: ReaderMode) {
        context.dataStore.edit { it[Keys.readerMode] = value.name }
    }
    suspend fun setReaderTheme(value: ReaderThemeMode) {
''',
    '''    suspend fun setReaderMode(value: ReaderMode) {
        context.dataStore.edit { it[Keys.readerMode] = value.name }
    }
    suspend fun setChapterSortDescending(value: Boolean) {
        context.dataStore.edit { it[Keys.chapterSortDescending] = value }
    }
    suspend fun setReaderTheme(value: ReaderThemeMode) {
''',
)

replace_once(
    vm,
    "    val readerMode: ReaderMode = ReaderMode.TEXT,\n    val readerDisplay: ReaderDisplaySettings = ReaderDisplaySettings(),\n",
    "    val readerMode: ReaderMode = ReaderMode.TEXT,\n    val chapterSortDescending: Boolean = false,\n    val readerDisplay: ReaderDisplaySettings = ReaderDisplaySettings(),\n",
)
replace_once(
    vm,
    "                        readerMode = settings.readerMode,\n                        readerDisplay = settings.readerDisplay,\n",
    "                        readerMode = settings.readerMode,\n                        chapterSortDescending = settings.chapterSortDescending,\n                        readerDisplay = settings.readerDisplay,\n",
)
replace_once(
    vm,
    '''    fun setReaderMode(mode: ReaderMode) {
        if (mode == ReaderMode.TEXT) {
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
        }
        mutableState.update { it.copy(readerMode = mode) }
        viewModelScope.launch { container.settingsRepository.setReaderMode(mode) }
        showMessage(if (mode == ReaderMode.TTS) "Đã chuyển sang chế độ TTS." else "Đã chuyển sang chế độ Văn bản.")
    }

    fun saveReadingPositionNow() {
''',
    '''    fun setReaderMode(mode: ReaderMode) {
        if (mode == ReaderMode.TEXT) {
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
        }
        mutableState.update { it.copy(readerMode = mode) }
        viewModelScope.launch { container.settingsRepository.setReaderMode(mode) }
        showMessage(if (mode == ReaderMode.TTS) "Đã chuyển sang chế độ TTS." else "Đã chuyển sang chế độ Văn bản.")
    }

    fun setChapterSortDescending(descending: Boolean) {
        mutableState.update { it.copy(chapterSortDescending = descending) }
        viewModelScope.launch { container.settingsRepository.setChapterSortDescending(descending) }
    }

    fun saveReadingPositionNow() {
''',
)

replace_once(
    story,
    "import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\n",
    "import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.rememberLazyListState\n",
)
replace_once(
    story,
    "    onGenreSelected: (String) -> Unit,\n    onTabSelected: (String) -> Unit,\n",
    "    onGenreSelected: (String) -> Unit,\n    onTabSelected: (String) -> Unit,\n    onChapterSortDescendingChange: (Boolean) -> Unit,\n",
)
replace_once(
    story,
    "    onLoadMoreComments: () -> Unit,\n    onOpenOriginal: (String) -> Unit,\n",
    "    onLoadMoreComments: () -> Unit,\n    onOpenOriginal: (String) -> Unit,\n    onCheckSource: (String) -> Unit,\n",
)
replace_once(
    story,
    '    var chapterSortDescending by remember(detail.story.id) { mutableStateOf(false) }\n',
    '    val chapterSortDescending = state.chapterSortDescending\n',
)
replace_once(
    story,
    '''    val visibleChapters = remember(filteredChapters, chapterSortDescending) {
        if (chapterSortDescending) filteredChapters.sortedByDescending { it.index } else filteredChapters.sortedBy { it.index }
    }
    val tabs = buildList {
''',
    '''    val visibleChapters = remember(filteredChapters, chapterSortDescending) {
        if (chapterSortDescending) filteredChapters.sortedByDescending { it.index } else filteredChapters.sortedBy { it.index }
    }
    val chapterListState = rememberLazyListState()
    val tabs = buildList {
''',
)
replace_once(
    story,
    '''    val currentChapterIndex = detail.chapters.firstOrNull { it.id == state.playback.chapterId }?.index
    LaunchedEffect(state.storyAdvancedOptionsRequested) {
''',
    '''    val currentChapterIndex = detail.chapters.firstOrNull { it.id == state.playback.chapterId }?.index
    val sourceDescriptor = state.sources.firstOrNull { it.id == detail.story.sourceId }
    LaunchedEffect(selectedTab, visibleChapters, state.playback.chapterId, chapterSortDescending) {
        if (selectedTab == "chapters" && state.playback.chapterId.isNotBlank()) {
            val targetIndex = visibleChapters.indexOfFirst { it.id == state.playback.chapterId }
            if (targetIndex >= 0) {
                delay(100)
                chapterListState.scrollToItem(targetIndex)
            }
        }
    }
    LaunchedEffect(state.storyAdvancedOptionsRequested) {
''',
)
replace_once(
    story,
    '                text = if (state.continueAvailable) "ĐỌC TIẾP" else "ĐỌC TỪ ĐẦU",\n',
    '                text = if (state.continueAvailable) "ĐỌC TIẾP" else "ĐỌC NGAY",\n',
)
replace_once(
    story,
    '                accessibilityLabel = if (state.continueAvailable) "Đọc tiếp truyện" else "Đọc từ đầu truyện",\n',
    '                accessibilityLabel = if (state.continueAvailable) "Đọc tiếp truyện" else "Đọc ngay truyện",\n',
)
replace_once(
    story,
    '                    onClick = { chapterSortDescending = !chapterSortDescending },\n',
    '                    onClick = { onChapterSortDescendingChange(!chapterSortDescending) },\n',
)
replace_once(
    story,
    '''                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleChapters, key = { it.id }) { chapter ->
''',
    '''                LazyColumn(state = chapterListState, modifier = Modifier.fillMaxSize()) {
                    items(visibleChapters, key = { it.id }) { chapter ->
''',
)
old_source = '''            "source" -> Column(modifier = Modifier.padding(16.dp)) {
                Text("Nguồn: ${detail.story.sourceId}")
                Text("Địa chỉ: ${detail.story.url}")
                ReferenceActionButton(
                    text = "CẤU HÌNH GIỌNG & AI",
                    onClick = { showAdvancedOptions = true },
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Mở cấu hình giọng đọc và AI nâng cao",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (detail.story.url.startsWith("https://")) {
                    Button(onClick = { onOpenOriginal(detail.story.url) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text("MỞ TRANG GỐC")
                    }
                }
                if (!detail.commentsUrl.isNullOrBlank()) {
                    Button(onClick = { onOpenOriginal(detail.commentsUrl) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("MỞ TRANG BÌNH LUẬN GỐC")
                    }
                }
                if (!state.storyCommentsAvailable) {
                    Text(
                        "Nguồn này chưa khai báo khả năng lấy bình luận trong ứng dụng.",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
'''
new_source = '''            "source" -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    "NGUỒN",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
                Text("Tên: ${sourceDescriptor?.displayName ?: detail.story.sourceId}")
                Text("ID: ${detail.story.sourceId}", modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Website: ${sourceDescriptor?.baseUrl ?: detail.story.url}",
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Truyện gốc: ${detail.story.url.ifBlank { "Không có địa chỉ gốc" }}",
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                ReferenceActionButton(
                    text = "MỞ TRANG GỐC",
                    onClick = { if (detail.story.url.startsWith("https://")) onOpenOriginal(detail.story.url) },
                    enabled = detail.story.url.startsWith("https://"),
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Mở trang gốc của truyện",
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                ReferenceActionButton(
                    text = if (detail.story.sourceId in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA",
                    onClick = { onCheckSource(detail.story.sourceId) },
                    enabled = detail.story.sourceId !in state.sourceHealthChecking,
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Kiểm tra nguồn ${sourceDescriptor?.displayName ?: detail.story.sourceId}",
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
'''
replace_once(story, old_source, new_source)

remove_once(
    reader,
    '                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") { showReaderOptions = false; showExtrasDialog = true }\n',
)
remove_once(
    reader,
    '                    ReaderMenuButton("TIỆN ÍCH BỔ SUNG") { showReaderOptions = false; showExtrasDialog = true }\n',
)

replace_once(
    app,
    "                    onTabSelected = viewModel::setStoryDetailTab,\n                    onConsumeAdvancedOptionsRequest = viewModel::consumeStoryAdvancedOptionsRequest,\n",
    "                    onTabSelected = viewModel::setStoryDetailTab,\n                    onChapterSortDescendingChange = viewModel::setChapterSortDescending,\n                    onConsumeAdvancedOptionsRequest = viewModel::consumeStoryAdvancedOptionsRequest,\n",
)
replace_once(
    app,
    "                    onLoadMoreComments = viewModel::loadMoreStoryComments,\n                    onOpenOriginal = viewModel::openExternalUrl,\n",
    "                    onLoadMoreComments = viewModel::loadMoreStoryComments,\n                    onOpenOriginal = viewModel::openExternalUrl,\n                    onCheckSource = viewModel::checkSource,\n",
)

print("REFERENCE_PARITY_V41_APPLIED")