package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.playback.PlaybackPreparationState
import vn.nghetruyen.app.ui.ChapterTextMode
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferenceGreen
import vn.nghetruyen.app.ui.components.ReferencePurple

@Composable
fun ReaderScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onTogglePlayback: () -> Unit,
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
    onMessage: (String) -> Unit,
) {
    val content = state.chapterContent ?: return
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val display = state.readerDisplay
    val activeIndex = state.playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))
    var showDisplayDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showReaderActions by remember(content.chapter.id) { mutableStateOf(false) }
    var noteDraft by remember(content.chapter.id, activeIndex) { mutableStateOf("") }
    var searchQuery by remember(content.chapter.id) { mutableStateOf("") }
    var searchResultPosition by remember(content.chapter.id) { mutableIntStateOf(0) }
    val normalizedQuery = StorySearch.normalize(searchQuery)
    val searchMatches = remember(content.chapter.id, content.paragraphs, normalizedQuery) {
        if (normalizedQuery.isBlank()) emptyList()
        else content.paragraphs.indices.filter { StorySearch.normalize(content.paragraphs[it]).contains(normalizedQuery) }
    }
    val selectedSearchIndex = searchMatches.getOrNull(searchResultPosition.coerceIn(0, searchMatches.lastIndex.coerceAtLeast(0)))
    val activeNote = state.notes.firstOrNull {
        it.chapterId == content.chapter.id && it.paragraphIndex == activeIndex
    }

    DisposableEffect(view, display.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = display.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }

    LaunchedEffect(activeIndex, display.layoutMode) {
        if (
            display.layoutMode == ReaderLayoutMode.SCROLL &&
            content.paragraphs.isNotEmpty() &&
            normalizedQuery.isBlank() &&
            !listState.isScrollInProgress &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == activeIndex }
        ) listState.scrollToItem(activeIndex)
    }
    LaunchedEffect(
        content.chapter.id,
        display.layoutMode,
        listState.firstVisibleItemIndex,
        listState.isScrollInProgress,
    ) {
        if (
            display.layoutMode == ReaderLayoutMode.SCROLL &&
            !listState.isScrollInProgress &&
            normalizedQuery.isBlank() &&
            content.paragraphs.isNotEmpty()
        ) {
            delay(300)
            onParagraphSelected(listState.firstVisibleItemIndex.coerceIn(0, content.paragraphs.lastIndex))
        }
    }
    LaunchedEffect(selectedSearchIndex) {
        selectedSearchIndex?.let { listState.animateScrollToItem(it) }
    }

    LaunchedEffect(content.chapter.id) {
        delay(120)
        view.announceForAccessibility("${content.chapter.title}. ${content.paragraphs.size} đoạn")
    }

    val palette = readerPalette(display.theme)
    Surface(modifier = Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton(
                    "QUAY LẠI",
                    onBack,
                    Modifier.weight(1.1f),
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Quay lại màn hình trước",
                )
                ReaderButton("TÌM", { showSearchDialog = true }, Modifier.weight(0.8f), accessibilityLabel = "Tìm trong chương")
                ReaderButton(
                    "TÙY CHỌN",
                    { showReaderActions = !showReaderActions },
                    Modifier.weight(1f),
                    normalColor = ReferenceGray,
                    selectedColor = ReferenceGray,
                    selected = showReaderActions,
                    accessibilityLabel = if (showReaderActions) "Đóng tùy chọn đọc" else "Mở tùy chọn đọc",
                )
            }
            if (showReaderActions) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("HIỂN THỊ", { showDisplayDialog = true }, Modifier.weight(1f))
                    ReaderButton("BẢN GỐC", onShowOriginal, Modifier.weight(1f))
                    ReaderButton("VIETPHRASE", onApplyVietPhrase, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("CẢI THIỆN VP", onImproveVietPhrase, Modifier.weight(1f), enabled = !state.aiBusy)
                    ReaderButton("DÀN DỰNG AI", onPlanNarration, Modifier.weight(1.1f), enabled = !state.aiBusy)
                    ReaderButton("NHẠC CẢNH", onPlanSceneMusic, Modifier.weight(1f), enabled = !state.aiBusy)
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (state.chapterTextMode) {
                        ChapterTextMode.ORIGINAL -> "Bản gốc"
                        ChapterTextMode.VIETPHRASE -> "VietPhrase"
                        ChapterTextMode.AI_TRANSLATION -> "Bản dịch AI"
                    },
                    modifier = Modifier.weight(1f).padding(12.dp),
                    color = palette.text,
                )
            }
            Text(
                text = content.chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = palette.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .padding(12.dp),
            )
            if (searchMatches.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ReaderButton("KẾT QUẢ TRƯỚC", {
                        searchResultPosition = if (searchResultPosition <= 0) searchMatches.lastIndex else searchResultPosition - 1
                        searchMatches.getOrNull(searchResultPosition)?.let(onParagraphSelected)
                    }, Modifier.weight(1f))
                    Text(
                        "${searchResultPosition.coerceAtMost(searchMatches.lastIndex) + 1}/${searchMatches.size}",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = palette.text,
                    )
                    ReaderButton("KẾT QUẢ SAU", {
                        searchResultPosition = if (searchResultPosition >= searchMatches.lastIndex) 0 else searchResultPosition + 1
                        searchMatches.getOrNull(searchResultPosition)?.let(onParagraphSelected)
                    }, Modifier.weight(1f))
                }
            }

            when (display.layoutMode) {
                ReaderLayoutMode.SCROLL -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(count = content.paragraphs.size, key = { index -> "${content.chapter.id}:$index" }) { index ->
                        val active = index == activeIndex
                        val searchHit = index in searchMatches
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    searchHit -> palette.search
                                    active -> palette.active
                                    else -> palette.card
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = display.horizontalPaddingDp.dp,
                                    vertical = (display.paragraphSpacingDp / 2f).dp,
                                )
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    this.selected = active
                                    contentDescription = "Đoạn ${index + 1}" + (if (active) ", đang đọc. " else ". ") + content.paragraphs[index]
                                }
                                .clickable { onParagraphSelected(index) },
                        ) {
                            Text(
                                text = content.paragraphs[index],
                                color = palette.text,
                                fontSize = display.fontSizeSp.sp,
                                lineHeight = (display.fontSizeSp * display.lineHeightPercent / 100f).sp,
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                            )
                        }
                    }
                }
                ReaderLayoutMode.PAGED -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.card),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = display.horizontalPaddingDp.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = content.paragraphs.getOrNull(activeIndex).orEmpty(),
                            color = palette.text,
                            fontSize = display.fontSizeSp.sp,
                            lineHeight = (display.fontSizeSp * display.lineHeightPercent / 100f).sp,
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ReaderButton(
                            "TRANG TRƯỚC",
                            { onParagraphSelected((activeIndex - 1).coerceAtLeast(0)) },
                            Modifier.weight(1f),
                            enabled = activeIndex > 0,
                        )
                        ReaderButton(
                            "TRANG SAU",
                            { onParagraphSelected((activeIndex + 1).coerceAtMost(content.paragraphs.lastIndex)) },
                            Modifier.weight(1f),
                            enabled = activeIndex < content.paragraphs.lastIndex,
                        )
                    }
                }
            }

            Text(
                text = "Đoạn ${activeIndex + 1}/${content.paragraphs.size.coerceAtLeast(1)}",
                color = palette.text,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
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
                ReaderButton(
                    "PHÂN VAI AI",
                    onVoiceCast,
                    Modifier.weight(1f),
                    enabled = !state.aiBusy,
                    normalColor = ReferencePurple,
                    accessibilityLabel = "Phân vai giọng đọc bằng AI",
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1.2f), minHeight = 64.dp, accessibilityLabel = "Chương trước")
                ReaderButton("LÙI", onRewind, Modifier.weight(1f), minHeight = 64.dp, accessibilityLabel = "Tua lùi")
                ReaderButton(
                    when {
                        state.playback.preparationState == PlaybackPreparationState.PREPARING -> "ĐANG CHUẨN BỊ"
                        state.playback.preparationState == PlaybackPreparationState.FAILED -> "AI LỖI"
                        state.playback.isPlaying -> "DỪNG"
                        else -> "PHÁT"
                    },
                    onTogglePlayback,
                    Modifier.weight(1.4f),
                    enabled = state.playback.preparationState == PlaybackPreparationState.READY,
                    minHeight = 64.dp,
                    normalColor = ReferenceGreen,
                    selectedColor = ReferenceGreen,
                    selected = state.playback.isPlaying,
                    accessibilityLabel = if (state.playback.isPlaying) "Tạm dừng đọc truyện" else "Phát tiếp truyện",
                )
                ReaderButton("TỚI", onForward, Modifier.weight(1f), minHeight = 64.dp, accessibilityLabel = "Tua tới")
                ReaderButton("SAU", onNextChapter, Modifier.weight(1.2f), minHeight = 64.dp, accessibilityLabel = "Chương sau")
            }
            if (showReaderActions) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("ĐÁNH DẤU", onBookmark, Modifier.weight(1f))
                    ReaderButton("SAO CHÉP ĐOẠN", {
                        clipboard.setText(AnnotatedString(content.paragraphs.getOrNull(activeIndex).orEmpty()))
                        onMessage("Đã sao chép đoạn đang đọc.")
                    }, Modifier.weight(1.2f))
                    ReaderButton("SAO CHÉP CHƯƠNG", {
                        clipboard.setText(AnnotatedString(content.paragraphs.joinToString("\n\n")))
                        onMessage("Đã sao chép toàn bộ chương.")
                    }, Modifier.weight(1.2f))
                    ReaderButton("XUẤT WAV", onExportChapterWav, Modifier.weight(1f))
                    ReaderButton("XUẤT M4A", onExportChapterM4a, Modifier.weight(1f))
                    ReaderButton("XUẤT MP3", onExportChapterMp3, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton(
                        if (activeNote == null) "GHI CHÚ" else "SỬA GHI CHÚ",
                        {
                            noteDraft = activeNote?.text.orEmpty()
                            showNoteDialog = true
                        },
                        Modifier.weight(1f),
                    )
                    Text(
                        if (activeNote == null) "Đoạn này chưa có ghi chú" else "Đã lưu ghi chú cho đoạn ${activeIndex + 1}",
                        modifier = Modifier.weight(2f).padding(12.dp),
                        color = palette.text,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("HẸN 15'", { onSleepTimer(15) }, Modifier.weight(1f))
                    ReaderButton("HẸN 30'", { onSleepTimer(30) }, Modifier.weight(1f))
                    ReaderButton("HẸN 60'", { onSleepTimer(60) }, Modifier.weight(1f))
                    ReaderButton("HỦY HẸN", { onSleepTimer(null) }, Modifier.weight(1f))
                }
                val hasVoiceProfile = state.storyTtsProfiles.containsKey(content.chapter.storyId)
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton(
                        if (hasVoiceProfile) "CẬP NHẬT GIỌNG RIÊNG" else "LƯU GIỌNG RIÊNG",
                        onSaveVoiceProfile,
                        Modifier.weight(1f),
                    )
                    if (hasVoiceProfile) ReaderButton("BỎ GIỌNG RIÊNG", onClearVoiceProfile, Modifier.weight(1f))
                }
            }
        }
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Ghi chú đoạn ${activeIndex + 1}") },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it.take(4_000) },
                    label = { Text("Nội dung ghi chú") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = noteDraft.isNotBlank(),
                    onClick = {
                        onSaveNote(noteDraft)
                        showNoteDialog = false
                    },
                ) { Text("LƯU") }
            },
            dismissButton = {
                Row {
                    if (activeNote != null) {
                        TextButton(onClick = {
                            onDeleteNote(activeNote.id)
                            showNoteDialog = false
                        }) { Text("XÓA") }
                    }
                    TextButton(onClick = { showNoteDialog = false }) { Text("HỦY") }
                }
            },
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
                        onValueChange = {
                            searchQuery = it.take(120)
                            searchResultPosition = 0
                        },
                        label = { Text("Từ hoặc cụm từ") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (normalizedQuery.isBlank()) "Nhập nội dung cần tìm."
                        else "Tìm thấy ${searchMatches.size} đoạn.",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSearchDialog = false }) { Text("XONG") } },
            dismissButton = {
                TextButton(onClick = {
                    searchQuery = ""
                    searchResultPosition = 0
                    showSearchDialog = false
                }) { Text("XÓA TÌM KIẾM") }
            },
        )
    }

    if (showDisplayDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayDialog = false },
            title = { Text("Hiển thị khi đọc") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Màu nền")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            ReaderThemeMode.SYSTEM to "HỆ THỐNG",
                            ReaderThemeMode.LIGHT to "SÁNG",
                            ReaderThemeMode.DARK to "TỐI",
                            ReaderThemeMode.SEPIA to "GIẤY",
                        ).forEach { (mode, label) ->
                            TextButton(onClick = { onThemeChange(mode) }, modifier = Modifier.weight(1f)) {
                                Text(if (display.theme == mode) "✓ $label" else label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("Kiểu đọc")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            ReaderLayoutMode.SCROLL to "CUỘN",
                            ReaderLayoutMode.PAGED to "PHÂN TRANG",
                        ).forEach { (mode, label) ->
                            TextButton(onClick = { onLayoutModeChange(mode) }, modifier = Modifier.weight(1f)) {
                                Text(if (display.layoutMode == mode) "✓ $label" else label)
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cỡ chữ: ${display.fontSizeSp} sp")
                        Row {
                            TextButton(onClick = { onFontSizeChange(display.fontSizeSp - 1) }) { Text("−") }
                            TextButton(onClick = { onFontSizeChange(display.fontSizeSp + 1) }) { Text("+") }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Giãn dòng: ${display.lineHeightPercent}%")
                        Row {
                            TextButton(onClick = { onLineHeightChange(display.lineHeightPercent - 10) }) { Text("−") }
                            TextButton(onClick = { onLineHeightChange(display.lineHeightPercent + 10) }) { Text("+") }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Lề ngang: ${display.horizontalPaddingDp} dp")
                        Row {
                            TextButton(onClick = { onHorizontalPaddingChange(display.horizontalPaddingDp - 2) }) { Text("−") }
                            TextButton(onClick = { onHorizontalPaddingChange(display.horizontalPaddingDp + 2) }) { Text("+") }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cách đoạn: ${display.paragraphSpacingDp} dp")
                        Row {
                            TextButton(onClick = { onParagraphSpacingChange(display.paragraphSpacingDp - 2) }) { Text("−") }
                            TextButton(onClick = { onParagraphSpacingChange(display.paragraphSpacingDp + 2) }) { Text("+") }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = display.keepScreenOn, onCheckedChange = onKeepScreenOnChange)
                        Text("Giữ màn hình sáng khi đọc", modifier = Modifier.padding(top = 12.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = display.volumeKeysNavigate, onCheckedChange = onVolumeKeysNavigateChange)
                        Text("Dùng phím âm lượng để chuyển đoạn", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisplayDialog = false }) { Text("XONG") } },
        )
    }
}

private data class ReaderPalette(
    val background: Color,
    val card: Color,
    val text: Color,
    val active: Color,
    val search: Color,
)

@Composable
private fun readerPalette(mode: ReaderThemeMode): ReaderPalette = when (mode) {
    ReaderThemeMode.SYSTEM -> ReaderPalette(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onBackground,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    ReaderThemeMode.LIGHT -> ReaderPalette(Color(0xFFFDFDFD), Color.White, Color(0xFF171717), Color(0xFFDCEBFF), Color(0xFFFFE8A3))
    ReaderThemeMode.DARK -> ReaderPalette(Color(0xFF111315), Color(0xFF1C1F22), Color(0xFFE9ECEF), Color(0xFF263B4D), Color(0xFF5A4A1F))
    ReaderThemeMode.SEPIA -> ReaderPalette(Color(0xFFF4ECD8), Color(0xFFF8F0DF), Color(0xFF3E3228), Color(0xFFE0D1B2), Color(0xFFFFD978))
}

@Composable
private fun ReaderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 54.dp,
    normalColor: Color = vn.nghetruyen.app.ui.components.ReferenceBlue,
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
