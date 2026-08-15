package vn.nghetruyen.app.ui.screens

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow
import java.util.UUID
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.StoryVoiceCastMode
import vn.nghetruyen.app.ai.StoryVoiceCastReferenceCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDiagnosticExport
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDiagnosticExporter
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine
import vn.nghetruyen.app.ai.vietphrase.VietPhraseMatchMode
import vn.nghetruyen.app.ai.vietphrase.VietPhraseOptions
import vn.nghetruyen.app.ai.vietphrase.VietPhraseRule
import vn.nghetruyen.app.ai.vietphrase.VietPhraseScope
import vn.nghetruyen.app.audio.ReferenceAudioExportRuntime
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.audio.AudioExportRequest
import vn.nghetruyen.app.audio.AudioExportScope
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioExportFormat
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.data.settings.AiProvider
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
    onExportAudio: (AudioExportRequest) -> Unit,
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
    onSaveVoiceRole: (VoiceRoleDraft) -> Unit,
    onPreviewVoiceRole: (VoiceRoleDraft) -> Unit,
    onDeleteVoiceRole: (String) -> Unit,
    onSaveAiProfile: (String, Boolean, AiProvider, String, String, Float, Boolean, String, String, Boolean, Boolean, String, String, Boolean, Boolean, Boolean, String, Int, Int, Int) -> Unit,
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
    val storyAiProfile = state.storyAiProfiles[storyId]
    val storyVoiceReference = storyAiProfile?.let { StoryVoiceCastReferenceCodec.decode(it.voiceCastNote) }
    val effectiveAutoVoiceCastEnabled = state.autoVoiceCastEnabled && when {
        storyAiProfile == null -> false
        !StoryVoiceCastReferenceCodec.hasStoredSettings(storyAiProfile.voiceCastNote) -> false
        storyVoiceReference?.mode == StoryVoiceCastMode.OFF -> false
        else -> storyVoiceReference?.autoRunOnOpenTts == true
    }
    val activeIndex = state.playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))
    val storyDetail = state.storyDetail
    val sourceDescriptor = storyDetail?.story?.sourceId?.let { id -> state.sources.firstOrNull { it.id == id } }

    var showReaderOptions by remember(content.chapter.id) { mutableStateOf(false) }
    var showReaderModeDialog by remember { mutableStateOf(false) }
    var showDisplayDialog by remember { mutableStateOf(false) }
    var storyAdvancedMode by remember(content.chapter.id) { mutableStateOf<String?>(null) }
    var displayFontSizeDraft by remember(content.chapter.id) { mutableIntStateOf(display.fontSizeSp) }
    var displayLineHeightDraft by remember(content.chapter.id) { mutableIntStateOf(display.lineHeightPercent) }
    var displayDarkDraft by remember(content.chapter.id) { mutableStateOf(display.theme == ReaderThemeMode.DARK) }
    var displayKeepScreenDraft by remember(content.chapter.id) { mutableStateOf(display.keepScreenOn) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showSearchResults by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showTtsDialog by remember { mutableStateOf(false) }
    var showMusicDialog by remember { mutableStateOf(false) }
    var showMusicLibrary by remember { mutableStateOf(false) }
    var musicLibraryDraft by remember { mutableStateOf<List<SceneMusicTrackEntity>>(emptyList()) }
    var musicLibraryBaselineIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var musicSearch by remember { mutableStateOf("") }
    var selectedMusicTrackId by remember { mutableStateOf<String?>(null) }
    var editingTrack by remember { mutableStateOf<SceneMusicTrackEntity?>(null) }
    var showMusicBulkDialog by remember { mutableStateOf(false) }
    var musicBulkText by remember { mutableStateOf("") }
    var showMusicBulkResult by remember { mutableStateOf(false) }
    var musicBulkUpdates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var musicBulkErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMusicNormalizationProgress by remember { mutableStateOf(false) }
    var musicNormalizationWorkIds by remember { mutableStateOf<List<UUID>>(emptyList()) }
    var musicNormalizationDone by remember { mutableIntStateOf(0) }
    var musicNormalizationFailed by remember { mutableIntStateOf(0) }
    var musicNormalizationCancelled by remember { mutableIntStateOf(0) }
    var musicNormalizationTarget by remember { mutableStateOf(-24f) }
    var musicNormalizationRunToken by remember { mutableIntStateOf(0) }
    var showMusicClearAllConfirm by remember { mutableStateOf(false) }
    var musicPreviewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showChapterInfoDialog by remember { mutableStateOf(false) }
    var vietPhraseDiagnosticBusy by remember(content.chapter.id) { mutableStateOf(false) }
    var vietPhraseDiagnosticResult by remember(content.chapter.id) { mutableStateOf<VietPhraseDiagnosticExport?>(null) }
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
    var musicMode by remember { mutableStateOf(state.sceneMusicPlaybackMode) }
    var musicTargetLufs by remember { mutableStateOf(state.sceneMusicTargetLufs.coerceIn(-36f, -18f)) }
    var musicDuckDb by remember { mutableStateOf((-20.0 * log10(state.backgroundMusicDuckFactor.coerceAtLeast(0.0630957f).toDouble())).toFloat().coerceIn(0f, 24f)) }
    var musicAttackMs by remember { mutableIntStateOf(1850) }
    var musicReleaseMs by remember { mutableIntStateOf(2050) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { musicPreviewPlayer?.stop() }
            runCatching { musicPreviewPlayer?.release() }
            musicPreviewPlayer = null
        }
    }

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

    LaunchedEffect(showDisplayDialog) {
        if (showDisplayDialog) {
            displayFontSizeDraft = display.fontSizeSp
            displayLineHeightDraft = display.lineHeightPercent
            displayDarkDraft = display.theme == ReaderThemeMode.DARK
            displayKeepScreenDraft = display.keepScreenOn
        }
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
            musicMode = if (settings.sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE) SceneMusicPlaybackMode.SHUFFLE else SceneMusicPlaybackMode.SEQUENTIAL
            musicTargetLufs = settings.sceneMusicTargetLufs.coerceIn(-36f, -18f)
            musicDuckDb = (-20.0 * log10(settings.backgroundMusicDuckFactor.coerceAtLeast(0.0630957f).toDouble())).toFloat().coerceIn(0f, 24f)
            musicAttackMs = settings.backgroundMusicAttackMillis
            musicReleaseMs = settings.backgroundMusicReleaseMillis
        }
    }

    LaunchedEffect(state.sceneMusicTracks, showMusicLibrary) {
        if (showMusicLibrary) {
            val draftIds = musicLibraryDraft.mapTo(linkedSetOf()) { it.id }
            val added = state.sceneMusicTracks.filter { it.id !in musicLibraryBaselineIds && it.id !in draftIds }
            if (added.isNotEmpty()) {
                musicLibraryDraft = (musicLibraryDraft + added)
                    .take(500)
                    .mapIndexed { index, row -> row.copy(orderIndex = index) }
            }
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
    LaunchedEffect(state.playback.narrationStage, state.playback.narrationMessage) {
        val announcement = state.playback.narrationMessage?.trim().orEmpty()
        if (announcement.isNotBlank()) {
            delay(80)
            view.announceForAccessibility(announcement)
        }
    }

    fun createVietPhraseDiagnostic() {
        if (vietPhraseDiagnosticBusy) {
            onMessage("Đang tạo một nhật ký VietPhrase khác.")
            return
        }
        val rawParagraphs = state.originalChapterContent?.paragraphs ?: content.paragraphs
        if (rawParagraphs.isEmpty()) {
            onMessage("Hãy mở một chương truyện trước khi tạo nhật ký VietPhrase.")
            return
        }
        val rules = state.vietPhraseRules.mapNotNull { item ->
            runCatching {
                VietPhraseRule(
                    id = item.id.toString(),
                    source = item.source,
                    target = item.target,
                    kind = VietPhraseDictionaryKind.valueOf(item.kind),
                    priority = item.priority,
                    enabled = item.enabled,
                    scope = VietPhraseScope.valueOf(item.scope),
                    storyId = item.storyId.takeIf(String::isNotBlank),
                    matchMode = VietPhraseMatchMode.valueOf(item.matchMode),
                    ignoreCase = item.ignoreCase,
                    updatedAt = item.updatedAt,
                )
            }.getOrNull()
        }
        vietPhraseDiagnosticBusy = true
        val diagnosticTraceId = "vietphrase:${content.chapter.id}:${UUID.randomUUID()}"
        val diagnosticSourceId = storyDetail?.story?.sourceId ?: "vietphrase"
        app.container.sourceDiagnostics.mark(
            name = "VIETPHRASE_DIAGNOSTIC_STARTED",
            sourceId = diagnosticSourceId,
            traceId = diagnosticTraceId,
            severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
            attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "rules" to rules.size.toString()),
        )
        scope.launch {
            val exported = withContext(Dispatchers.IO) {
                VietPhraseDiagnosticExporter.export(
                    context = context,
                    title = content.chapter.title,
                    paragraphs = rawParagraphs,
                    rules = rules,
                    storyId = storyId,
                    fallbackHanViet = state.vietPhraseFallbackHanViet,
                    diagnostics = app.container.sourceDiagnostics,
                    diagnosticTraceId = diagnosticTraceId,
                    diagnosticSourceId = diagnosticSourceId,
                )
            }
            vietPhraseDiagnosticBusy = false
            exported.onSuccess {
                vietPhraseDiagnosticResult = it
                app.container.sourceDiagnostics.mark(
                    name = "VIETPHRASE_DIAGNOSTIC_COMPLETED",
                    sourceId = diagnosticSourceId,
                    traceId = diagnosticTraceId,
                    severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "traceCount" to it.traceCount.toString(), "probeCount" to it.probeCount.toString()),
                )
            }.onFailure { error ->
                app.container.sourceDiagnostics.mark(
                    name = "VIETPHRASE_DIAGNOSTIC_FAILED",
                    severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR,
                    sourceId = diagnosticSourceId,
                    traceId = diagnosticTraceId,
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "error" to (error.message ?: error.javaClass.simpleName)),
                )
                onMessage(error.message ?: "Lỗi tạo nhật ký VietPhrase.")
            }
        }
    }

    val palette = readerPalette(display.theme)
    Surface(modifier = Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
        Column(Modifier.fillMaxSize()) {
            ReaderButton("TÙY CHỌN", { showReaderOptions = true }, Modifier.fillMaxWidth(), normalColor = ReferenceGray, minHeight = 52.dp)
            if (textMode) {
                if (display.layoutMode == ReaderLayoutMode.SCROLL) {
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
                                    .clickable { onParagraphSelected(index) }
                                    .padding(horizontal = display.horizontalPaddingDp.dp, vertical = (display.paragraphSpacingDp / 2f).dp),
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = display.horizontalPaddingDp.dp, vertical = 16.dp),
                    ) {
                        Text(content.chapter.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.text)
                        Text(
                            content.paragraphs.getOrElse(activeIndex) { "" },
                            color = palette.text,
                            fontSize = display.fontSizeSp.sp,
                            lineHeight = (display.fontSizeSp * display.lineHeightPercent / 100f).sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ReaderButton("ĐOẠN TRƯỚC", { onParagraphSelected((activeIndex - 1).coerceAtLeast(0)) }, Modifier.weight(1f), enabled = activeIndex > 0, minHeight = 52.dp)
                        Text("${activeIndex + 1}/${content.paragraphs.size}", modifier = Modifier.padding(horizontal = 8.dp), color = palette.text)
                        ReaderButton("ĐOẠN SAU", { onParagraphSelected((activeIndex + 1).coerceAtMost(content.paragraphs.lastIndex)) }, Modifier.weight(1f), enabled = activeIndex < content.paragraphs.lastIndex, minHeight = 52.dp)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1f), minHeight = 56.dp)
                    ReaderButton("SAU", onNextChapter, Modifier.weight(1f), minHeight = 56.dp)
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(content.chapter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = palette.text)
                    Text("Đoạn ${activeIndex + 1}/${content.paragraphs.size.coerceAtLeast(1)}", color = palette.text, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth()) {
                    ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1.2f), minHeight = 56.dp)
                    ReaderButton("LÙI", onRewind, Modifier.weight(1f), minHeight = 56.dp)
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
                        minHeight = 56.dp,
                        normalColor = ReferenceGreen,
                        selected = state.playback.isPlaying,
                    )
                    ReaderButton("TỚI", onForward, Modifier.weight(1f), minHeight = 56.dp)
                    ReaderButton("SAU", onNextChapter, Modifier.weight(1.2f), minHeight = 56.dp)
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
            if (effectiveAutoVoiceCastEnabled) {
                val autoNarrationStatus = state.playback.narrationMessage ?: if (state.prefetchNarrationPlansEnabled) {
                    "Tự phân vai đang bật. Từ 75% chương, ứng dụng sẽ tải và phân vai trước chương tiếp theo."
                } else {
                    "Tự phân vai đang bật. Tải/phân vai trước chương tiếp theo đang tắt trong cài đặt."
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("TỰ PHÂN VAI", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { state.playback.narrationProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(autoNarrationStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ReaderButton(if (state.aiBusy) "AI ĐANG CHẠY…" else if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) "DỊCH LẠI" else "DỊCH AI", onAiTranslate, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = ReferencePurple)
                ReaderButton("PHÂN VAI AI", onVoiceCast, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = Color(0xFFAF52DE))
            }
            if (state.playback.preparationState == PlaybackPreparationState.FAILED) {
                ReaderButton("TIẾP TỤC BẰNG BẢN GỐC", onShowOriginal, Modifier.fillMaxWidth(), normalColor = ReferenceGreen)
            }
        }
    }

    if (showReaderOptions) {
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
        var processingExpanded by remember { mutableStateOf(false) }
        var sonicQualityExpanded by remember { mutableStateOf(false) }
        val languages = ttsVoices.map { it.languageTag }.filter(String::isNotBlank).distinct().sorted()
        val visibleVoices = ttsVoices.filter { ttsDraft.languageTag.isBlank() || it.languageTag == ttsDraft.languageTag }
        val sonicSelected = ttsDraft.processingMethod == "sonic"
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
                if (!ttsLoading && state.ttsEngines.isEmpty()) {
                    ReaderMenuButton("SAO CHÉP CHẨN ĐOÁN BỘ ĐỌC") {
                        clipboard.setText(AnnotatedString("Bộ đọc: ${ttsDraft.enginePackage ?: "Mặc định hệ thống"}\nNgôn ngữ: ${ttsDraft.languageTag}\nGiọng: ${ttsDraft.voiceName ?: "Mặc định"}\nSố bộ đọc/giọng: ${state.ttsEngines.size}/${ttsVoices.size}"))
                        onMessage("Đã sao chép chẩn đoán bộ đọc.")
                    }
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
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { processingExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (sonicSelected) "Sonic, tối đa 200%" else "Android, tối đa 100%")
                    }
                    DropdownMenu(expanded = processingExpanded, onDismissRequest = { processingExpanded = false }) {
                        DropdownMenuItem(text = { Text("Android, tối đa 100%") }, onClick = { processingExpanded = false; ttsDraft = ttsDraft.copy(processingMethod = "system") })
                        DropdownMenuItem(text = { Text("Sonic, tối đa 200%") }, onClick = { processingExpanded = false; ttsDraft = ttsDraft.copy(processingMethod = "sonic") })
                    }
                }
                if (sonicSelected) {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth()) {
                        Button(onClick = { sonicQualityExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(if (ttsDraft.sonicAccurate) "Chính xác" else "Nhanh") }
                        DropdownMenu(expanded = sonicQualityExpanded, onDismissRequest = { sonicQualityExpanded = false }) {
                            DropdownMenuItem(text = { Text("Nhanh") }, onClick = { sonicQualityExpanded = false; ttsDraft = ttsDraft.copy(sonicAccurate = false) })
                            DropdownMenuItem(text = { Text("Chính xác") }, onClick = { sonicQualityExpanded = false; ttsDraft = ttsDraft.copy(sonicAccurate = true) })
                        }
                    }
                }
                TtsSlider("Tốc độ đọc", ttsDraft.speed, 0.25f, 3f) { ttsDraft = ttsDraft.copy(speed = it) }
                TtsSlider("Cao độ", ttsDraft.pitch, 0.5f, 2f) { ttsDraft = ttsDraft.copy(pitch = it) }
                TtsSlider("Âm lượng", if (sonicSelected) ttsDraft.sonicVolume else ttsDraft.volume, 0f, if (sonicSelected) 2f else 1f, percent = true) { value ->
                    ttsDraft = if (sonicSelected) ttsDraft.copy(sonicVolume = value) else ttsDraft.copy(volume = value)
                }
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
        var musicModeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("NHẠC NỀN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nhạc nền", Modifier.weight(1f))
                    Switch(musicEnabled, { musicEnabled = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                vn.nghetruyen.app.ui.components.AudioDirectionLayerSwitches(
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Chế độ phát", fontWeight = FontWeight.SemiBold)
                Button(onClick = { musicModeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "Phát ngẫu nhiên" else "Phát lần lượt")
                }
                DropdownMenu(expanded = musicModeExpanded, onDismissRequest = { musicModeExpanded = false }) {
                    DropdownMenuItem(text = { Text("Phát lần lượt") }, onClick = { musicMode = SceneMusicPlaybackMode.SEQUENTIAL; musicModeExpanded = false })
                    DropdownMenuItem(text = { Text("Phát ngẫu nhiên") }, onClick = { musicMode = SceneMusicPlaybackMode.SHUFFLE; musicModeExpanded = false })
                }
                ReaderFloatSlider("Giảm nhạc khi giọng đọc phát", musicDuckDb, 0f, 24f, steps = 23, shown = { "%.0f dB".format(it) }) { musicDuckDb = it }
                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") {
                    val rows = state.sceneMusicTracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                    musicLibraryDraft = rows.mapIndexed { index, row -> row.copy(orderIndex = index) }
                    musicLibraryBaselineIds = rows.mapTo(linkedSetOf()) { it.id }
                    musicSearch = ""; showMusicLibrary = true
                }
            } },
            confirmButton = { TextButton(onClick = {
                val activeCount = state.sceneMusicTracks.count { it.enabled }
                if (musicEnabled && activeCount == 0) onMessage("Hãy bật ít nhất một bài trong danh sách nhạc trước.")
                else scope.launch {
                    val settings = app.container.settingsRepository
                    settings.setBackgroundMusicEnabled(musicEnabled)
                    settings.setSceneMusicPlaybackMode(musicMode)
                    settings.setSceneMusicTargetLufs(musicTargetLufs)
                    settings.setBackgroundMusicDuckFactor(10.0.pow(-musicDuckDb / 20.0).toFloat())
                    settings.setBackgroundMusicAttackMillis(musicAttackMs)
                    settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id, musicTargetLufs) }
                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                    onMessage("Đã lưu cài đặt nhạc nền."); showMusicDialog = false
                }
            }) { Text("LƯU CÀI ĐẶT") } },
            dismissButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMusicNormalizationProgress) {
        val total = musicNormalizationWorkIds.size
        val finished = musicNormalizationDone + musicNormalizationFailed + musicNormalizationCancelled
        val running = (total - finished).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("CHUẨN HÓA KHO NHẠC") },
            text = {
                Column {
                    Text("Mục tiêu: %.0f LUFS".format(musicNormalizationTarget), fontWeight = FontWeight.SemiBold)
                    Text("Hoàn tất: $finished / $total")
                    Text("Thành công: $musicNormalizationDone")
                    if (musicNormalizationFailed > 0) Text("Lỗi: $musicNormalizationFailed")
                    if (musicNormalizationCancelled > 0) Text("Đã hủy: $musicNormalizationCancelled")
                    if (running > 0) Text("Đang xử lý: $running")
                    Text(
                        "Các bài đã có loudness và peak chỉ được tính lại gain, không giải mã lại.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                if (finished >= total && total > 0) {
                    TextButton(onClick = {
                        showMusicNormalizationProgress = false
                        musicNormalizationWorkIds = emptyList()
                        onMessage("Chuẩn hóa xong: $musicNormalizationDone thành công, $musicNormalizationFailed lỗi.")
                    }) { Text("ĐÓNG") }
                }
            },
            dismissButton = {
                if (finished < total) {
                    TextButton(onClick = {
                        musicNormalizationRunToken += 1
                        musicNormalizationWorkIds.forEach { SceneMusicAnalysisWorker.cancel(context, it) }
                        showMusicNormalizationProgress = false
                        onMessage("Đã hủy hàng đợi chuẩn hóa nhạc.")
                    }) { Text("HỦY") }
                }
            },
        )
    }

    if (showMusicLibrary) {
        val normalizedSearch = musicSearch.trim().lowercase()
        val visibleTracks = musicLibraryDraft.filter { normalizedSearch.isBlank() || it.title.lowercase().contains(normalizedSearch) }
        fun stopPreview() {
            runCatching { musicPreviewPlayer?.stop() }
            runCatching { musicPreviewPlayer?.release() }
            musicPreviewPlayer = null
            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
        }
        fun cancelLibrary() {
            stopPreview()
            val transientIds = state.sceneMusicTracks.mapTo(linkedSetOf()) { it.id } - musicLibraryBaselineIds
            scope.launch { transientIds.forEach { app.container.database.sceneMusicTrackDao().delete(it) } }
            showMusicLibrary = false
        }
        AlertDialog(
            onDismissRequest = ::cancelLibrary,
            title = { Text("DANH SÁCH NHẠC NỀN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    musicSearch,
                    { musicSearch = it.take(120) },
                    placeholder = { Text("Tìm theo tên bài") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visibleTracks.isEmpty()) {
                    Text(if (musicLibraryDraft.isEmpty()) "Chưa có bài nhạc nào." else "Không có bài phù hợp với nội dung tìm kiếm.", modifier = Modifier.padding(vertical = 8.dp))
                }
                visibleTracks.forEach { track ->
                    val index = musicLibraryDraft.indexOfFirst { it.id == track.id }
                    ReferenceActionButton(
                        "${index + 1}. ${track.title}",
                        { selectedMusicTrackId = track.id },
                        normalColor = ReferenceGray,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            } },
            confirmButton = { TextButton(onClick = {
                if (musicLibraryDraft.size > 500) {
                    onMessage("Danh sách vượt giới hạn 500 bài.")
                } else {
                    stopPreview()
                    scope.launch {
                        val dao = app.container.database.sceneMusicTrackDao()
                        val existing = dao.listAll()
                        val now = System.currentTimeMillis()
                        val normalized = musicLibraryDraft.mapIndexed { index, row -> row.copy(orderIndex = index, updatedAt = now) }
                        val keepIds = normalized.mapTo(hashSetOf()) { it.id }
                        existing.filter { it.id !in keepIds }.forEach { dao.delete(it.id) }
                        dao.upsertAll(normalized)
                        onMessage("Đã lưu danh sách nhạc nền.")
                        showMusicLibrary = false
                    }
                }
            }) { Text("LƯU DANH SÁCH") } },
            dismissButton = { Row {
                TextButton(onClick = onSelectSceneMusic, enabled = musicLibraryDraft.size < 500) { Text("THÊM BÀI") }
                if (musicPreviewPlayer != null) TextButton(onClick = ::stopPreview) { Text("DỪNG NGHE") }
                TextButton(onClick = ::cancelLibrary) { Text("HỦY") }
            } },
        )
    }

    selectedMusicTrackId?.let { selectedId ->
        musicLibraryDraft.firstOrNull { it.id == selectedId }?.let { track ->
            val index = musicLibraryDraft.indexOfFirst { it.id == track.id }
            AlertDialog(
                onDismissRequest = { selectedMusicTrackId = null },
                title = { Text(track.title) },
                text = {},
                confirmButton = { Column(Modifier.fillMaxWidth()) {
                    ReaderMenuButton("NGHE THỬ") {
                        runCatching { musicPreviewPlayer?.stop() }
                        runCatching { musicPreviewPlayer?.release() }
                        musicPreviewPlayer = null
                        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN)
                        val gainDb = if (
                            track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
                            track.normalizationError.isBlank() &&
                            track.loudnessLufsEstimate.isFinite() &&
                            track.peakDbfs.isFinite()
                        ) {
                            PcmLoudnessEstimator.calculateNormalization(
                                track.loudnessLufsEstimate,
                                track.peakDbfs,
                                musicTargetLufs,
                            ).gainDb
                        } else 0f
                        val previewLevel = (track.volume * PcmLoudnessEstimator.gainDbToLinear(gainDb)).coerceIn(0f, 1f)
                        musicPreviewPlayer = runCatching { MediaPlayer.create(context, Uri.parse(track.uri)) }.getOrNull()?.also { player ->
                            player.setVolume(previewLevel, previewLevel)
                            player.setOnCompletionListener { completed ->
                                runCatching { completed.release() }
                                if (musicPreviewPlayer === completed) {
                                    musicPreviewPlayer = null
                                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                                }
                            }
                            player.start()
                            scope.launch {
                                delay(15_000)
                                if (musicPreviewPlayer === player) {
                                    runCatching { player.stop() }
                                    runCatching { player.release() }
                                    musicPreviewPlayer = null
                                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                                }
                            }
                        }
                        if (musicPreviewPlayer == null) {
                            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                            onMessage("Không nghe thử được bài nhạc này.")
                        }
                    }
                    ReaderMenuButton("SỬA TÊN") { editingTrack = track; selectedMusicTrackId = null }
                    ReaderMenuButton(if (track.enabled) "TẮT BÀI NÀY" else "BẬT BÀI NÀY") {
                        musicLibraryDraft = musicLibraryDraft.map { if (it.id == track.id) it.copy(enabled = !track.enabled) else it }
                        selectedMusicTrackId = null
                    }
                    if (index > 0) ReaderMenuButton("DI CHUYỂN LÊN") {
                        val rows = musicLibraryDraft.toMutableList()
                        val previous = rows[index - 1]
                        rows[index - 1] = rows[index]
                        rows[index] = previous
                        musicLibraryDraft = rows.mapIndexed { position, row -> row.copy(orderIndex = position) }
                        selectedMusicTrackId = null
                    }
                    if (index in 0 until musicLibraryDraft.lastIndex) ReaderMenuButton("DI CHUYỂN XUỐNG") {
                        val rows = musicLibraryDraft.toMutableList()
                        val next = rows[index + 1]
                        rows[index + 1] = rows[index]
                        rows[index] = next
                        musicLibraryDraft = rows.mapIndexed { position, row -> row.copy(orderIndex = position) }
                        selectedMusicTrackId = null
                    }
                    ReaderMenuButton("XÓA KHỎI DANH SÁCH") {
                        selectedMusicTrackId = null
                        editingTrack = track.copy(title = "__DELETE_CONFIRM__${track.title}")
                    }
                    TextButton(onClick = { selectedMusicTrackId = null }, modifier = Modifier.align(Alignment.End)) { Text("ĐÓNG") }
                } },
            )
        }
    }

    editingTrack?.let { track ->
        if (track.title.startsWith("__DELETE_CONFIRM__")) {
            val realTitle = track.title.removePrefix("__DELETE_CONFIRM__")
            AlertDialog(
                onDismissRequest = { editingTrack = null },
                title = { Text("XÓA BÀI NHẠC") },
                text = { Text("Xóa ‘$realTitle’ khỏi danh sách?") },
                confirmButton = { TextButton(onClick = {
                    musicPreviewPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
                    musicPreviewPlayer = null
                    musicLibraryDraft = musicLibraryDraft.filterNot { it.id == track.id }
                        .mapIndexed { index, row -> row.copy(orderIndex = index) }
                    editingTrack = null
                }) { Text("XÓA") } },
                dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("HỦY") } },
            )
        } else {
            var title by remember(track.id, track.title) { mutableStateOf(track.title) }
            AlertDialog(
                onDismissRequest = { editingTrack = null },
                title = { Text("SỬA TÊN") },
                text = {
                    OutlinedTextField(title, { title = it.take(120) }, placeholder = { Text("Tên bài") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = { TextButton(onClick = {
                    val cleanTitle = title.trim().ifBlank { track.title }
                    musicLibraryDraft = musicLibraryDraft.map {
                        if (it.id == track.id) it.copy(title = cleanTitle) else it
                    }
                    editingTrack = null
                }) { Text("LƯU") } },
                dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("HỦY") } },
            )
        }
    }

    if (showMusicBulkDialog) {
        AlertDialog(
            onDismissRequest = { showMusicBulkDialog = false },
            title = { Text("DÁN MÔ TẢ HÀNG LOẠT") },
            text = { Column {
                Text("Mỗi dòng: Tên bài || Mô tả. Dùng [XÓA] để xóa mô tả.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    musicBulkText,
                    { musicBulkText = it.take(120_000) },
                    placeholder = { Text("Dán toàn bộ tên và mô tả tại đây") },
                    minLines = 12,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            } },
            confirmButton = { TextButton(onClick = {
                val tracksByName = musicLibraryDraft.associateBy { it.title.trim().lowercase() }
                val updates = linkedMapOf<String, String>()
                val errors = mutableListOf<String>()
                musicBulkText.lineSequence().forEachIndexed { index, raw ->
                    val line = raw.trim()
                    if (line.isBlank()) return@forEachIndexed
                    val separator = line.indexOf("||")
                    if (separator < 0) {
                        errors += "Dòng ${index + 1}: thiếu dấu ||."
                        return@forEachIndexed
                    }
                    val name = line.substring(0, separator).trim()
                    val description = line.substring(separator + 2).trim()
                    val track = tracksByName[name.lowercase()]
                    when {
                        name.isBlank() -> errors += "Dòng ${index + 1}: thiếu tên bài."
                        track == null -> errors += "Dòng ${index + 1}: không tìm thấy bài “$name”."
                        description.isBlank() -> Unit
                        description != "[XÓA]" && description.length > 300 -> errors += "Dòng ${index + 1}: mô tả có ${description.length} ký tự, vượt giới hạn 300."
                        else -> updates[track.id] = if (description == "[XÓA]") "" else description
                    }
                }
                musicBulkUpdates = updates
                musicBulkErrors = errors
                showMusicBulkResult = true
            }) { Text("KIỂM TRA") } },
            dismissButton = { TextButton(onClick = { showMusicBulkDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMusicBulkResult) {
        val message = buildString {
            append("Dòng hợp lệ: ${musicBulkUpdates.size}\nDòng cần kiểm tra: ${musicBulkErrors.size}")
            if (musicBulkErrors.isNotEmpty()) {
                append("\n\nCÁC DÒNG CẦN KIỂM TRA:\n")
                append(musicBulkErrors.take(20).joinToString("\n"))
                if (musicBulkErrors.size > 20) append("\n... và ${musicBulkErrors.size - 20} lỗi khác.")
            }
        }
        AlertDialog(
            onDismissRequest = { showMusicBulkResult = false },
            title = { Text("KẾT QUẢ KIỂM TRA") },
            text = { Text(message) },
            confirmButton = {
                if (musicBulkUpdates.isNotEmpty()) TextButton(onClick = {
                    musicLibraryDraft = musicLibraryDraft.map { row ->
                        musicBulkUpdates[row.id]?.let { row.copy(tagsCsv = it) } ?: row
                    }
                    showMusicBulkResult = false
                    showMusicBulkDialog = false
                    onMessage("Đã cập nhật ${musicBulkUpdates.size} bài. ${musicBulkErrors.size} dòng cần kiểm tra.")
                }) { Text("ÁP DỤNG DÒNG HỢP LỆ") }
            },
            dismissButton = { Row {
                if (musicBulkErrors.isNotEmpty()) TextButton(onClick = {
                    clipboard.setText(AnnotatedString(musicBulkErrors.joinToString("\n")))
                    onMessage("Đã sao chép danh sách lỗi.")
                }) { Text("SAO CHÉP LỖI") }
                TextButton(onClick = { showMusicBulkResult = false }) { Text("SỬA") }
            } },
        )
    }

    if (showMusicClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showMusicClearAllConfirm = false },
            title = { Text("XÓA TOÀN BỘ DANH SÁCH") },
            text = { Text("Xóa tất cả bài nhạc khỏi bản nháp?") },
            confirmButton = { TextButton(onClick = {
                runCatching { musicPreviewPlayer?.stop() }
                runCatching { musicPreviewPlayer?.release() }
                musicPreviewPlayer = null
                ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                musicLibraryDraft = emptyList()
                showMusicClearAllConfirm = false
            }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { showMusicClearAllConfirm = false }) { Text("HỦY") } },
        )
    }

    if (showExportDialog) {
        var fileName by remember(showExportDialog) { mutableStateOf(content.chapter.title.take(120).ifBlank { "chuong-${content.chapter.index + 1}" }) }
        var format by remember(showExportDialog) { mutableStateOf(AudioExportFormat.MP3) }
        var formatExpanded by remember { mutableStateOf(false) }
        var includeMusic by remember(showExportDialog) { mutableStateOf(false) }
        var chapterMarkers by remember(showExportDialog) { mutableStateOf(true) }
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kèm nhạc cảnh", Modifier.weight(1f))
                    Switch(includeMusic, { includeMusic = it })
                }
                if (format == AudioExportFormat.MP3) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Đánh dấu chương trong MP3", Modifier.weight(1f))
                        Switch(chapterMarkers, { chapterMarkers = it })
                    }
                }
            } },
            confirmButton = { TextButton(enabled = fileName.isNotBlank(), onClick = {
                ReferenceAudioExportRuntime.setNextFileName("${fileName.trim()}.${format.extension}")
                showExportDialog = false
                onExportAudio(
                    AudioExportRequest(
                        scope = AudioExportScope.CURRENT_CHAPTER,
                        format = format,
                        includeSceneMusic = includeMusic,
                        chapterMarkers = chapterMarkers,
                    ),
                )
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
        val offline = content.chapter.id in state.downloadedChapterIds
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

    if (vietPhraseDiagnosticBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("ĐANG TẠO NHẬT KÝ VIETPHRASE") },
            text = { Text("Đang phân tích chương và đóng gói file ZIP…") },
            confirmButton = {},
        )
    }

    vietPhraseDiagnosticResult?.let { result ->
        val output = buildString {
            append(result.summary)
            append("\n\nFILE ZIP:\n")
            append(result.path)
            if (result.preview.isNotBlank()) {
                append("\n\n60 QUYẾT ĐỊNH ĐẦU TIÊN:\n")
                append(result.preview)
            }
        }
        AlertDialog(
            onDismissRequest = { vietPhraseDiagnosticResult = null },
            title = { Text("NHẬT KÝ VIETPHRASE") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { Text(output) } },
            confirmButton = { TextButton(onClick = { vietPhraseDiagnosticResult = null }) { Text("ĐÓNG") } },
            dismissButton = { TextButton(onClick = {
                clipboard.setText(AnnotatedString(result.path))
                onMessage("Đã sao chép đường dẫn file ZIP.")
            }) { Text("SAO CHÉP ĐƯỜNG DẪN") } },
        )
    }

    StoryReferenceAdvancedDialogs(
        state = state,
        mode = storyAdvancedMode,
        onDismiss = { storyAdvancedMode = null },
        onSaveVoiceRole = onSaveVoiceRole,
        onPreviewVoiceRole = onPreviewVoiceRole,
        onDeleteVoiceRole = onDeleteVoiceRole,
        onSaveAiProfile = onSaveAiProfile,
    )

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
private fun ReaderIntSlider(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    val safeStep = step.coerceAtLeast(1)
    val safe = value.coerceIn(minimum, maximum)
    val intervals = ((maximum - minimum) / safeStep).coerceAtLeast(1)
    val description = "$label: $safe$suffix"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = safe.toFloat(),
        onValueChange = { raw ->
            val snapped = minimum + (((raw - minimum.toFloat()) / safeStep.toFloat()).toInt() * safeStep)
            onChange(snapped.coerceIn(minimum, maximum))
        },
        valueRange = minimum.toFloat()..maximum.toFloat(),
        steps = (intervals - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun ReaderFloatSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    steps: Int = 0,
    shown: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val safe = value.coerceIn(minimum, maximum)
    val description = "$label: ${shown(safe)}"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = safe,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        steps = steps,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun TtsSlider(label: String, value: Float, min: Float, max: Float, percent: Boolean = false, onChange: (Float) -> Unit) {
    val shown = value.coerceIn(min, max)
    val description = if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    val intervals = when {
        percent && max <= 1f -> 100
        percent -> 200
        label.contains("Tốc độ", ignoreCase = true) -> 275
        else -> 150
    }
    Slider(
        value = shown,
        onValueChange = { onChange(it.coerceIn(min, max)) },
        valueRange = min..max,
        steps = (intervals - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}
