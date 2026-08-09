#!/usr/bin/env python3
from pathlib import Path


def rep(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)


# Reader: flat XPK navigation, local display draft, one reference music dialog.
p = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt")
s = p.read_text()
s = rep(s, "import vn.nghetruyen.app.core.model.TtsVoiceOption\n", "import vn.nghetruyen.app.core.model.TtsVoiceOption\nimport vn.nghetruyen.app.core.model.VoiceRoleDraft\nimport vn.nghetruyen.app.data.settings.AiProvider\n", "reader imports")
s = rep(
    s,
    "    onOpenStoryAiOptions: () -> Unit,\n    onOpenStoryVoiceCastOptions: () -> Unit,\n",
    "    onSaveVoiceRole: (VoiceRoleDraft) -> Unit,\n    onPreviewVoiceRole: (VoiceRoleDraft) -> Unit,\n    onDeleteVoiceRole: (String) -> Unit,\n    onSaveAiProfile: (String, Boolean, AiProvider, String, String, Float, Boolean, String, String, Boolean, Boolean, String, String, Boolean, Boolean, Boolean, String, Int, Int, Int) -> Unit,\n",
    "reader story callbacks",
)
s = s.replace("    var showReaderToolsDialog by remember { mutableStateOf(false) }\n", "")
s = s.replace("    var showAiToolsDialog by remember { mutableStateOf(false) }\n", "")
s = s.replace("    var musicAdvanced by remember { mutableStateOf(false) }\n", "")
s = rep(
    s,
    "    var showDisplayDialog by remember { mutableStateOf(false) }\n",
    "    var showDisplayDialog by remember { mutableStateOf(false) }\n"
    "    var storyAdvancedMode by remember(content.chapter.id) { mutableStateOf<String?>(null) }\n"
    "    var displayFontSizeDraft by remember(content.chapter.id) { mutableIntStateOf(display.fontSizeSp) }\n"
    "    var displayLineHeightDraft by remember(content.chapter.id) { mutableIntStateOf(display.lineHeightPercent) }\n"
    "    var displayDarkDraft by remember(content.chapter.id) { mutableStateOf(display.theme == ReaderThemeMode.DARK) }\n"
    "    var displayKeepScreenDraft by remember(content.chapter.id) { mutableStateOf(display.keepScreenOn) }\n",
    "reader draft state",
)
for line in (
    "    var musicCrossfadeMs by remember { mutableIntStateOf(state.sceneMusicCrossfadeMillis) }\n",
    "    var musicContinueAcrossChapters by remember { mutableStateOf(state.sceneMusicContinueAcrossChapters) }\n",
    "    var musicAvoidRepeatWindow by remember { mutableIntStateOf(state.sceneMusicAvoidRepeatWindow) }\n",
):
    s = s.replace(line, "")
s = s.replace("    var musicAttackMs by remember { mutableIntStateOf(250) }\n", "    var musicAttackMs by remember { mutableIntStateOf(1850) }\n")
s = s.replace("    var musicReleaseMs by remember { mutableIntStateOf(900) }\n", "    var musicReleaseMs by remember { mutableIntStateOf(2050) }\n")
s = rep(
    s,
    "    LaunchedEffect(showTtsDialog) {\n",
    """    LaunchedEffect(showDisplayDialog) {
        if (showDisplayDialog) {
            displayFontSizeDraft = display.fontSizeSp
            displayLineHeightDraft = display.lineHeightPercent
            displayDarkDraft = display.theme == ReaderThemeMode.DARK
            displayKeepScreenDraft = display.keepScreenOn
        }
    }

    LaunchedEffect(showTtsDialog) {
""",
    "display draft load",
)
s = s.replace(
    "            musicMode = settings.sceneMusicPlaybackMode\n",
    "            musicMode = if (settings.sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE) SceneMusicPlaybackMode.SHUFFLE else SceneMusicPlaybackMode.SEQUENTIAL\n",
)
for line in (
    "            musicCrossfadeMs = settings.sceneMusicCrossfadeMillis\n",
    "            musicContinueAcrossChapters = settings.sceneMusicContinueAcrossChapters\n",
    "            musicAvoidRepeatWindow = settings.sceneMusicAvoidRepeatWindow\n",
):
    s = s.replace(line, "")

