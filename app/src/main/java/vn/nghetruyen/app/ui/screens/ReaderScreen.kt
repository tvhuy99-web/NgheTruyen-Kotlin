package vn.nghetruyen.app.ui.screens

// REFERENCE_PARITY_V4_READER: explicit Văn bản/TTS modes and reference-style options.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.playback.PlaybackPreparationState
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.ui.ChapterTextMode
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceBlue
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferenceGreen
import vn.nghetruyen.app.ui.components.ReferencePurple

@Composable
fun ReaderScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onBackToChapters: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onTogglePlayback: () -> Unit,
    onReaderModeChange: (ReaderMode) -> Unit,
    onSaveReadingPosition: () -> Unit,
    onSleepTimer: (Int?) -> Unit,
    onBookmark: () -> Unit,
    onExportChapterWav: () -> Unit,
    onExportChapterM4a: () -> Unit,
    onExportChapterMp3: () -> Unit,
    onSaveVoiceProfile: () -> Unit,
    onClearVoiceProfile: () -> Unit,
    onThemeChange: (ReaderThemeMode) -> Unit,
    onLayoutModeChange: (ReaderLayoutMode) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onLineHeightChange: (Int) -> Unit,
    onHorizontalPaddingChange: (Int) -> Unit,
    onParagraphSpacingChange: (Int) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onVolumeKeysNavigateChange: (Boolean) -> Unit,
    onParagraphSelected: (Int) -> Unit,
    onSaveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onApplyVietPhrase: () -> Unit,
    onImproveVietPhrase: () -> Unit,
    onAiTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onVoiceCast: () -> Unit,
    onPlanSceneMusic: () -> Unit,
    onPlanNarration: () -> Unit,
    onOpenStoryAdvancedOptions: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onSelectBackgroundMusic: () -> Unit,
    onClearBackgroundMusic: () -> Unit,
    onBackgroundMusicEnabledChange: (Boolean) -> Unit,
    onBackgroundMusicVolumeChange: (Float) -> Unit,
    onMessage: (String) -> Unit,
) {
    val content = state.chapterContent ?: return
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val display = state.readerDisplay
    val textMode = state.readerMode == ReaderMode.TEXT
    val activeIndex = state.playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))

    var showReaderOptions by remember(content.chapter.id) { mutableStateOf(false) }
    var showReaderModeDialog by remember { mutableStateOf(false) }
    var showDisplayDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var sleepStatus by remember(content.chapter.id) { mutableStateOf("Đang tắt") }
    var showMusicDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showChapterInfoDialog by remember { mutableStateOf(false) }
    var showExtrasDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteDraft by remember(content.chapter.id, activeIndex) { mutableStateOf("") }
    var searchQuery by remember(content.chapter.id) { mutableStateOf("") }
    var searchResultPosition by remember(content.chapter.id) { mutableIntStateOf(0) }
    val normalizedQuery = StorySearch.normalize(searchQuery)
    val searchMatches = remember(content.chapter.id, content.paragraphs, normalizedQuery) {
        if (normalizedQuery.isBlank()) emptyList()
        else content.paragraphs.indices.filter { StorySearch.normalize(content.paragraphs[it]).contains(normalizedQuery) }
    }
    val selectedSearchIndex = searchMatches.getOrNull(searchResultPosition.coerceIn(0, searchMatches.lastIndex.coerceAtLeast(0)))
    val activeNote = state.notes.firstOrNull { it.chapterId == content.chapter.id && it.paragraphIndex == activeIndex }

    DisposableEffect(view, display.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = display.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }

    LaunchedEffect(activeIndex, textMode) {
        if (textMode && content.paragraphs.isNotEmpty() && normalizedQuery.isBlank() && !listState.isScrollInProgress) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == activeIndex + 1 }) {
                listState.scrollToItem((activeIndex + 1).coerceAtMost(content.paragraphs.size))
            }
        }
    }
    LaunchedEffect(selectedSearchIndex) {
        if (textMode) selectedSearchIndex?.let { listState.animateScrollToItem(it + 1) }
    }
    LaunchedEffect(content.chapter.id, state.readerMode) {
        delay(120)
        view.announceForAccessibility(
            "${content.chapter.title}. Chế độ ${if (textMode) "Văn bản" else "TTS"}. ${content.paragraphs.size} đoạn",
        )
    }

    val palette = readerPalette(display.theme)
    Surface(modifier = Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReaderButton(
                "TÙY CHỌN",
                { showReaderOptions = true },
                Modifier.fillMaxWidth(),
                normalColor = ReferenceGray,
                minHeight = 58.dp,
                accessibilityLabel = "Mở tùy chọn đọc",
            )

            if (textMode) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "reader-title") {
                        Text(
                            text = content.chapter.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.text,
                            modifier = Modifier.fillMaxWidth().semantics { heading() }.padding(12.dp),
                        )
                    }
                    items(count = content.paragraphs.size, key = { index -> "${content.chapter.id}:text:$index" }) { index ->
                        val searchHit = index in searchMatches
                        Text(
                            text = content.paragraphs[index],
                            color = palette.text,
                            fontSize = display.fontSizeSp.sp,
                            lineHeight = (display.fontSizeSp * display.lineHeightPercent / 100f).sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (searchHit) palette.search else Color.Transparent)
                                .padding(
                                    horizontal = display.horizontalPaddingDp.dp,
                                    vertical = (display.paragraphSpacingDp / 2f).dp,
                                )
                                .semantics { contentDescription = "Đoạn ${index + 1}. ${content.paragraphs[index]}" },
                        )
                    }
                }
                if (searchMatches.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        ReaderButton("KẾT QUẢ TRƯỚC", {
                            searchResultPosition = if (searchResultPosition <= 0) searchMatches.lastIndex else searchResultPosition - 1
                        }, Modifier.weight(1f), minHeight = 48.dp)
                        Text("${searchResultPosition + 1}/${searchMatches.size}", modifier = Modifier.padding(12.dp), color = palette.text)
                        ReaderButton("KẾT QUẢ SAU", {
                            searchResultPosition = if (searchResultPosition >= searchMatches.lastIndex) 0 else searchResultPosition + 1
                        }, Modifier.weight(1f), minHeight = 48.dp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1f), minHeight = 64.dp, accessibilityLabel = "Chương trước")
                    ReaderButton("SAU", onNextChapter, Modifier.weight(1f), minHeight = 64.dp, accessibilityLabel = "Chương sau")
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        content.chapter.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Đoạn ${activeIndex + 1}/${content.paragraphs.size.coerceAtLeast(1)}",
                        color = palette.text,
                        modifier = Modifier.padding(top = 8.dp).semantics {
                            contentDescription = "Đang đọc đoạn ${activeIndex + 1} trên ${content.paragraphs.size.coerceAtLeast(1)}"
                        },
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1.2f), minHeight = 64.dp, accessibilityLabel = "Chương trước")
                    ReaderButton("LÙI", onRewind, Modifier.weight(1f), minHeight = 64.dp, accessibilityLabel = "Lùi một đoạn")
                    ReaderButton(
                        when {
                            state.playback.preparationState == PlaybackPreparationState.PREPARING -> "ĐANG CHUẨN BỊ"
                            state.playback.preparationState == PlaybackPreparationState.FAILED -> "AI LỖI"
                            state.playback.isPlaying -> "TẠM DỪNG"
                            else -> "PHÁT"
                        },
                        onTogglePlayback,
                        Modifier.weight(1.4f),
                        enabled = state.playback.preparationState == PlaybackPreparationState.READY,
                        minHeight = 64.dp,
                        normalColor = ReferenceGreen,
                        selectedColor = ReferenceGreen,
                        selected = state.playback.isPlaying,
                        accessibilityLabel = if (state.playback.isPlaying) "Tạm dừng đọc truyện" else "Phát truyện bằng TTS",
                    )
                    ReaderButton("TỚI", onForward, Modifier.weight(1f), minHeight = 64.dp, accessibilityLabel = "Tới một đoạn")
                    ReaderButton("SAU", onNextChapter, Modifier.weight(1.2f), minHeight = 64.dp, accessibilityLabel = "Chương sau")
                }
                if (state.playback.preparationState == PlaybackPreparationState.PREPARING) {
                    Text("Đang chuẩn bị giọng đọc và nội dung tiếp theo…", modifier = Modifier.fillMaxWidth().padding(10.dp), color = palette.text)
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton(
                    when {
                        state.aiBusy -> "AI ĐANG CHẠY…"
                        state.chapterTextMode == ChapterTextMode.AI_TRANSLATION -> "DỊCH LẠI"
                        else -> "DỊCH AI"
                    },
                    onAiTranslate,
                    Modifier.weight(1f),
                    enabled = !state.aiBusy,
                    normalColor = ReferencePurple,
                    accessibilityLabel = "Dịch chương bằng AI",
                )
                if (!textMode) {
                    ReaderButton(
                        "PHÂN VAI AI",
                        onVoiceCast,
                        Modifier.weight(1f),
                        enabled = !state.aiBusy,
                        normalColor = Color(0xFFAF52DE),
                        accessibilityLabel = "Phân vai giọng đọc bằng AI",
                    )
                }
            }
        }
    }

    if (showReaderOptions) {
        AlertDialog(
            onDismissRequest = { showReaderOptions = false },
            title = { Text("TÙY CHỌN ĐỌC") },
            text = {
                Column(modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                    ReaderMenuButton("TRỞ LẠI DANH SÁCH CHƯƠNG") { showReaderOptions = false; onBackToChapters() }
                    ReaderMenuButton("LƯU VỊ TRÍ ĐỌC") { showReaderOptions = false; onSaveReadingPosition() }
                    ReaderMenuButton("TÌM TRONG CHƯƠNG") { showReaderOptions = false; showSearchDialog = true }
                    if (textMode) ReaderMenuButton("HIỂN THỊ VĂN BẢN") { showReaderOptions = false; showDisplayDialog = true }
                    ReaderMenuButton("HẸN GIỜ NGỦ - $sleepStatus") { showReaderOptions = false; showSleepDialog = true }
                    ReaderMenuButton("NHẠC NỀN") { showReaderOptions = false; showMusicDialog = true }
                    ReaderMenuButton(if (textMode) "XUẤT ÂM THANH (CẦN CHẾ ĐỘ TTS)" else "XUẤT ÂM THANH") {
                        showReaderOptions = false
                        if (textMode) onMessage("XUẤT ÂM THANH chỉ hoạt động trong chế độ TTS.") else showExportDialog = true
                    }
                    ReaderMenuButton("CHẾ ĐỘ ĐỌC: ${if (textMode) "VĂN BẢN" else "TTS"}") { showReaderOptions = false; showReaderModeDialog = true }
                    ReaderMenuButton("THIẾT LẬP AI CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryAdvancedOptions() }
                    ReaderMenuButton("PHÂN VAI TTS CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryAdvancedOptions() }
                    if (state.chapterTextMode != ChapterTextMode.ORIGINAL) {
                        ReaderMenuButton("KHÔI PHỤC CHƯƠNG GỐC TRƯỚC AI") { showReaderOptions = false; onShowOriginal() }
                    }
                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") {
                        showReaderOptions = false
                        onMessage("Mục nhật ký VietPhrase đã được đưa về đúng vị trí; trình xem nhật ký chuyên dụng sẽ được nối ở bước hoàn thiện chức năng.")
                    }
                    ReaderMenuButton("CÀI ĐẶT TTS") { showReaderOptions = false; showTtsSettingsDialog = true }
                    ReaderMenuButton("SAO CHÉP CHƯƠNG") {
                        showReaderOptions = false
                        clipboard.setText(AnnotatedString(content.paragraphs.joinToString("\n\n")))
                        onMessage("Đã sao chép chương hiện tại.")
                    }
                    ReaderMenuButton("THÔNG TIN CHƯƠNG") { showReaderOptions = false; showChapterInfoDialog = true }
                }
            },
            confirmButton = { TextButton(onClick = { showReaderOptions = false }) { Text("ĐÓNG") } },
        )
    }

    if (showReaderModeDialog) {
        AlertDialog(
            onDismissRequest = { showReaderModeDialog = false },
            title = { Text("CHẾ ĐỘ ĐỌC") },
            text = {
                Column {
                    ReaderMenuButton("VĂN BẢN" + if (textMode) " - ĐANG DÙNG" else "") {
                        showReaderModeDialog = false
                        onReaderModeChange(ReaderMode.TEXT)
                    }
                    ReaderMenuButton("TTS" + if (!textMode) " - ĐANG DÙNG" else "") {
                        showReaderModeDialog = false
                        onReaderModeChange(ReaderMode.TTS)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReaderModeDialog = false }) { Text("HỦY") } },
        )
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Tìm trong chương") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.take(120); searchResultPosition = 0 },
                        label = { Text("Từ hoặc cụm từ") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(if (normalizedQuery.isBlank()) "Nhập nội dung cần tìm." else "Tìm thấy ${searchMatches.size} đoạn.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    if (searchMatches.isNotEmpty() && !textMode) onReaderModeChange(ReaderMode.TEXT)
                }) { Text("XONG") }
            },
            dismissButton = { TextButton(onClick = { searchQuery = ""; searchResultPosition = 0; showSearchDialog = false }) { Text("XÓA TÌM KIẾM") } },
        )
    }

    if (showDisplayDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayDialog = false },
            title = { Text("HIỂN THỊ VĂN BẢN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cỡ chữ: ${display.fontSizeSp} sp")
                        Row { TextButton({ onFontSizeChange(display.fontSizeSp - 1) }) { Text("−") }; TextButton({ onFontSizeChange(display.fontSizeSp + 1) }) { Text("+") } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Khoảng cách dòng: ${display.lineHeightPercent}%")
                        Row { TextButton({ onLineHeightChange(display.lineHeightPercent - 10) }) { Text("−") }; TextButton({ onLineHeightChange(display.lineHeightPercent + 10) }) { Text("+") } }
                    }
                    Text("Màu nền")
                    Row(Modifier.fillMaxWidth()) {
                        listOf(
                            ReaderThemeMode.LIGHT to "SÁNG",
                            ReaderThemeMode.DARK to "TỐI",
                            ReaderThemeMode.SEPIA to "GIẤY",
                        ).forEach { (mode, label) ->
                            TextButton({ onThemeChange(mode) }, Modifier.weight(1f)) { Text(if (display.theme == mode) "✓ $label" else label) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = display.keepScreenOn, onCheckedChange = onKeepScreenOnChange)
                        Text("Giữ màn hình sáng khi đọc")
                    }
                    Text("Tùy chỉnh nâng cao", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ onHorizontalPaddingChange(display.horizontalPaddingDp - 2) }, Modifier.weight(1f)) { Text("LỀ −") }
                        TextButton({ onHorizontalPaddingChange(display.horizontalPaddingDp + 2) }, Modifier.weight(1f)) { Text("LỀ +") }
                        TextButton({ onParagraphSpacingChange(display.paragraphSpacingDp - 2) }, Modifier.weight(1f)) { Text("ĐOẠN −") }
                        TextButton({ onParagraphSpacingChange(display.paragraphSpacingDp + 2) }, Modifier.weight(1f)) { Text("ĐOẠN +") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisplayDialog = false }) { Text("LƯU") } },
        )
    }

    if (showTtsSettingsDialog) {
        val hasVoiceProfile = state.storyTtsProfiles.containsKey(content.chapter.storyId)
        AlertDialog(
            onDismissRequest = { showTtsSettingsDialog = false },
            title = { Text("CÀI ĐẶT TTS") },
            text = {
                Column(modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = hasVoiceProfile,
                            onCheckedChange = { enabled -> if (enabled) onSaveVoiceProfile() else onClearVoiceProfile() },
                        )
                        Text("Dùng cài đặt TTS riêng cho truyện này")
                    }
                    Text("Bộ đọc TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(state.selectedTtsEnginePackage ?: "Giọng đọc hệ thống")
                    Text("Ngôn ngữ", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(state.selectedTtsLanguageTag.ifBlank { "vi-VN" })
                    Text("Giọng đọc", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(state.selectedTtsVoiceName ?: "Giọng mặc định")
                    Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(if (state.sonicProcessingEnabled) "Sonic" else "TTS hệ thống")
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(if (state.sonicProcessingEnabled) "Đang bật" else "Đang tắt")
                    Text("Tốc độ đọc: ${"%.2f".format(state.playback.rate)}×", modifier = Modifier.padding(top = 8.dp))
                    Text("Cao độ: ${"%.2f".format(state.playback.pitch)}×", modifier = Modifier.padding(top = 4.dp))
                    Text("Âm lượng: ${(state.ttsVolume * 100).toInt()}%", modifier = Modifier.padding(top = 4.dp))
                    ReaderMenuButton("NGHE THỬ") {
                        onMessage("Nghe thử TTS vẫn dùng bộ điều khiển hiện có; bước này ưu tiên đưa mục về đúng vị trí của công cụ tham chiếu.")
                    }
                    ReaderMenuButton("KHÔI PHỤC MẶC ĐỊNH") {
                        onMessage("Khôi phục mặc định sẽ được nối sau khi hoàn thiện bộ chỉnh TTS nội bộ.")
                    }
                    ReaderMenuButton("LƯU CÀI ĐẶT TTS") {
                        onSaveVoiceProfile()
                        showTtsSettingsDialog = false
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTtsSettingsDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("HẸN GIỜ NGỦ - $sleepStatus") },
            text = { Column {
                ReaderMenuButton("15 PHÚT") { showSleepDialog = false; sleepStatus = "Còn khoảng 15 phút"; onSleepTimer(15) }
                ReaderMenuButton("30 PHÚT") { showSleepDialog = false; sleepStatus = "Còn khoảng 30 phút"; onSleepTimer(30) }
                ReaderMenuButton("45 PHÚT") { showSleepDialog = false; sleepStatus = "Còn khoảng 45 phút"; onSleepTimer(45) }
                ReaderMenuButton("60 PHÚT") { showSleepDialog = false; sleepStatus = "Còn khoảng 60 phút"; onSleepTimer(60) }
                ReaderMenuButton("HẾT CHƯƠNG HIỆN TẠI") {
                    showSleepDialog = false
                    sleepStatus = "Hết chương hiện tại"
                    onMessage("Tùy chọn hẹn giờ theo chương đã được đặt đúng vị trí; backend hiện mới hỗ trợ hẹn giờ theo phút.")
                }
                ReaderMenuButton("HẾT 3 CHƯƠNG") {
                    showSleepDialog = false
                    sleepStatus = "Hết 3 chương"
                    onMessage("Tùy chọn hẹn giờ theo chương đã được đặt đúng vị trí; backend hiện mới hỗ trợ hẹn giờ theo phút.")
                }
                ReaderMenuButton("TẮT HẸN GIỜ") { showSleepDialog = false; sleepStatus = "Đang tắt"; onSleepTimer(null) }
            } },
            confirmButton = { TextButton(onClick = { showSleepDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("NHẠC NỀN") },
            text = { Column(modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nhạc nền khi đọc bằng TTS", Modifier.weight(1f))
                    Switch(state.backgroundMusicEnabled, onBackgroundMusicEnabledChange)
                }
                Text("Chế độ phát khi không dùng nhạc theo cảnh", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(state.sceneMusicPlaybackMode.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                Text("CÂN BẰNG ÂM THANH", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("Mức chuẩn hóa: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS", modifier = Modifier.padding(top = 6.dp))
                Text("Âm lượng: ${(state.backgroundMusicVolume * 100).toInt()}%")
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ onBackgroundMusicVolumeChange(state.backgroundMusicVolume - 0.05f) }, Modifier.weight(1f)) { Text("NHỎ HƠN") }
                    TextButton({ onBackgroundMusicVolumeChange(state.backgroundMusicVolume + 0.05f) }, Modifier.weight(1f)) { Text("LỚN HƠN") }
                }
                Text(state.backgroundMusicUri ?: "Chưa chọn tệp nhạc nền.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") { onSelectBackgroundMusic() }
                if (!state.backgroundMusicUri.isNullOrBlank()) ReaderMenuButton("BỎ NHẠC NỀN") { onClearBackgroundMusic() }
                Text(
                    "Các điều khiển chuẩn hóa toàn kho, attack/release và quyền đổi nhạc cho AI sẽ tiếp tục được nối vào dialog này ở bước parity chức năng.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } },
            confirmButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("XUẤT ÂM THANH") },
            text = { Column {
                ReaderMenuButton("WAV") { showExportDialog = false; onExportChapterWav() }
                ReaderMenuButton("M4A") { showExportDialog = false; onExportChapterM4a() }
                ReaderMenuButton("MP3") { showExportDialog = false; onExportChapterMp3() }
            } },
            confirmButton = { TextButton(onClick = { showExportDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showChapterInfoDialog) {
        val storyTitle = state.storyDetail?.story?.title ?: "Truyện"
        AlertDialog(
            onDismissRequest = { showChapterInfoDialog = false },
            title = { Text("THÔNG TIN CHƯƠNG") },
            text = { Text("Tên truyện: $storyTitle\n\nTên chương: ${content.chapter.title}\n\nNguồn: ${state.storyDetail?.story?.sourceId ?: "Không rõ"}\n\nChế độ đọc: ${if (textMode) "Văn bản" else "TTS"}\n\nSố đoạn: ${content.paragraphs.size}") },
            confirmButton = { TextButton(onClick = { showChapterInfoDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showExtrasDialog) {
        val hasVoiceProfile = state.storyTtsProfiles.containsKey(content.chapter.storyId)
        AlertDialog(
            onDismissRequest = { showExtrasDialog = false },
            title = { Text("TIỆN ÍCH BỔ SUNG") },
            text = { Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("ĐÁNH DẤU") { showExtrasDialog = false; onBookmark() }
                ReaderMenuButton(if (activeNote == null) "GHI CHÚ" else "SỬA GHI CHÚ") {
                    showExtrasDialog = false; noteDraft = activeNote?.text.orEmpty(); showNoteDialog = true
                }
                ReaderMenuButton("SAO CHÉP ĐOẠN") {
                    showExtrasDialog = false
                    clipboard.setText(AnnotatedString(content.paragraphs.getOrNull(activeIndex).orEmpty()))
                    onMessage("Đã sao chép đoạn đang đọc.")
                }
                ReaderMenuButton("VIETPHRASE") { showExtrasDialog = false; onApplyVietPhrase() }
                ReaderMenuButton("CẢI THIỆN VP") { showExtrasDialog = false; onImproveVietPhrase() }
                ReaderMenuButton("DÀN DỰNG AI") { showExtrasDialog = false; onPlanNarration() }
                ReaderMenuButton("NHẠC CẢNH") { showExtrasDialog = false; onPlanSceneMusic() }
                ReaderMenuButton(if (hasVoiceProfile) "CẬP NHẬT GIỌNG RIÊNG" else "LƯU GIỌNG RIÊNG") { showExtrasDialog = false; onSaveVoiceProfile() }
                if (hasVoiceProfile) ReaderMenuButton("BỎ GIỌNG RIÊNG") { showExtrasDialog = false; onClearVoiceProfile() }
            } },
            confirmButton = { TextButton(onClick = { showExtrasDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Ghi chú đoạn ${activeIndex + 1}") },
            text = { OutlinedTextField(value = noteDraft, onValueChange = { noteDraft = it.take(4_000) }, label = { Text("Nội dung ghi chú") }, minLines = 4, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = noteDraft.isNotBlank(), onClick = { onSaveNote(noteDraft); showNoteDialog = false }) { Text("LƯU") } },
            dismissButton = { Row {
                if (activeNote != null) TextButton(onClick = { onDeleteNote(activeNote.id); showNoteDialog = false }) { Text("XÓA") }
                TextButton(onClick = { showNoteDialog = false }) { Text("HỦY") }
            } },
        )
    }
}

private data class ReaderPalette(val background: Color, val card: Color, val text: Color, val active: Color, val search: Color)

@Composable
private fun readerPalette(mode: ReaderThemeMode): ReaderPalette = when (mode) {
    ReaderThemeMode.SYSTEM -> ReaderPalette(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onBackground, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    ReaderThemeMode.LIGHT -> ReaderPalette(Color(0xFFFDFDFD), Color.White, Color(0xFF171717), Color(0xFFDCEBFF), Color(0xFFFFE8A3))
    ReaderThemeMode.DARK -> ReaderPalette(Color(0xFF111315), Color(0xFF1C1F22), Color(0xFFE9ECEF), Color(0xFF263B4D), Color(0xFF5A4A1F))
    ReaderThemeMode.SEPIA -> ReaderPalette(Color(0xFFF4ECD8), Color(0xFFF8F0DF), Color(0xFF3E3228), Color(0xFFE0D1B2), Color(0xFFFFD978))
}

@Composable
private fun ReaderMenuButton(text: String, onClick: () -> Unit) {
    ReferenceActionButton(text = text, onClick = onClick, normalColor = ReferenceGray, minHeight = 52.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
}

@Composable
private fun ReaderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 54.dp,
    normalColor: Color = ReferenceBlue,
    selectedColor: Color = ReferenceGreen,
    selected: Boolean = false,
    accessibilityLabel: String = text,
) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier.padding(1.dp),
        accessibilityLabel = accessibilityLabel,
        selected = selected,
        enabled = enabled,
        minHeight = minHeight,
        selectedColor = selectedColor,
        normalColor = normalColor,
    )
}
