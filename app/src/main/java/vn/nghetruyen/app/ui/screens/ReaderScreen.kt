package vn.nghetruyen.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine
import vn.nghetruyen.app.ai.vietphrase.VietPhraseMatchMode
import vn.nghetruyen.app.ai.vietphrase.VietPhraseOptions
import vn.nghetruyen.app.ai.vietphrase.VietPhraseRule
import vn.nghetruyen.app.ai.vietphrase.VietPhraseScope
import vn.nghetruyen.app.audio.ReferenceAudioExportRuntime
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioExportFormat
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.playback.PlaybackPreparationState
import vn.nghetruyen.app.playback.ReaderPlaybackService
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.ui.ChapterTextMode
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceBlue
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferenceGreen
import vn.nghetruyen.app.ui.components.ReferencePurple
import vn.nghetruyen.app.ui.reference.ReferenceTtsDraft
import vn.nghetruyen.app.ui.reference.ReferenceTtsPersistence

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
    onSleepTimerByChapters: (Int) -> Unit,
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
    onOpenStoryAiOptions: () -> Unit,
    onOpenStoryVoiceCastOptions: () -> Unit,
    onEngineSelected: (TtsEngineOption?) -> Unit,
    onVoiceSelected: (TtsVoiceOption?) -> Unit,
    onRefreshVoices: () -> Unit,
    onPreviewVoice: () -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSonicProcessingEnabledChange: (Boolean) -> Unit,
    onOpenTtsSettings: () -> Unit,
    onSelectBackgroundMusic: () -> Unit,
    onClearBackgroundMusic: () -> Unit,
    onBackgroundMusicEnabledChange: (Boolean) -> Unit,
    onBackgroundMusicVolumeChange: (Float) -> Unit,
    onBackgroundMusicDuckChange: (Float) -> Unit,
    onAutoSceneMusicChange: (Boolean) -> Unit,
    onSceneMusicPlaybackModeChange: (SceneMusicPlaybackMode) -> Unit,
    onSceneMusicTargetLufsChange: (Float) -> Unit,
    onSelectSceneMusic: () -> Unit,
    onOpenSourceLogin: (String) -> Unit,
    onCheckSource: (String) -> Unit,
    onSourceUiAction: (String, String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val content = state.chapterContent ?: return
    val context = LocalContext.current
    val app = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val display = state.readerDisplay
    val textMode = state.readerMode == ReaderMode.TEXT
    val storyId = content.chapter.storyId
    val activeIndex = state.playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))
    val storyDetail = state.storyDetail
    val sourceDescriptor = storyDetail?.story?.sourceId?.let { id -> state.sources.firstOrNull { it.id == id } }

    var showReaderOptions by remember(content.chapter.id) { mutableStateOf(false) }
    var showReaderModeDialog by remember { mutableStateOf(false) }
    var showDisplayDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showSearchResults by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showTtsDialog by remember { mutableStateOf(false) }
    var showMusicDialog by remember { mutableStateOf(false) }
    var showMusicLibrary by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<SceneMusicTrackEntity?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showChapterInfoDialog by remember { mutableStateOf(false) }
    var showDiagnosticLogDialog by remember { mutableStateOf(false) }
    var showVietPhraseLogDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteDraft by remember(content.chapter.id, activeIndex) { mutableStateOf("") }
    var searchDraft by remember(content.chapter.id) { mutableStateOf("") }
    var searchQuery by remember(content.chapter.id) { mutableStateOf("") }
    var searchResultPosition by remember(content.chapter.id) { mutableIntStateOf(0) }
    val normalizedQuery = StorySearch.normalize(searchQuery)
    val searchMatches = remember(content.chapter.id, content.paragraphs, normalizedQuery) {
        if (normalizedQuery.isBlank()) emptyList()
        else content.paragraphs.indices.filter { StorySearch.normalize(content.paragraphs[it]).contains(normalizedQuery) }
    }
    val activeNote = state.notes.firstOrNull { it.chapterId == content.chapter.id && it.paragraphIndex == activeIndex }

    var ttsDraft by remember(content.chapter.id) { mutableStateOf(ReferenceTtsDraft()) }
    var useStoryTts by remember(content.chapter.id, state.storyTtsProfiles) { mutableStateOf(state.storyTtsProfiles.containsKey(storyId)) }
    var ttsVoices by remember { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }
    var ttsLoading by remember { mutableStateOf(false) }

    var musicEnabled by remember { mutableStateOf(state.backgroundMusicEnabled) }
    var musicAi by remember { mutableStateOf(state.autoSceneMusicEnabled) }
    var musicMode by remember { mutableStateOf(if (state.sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE) SceneMusicPlaybackMode.SHUFFLE else SceneMusicPlaybackMode.SEQUENTIAL) }
    var musicTargetLufs by remember { mutableStateOf(state.sceneMusicTargetLufs.coerceIn(-36f, -18f)) }
    var musicDuckDb by remember { mutableStateOf((-20.0 * log10(state.backgroundMusicDuckFactor.coerceAtLeast(0.0630957f).toDouble())).toFloat().coerceIn(0f, 24f)) }
    var musicAttackMs by remember { mutableIntStateOf(250) }
    var musicReleaseMs by remember { mutableIntStateOf(900) }

    DisposableEffect(view, display.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = display.keepScreenOn
        onDispose {
            view.keepScreenOn = previous
            scope.launch { ReferenceTtsPersistence.restoreGlobal(context) }
        }
    }

    LaunchedEffect(content.chapter.id, state.storyTtsProfiles.containsKey(storyId)) {
        ReferenceTtsPersistence.activateStoryOverride(context, storyId, state.storyTtsProfiles.containsKey(storyId))
    }

    LaunchedEffect(showTtsDialog) {
        if (showTtsDialog) {
            useStoryTts = state.storyTtsProfiles.containsKey(storyId)
            ttsDraft = ReferenceTtsPersistence.load(context, storyId, useStoryTts)
            ttsLoading = true
            ttsVoices = when (val result = app.container.ttsVoiceCatalog.load(ttsDraft.enginePackage)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> emptyList()
            }
            ttsLoading = false
        }
    }

    LaunchedEffect(showMusicDialog) {
        if (showMusicDialog) {
            val settings = app.container.settingsRepository.snapshot()
            musicEnabled = settings.backgroundMusicEnabled
            musicAi = settings.autoSceneMusicEnabled
            musicMode = if (settings.sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE) SceneMusicPlaybackMode.SHUFFLE else SceneMusicPlaybackMode.SEQUENTIAL
            musicTargetLufs = settings.sceneMusicTargetLufs.coerceIn(-36f, -18f)
            musicDuckDb = (-20.0 * log10(settings.backgroundMusicDuckFactor.coerceAtLeast(0.0630957f).toDouble())).toFloat().coerceIn(0f, 24f)
            musicAttackMs = settings.backgroundMusicAttackMillis
            musicReleaseMs = settings.backgroundMusicReleaseMillis
        }
    }

    LaunchedEffect(activeIndex, textMode) {
        if (textMode && content.paragraphs.isNotEmpty() && normalizedQuery.isBlank() && !listState.isScrollInProgress) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == activeIndex + 1 }) {
                listState.scrollToItem((activeIndex + 1).coerceAtMost(content.paragraphs.size))
            }
        }
    }
    LaunchedEffect(content.chapter.id, state.readerMode) {
        delay(120)
        view.announceForAccessibility("${content.chapter.title}. Chế độ ${if (textMode) "Văn bản" else "TTS"}. ${content.paragraphs.size} đoạn")
    }

    val palette = readerPalette(display.theme)
    Surface(modifier = Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
        Column(Modifier.fillMaxSize()) {
            ReaderButton("TÙY CHỌN", { showReaderOptions = true }, Modifier.fillMaxWidth(), normalColor = ReferenceGray, minHeight = 58.dp)
            if (textMode) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "reader-title") {
                        Text(content.chapter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = palette.text, modifier = Modifier.fillMaxWidth().semantics { heading() }.padding(12.dp))
                    }
                    items(count = content.paragraphs.size, key = { "${content.chapter.id}:$it" }) { index ->
                        Text(
                            content.paragraphs[index],
                            color = palette.text,
                            fontSize = display.fontSizeSp.sp,
                            lineHeight = (display.fontSizeSp * display.lineHeightPercent / 100f).sp,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (index in searchMatches) palette.search else Color.Transparent)
                                .padding(horizontal = display.horizontalPaddingDp.dp, vertical = (display.paragraphSpacingDp / 2f).dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1f), minHeight = 64.dp)
                    ReaderButton("SAU", onNextChapter, Modifier.weight(1f), minHeight = 64.dp)
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(content.chapter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = palette.text)
                    Text("Đoạn ${activeIndex + 1}/${content.paragraphs.size.coerceAtLeast(1)}", color = palette.text, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth()) {
                    ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1.2f), minHeight = 64.dp)
                    ReaderButton("LÙI", onRewind, Modifier.weight(1f), minHeight = 64.dp)
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
                        selected = state.playback.isPlaying,
                    )
                    ReaderButton("TỚI", onForward, Modifier.weight(1f), minHeight = 64.dp)
                    ReaderButton("SAU", onNextChapter, Modifier.weight(1.2f), minHeight = 64.dp)
                }
            }

            val customActions = sourceDescriptor?.uiActions.orEmpty()
                .filter { vn.nghetruyen.app.sources.SourceUiSurface.READER in it.surfaces }
                .sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
            if (sourceDescriptor != null && customActions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    customActions.forEach { action -> ReaderButton(action.label, { onSourceUiAction(sourceDescriptor.id, action.id) }, Modifier.padding(1.dp), normalColor = ReferenceGray) }
                }
            }
            if (sourceDescriptor != null && (sourceDescriptor.loginUrl != null || sourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)) {
                Row(Modifier.fillMaxWidth()) {
                    if (sourceDescriptor.loginUrl != null) {
                        ReaderButton(if (sourceDescriptor.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN", { onOpenSourceLogin(sourceDescriptor.id) }, Modifier.weight(1f), normalColor = ReferenceGray)
                    }
                    ReaderButton(if (sourceDescriptor.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN", { onCheckSource(sourceDescriptor.id) }, Modifier.weight(1f), enabled = sourceDescriptor.id !in state.sourceHealthChecking, normalColor = ReferenceGray)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ReaderButton("XEM NHẬT KÝ", { showDiagnosticLogDialog = true }, Modifier.weight(1f), normalColor = ReferenceGray)
                ReaderButton(if (state.aiBusy) "AI ĐANG CHẠY…" else if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) "DỊCH LẠI" else "DỊCH AI", onAiTranslate, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = ReferencePurple)
                ReaderButton("PHÂN VAI AI", onVoiceCast, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = Color(0xFFAF52DE))
            }
        }
    }

    if (showReaderOptions) {
        AlertDialog(
            onDismissRequest = { showReaderOptions = false },
            title = { Text("TÙY CHỌN ĐỌC") },
            text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("TRỞ LẠI DANH SÁCH CHƯƠNG") { showReaderOptions = false; onBackToChapters() }
                ReaderMenuButton("LƯU VỊ TRÍ ĐỌC") { showReaderOptions = false; onSaveReadingPosition() }
                ReaderMenuButton("TÌM TRONG CHƯƠNG") { showReaderOptions = false; searchDraft = ""; showSearchDialog = true }
                if (textMode) ReaderMenuButton("HIỂN THỊ VĂN BẢN") { showReaderOptions = false; showDisplayDialog = true }
                ReaderMenuButton("HẸN GIỜ NGỦ - ${state.sleepTimerStatus}") { showReaderOptions = false; showSleepDialog = true }
                ReaderMenuButton("NHẠC NỀN") { showReaderOptions = false; showMusicDialog = true }
                ReaderMenuButton(if (textMode) "XUẤT ÂM THANH (CẦN CHẾ ĐỘ TTS)" else "XUẤT ÂM THANH") {
                    showReaderOptions = false
                    if (textMode) onMessage("XUẤT ÂM THANH chỉ hoạt động trong chế độ TTS.") else showExportDialog = true
                }
                ReaderMenuButton("CHẾ ĐỘ ĐỌC: ${if (textMode) "VĂN BẢN" else "TTS"}") { showReaderOptions = false; showReaderModeDialog = true }
                ReaderMenuButton("THIẾT LẬP AI CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryAiOptions() }
                ReaderMenuButton("PHÂN VAI TTS CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryVoiceCastOptions() }
                if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) {
                    ReaderMenuButton("KHÔI PHỤC CHƯƠNG GỐC TRƯỚC AI") { showReaderOptions = false; onShowOriginal() }
                }
                if (state.vietPhraseRules.isNotEmpty() || state.vietPhraseDictionaryStates.isNotEmpty()) {
                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") { showReaderOptions = false; showVietPhraseLogDialog = true }
                }
                ReaderMenuButton("CÀI ĐẶT TTS") { showReaderOptions = false; showTtsDialog = true }
                ReaderMenuButton("SAO CHÉP CHƯƠNG") { showReaderOptions = false; showCopyDialog = true }
                ReaderMenuButton("THÔNG TIN CHƯƠNG") { showReaderOptions = false; showChapterInfoDialog = true }
            } },
            confirmButton = { TextButton(onClick = { showReaderOptions = false }) { Text("ĐÓNG") } },
        )
    }

    if (showReaderModeDialog) {
        AlertDialog(
            onDismissRequest = { showReaderModeDialog = false },
            title = { Text("CHẾ ĐỘ ĐỌC") },
            text = { Column {
                ReaderMenuButton("VĂN BẢN" + if (textMode) " - ĐANG DÙNG" else "") { showReaderModeDialog = false; onReaderModeChange(ReaderMode.TEXT) }
                ReaderMenuButton("TTS" + if (!textMode) " - ĐANG DÙNG" else "") { showReaderModeDialog = false; onReaderModeChange(ReaderMode.TTS) }
            } },
            confirmButton = { TextButton(onClick = { showReaderModeDialog = false }) { Text("HỦY") } },
        )
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("TÌM TRONG CHƯƠNG") },
            text = { OutlinedTextField(searchDraft, { searchDraft = it.take(160) }, placeholder = { Text("Nhập từ hoặc câu cần tìm") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = searchDraft.isNotBlank(), onClick = {
                searchQuery = searchDraft
                searchResultPosition = 0
                showSearchDialog = false
                showSearchResults = true
                if (!textMode) onReaderModeChange(ReaderMode.TEXT)
            }) { Text("TÌM") } },
            dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text("HỦY") } },
        )
    }

    if (showSearchResults) {
        AlertDialog(
            onDismissRequest = { showSearchResults = false },
            title = { Text("TÌM THẤY ${searchMatches.size} KẾT QUẢ") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (searchMatches.isEmpty()) Text("Không tìm thấy nội dung phù hợp.")
                searchMatches.take(100).forEachIndexed { resultIndex, paragraphIndex ->
                    val snippet = content.paragraphs[paragraphIndex].replace('\n', ' ').take(140)
                    TextButton(onClick = {
                        searchResultPosition = resultIndex
                        onParagraphSelected(paragraphIndex)
                        scope.launch { listState.animateScrollToItem((paragraphIndex + 1).coerceAtMost(content.paragraphs.size)) }
                        showSearchResults = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Đoạn ${paragraphIndex + 1}: $snippet") }
                }
            } },
            confirmButton = { TextButton(onClick = { showSearchResults = false }) { Text("ĐÓNG") } },
        )
    }

    if (showDisplayDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayDialog = false },
            title = { Text("HIỂN THỊ VĂN BẢN") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueStepper("Cỡ chữ", "${display.fontSizeSp} sp", { onFontSizeChange(display.fontSizeSp - 1) }, { onFontSizeChange(display.fontSizeSp + 1) })
                ValueStepper("Khoảng cách dòng", "${display.lineHeightPercent}%", { onLineHeightChange(display.lineHeightPercent - 10) }, { onLineHeightChange(display.lineHeightPercent + 10) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Chế độ nền tối khi đọc", Modifier.weight(1f)); Switch(display.theme == ReaderThemeMode.DARK, { onThemeChange(if (it) ReaderThemeMode.DARK else ReaderThemeMode.LIGHT) }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Giữ màn hình sáng khi đọc", Modifier.weight(1f)); Switch(display.keepScreenOn, onKeepScreenOnChange) }
            } },
            confirmButton = { TextButton(onClick = { showDisplayDialog = false }) { Text("LƯU") } },
        )
    }

    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("HẸN GIỜ NGỦ - ${state.sleepTimerStatus}") },
            text = { Column {
                listOf(15, 30, 45, 60).forEach { minutes -> ReaderMenuButton("$minutes PHÚT") { showSleepDialog = false; onSleepTimer(minutes) } }
                ReaderMenuButton("HẾT CHƯƠNG HIỆN TẠI") { showSleepDialog = false; onSleepTimerByChapters(1) }
                ReaderMenuButton("HẾT 3 CHƯƠNG") { showSleepDialog = false; onSleepTimerByChapters(3) }
                ReaderMenuButton("TẮT HẸN GIỜ") { showSleepDialog = false; onSleepTimer(null) }
            } },
            confirmButton = { TextButton(onClick = { showSleepDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showTtsDialog) {
        var engineExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }
        var voiceExpanded by remember { mutableStateOf(false) }
        val languages = ttsVoices.map { it.languageTag }.filter(String::isNotBlank).distinct().sorted()
        val visibleVoices = ttsVoices.filter { ttsDraft.languageTag.isBlank() || it.languageTag == ttsDraft.languageTag }
        AlertDialog(
            onDismissRequest = { showTtsDialog = false },
            title = { Text("CÀI ĐẶT TTS") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { useStoryTts = !useStoryTts }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useStoryTts, { useStoryTts = it })
                    Text("Dùng cài đặt TTS riêng cho truyện này")
                }
                Text("Bộ đọc TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { engineExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(state.ttsEngines.firstOrNull { it.packageName == ttsDraft.enginePackage }?.label ?: "Mặc định hệ thống")
                }
                DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    DropdownMenuItem(text = { Text("Mặc định hệ thống") }, onClick = {
                        ttsDraft = ttsDraft.copy(enginePackage = null, voiceName = null); engineExpanded = false
                        scope.launch { ttsLoading = true; ttsVoices = (app.container.ttsVoiceCatalog.load(null) as? AppResult.Success)?.value.orEmpty(); ttsLoading = false }
                    })
                    state.ttsEngines.forEach { engine -> DropdownMenuItem(text = { Text(engine.label) }, onClick = {
                        ttsDraft = ttsDraft.copy(enginePackage = engine.packageName, voiceName = null); engineExpanded = false
                        scope.launch { ttsLoading = true; ttsVoices = (app.container.ttsVoiceCatalog.load(engine.packageName) as? AppResult.Success)?.value.orEmpty(); ttsLoading = false }
                    }) }
                }
                Text(if (ttsLoading) "Đang quét bộ đọc và giọng…" else "Đã nhận ${state.ttsEngines.size} bộ đọc.", style = MaterialTheme.typography.bodySmall)
                ReaderMenuButton("QUÉT LẠI BỘ ĐỌC") { onRefreshVoices() }
                ReaderMenuButton("SAO CHÉP CHẨN ĐOÁN BỘ ĐỌC") {
                    clipboard.setText(AnnotatedString("Bộ đọc: ${ttsDraft.enginePackage ?: "Mặc định hệ thống"}\nNgôn ngữ: ${ttsDraft.languageTag}\nGiọng: ${ttsDraft.voiceName ?: "Mặc định"}\nSố bộ đọc/giọng: ${state.ttsEngines.size}/${ttsVoices.size}"))
                    onMessage("Đã sao chép chẩn đoán bộ đọc.")
                }
                Text("Ngôn ngữ", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { languageExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(ttsDraft.languageTag.ifBlank { "vi-VN" }) }
                DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                    (if (languages.isEmpty()) listOf("vi-VN") else languages).forEach { lang -> DropdownMenuItem(text = { Text(lang) }, onClick = { ttsDraft = ttsDraft.copy(languageTag = lang, voiceName = null); languageExpanded = false }) }
                }
                Text("Giọng đọc", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { voiceExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(visibleVoices.firstOrNull { it.name == ttsDraft.voiceName }?.displayName ?: "Giọng mặc định") }
                DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    DropdownMenuItem(text = { Text("Giọng mặc định") }, onClick = { ttsDraft = ttsDraft.copy(voiceName = null); voiceExpanded = false })
                    visibleVoices.forEach { voice -> DropdownMenuItem(text = { Text(voice.displayName) }, onClick = { ttsDraft = ttsDraft.copy(voiceName = voice.name, languageTag = voice.languageTag); voiceExpanded = false }) }
                }
                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ ttsDraft = ttsDraft.copy(processingMethod = "system", volume = ttsDraft.volume.coerceAtMost(1f)) }, Modifier.weight(1f)) { Text((if (ttsDraft.processingMethod == "system") "✓ " else "") + "Android, tối đa 100%") }
                    TextButton({ ttsDraft = ttsDraft.copy(processingMethod = "sonic") }, Modifier.weight(1f)) { Text((if (ttsDraft.processingMethod == "sonic") "✓ " else "") + "Sonic, tối đa 200%") }
                }
                if (ttsDraft.processingMethod == "sonic") {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ ttsDraft = ttsDraft.copy(sonicAccurate = false) }, Modifier.weight(1f)) { Text((if (!ttsDraft.sonicAccurate) "✓ " else "") + "Nhanh") }
                        TextButton({ ttsDraft = ttsDraft.copy(sonicAccurate = true) }, Modifier.weight(1f)) { Text((if (ttsDraft.sonicAccurate) "✓ " else "") + "Chính xác") }
                    }
                }
                TtsSlider("Tốc độ đọc", ttsDraft.speed, 0.25f, 3f) { ttsDraft = ttsDraft.copy(speed = it) }
                TtsSlider("Cao độ", ttsDraft.pitch, 0.5f, 2f) { ttsDraft = ttsDraft.copy(pitch = it) }
                TtsSlider("Âm lượng", ttsDraft.volume, 0f, if (ttsDraft.processingMethod == "sonic") 2f else 1f, percent = true) { ttsDraft = ttsDraft.copy(volume = it) }
                ReaderMenuButton("NGHE THỬ") {
                    ReaderPlaybackService.previewRole(context, "Đây là giọng đọc thử của Nghe Truyện.", ReferenceTtsPersistence.previewDraft(ttsDraft))
                }
                ReaderMenuButton("KHÔI PHỤC MẶC ĐỊNH") {
                    ttsDraft = ReferenceTtsDraft()
                }
                ReaderMenuButton("LƯU CÀI ĐẶT TTS") {
                    scope.launch {
                        ReferenceTtsPersistence.save(context, storyId, ttsDraft, useStoryTts)
                        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                        onMessage("Đã lưu cài đặt TTS.")
                        showTtsDialog = false
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { showTtsDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("NHẠC NỀN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Bật nhạc nền khi đọc bằng TTS", Modifier.weight(1f)); Switch(musicEnabled, { musicEnabled = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Trao toàn quyền giữ và đổi nhạc cho AI", Modifier.weight(1f)); Switch(musicAi, { musicAi = it }) }
                Text("Chế độ phát", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ musicMode = SceneMusicPlaybackMode.SEQUENTIAL }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SEQUENTIAL) "✓ " else "") + "PHÁT LẦN LƯỢT") }
                    TextButton({ musicMode = SceneMusicPlaybackMode.SHUFFLE }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "✓ " else "") + "PHÁT NGẪU NHIÊN") }
                }
                Text("CÂN BẰNG ÂM THANH", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("Mức chuẩn hóa: ${"%.1f".format(musicTargetLufs)} LUFS")
                Slider(musicTargetLufs, { musicTargetLufs = it }, valueRange = -36f..-18f, steps = 35)
                Text("Giảm khi giọng đọc phát: ${"%.1f".format(musicDuckDb)} dB")
                Slider(musicDuckDb, { musicDuckDb = it }, valueRange = 0f..24f, steps = 47)
                Text("Attack: $musicAttackMs ms")
                Slider(musicAttackMs.toFloat(), { musicAttackMs = it.toInt() }, valueRange = 0f..2000f, steps = 39)
                Text("Release: $musicReleaseMs ms")
                Slider(musicReleaseMs.toFloat(), { musicReleaseMs = it.toInt() }, valueRange = 0f..5000f, steps = 99)
                ReaderMenuButton("CHUẨN HÓA TOÀN BỘ KHO NHẠC") {
                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id) }
                    onMessage("Đã đưa toàn bộ kho nhạc vào hàng đợi chuẩn hóa.")
                }
                Text("Kho nhạc: ${state.sceneMusicTracks.size} mục", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") { showMusicLibrary = true }
                Text("AI chỉ đổi nhạc khi tùy chọn trao quyền ở trên được bật.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            } },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    val settings = app.container.settingsRepository
                    settings.setBackgroundMusicEnabled(musicEnabled)
                    settings.setAutoSceneMusicEnabled(musicAi)
                    settings.setSceneMusicPlaybackMode(musicMode)
                    settings.setSceneMusicTargetLufs(musicTargetLufs)
                    settings.setBackgroundMusicDuckFactor(10.0.pow(-musicDuckDb / 20.0).toFloat())
                    settings.setBackgroundMusicAttackMillis(musicAttackMs)
                    settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                    onMessage("Đã lưu cài đặt nhạc nền.")
                    showMusicDialog = false
                }
            }) { Text("LƯU CÀI ĐẶT") } },
            dismissButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMusicLibrary) {
        AlertDialog(
            onDismissRequest = { showMusicLibrary = false },
            title = { Text("QUẢN LÝ DANH SÁCH NHẠC") },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("THÊM TỆP NHẠC") { onSelectSceneMusic() }
                state.sceneMusicTracks.forEach { track ->
                    ReferenceActionButton(track.title + track.tagsCsv.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(), { editingTrack = track }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                }
            } },
            confirmButton = { TextButton(onClick = { showMusicLibrary = false }) { Text("ĐÓNG") } },
        )
    }

    editingTrack?.let { track ->
        var title by remember(track.id, track.title) { mutableStateOf(track.title) }
        var tags by remember(track.id, track.tagsCsv) { mutableStateOf(track.tagsCsv) }
        AlertDialog(
            onDismissRequest = { editingTrack = null },
            title = { Text("SỬA TỆP NHẠC") },
            text = { Column {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Tên hiển thị") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it.take(500) }, label = { Text("Mô tả / tag") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                TextButton(onClick = { clipboard.getText()?.text?.takeIf(String::isNotBlank)?.let { tags = it.take(500) } }) { Text("DÁN MÔ TẢ") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Bật tệp này", Modifier.weight(1f)); Switch(track.enabled, { enabled -> scope.launch { app.container.database.sceneMusicTrackDao().setEnabled(track.id, enabled, System.currentTimeMillis()) } }) }
            } },
            confirmButton = { TextButton(onClick = {
                scope.launch { app.container.database.sceneMusicTrackDao().upsert(track.copy(title = title.trim().ifBlank { track.title }, tagsCsv = tags.trim(), updatedAt = System.currentTimeMillis())) }
                editingTrack = null
            }) { Text("LƯU") } },
            dismissButton = { Row {
                TextButton(onClick = { scope.launch { app.container.database.sceneMusicTrackDao().delete(track.id) }; editingTrack = null }) { Text("XÓA") }
                TextButton(onClick = { editingTrack = null }) { Text("HỦY") }
            } },
        )
    }

    if (showExportDialog) {
        var fileName by remember(showExportDialog) { mutableStateOf(content.chapter.title.take(120).ifBlank { "chuong-${content.chapter.index + 1}" }) }
        var format by remember(showExportDialog) { mutableStateOf(AudioExportFormat.MP3) }
        var formatExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("XUẤT ÂM THANH") },
            text = { Column {
                OutlinedTextField(fileName, { fileName = it.replace(Regex("[\\/:*?\"<>|]"), "_").take(180) }, placeholder = { Text("Nhập tên tệp âm thanh") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Định dạng", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = { formatExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(format.name) }
                DropdownMenu(expanded = formatExpanded, onDismissRequest = { formatExpanded = false }) {
                    listOf(AudioExportFormat.WAV, AudioExportFormat.M4A, AudioExportFormat.MP3).forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { format = item; formatExpanded = false }) }
                }
            } },
            confirmButton = { TextButton(enabled = fileName.isNotBlank(), onClick = {
                ReferenceAudioExportRuntime.setNextFileName("${fileName.trim()}.${format.extension}")
                showExportDialog = false
                when (format) {
                    AudioExportFormat.WAV -> onExportChapterWav()
                    AudioExportFormat.M4A -> onExportChapterM4a()
                    AudioExportFormat.MP3 -> onExportChapterMp3()
                }
            }) { Text("CHỌN NƠI LƯU VÀ BẮT ĐẦU") } },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("HỦY") } },
        )
    }

    if (showCopyDialog) {
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("SAO CHÉP CHƯƠNG") },
            text = { Column {
                ReaderMenuButton("CHỈ NỘI DUNG CHƯƠNG") {
                    clipboard.setText(AnnotatedString(content.paragraphs.joinToString("\n\n"))); showCopyDialog = false; onMessage("Đã sao chép nội dung chương.")
                }
                ReaderMenuButton("TÊN CHƯƠNG VÀ NỘI DUNG") {
                    clipboard.setText(AnnotatedString(content.chapter.title + "\n\n" + content.paragraphs.joinToString("\n\n"))); showCopyDialog = false; onMessage("Đã sao chép tên và nội dung chương.")
                }
            } },
            confirmButton = { TextButton(onClick = { showCopyDialog = false }) { Text("HỦY") } },
        )
    }

    if (showChapterInfoDialog) {
        val chapterPosition = storyDetail?.chapters?.indexOfFirst { it.id == content.chapter.id }?.takeIf { it >= 0 }?.plus(1)
        val total = storyDetail?.chapters?.size ?: 0
        val offline = state.offlineStorage[storyId]?.chapterCount?.let { it > 0 } == true
        AlertDialog(
            onDismissRequest = { showChapterInfoDialog = false },
            title = { Text("THÔNG TIN CHƯƠNG") },
            text = { Text("Tên truyện: ${storyDetail?.story?.title ?: "Truyện"}\n\nTên chương: ${content.chapter.title}\n\nNguồn: ${storyDetail?.story?.sourceId ?: "Không rõ"}\n\nChế độ đọc: ${if (textMode) "Văn bản" else "TTS"}\n\nĐã lưu ngoại tuyến: ${if (offline) "Có" else "Không"}\n\nVị trí trong danh sách: ${chapterPosition ?: "?"} / ${if (total > 0) total else "?"}") },
            confirmButton = { TextButton(onClick = { showChapterInfoDialog = false }) { Text("ĐÓNG") } },
            dismissButton = {
                if (content.chapter.url.startsWith("http://") || content.chapter.url.startsWith("https://")) {
                    TextButton(onClick = { showChapterInfoDialog = false; app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(content.chapter.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("MỞ TRANG GỐC") }
                }
            },
        )
    }

    if (showVietPhraseLogDialog) {
        val rawParagraphs = state.originalChapterContent?.paragraphs ?: content.paragraphs
        val rules = state.vietPhraseRules.mapNotNull { item ->
            runCatching {
                VietPhraseRule(
                    id = item.id.toString(), source = item.source, target = item.target,
                    kind = VietPhraseDictionaryKind.valueOf(item.kind), priority = item.priority, enabled = item.enabled,
                    scope = VietPhraseScope.valueOf(item.scope), storyId = item.storyId.takeIf(String::isNotBlank),
                    matchMode = VietPhraseMatchMode.valueOf(item.matchMode), ignoreCase = item.ignoreCase, updatedAt = item.updatedAt,
                )
            }.getOrNull()
        }
        val result = remember(content.chapter.id, rules, rawParagraphs) {
            runCatching { VietPhraseEngine(rules).translateWithTrace(rawParagraphs.joinToString("\n"), VietPhraseOptions(storyId = storyId, traceLimit = 2_000)) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { showVietPhraseLogDialog = false },
            title = { Text("NHẬT KÝ VIETPHRASE") },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                if (result == null) Text("Không tạo được nhật ký VietPhrase.")
                else {
                    Text("Đã áp dụng ${result.trace.size} lượt thay thế${if (result.traceTruncated) " (đã rút gọn)" else ""}.", fontWeight = FontWeight.SemiBold)
                    result.appliedByKind.forEach { (kind, count) -> Text("${kind.fileName}: $count", style = MaterialTheme.typography.bodySmall) }
                    result.trace.take(300).forEach { entry -> Text("${entry.kind?.fileName ?: "?"}: ${entry.source} → ${entry.replacement}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
                }
            } },
            confirmButton = { TextButton(onClick = { showVietPhraseLogDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showDiagnosticLogDialog) {
        val sourceId = storyDetail?.story?.sourceId ?: storyId
        val events = state.sourceDiagnostics.filter { it.sourceId == sourceId }.take(40)
        AlertDialog(
            onDismissRequest = { showDiagnosticLogDialog = false },
            title = { Text("NHẬT KÝ CHẨN ĐOÁN") },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text("Mức: ${state.diagnosticsMode}", fontWeight = FontWeight.SemiBold)
                events.forEach { event -> Text("${event.severity} • ${event.category}/${event.name}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp)) }
                if (events.isEmpty()) Text("Chưa có sự kiện chẩn đoán cho nguồn hiện tại.")
            } },
            confirmButton = { TextButton(onClick = { showDiagnosticLogDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Ghi chú đoạn ${activeIndex + 1}") },
            text = { OutlinedTextField(noteDraft, { noteDraft = it.take(4_000) }, label = { Text("Nội dung ghi chú") }, minLines = 4, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = noteDraft.isNotBlank(), onClick = { onSaveNote(noteDraft); showNoteDialog = false }) { Text("LƯU") } },
            dismissButton = { Row { if (activeNote != null) TextButton(onClick = { onDeleteNote(activeNote.id); showNoteDialog = false }) { Text("XÓA") }; TextButton(onClick = { showNoteDialog = false }) { Text("HỦY") } } },
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
    ReferenceActionButton(text = text, onClick = onClick, modifier = modifier.padding(1.dp), accessibilityLabel = accessibilityLabel, selected = selected, enabled = enabled, minHeight = minHeight, selectedColor = selectedColor, normalColor = normalColor)
}

@Composable
private fun ValueStepper(label: String, value: String, less: () -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $value", Modifier.weight(1f))
        TextButton(less) { Text("−") }
        TextButton(more) { Text("+") }
    }
}

@Composable
private fun TtsSlider(label: String, value: Float, min: Float, max: Float, percent: Boolean = false, onChange: (Float) -> Unit) {
    val shown = value.coerceIn(min, max)
    Text(if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(shown, onChange, valueRange = min..max)
}