menu_start = s.index("    if (showReaderOptions) {")
mode_start = s.index("    if (showReaderModeDialog) {", menu_start)
flat_menu = '''    if (showReaderOptions) {
        AlertDialog(
            onDismissRequest = { showReaderOptions = false },
            title = { Text("TÙY CHỌN ĐỌC") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("TRỞ LẠI DANH SÁCH CHƯƠNG") { showReaderOptions = false; onBackToChapters() }
                ReaderMenuButton("LƯU VỊ TRÍ ĐỌC") { showReaderOptions = false; onSaveReadingPosition() }
                ReaderMenuButton("TÌM TRONG CHƯƠNG") { showReaderOptions = false; searchDraft = ""; showSearchDialog = true }
                if (textMode) ReaderMenuButton("HIỂN THỊ VĂN BẢN") { showReaderOptions = false; showDisplayDialog = true }
                ReaderMenuButton("HẸN GIỜ NGỦ - ${state.sleepTimerStatus}") { showReaderOptions = false; showSleepDialog = true }
                ReaderMenuButton("NHẠC NỀN") { showReaderOptions = false; showMusicDialog = true }
                ReaderMenuButton(if (textMode) "XUẤT ÂM THANH (CẦN CHẾ ĐỘ TTS)" else "XUẤT ÂM THANH") {
                    showReaderOptions = false
                    if (textMode) onMessage("Hãy chuyển sang chế độ TTS trước khi xuất âm thanh.") else showExportDialog = true
                }
                ReaderMenuButton("CHẾ ĐỘ ĐỌC: ${if (textMode) "VĂN BẢN" else "TTS"}") { showReaderOptions = false; showReaderModeDialog = true }
                ReaderMenuButton("THIẾT LẬP AI CHO TRUYỆN NÀY") { showReaderOptions = false; storyAdvancedMode = "ai" }
                ReaderMenuButton("PHÂN VAI TTS CHO TRUYỆN NÀY") { showReaderOptions = false; storyAdvancedMode = "voice" }
                if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION || state.playback.preparationState == PlaybackPreparationState.FAILED) {
                    ReaderMenuButton("KHÔI PHỤC CHƯƠNG GỐC TRƯỚC AI") { showReaderOptions = false; onShowOriginal() }
                }
                if (state.vietPhraseRules.isNotEmpty() || state.vietPhraseDictionaryStates.isNotEmpty()) {
                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") { showReaderOptions = false; createVietPhraseDiagnostic() }
                }
                ReaderMenuButton("CÀI ĐẶT TTS") { showReaderOptions = false; showTtsDialog = true }
                ReaderMenuButton("SAO CHÉP CHƯƠNG") { showReaderOptions = false; showCopyDialog = true }
                ReaderMenuButton("THÔNG TIN CHƯƠNG") { showReaderOptions = false; showChapterInfoDialog = true }
                Text("MỞ RỘNG", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                ReaderMenuButton("ĐÁNH DẤU ĐOẠN ${activeIndex + 1}") { showReaderOptions = false; onBookmark() }
                ReaderMenuButton(if (activeNote == null) "GHI CHÚ ĐOẠN ${activeIndex + 1}" else "SỬA GHI CHÚ ĐOẠN ${activeIndex + 1}") {
                    showReaderOptions = false; noteDraft = activeNote?.text.orEmpty(); showNoteDialog = true
                }
                if (state.vietPhraseRules.isNotEmpty() || state.vietPhraseDictionaryStates.isNotEmpty()) {
                    ReaderMenuButton("ÁP DỤNG VIETPHRASE") { showReaderOptions = false; onApplyVietPhrase() }
                    ReaderMenuButton("CẢI THIỆN VIETPHRASE") { showReaderOptions = false; onImproveVietPhrase() }
                }
                ReaderMenuButton("LẬP NHẠC CẢNH") { showReaderOptions = false; onPlanSceneMusic() }
                ReaderMenuButton("PHÂN VAI + NHẠC") { showReaderOptions = false; onPlanNarration() }
                ReaderMenuButton("XEM NHẬT KÝ CHẨN ĐOÁN") { showReaderOptions = false; showDiagnosticLogDialog = true }
            } },
            confirmButton = { TextButton(onClick = { showReaderOptions = false }) { Text("ĐÓNG") } },
        )
    }

'''
s = s[:menu_start] + flat_menu + s[mode_start:]

display_start = s.index("    if (showDisplayDialog) {")
sleep_start = s.index("    if (showSleepDialog) {", display_start)
display_block = '''    if (showDisplayDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayDialog = false },
            title = { Text("HIỂN THỊ VĂN BẢN") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderIntSlider("Cỡ chữ", displayFontSizeDraft, 12, 40, suffix = " sp") { displayFontSizeDraft = it }
                ReaderIntSlider("Khoảng cách dòng", displayLineHeightDraft, 100, 200, suffix = "%") { displayLineHeightDraft = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Chế độ nền tối khi đọc", Modifier.weight(1f)); Switch(displayDarkDraft, { displayDarkDraft = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Giữ màn hình sáng khi đọc", Modifier.weight(1f)); Switch(displayKeepScreenDraft, { displayKeepScreenDraft = it })
                }
            } },
            confirmButton = { TextButton(onClick = {
                onFontSizeChange(displayFontSizeDraft)
                onLineHeightChange(displayLineHeightDraft)
                onThemeChange(if (displayDarkDraft) ReaderThemeMode.DARK else ReaderThemeMode.LIGHT)
                onKeepScreenOnChange(displayKeepScreenDraft)
                showDisplayDialog = false
            }) { Text("LƯU") } },
            dismissButton = { TextButton(onClick = { showDisplayDialog = false }) { Text("HỦY") } },
        )
    }

'''
s = s[:display_start] + display_block + s[sleep_start:]

music_start = s.index("    if (showMusicDialog) {")
library_start = s.index("    if (showMusicLibrary) {", music_start)
music_block = '''    if (showMusicDialog) {
        var musicModeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("NHẠC NỀN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nhạc nền khi đọc bằng TTS", Modifier.weight(1f)); Switch(musicEnabled, { musicEnabled = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Trao toàn quyền giữ và đổi nhạc cho AI", Modifier.weight(1f)); Switch(musicAi, { musicAi = it })
                }
                Text("Chế độ phát khi không dùng nhạc theo cảnh", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = { musicModeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "Phát ngẫu nhiên" else "Phát lần lượt")
                }
                DropdownMenu(expanded = musicModeExpanded, onDismissRequest = { musicModeExpanded = false }) {
                    DropdownMenuItem(text = { Text("Phát lần lượt") }, onClick = { musicMode = SceneMusicPlaybackMode.SEQUENTIAL; musicModeExpanded = false })
                    DropdownMenuItem(text = { Text("Phát ngẫu nhiên") }, onClick = { musicMode = SceneMusicPlaybackMode.SHUFFLE; musicModeExpanded = false })
                }
                Text("CÂN BẰNG ÂM THANH", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("Mỗi bài nhạc được đo một lần và dùng một mức gain chuẩn hóa cố định. Attack là thời gian hạ nhạc khi giọng đọc bắt đầu; Release là thời gian đưa nhạc trở lại sau khi giọng đọc dừng.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                ReaderFloatSlider("Mức chuẩn hóa", musicTargetLufs, -36f, -18f, steps = 17, shown = { "%.0f LUFS".format(it) }) { musicTargetLufs = it }
                ReaderFloatSlider("Giảm nhạc khi giọng đọc phát", musicDuckDb, 0f, 24f, steps = 23, shown = { "%.0f dB".format(it) }) { musicDuckDb = it }
                ReaderIntSlider("Attack", musicAttackMs, 0, 2_000, step = 10, suffix = " ms") { musicAttackMs = it }
                ReaderIntSlider("Release", musicReleaseMs, 0, 5_000, step = 10, suffix = " ms") { musicReleaseMs = it }
                ReaderMenuButton("CHUẨN HÓA TOÀN BỘ KHO NHẠC") {
                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id) }
                    onMessage("Đã đưa toàn bộ kho nhạc vào hàng đợi chuẩn hóa.")
                }
                Text("${state.sceneMusicTracks.size} bài • ${state.sceneMusicTracks.count { it.enabled }} đang bật", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") {
                    val rows = state.sceneMusicTracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                    musicLibraryDraft = rows.mapIndexed { index, row -> row.copy(orderIndex = index) }
                    musicLibraryBaselineIds = rows.mapTo(linkedSetOf()) { it.id }
                    musicSearch = ""; showMusicLibrary = true
                }
                Text("Tên và mô tả của các bài đang bật được gửi cho AI làm dữ liệu tham chiếu. Khi được trao quyền, AI tự quyết định giữ bài hiện tại hoặc đổi sang bài phù hợp với cảnh.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            } },
            confirmButton = { TextButton(onClick = {
                val activeCount = state.sceneMusicTracks.count { it.enabled }
                if ((musicEnabled || musicAi) && activeCount == 0) onMessage("Hãy bật ít nhất một bài trong danh sách nhạc trước.")
                else scope.launch {
                    val settings = app.container.settingsRepository
                    settings.setBackgroundMusicEnabled(musicEnabled)
                    settings.setAutoSceneMusicEnabled(musicAi)
                    settings.setSceneMusicPlaybackMode(musicMode)
                    settings.setSceneMusicTargetLufs(musicTargetLufs)
                    settings.setBackgroundMusicDuckFactor(10.0.pow(-musicDuckDb / 20.0).toFloat())
                    settings.setBackgroundMusicAttackMillis(musicAttackMs)
                    settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                    onMessage("Đã lưu cài đặt nhạc nền."); showMusicDialog = false
                }
            }) { Text("LƯU CÀI ĐẶT") } },
            dismissButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }

'''
s = s[:music_start] + music_block + s[library_start:]

anchor = "    if (showNoteDialog) {\n"
host = '''    StoryReferenceAdvancedDialogs(
        state = state,
        mode = storyAdvancedMode,
        onDismiss = { storyAdvancedMode = null },
        onSaveVoiceRole = onSaveVoiceRole,
        onPreviewVoiceRole = onPreviewVoiceRole,
        onDeleteVoiceRole = onDeleteVoiceRole,
        onSaveAiProfile = onSaveAiProfile,
    )

'''
s = rep(s, anchor, host + anchor, "reader advanced host")
p.write_text(s)

# App callback wiring.
p = Path("app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt")
s = p.read_text()
s = rep(s, "                    onOpenStoryAiOptions = viewModel::openStoryAiOptions,\n                    onOpenStoryVoiceCastOptions = viewModel::openStoryVoiceCastOptions,\n", "                    onSaveVoiceRole = viewModel::saveVoiceRoleForCurrentStory,\n                    onPreviewVoiceRole = viewModel::previewVoiceRole,\n                    onDeleteVoiceRole = viewModel::deleteVoiceRole,\n                    onSaveAiProfile = viewModel::saveStoryAiProfileForCurrentStory,\n", "reader callback wiring")
p.write_text(s)

# ViewModel lifecycle and library actions.
p = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
s = p.read_text()
s = rep(s, '''    fun setReaderMode(mode: ReaderMode) {
        if (mode == ReaderMode.TEXT) {
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
        }
        mutableState.update { it.copy(readerMode = mode) }
        viewModelScope.launch { container.settingsRepository.setReaderMode(mode) }
        showMessage(if (mode == ReaderMode.TTS) "Đã chuyển sang chế độ TTS." else "Đã chuyển sang chế độ Văn bản.")
    }
''', '''    fun setReaderMode(mode: ReaderMode) {
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
        mutableState.update { it.copy(readerMode = mode) }
        viewModelScope.launch {
            container.settingsRepository.setReaderMode(mode)
            if (mode == ReaderMode.TTS) ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
        showMessage(if (mode == ReaderMode.TTS) "Đã chuyển sang chế độ TTS." else "Đã chuyển sang chế độ Văn bản.")
    }
''', "reader mode")
s = rep(s, '''    private fun openStoryAdvancedOptions(mode: String) {
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
        mutableState.update {
            it.copy(
                destination = Destination.Story,
                storyAdvancedOptionsRequested = true,
                storyAdvancedOptionsMode = mode,
                loading = false,
            )
        }
    }
''', '''    private fun openStoryAdvancedOptions(mode: String) {
        mutableState.update { it.copy(storyAdvancedOptionsRequested = true, storyAdvancedOptionsMode = mode, loading = false) }
    }
''', "advanced trampoline")
anchor = "    fun deleteBookmark(bookmarkId: String) {\n"
addition = '''    fun removeFromReading(storyId: String) {
        viewModelScope.launch {
            container.libraryRepository.removeFromReading(storyId)
            showMessage("Đã xóa truyện khỏi danh sách Đang đọc. Truyện đã tải, dấu trang và lịch sử vẫn được giữ.")
        }
    }

    fun unfollowStory(storyId: String) {
        viewModelScope.launch {
            container.libraryRepository.unfollow(storyId)
            showMessage("Đã bỏ theo dõi truyện.")
        }
    }

'''
s = rep(s, anchor, addition + anchor, "library actions")
s = s.replace("    val backgroundMusicDuckFactor: Float = 0.25f,\n", "    val backgroundMusicDuckFactor: Float = 0.63095734f,\n", 1)
s = s.replace("    val sceneMusicPlaybackMode: SceneMusicPlaybackMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT,\n", "    val sceneMusicPlaybackMode: SceneMusicPlaybackMode = SceneMusicPlaybackMode.SEQUENTIAL,\n", 1)
s = s.replace("    val sceneMusicTargetLufs: Float = -18f,\n", "    val sceneMusicTargetLufs: Float = -24f,\n", 1)
p.write_text(s)

p = Path("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt")
s = p.read_text()
anchor = "    suspend fun clearReadingHistory() = db.readingHistoryDao().clear()\n"
s = rep(s, anchor, anchor + "\n    suspend fun removeFromReading(storyId: String) = db.progressDao().deleteForStory(storyId)\n", "repo remove reading")
p.write_text(s)

# Library: XPK item action menus.
p = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt")
s = p.read_text()
s = rep(s, "    onStoryClick: (StoryEntity) -> Unit,\n", "    onStoryClick: (StoryEntity) -> Unit,\n    onRemoveFromReading: (String) -> Unit,\n", "library signature")
s = rep(s, "    onFollowingClick: (FollowedStoryEntity) -> Unit,\n", "    onFollowingClick: (FollowedStoryEntity) -> Unit,\n    onUnfollow: (String) -> Unit,\n", "library following signature")
s = rep(s, '                StoryEntityList(readingVisible, onStoryClick, "Chưa có truyện đang đọc.")\n', '                StoryEntityList(readingVisible, onStoryClick, onRemoveFromReading, "Chưa có truyện đang đọc.")\n', "reading call")
s = rep(s, "                FollowingList(followingVisible, onFollowingClick)\n", "                FollowingList(followingVisible, onFollowingClick, onUnfollow)\n", "following call")

b0 = s.index("@Composable\nprivate fun BookmarkList(")
b1 = s.index("@Composable\nprivate fun NoteList(", b0)
bookmark_fn = '''@Composable
private fun BookmarkList(bookmarks: List<BookmarkEntity>, onBookmarkOpen: (BookmarkEntity) -> Unit, onBookmarkDelete: (String) -> Unit) {
    var selected by remember { mutableStateOf<BookmarkEntity?>(null) }
    var deleteConfirm by remember { mutableStateOf<BookmarkEntity?>(null) }
    selected?.let { item -> AlertDialog(
        onDismissRequest = { selected = null }, title = { Text(item.label.removePrefix("Truyện: ").ifBlank { "ĐÁNH DẤU" }) },
        text = { Column {
            ReferenceActionButton("MỞ TRUYỆN", { selected = null; onBookmarkOpen(item) }, modifier = Modifier.fillMaxWidth())
            ReferenceActionButton("BỎ ĐÁNH DẤU", { selected = null; deleteConfirm = item }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } },
    ) }
    deleteConfirm?.let { item -> AlertDialog(
        onDismissRequest = { deleteConfirm = null }, title = { Text("BỎ ĐÁNH DẤU") }, text = { Text("Bỏ đánh dấu truyện này?") },
        confirmButton = { TextButton(onClick = { onBookmarkDelete(item.id); deleteConfirm = null }) { Text("BỎ ĐÁNH DẤU") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("HỦY") } },
    ) }
    if (bookmarks.isEmpty()) { Text("Chưa có truyện đã đánh dấu.", modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(bookmarks, key = { "bookmark:${it.id}" }) { item ->
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { selected = item }) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(item.label.removePrefix("Truyện: ").ifBlank { "Đánh dấu đoạn ${item.paragraphIndex + 1}" }, fontWeight = FontWeight.SemiBold)
                if (!item.label.startsWith("Truyện:")) Text("Đoạn ${item.paragraphIndex + 1}")
            }
        }
    } }
}

'''
s = s[:b0] + bookmark_fn + s[b1:]

f0 = s.index("@Composable\nprivate fun FollowingList(")
f1 = s.index("@Composable\nprivate fun DownloadedSection(", f0)
following_fn = '''@Composable
private fun FollowingList(items: List<FollowedStoryEntity>, onOpen: (FollowedStoryEntity) -> Unit, onUnfollow: (String) -> Unit) {
    var selected by remember { mutableStateOf<FollowedStoryEntity?>(null) }
    var unfollowConfirm by remember { mutableStateOf<FollowedStoryEntity?>(null) }
    selected?.let { item -> AlertDialog(
        onDismissRequest = { selected = null }, title = { Text(item.title) }, text = { Column {
            ReferenceActionButton("MỞ TRUYỆN", { selected = null; onOpen(item) }, modifier = Modifier.fillMaxWidth())
            ReferenceActionButton("BỎ THEO DÕI", { selected = null; unfollowConfirm = item }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } },
    ) }
    unfollowConfirm?.let { item -> AlertDialog(
        onDismissRequest = { unfollowConfirm = null }, title = { Text("BỎ THEO DÕI") }, text = { Text("Bỏ theo dõi “${item.title}”?") },
        confirmButton = { TextButton(onClick = { onUnfollow(item.storyId); unfollowConfirm = null }) { Text("BỎ THEO DÕI") } },
        dismissButton = { TextButton(onClick = { unfollowConfirm = null }) { Text("HỦY") } },
    ) }
    if (items.isEmpty()) { Text("Chưa theo dõi truyện nào.", modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(items, key = { it.storyId }) { item ->
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { selected = item }) { Column(modifier = Modifier.padding(14.dp)) {
            Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.latestKnownChapter.ifBlank { "Chạm để mở tùy chọn" })
            if (item.newChapterCount > 0) Text("${item.newChapterCount} chương mới chưa xem", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(item.sourceId, style = MaterialTheme.typography.labelSmall)
        } }
    } }
}

'''
s = s[:f0] + following_fn + s[f1:]

r0 = s.index("@Composable\nprivate fun StoryEntityList(")
r1 = s.index("private fun formatStorageBytes", r0)
reading_fn = '''@Composable
private fun StoryEntityList(items: List<StoryEntity>, onStoryClick: (StoryEntity) -> Unit, onRemoveFromReading: (String) -> Unit, emptyText: String) {
    var selected by remember { mutableStateOf<StoryEntity?>(null) }
    var removeConfirm by remember { mutableStateOf<StoryEntity?>(null) }
    selected?.let { story -> AlertDialog(
        onDismissRequest = { selected = null }, title = { Text(story.title) }, text = { Column {
            ReferenceActionButton("ĐỌC TIẾP", { selected = null; onStoryClick(story) }, modifier = Modifier.fillMaxWidth())
            ReferenceActionButton("XÓA KHỎI ĐANG ĐỌC", { selected = null; removeConfirm = story }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } },
    ) }
    removeConfirm?.let { story -> AlertDialog(
        onDismissRequest = { removeConfirm = null }, title = { Text("XÓA KHỎI ĐANG ĐỌC") },
        text = { Text("Xóa “${story.title}” khỏi Đang đọc? Truyện đã tải, dấu trang và lịch sử đọc vẫn được giữ.") },
        confirmButton = { TextButton(onClick = { onRemoveFromReading(story.id); removeConfirm = null }) { Text("XÓA") } },
        dismissButton = { TextButton(onClick = { removeConfirm = null }) { Text("HỦY") } },
    ) }
    if (items.isEmpty()) { Text(emptyText, modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(items, key = { it.id }) { story ->
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { selected = story }) { Column(modifier = Modifier.padding(14.dp)) {
            Text(story.title, fontWeight = FontWeight.SemiBold); if (story.author.isNotBlank()) Text(story.author)
            Text(if (story.isOffline) "Có thể đọc ngoại tuyến" else story.sourceId)
        } }
    } }
}

'''
s = s[:r0] + reading_fn + s[r1:]
p.write_text(s)

p = Path("app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt")
s = p.read_text()
s = rep(s, "                        onStoryClick = viewModel::openLibraryStory,\n", "                        onStoryClick = viewModel::openLibraryStory,\n                        onRemoveFromReading = viewModel::removeFromReading,\n", "library wiring")
s = rep(s, "                        onFollowingClick = viewModel::openFollowedStory,\n", "                        onFollowingClick = viewModel::openFollowedStory,\n                        onUnfollow = viewModel::unfollowStory,\n", "following wiring")
p.write_text(s)

# XPK music defaults.
p = Path("app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt")
s = p.read_text()
s = s.replace("    val backgroundMusicDuckFactor: Float = 0.25f,\n", "    val backgroundMusicDuckFactor: Float = 0.63095734f,\n", 1)
s = s.replace("    val backgroundMusicAttackMillis: Int = 250,\n", "    val backgroundMusicAttackMillis: Int = 1850,\n", 1)
s = s.replace("    val backgroundMusicReleaseMillis: Int = 900,\n", "    val backgroundMusicReleaseMillis: Int = 2050,\n", 1)
s = s.replace("    val sceneMusicPlaybackMode: SceneMusicPlaybackMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT,\n", "    val sceneMusicPlaybackMode: SceneMusicPlaybackMode = SceneMusicPlaybackMode.SEQUENTIAL,\n", 1)
s = s.replace("    val sceneMusicTargetLufs: Float = -18.0f,\n", "    val sceneMusicTargetLufs: Float = -24.0f,\n", 1)
s = s.replace("preferences[Keys.backgroundMusicDuckFactor] ?: 0.25f", "preferences[Keys.backgroundMusicDuckFactor] ?: 0.63095734f")
s = s.replace("preferences[Keys.backgroundMusicAttackMillis] ?: 250", "preferences[Keys.backgroundMusicAttackMillis] ?: 1850")
s = s.replace("preferences[Keys.backgroundMusicReleaseMillis] ?: 900", "preferences[Keys.backgroundMusicReleaseMillis] ?: 2050")
s = s.replace("preferences[Keys.sceneMusicTargetLufs] ?: -18f", "preferences[Keys.sceneMusicTargetLufs] ?: -24f")
p.write_text(s)

# Freeform music descriptions must not be broken into comma-separated tags before AI.
p = Path("app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt")
s = p.read_text()
s = rep(s, '''    private fun SceneMusicTrackEntity.toOption(): SceneMusicTrackOption = SceneMusicTrackOption(
        id = id,
        title = title,
        tags = tagsCsv.split(',').map(String::trim).filter(String::isNotBlank),
    )
''', '''    private fun SceneMusicTrackEntity.toOption(): SceneMusicTrackOption = SceneMusicTrackOption(
        id = id,
        title = title,
        // Legacy column name. XPK treats this as one freeform AI description.
        tags = tagsCsv.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
    )
''', "music description")
p.write_text(s)

# New workflow-level parity gate.
Path("scripts/check_reference_workflow_parity.py").write_text('''#!/usr/bin/env python3
from pathlib import Path
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
vm = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text()
library = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt").read_text()
settings = Path("app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt").read_text()
narration = Path("app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt").read_text()
required = ["TRỞ LẠI DANH SÁCH CHƯƠNG", "LƯU VỊ TRÍ ĐỌC", "HIỂN THỊ VĂN BẢN", "XUẤT ÂM THANH (CẦN CHẾ ĐỘ TTS)", "THIẾT LẬP AI CHO TRUYỆN NÀY", "PHÂN VAI TTS CHO TRUYỆN NÀY", "StoryReferenceAdvancedDialogs(", "Trao toàn quyền giữ và đổi nhạc cho AI", "CHUẨN HÓA TOÀN BỘ KHO NHẠC", "QUẢN LÝ DANH SÁCH NHẠC", "displayFontSizeDraft"]
missing = [x for x in required if x not in reader]
if missing: raise SystemExit("REFERENCE_WORKFLOW missing Reader markers: " + repr(missing))
for old in ['title = { Text("AI & CHUYỂN NGỮ") }', 'title = { Text("KHÁC") }', "musicAdvanced"]:
    if old in reader: raise SystemExit("REFERENCE_WORKFLOW obsolete Reader navigation: " + old)
if "destination = Destination.Story,\n                storyAdvancedOptionsRequested = true" in vm:
    raise SystemExit("REFERENCE_WORKFLOW story settings still force destination change")
for marker in ["ĐỌC TIẾP", "XÓA KHỎI ĐANG ĐỌC", "MỞ TRUYỆN", "BỎ ĐÁNH DẤU", "BỎ THEO DÕI"]:
    if marker not in library: raise SystemExit("REFERENCE_WORKFLOW missing Library action: " + marker)
for marker in ["backgroundMusicAttackMillis: Int = 1850", "backgroundMusicReleaseMillis: Int = 2050", "sceneMusicTargetLufs: Float = -24.0f", "SceneMusicPlaybackMode.SEQUENTIAL"]:
    if marker not in settings: raise SystemExit("REFERENCE_WORKFLOW music default missing: " + marker)
if "tagsCsv.split(',')" in narration: raise SystemExit("REFERENCE_WORKFLOW music description is still CSV-split")
print("REFERENCE_WORKFLOW_PARITY=PASS")
''')

p = Path("scripts/m0_gate.sh")
s = p.read_text()
if "check_reference_workflow_parity.py" not in s:
    s = rep(s, "python scripts/check_ui_control_parity.py\n", "python scripts/check_ui_control_parity.py\npython scripts/check_reference_workflow_parity.py\n", "m0 gate")
p.write_text(s)
