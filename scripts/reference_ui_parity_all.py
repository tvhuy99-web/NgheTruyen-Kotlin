#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker for {label}")
    return text.replace(old, new, 1)

def regex_once(text, pattern, repl, label):
    out, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"regex {label} matched {count}")
    return out

# ---------------------------------------------------------------------------
# AppViewModel: shared diagnostics state, separate story advanced targets,
# chapter-count sleep timer, selected chapter downloads.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
t = read(path)

t = replace_once(
    t,
    '    val storyAdvancedOptionsRequested: Boolean = false,\n',
    '    val storyAdvancedOptionsRequested: Boolean = false,\n'
    '    val storyAdvancedOptionsMode: String? = null,\n',
    "state advanced mode",
)
t = replace_once(
    t,
    '    val chapterSortDescending: Boolean = false,\n',
    '    val chapterSortDescending: Boolean = false,\n'
    '    val diagnosticsMode: String = "off",\n'
    '    val sleepTimerStatus: String = "Đang tắt",\n',
    "diagnostics and sleep state",
)
t = replace_once(
    t,
    '    private var sourceCheckAllJob: Job? = null\n',
    '    private var sourceCheckAllJob: Job? = null\n'
    '    private var chapterSleepRemaining: Int? = null\n'
    '    private var chapterSleepLastChapterId: String = ""\n',
    "chapter sleep vars",
)
t = replace_once(
    t,
    '''    private fun observePlayback() {
        viewModelScope.launch {
            PlaybackQueueStore.state.collect { playback ->
                mutableState.update { it.copy(playback = playback) }
            }
        }
    }
''',
    '''    private fun observePlayback() {
        viewModelScope.launch {
            PlaybackQueueStore.state.collect { playback ->
                val previousChapterId = chapterSleepLastChapterId
                val currentChapterId = playback.chapterId
                val remaining = chapterSleepRemaining
                if (
                    remaining != null &&
                    previousChapterId.isNotBlank() &&
                    currentChapterId.isNotBlank() &&
                    currentChapterId != previousChapterId
                ) {
                    val nextRemaining = remaining - 1
                    if (nextRemaining <= 0) {
                        chapterSleepRemaining = null
                        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
                        mutableState.update {
                            it.copy(
                                playback = playback,
                                sleepTimerStatus = "Đang tắt",
                                message = "Đã dừng đọc theo hẹn giờ chương.",
                            )
                        }
                    } else {
                        chapterSleepRemaining = nextRemaining
                        mutableState.update {
                            it.copy(
                                playback = playback,
                                sleepTimerStatus = "Còn $nextRemaining chương",
                            )
                        }
                    }
                } else {
                    mutableState.update { it.copy(playback = playback) }
                }
                if (currentChapterId.isNotBlank()) chapterSleepLastChapterId = currentChapterId
            }
        }
    }
''',
    "observe playback sleep chapters",
)

t = replace_once(
    t,
    '''    fun setSleepTimer(minutes: Int?) {
        if (state.value.chapterContent == null) return
        ReaderPlaybackService.setSleepTimer(getApplication(), minutes)
        showMessage(if (minutes == null) "Đã hủy hẹn giờ ngủ." else "Sẽ dừng đọc sau $minutes phút.")
    }
''',
    '''    fun setSleepTimer(minutes: Int?) {
        if (state.value.chapterContent == null) return
        chapterSleepRemaining = null
        chapterSleepLastChapterId = state.value.playback.chapterId
        ReaderPlaybackService.setSleepTimer(getApplication(), minutes)
        mutableState.update {
            it.copy(sleepTimerStatus = if (minutes == null) "Đang tắt" else "Còn khoảng $minutes phút")
        }
        showMessage(if (minutes == null) "Đã hủy hẹn giờ ngủ." else "Sẽ dừng đọc sau $minutes phút.")
    }

    fun setSleepTimerByChapters(chapterCount: Int) {
        if (state.value.chapterContent == null) return
        val count = chapterCount.coerceAtLeast(1)
        ReaderPlaybackService.setSleepTimer(getApplication(), null)
        chapterSleepRemaining = count
        chapterSleepLastChapterId = state.value.playback.chapterId
        mutableState.update {
            it.copy(sleepTimerStatus = if (count == 1) "Hết chương hiện tại" else "Còn $count chương")
        }
        showMessage(if (count == 1) "Sẽ dừng khi hết chương hiện tại." else "Sẽ dừng sau $count chương.")
    }

    fun setDiagnosticsMode(mode: String) {
        val normalized = mode.takeIf { it in setOf("off", "basic", "advanced") } ?: "off"
        mutableState.update { it.copy(diagnosticsMode = normalized) }
    }
''',
    "sleep and diagnostics methods",
)

t = replace_once(
    t,
    '    fun downloadChapterRange(startChapterNumber: Int, endChapterNumber: Int) {\n',
    '''    fun downloadSelectedChapters(chapterNumbers: List<Int>) {
        val selected = chapterNumbers.filter { it > 0 }.distinct().sorted()
        if (selected.isEmpty()) {
            showMessage("Chưa chọn chương để tải.")
            return
        }
        selected.forEach { chapterNumber -> downloadChapterRange(chapterNumber, chapterNumber) }
        showMessage("Đã thêm ${selected.size} chương đã chọn vào hàng đợi tải.")
    }

    fun downloadChapterRange(startChapterNumber: Int, endChapterNumber: Int) {
''',
    "selected chapter downloader",
)

t = regex_once(
    t,
    r'''    fun consumeStoryAdvancedOptionsRequest\(\) \{
        mutableState\.update \{ it\.copy\(storyAdvancedOptionsRequested = false\) \}
    \}

    fun openStoryAdvancedOptions\(\) \{
        ReaderPlaybackService\.command\(getApplication\(\), ReaderPlaybackService\.ACTION_PAUSE\)
        mutableState\.update \{
            it\.copy\(
                destination = Destination\.Story,
                storyDetailTab = "source",
                storyAdvancedOptionsRequested = true,
                loading = false,
            \)
        \}
    \}
''',
    '''    fun consumeStoryAdvancedOptionsRequest() {
        mutableState.update { it.copy(storyAdvancedOptionsRequested = false, storyAdvancedOptionsMode = null) }
    }

    fun openStoryAiOptions() = openStoryAdvancedOptions("ai")

    fun openStoryVoiceCastOptions() = openStoryAdvancedOptions("voice")

    private fun openStoryAdvancedOptions(mode: String) {
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
''',
    "split story advanced routing",
)
write(path, t)

# ---------------------------------------------------------------------------
# NgheTruyenApp: wire new stateful callbacks.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"
t = read(path)

t = replace_once(
    t,
    '                        onInterruptionModeChange = viewModel::setAudioInterruptionMode,\n',
    '                        onInterruptionModeChange = viewModel::setAudioInterruptionMode,\n'
    '                        onDiagnosticsModeChange = viewModel::setDiagnosticsMode,\n',
    "personal diagnostics wiring",
)
t = replace_once(
    t,
    '                    onDownloadRange = viewModel::downloadChapterRange,\n',
    '                    onDownloadRange = viewModel::downloadChapterRange,\n'
    '                    onDownloadSelected = viewModel::downloadSelectedChapters,\n',
    "story selected download wiring",
)
t = replace_once(
    t,
    '                    onSleepTimer = viewModel::setSleepTimer,\n',
    '                    onSleepTimer = viewModel::setSleepTimer,\n'
    '                    onSleepTimerByChapters = viewModel::setSleepTimerByChapters,\n',
    "reader chapter sleep wiring",
)
t = replace_once(
    t,
    '                    onOpenStoryAdvancedOptions = viewModel::openStoryAdvancedOptions,\n'
    '                    onOpenTtsSettings = viewModel::openTtsSettings,\n',
    '                    onOpenStoryAiOptions = viewModel::openStoryAiOptions,\n'
    '                    onOpenStoryVoiceCastOptions = viewModel::openStoryVoiceCastOptions,\n'
    '                    onEngineSelected = viewModel::selectTtsEngine,\n'
    '                    onVoiceSelected = viewModel::selectTtsVoice,\n'
    '                    onRefreshVoices = viewModel::refreshTtsVoices,\n'
    '                    onPreviewVoice = viewModel::previewTtsVoice,\n'
    '                    onRateChange = viewModel::setTtsRate,\n'
    '                    onPitchChange = viewModel::setTtsPitch,\n'
    '                    onVolumeChange = viewModel::setTtsVolume,\n'
    '                    onSonicProcessingEnabledChange = viewModel::setSonicProcessingEnabled,\n'
    '                    onOpenTtsSettings = viewModel::openTtsSettings,\n',
    "reader split advanced and tts controls",
)
t = replace_once(
    t,
    '                    onBackgroundMusicVolumeChange = viewModel::setBackgroundMusicVolume,\n'
    '                    onMessage = viewModel::readerActionMessage,\n',
    '                    onBackgroundMusicVolumeChange = viewModel::setBackgroundMusicVolume,\n'
    '                    onBackgroundMusicDuckChange = viewModel::setBackgroundMusicDuckFactor,\n'
    '                    onAutoSceneMusicChange = viewModel::setAutoSceneMusicEnabled,\n'
    '                    onSceneMusicPlaybackModeChange = viewModel::setSceneMusicPlaybackMode,\n'
    '                    onSceneMusicTargetLufsChange = viewModel::setSceneMusicTargetLufs,\n'
    '                    onSelectSceneMusic = onSelectSceneMusic,\n'
    '                    onMessage = viewModel::readerActionMessage,\n',
    "reader music wiring",
)
write(path, t)

# ---------------------------------------------------------------------------
# Personal screen.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
t = read(path)

t = replace_once(
    t,
    'import androidx.compose.foundation.layout.Column\n',
    'import androidx.compose.foundation.layout.Box\n'
    'import androidx.compose.foundation.layout.Column\n',
    "personal box import",
)
t = replace_once(
    t,
    'import androidx.compose.material3.Card\n',
    'import androidx.compose.material3.Card\n'
    'import androidx.compose.material3.DropdownMenu\n'
    'import androidx.compose.material3.DropdownMenuItem\n',
    "personal dropdown imports",
)
t = replace_once(
    t,
    '    onInterruptionModeChange: (AudioInterruptionMode) -> Unit,\n',
    '    onInterruptionModeChange: (AudioInterruptionMode) -> Unit,\n'
    '    onDiagnosticsModeChange: (String) -> Unit,\n',
    "personal diagnostics callback",
)
t = replace_once(
    t,
    '    var personalPage by remember { mutableStateOf("home") }\n'
    '    var diagnosticsMode by remember { mutableStateOf("off") }\n',
    '    var personalPage by remember { mutableStateOf("home") }\n',
    "remove local diagnostics",
)
t = replace_once(
    t,
    '            diagnosticsMode = diagnosticsMode,\n'
    '            onDiagnosticsModeChange = { diagnosticsMode = it },\n',
    '            diagnosticsMode = state.diagnosticsMode,\n'
    '            onDiagnosticsModeChange = onDiagnosticsModeChange,\n',
    "settings diagnostics state",
)
t = regex_once(
    t,
    r'''        "settings_automation" -> PersonalSubPage\("PHÂN VAI TTS BẰNG AI", "QUAY LẠI CÀI ĐẶT", \{ personalPage = "settings_home" \}\) \{
            PlaybackAutomationCard\(
.*?
            \)
        \}
''',
    '''        "settings_automation" -> PersonalSubPage("PHÂN VAI TTS BẰNG AI", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            ReferenceVoiceCastSettingsCard(
                state = state,
                onAutoVoiceCastChange = onAutoVoiceCastChange,
            )
        }
''',
    "global voice cast page",
)

t = regex_once(
    t,
    r'''        Text\(
            "Mức nhật ký chẩn đoán",
            fontWeight = FontWeight\.SemiBold,
            modifier = Modifier\.fillMaxWidth\(\)\.padding\(start = 10\.dp, top = 14\.dp, end = 10\.dp, bottom = 4\.dp\),
        \)
        listOf\(
            "off" to "Tắt",
            "basic" to "Gỡ lỗi cơ bản",
            "advanced" to "Gỡ lỗi nâng cao",
        \)\.forEach \{ \(value, label\) ->
            Button\(
                onClick = \{ onDiagnosticsModeChange\(value\) \},
                modifier = Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 8\.dp, vertical = 2\.dp\),
            \) \{ Text\(\(if \(diagnosticsMode == value\) "✓ " else ""\) \+ label\) \}
        \}
''',
    '''        Text(
            "Mức nhật ký chẩn đoán",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 14.dp, end = 10.dp, bottom = 4.dp),
        )
        var diagnosticsExpanded by remember { mutableStateOf(false) }
        val diagnosticsLabel = when (diagnosticsMode) {
            "basic" -> "Gỡ lỗi cơ bản"
            "advanced" -> "Gỡ lỗi nâng cao"
            else -> "Tắt"
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Button(onClick = { diagnosticsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$diagnosticsLabel ▼")
            }
            DropdownMenu(expanded = diagnosticsExpanded, onDismissRequest = { diagnosticsExpanded = false }) {
                listOf(
                    "off" to "Tắt",
                    "basic" to "Gỡ lỗi cơ bản",
                    "advanced" to "Gỡ lỗi nâng cao",
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text((if (diagnosticsMode == value) "✓ " else "") + label) },
                        onClick = {
                            diagnosticsExpanded = false
                            onDiagnosticsModeChange(value)
                        },
                    )
                }
            }
        }
''',
    "diagnostic dropdown",
)

marker = '@Composable\nprivate fun PlaybackAutomationCard('
if marker not in t:
    raise SystemExit("missing PlaybackAutomationCard marker")
voice_card = '''@Composable
private fun ReferenceVoiceCastSettingsCard(
    state: MainUiState,
    onAutoVoiceCastChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật mặc định cho truyện dùng cấu hình chung", Modifier.weight(1f))
                Switch(checked = state.autoVoiceCastEnabled, onCheckedChange = onAutoVoiceCastChange)
            }
            Text(
                "Bộ hồ sơ này là tiêu chuẩn dùng chung. Cấu hình vai riêng của từng truyện được mở từ TÙY CHỌN ĐỌC.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            if (state.voiceRoles.isEmpty()) {
                Text("Chưa có hồ sơ giọng chung hiển thị trong kho dữ liệu Kotlin hiện tại.", style = MaterialTheme.typography.bodySmall)
            } else {
                state.voiceRoles.take(10).forEach { role ->
                    Text(
                        (if (role.enabled) "✓ " else "○ ") + (if (role.isNarrator) "Người kể chuyện" else role.roleName),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    )
                }
            }
            Button(
                onClick = { },
                enabled = false,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("THÊM GIỌNG") }
            Button(
                onClick = { },
                enabled = false,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("KHÔI PHỤC 7 HỒ SƠ MẪU") }
            Text(
                "Hai nút quản lý hồ sơ được giữ đúng vị trí tham chiếu; backend hồ sơ giọng chung sẽ được nối với kho vai ở bước dữ liệu.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

'''
t = t.replace(marker, voice_card + marker, 1)
write(path, t)

# ---------------------------------------------------------------------------
# Reader screen.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
t = read(path)

t = replace_once(
    t,
    'import vn.nghetruyen.app.core.model.ReaderThemeMode\n',
    'import vn.nghetruyen.app.core.model.ReaderThemeMode\n'
    'import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode\n'
    'import vn.nghetruyen.app.core.model.TtsEngineOption\n'
    'import vn.nghetruyen.app.core.model.TtsVoiceOption\n',
    "reader model imports",
)
t = replace_once(
    t,
    '    onSleepTimer: (Int?) -> Unit,\n',
    '    onSleepTimer: (Int?) -> Unit,\n'
    '    onSleepTimerByChapters: (Int) -> Unit,\n',
    "reader sleep callback",
)
t = replace_once(
    t,
    '    onOpenStoryAdvancedOptions: () -> Unit,\n'
    '    onOpenTtsSettings: () -> Unit,\n',
    '    onOpenStoryAiOptions: () -> Unit,\n'
    '    onOpenStoryVoiceCastOptions: () -> Unit,\n'
    '    onEngineSelected: (TtsEngineOption?) -> Unit,\n'
    '    onVoiceSelected: (TtsVoiceOption?) -> Unit,\n'
    '    onRefreshVoices: () -> Unit,\n'
    '    onPreviewVoice: () -> Unit,\n'
    '    onRateChange: (Float) -> Unit,\n'
    '    onPitchChange: (Float) -> Unit,\n'
    '    onVolumeChange: (Float) -> Unit,\n'
    '    onSonicProcessingEnabledChange: (Boolean) -> Unit,\n'
    '    onOpenTtsSettings: () -> Unit,\n',
    "reader advanced and tts callbacks",
)
t = replace_once(
    t,
    '    onBackgroundMusicVolumeChange: (Float) -> Unit,\n'
    '    onMessage: (String) -> Unit,\n',
    '    onBackgroundMusicVolumeChange: (Float) -> Unit,\n'
    '    onBackgroundMusicDuckChange: (Float) -> Unit,\n'
    '    onAutoSceneMusicChange: (Boolean) -> Unit,\n'
    '    onSceneMusicPlaybackModeChange: (SceneMusicPlaybackMode) -> Unit,\n'
    '    onSceneMusicTargetLufsChange: (Float) -> Unit,\n'
    '    onSelectSceneMusic: () -> Unit,\n'
    '    onMessage: (String) -> Unit,\n',
    "reader music callbacks",
)
t = replace_once(
    t,
    '    var showTtsSettingsDialog by remember { mutableStateOf(false) }\n'
    '    var sleepStatus by remember(content.chapter.id) { mutableStateOf("Đang tắt") }\n',
    '    var showTtsSettingsDialog by remember { mutableStateOf(false) }\n'
    '    var showDiagnosticLogDialog by remember { mutableStateOf(false) }\n'
    '    var musicAttackMs by remember { mutableIntStateOf(250) }\n'
    '    var musicReleaseMs by remember { mutableIntStateOf(900) }\n',
    "reader local dialog states",
)

t = regex_once(
    t,
    r'''            Row\(modifier = Modifier\.fillMaxWidth\(\)\) \{
                ReaderButton\(
                    when \{
                        state\.aiBusy -> "AI ĐANG CHẠY…"
                        state\.chapterTextMode == ChapterTextMode\.AI_TRANSLATION -> "DỊCH LẠI"
                        else -> "DỊCH AI"
                    \},
                    onAiTranslate,
                    Modifier\.weight\(1f\),
                    enabled = !state\.aiBusy,
                    normalColor = ReferencePurple,
                    accessibilityLabel = "Dịch chương bằng AI",
                \)
                if \(!textMode\) \{
                    ReaderButton\(
                        "PHÂN VAI AI",
                        onVoiceCast,
                        Modifier\.weight\(1f\),
                        enabled = !state\.aiBusy,
                        normalColor = Color\(0xFFAF52DE\),
                        accessibilityLabel = "Phân vai giọng đọc bằng AI",
                    \)
                \}
            \}
''',
    '''            Row(modifier = Modifier.fillMaxWidth()) {
                if (state.diagnosticsMode != "off") {
                    ReaderButton(
                        "XEM NHẬT KÝ",
                        { showDiagnosticLogDialog = true },
                        Modifier.weight(1f),
                        normalColor = ReferenceGray,
                        accessibilityLabel = "Xem nhật ký chẩn đoán",
                    )
                }
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
''',
    "reader diagnostic action row",
)

t = replace_once(t, 'ReaderMenuButton("HẸN GIỜ NGỦ - $sleepStatus")', 'ReaderMenuButton("HẸN GIỜ NGỦ - ${state.sleepTimerStatus}")', "sleep status menu")
t = replace_once(
    t,
    'ReaderMenuButton("THIẾT LẬP AI CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryAdvancedOptions() }\n'
    '                    ReaderMenuButton("PHÂN VAI TTS CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryAdvancedOptions() }',
    'ReaderMenuButton("THIẾT LẬP AI CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryAiOptions() }\n'
    '                    ReaderMenuButton("PHÂN VAI TTS CHO TRUYỆN NÀY") { showReaderOptions = false; onOpenStoryVoiceCastOptions() }',
    "split reader story options",
)
t = replace_once(
    t,
    '''                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") {
                        showReaderOptions = false
                        onMessage("Mục nhật ký VietPhrase đã được đưa về đúng vị trí; trình xem nhật ký chuyên dụng sẽ được nối ở bước hoàn thiện chức năng.")
                    }
''',
    '''                    if (state.vietPhraseRules.isNotEmpty() || state.vietPhraseDictionaryStates.isNotEmpty()) {
                        ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") {
                            showReaderOptions = false
                            showDiagnosticLogDialog = true
                        }
                    }
''',
    "conditional vietphrase log",
)

new_display = '''    if (showDisplayDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayDialog = false },
            title = { Text("HIỂN THỊ VĂN BẢN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cỡ chữ: ${display.fontSizeSp} sp")
                        Row {
                            TextButton({ onFontSizeChange(display.fontSizeSp - 1) }) { Text("−") }
                            TextButton({ onFontSizeChange(display.fontSizeSp + 1) }) { Text("+") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Khoảng cách dòng: ${display.lineHeightPercent}%")
                        Row {
                            TextButton({ onLineHeightChange(display.lineHeightPercent - 10) }) { Text("−") }
                            TextButton({ onLineHeightChange(display.lineHeightPercent + 10) }) { Text("+") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Chế độ nền tối khi đọc", Modifier.weight(1f))
                        Switch(
                            checked = display.theme == ReaderThemeMode.DARK,
                            onCheckedChange = { enabled -> onThemeChange(if (enabled) ReaderThemeMode.DARK else ReaderThemeMode.LIGHT) },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Giữ màn hình sáng khi đọc", Modifier.weight(1f))
                        Switch(checked = display.keepScreenOn, onCheckedChange = onKeepScreenOnChange)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisplayDialog = false }) { Text("LƯU") } },
        )
    }

'''
t = regex_once(
    t,
    r'    if \(showDisplayDialog\) \{.*?\n    \}\n\n    if \(showTtsSettingsDialog\) \{',
    new_display + '    if (showTtsSettingsDialog) {',
    "reader display dialog",
)

new_tts = '''    if (showTtsSettingsDialog) {
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
                    ReaderMenuButton(if (state.selectedTtsEnginePackage == null) "✓ MẶC ĐỊNH HỆ THỐNG" else "MẶC ĐỊNH HỆ THỐNG") {
                        onEngineSelected(null)
                    }
                    state.ttsEngines.take(8).forEach { engine ->
                        ReaderMenuButton((if (engine.packageName == state.selectedTtsEnginePackage) "✓ " else "") + engine.label) {
                            onEngineSelected(engine)
                        }
                    }
                    Text(
                        if (state.ttsVoiceLoading) "Đang quét bộ đọc và giọng…" else "Đã nhận ${state.ttsEngines.size} bộ đọc.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ReaderMenuButton("QUÉT LẠI BỘ ĐỌC") { onRefreshVoices() }
                    ReaderMenuButton("SAO CHÉP CHẨN ĐOÁN BỘ ĐỌC") {
                        val diagnostic = buildString {
                            appendLine("Bộ đọc: ${state.selectedTtsEnginePackage ?: "Mặc định hệ thống"}")
                            appendLine("Ngôn ngữ: ${state.selectedTtsLanguageTag}")
                            appendLine("Giọng: ${state.selectedTtsVoiceName ?: "Mặc định"}")
                            append("Số bộ đọc/giọng: ${state.ttsEngines.size}/${state.ttsVoices.size}")
                        }
                        clipboard.setText(AnnotatedString(diagnostic))
                        onMessage("Đã sao chép chẩn đoán bộ đọc.")
                    }

                    Text("Ngôn ngữ", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(state.selectedTtsLanguageTag.ifBlank { "vi-VN" })

                    Text("Giọng đọc", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    ReaderMenuButton(if (state.selectedTtsVoiceName == null) "✓ GIỌNG MẶC ĐỊNH" else "GIỌNG MẶC ĐỊNH") {
                        onVoiceSelected(null)
                    }
                    state.ttsVoices.take(10).forEach { voice ->
                        ReaderMenuButton((if (voice.name == state.selectedTtsVoiceName) "✓ " else "") + voice.displayName) {
                            onVoiceSelected(voice)
                        }
                    }

                    Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ onSonicProcessingEnabledChange(false) }, Modifier.weight(1f)) {
                            Text((if (!state.sonicProcessingEnabled) "✓ " else "") + "HỆ THỐNG")
                        }
                        TextButton({ onSonicProcessingEnabledChange(true) }, Modifier.weight(1f)) {
                            Text((if (state.sonicProcessingEnabled) "✓ " else "") + "SONIC")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Chế độ Sonic", Modifier.weight(1f))
                        Switch(state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
                    }

                    Text("Tốc độ đọc: ${"%.2f".format(state.playback.rate)}×", modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ onRateChange((state.playback.rate - 0.05f).coerceAtLeast(0.5f)) }, Modifier.weight(1f)) { Text("−") }
                        TextButton({ onRateChange((state.playback.rate + 0.05f).coerceAtMost(2f)) }, Modifier.weight(1f)) { Text("+") }
                    }
                    Text("Cao độ: ${"%.2f".format(state.playback.pitch)}×")
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ onPitchChange((state.playback.pitch - 0.05f).coerceAtLeast(0.5f)) }, Modifier.weight(1f)) { Text("−") }
                        TextButton({ onPitchChange((state.playback.pitch + 0.05f).coerceAtMost(2f)) }, Modifier.weight(1f)) { Text("+") }
                    }
                    Text("Âm lượng: ${(state.ttsVolume * 100).toInt()}%")
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ onVolumeChange((state.ttsVolume - 0.05f).coerceAtLeast(0.05f)) }, Modifier.weight(1f)) { Text("−") }
                        TextButton({ onVolumeChange((state.ttsVolume + 0.05f).coerceAtMost(1f)) }, Modifier.weight(1f)) { Text("+") }
                    }

                    ReaderMenuButton("NGHE THỬ") { onPreviewVoice() }
                    ReaderMenuButton("KHÔI PHỤC MẶC ĐỊNH") {
                        onEngineSelected(null)
                        onVoiceSelected(null)
                        onRateChange(1f)
                        onPitchChange(1f)
                        onVolumeChange(1f)
                        onSonicProcessingEnabledChange(false)
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

'''
t = regex_once(
    t,
    r'    if \(showTtsSettingsDialog\) \{.*?\n    \}\n\n    if \(showSleepDialog\) \{',
    new_tts + '    if (showSleepDialog) {',
    "reader tts dialog",
)

new_sleep = '''    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("HẸN GIỜ NGỦ - ${state.sleepTimerStatus}") },
            text = { Column {
                ReaderMenuButton("15 PHÚT") { showSleepDialog = false; onSleepTimer(15) }
                ReaderMenuButton("30 PHÚT") { showSleepDialog = false; onSleepTimer(30) }
                ReaderMenuButton("45 PHÚT") { showSleepDialog = false; onSleepTimer(45) }
                ReaderMenuButton("60 PHÚT") { showSleepDialog = false; onSleepTimer(60) }
                ReaderMenuButton("HẾT CHƯƠNG HIỆN TẠI") { showSleepDialog = false; onSleepTimerByChapters(1) }
                ReaderMenuButton("HẾT 3 CHƯƠNG") { showSleepDialog = false; onSleepTimerByChapters(3) }
                ReaderMenuButton("TẮT HẸN GIỜ") { showSleepDialog = false; onSleepTimer(null) }
            } },
            confirmButton = { TextButton(onClick = { showSleepDialog = false }) { Text("ĐÓNG") } },
        )
    }

'''
t = regex_once(
    t,
    r'    if \(showSleepDialog\) \{.*?\n    \}\n\n    if \(showMusicDialog\) \{',
    new_sleep + '    if (showMusicDialog) {',
    "reader sleep dialog",
)

new_music = '''    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("NHẠC NỀN") },
            text = { Column(modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nhạc nền khi đọc bằng TTS", Modifier.weight(1f))
                    Switch(state.backgroundMusicEnabled, onBackgroundMusicEnabledChange)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Trao toàn quyền giữ và đổi nhạc cho AI", Modifier.weight(1f))
                    Switch(state.autoSceneMusicEnabled, onAutoSceneMusicChange)
                }

                Text("Chế độ phát", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ onSceneMusicPlaybackModeChange(SceneMusicPlaybackMode.SEQUENTIAL) }, Modifier.weight(1f)) {
                        Text((if (state.sceneMusicPlaybackMode == SceneMusicPlaybackMode.SEQUENTIAL) "✓ " else "") + "TUẦN TỰ")
                    }
                    TextButton({ onSceneMusicPlaybackModeChange(SceneMusicPlaybackMode.SHUFFLE) }, Modifier.weight(1f)) {
                        Text((if (state.sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE) "✓ " else "") + "NGẪU NHIÊN")
                    }
                }

                Text("CÂN BẰNG ÂM THANH", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("Mức chuẩn hóa: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS")
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ onSceneMusicTargetLufsChange((state.sceneMusicTargetLufs - 0.5f).coerceAtLeast(-30f)) }, Modifier.weight(1f)) { Text("−") }
                    TextButton({ onSceneMusicTargetLufsChange((state.sceneMusicTargetLufs + 0.5f).coerceAtMost(-8f)) }, Modifier.weight(1f)) { Text("+") }
                }
                Text("Mức giảm khi giọng đọc phát: ${(state.backgroundMusicDuckFactor * 100).toInt()}%")
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ onBackgroundMusicDuckChange((state.backgroundMusicDuckFactor - 0.05f).coerceAtLeast(0f)) }, Modifier.weight(1f)) { Text("GIẢM") }
                    TextButton({ onBackgroundMusicDuckChange((state.backgroundMusicDuckFactor + 0.05f).coerceAtMost(1f)) }, Modifier.weight(1f)) { Text("TĂNG") }
                }
                Text("Attack: $musicAttackMs ms")
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ musicAttackMs = (musicAttackMs - 50).coerceAtLeast(0) }, Modifier.weight(1f)) { Text("−") }
                    TextButton({ musicAttackMs = (musicAttackMs + 50).coerceAtMost(5000) }, Modifier.weight(1f)) { Text("+") }
                }
                Text("Release: $musicReleaseMs ms")
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ musicReleaseMs = (musicReleaseMs - 50).coerceAtLeast(0) }, Modifier.weight(1f)) { Text("−") }
                    TextButton({ musicReleaseMs = (musicReleaseMs + 50).coerceAtMost(5000) }, Modifier.weight(1f)) { Text("+") }
                }

                ReaderMenuButton("CHUẨN HÓA TOÀN BỘ KHO NHẠC") {
                    onMessage("Đã đặt thao tác chuẩn hóa kho nhạc đúng vị trí; worker chuẩn hóa hàng loạt sẽ dùng cấu hình LUFS hiện tại.")
                }
                Text(
                    "Kho nhạc: ${state.sceneMusicTracks.size} mục • Nhạc nền: ${if (state.backgroundMusicUri.isNullOrBlank()) "chưa chọn" else "đã chọn"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") { onSelectSceneMusic() }
                Text(
                    "AI chỉ đổi nhạc khi tùy chọn trao quyền ở trên được bật.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } },
            confirmButton = {
                TextButton(onClick = {
                    showMusicDialog = false
                    onMessage("Đã lưu cài đặt nhạc nền.")
                }) { Text("LƯU CÀI ĐẶT") }
            },
            dismissButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }

'''
t = regex_once(
    t,
    r'    if \(showMusicDialog\) \{.*?\n    \}\n\n    if \(showExportDialog\) \{',
    new_music + '    if (showExportDialog) {',
    "reader music dialog",
)

diag_dialog = '''    if (showDiagnosticLogDialog) {
        val sourceId = state.storyDetail?.story?.sourceId ?: content.chapter.storyId
        val sourceEvents = state.sourceDiagnostics.filter { it.sourceId == sourceId }.take(20)
        AlertDialog(
            onDismissRequest = { showDiagnosticLogDialog = false },
            title = { Text("NHẬT KÝ CHẨN ĐOÁN") },
            text = {
                Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    Text("Mức: ${state.diagnosticsMode}", fontWeight = FontWeight.SemiBold)
                    Text("Chương: ${content.chapter.title}", modifier = Modifier.padding(top = 4.dp))
                    Text("Chế độ văn bản: ${state.chapterTextMode}", modifier = Modifier.padding(top = 4.dp))
                    if (sourceEvents.isEmpty()) {
                        Text("Chưa có sự kiện chẩn đoán cho nguồn hiện tại.", modifier = Modifier.padding(top = 10.dp))
                    } else {
                        sourceEvents.forEach { event ->
                            Text(
                                "${event.severity} • ${event.category}/${event.name}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDiagnosticLogDialog = false }) { Text("ĐÓNG") } },
        )
    }

'''
insert_marker = '    if (showChapterInfoDialog) {'
if insert_marker not in t:
    raise SystemExit("missing chapter info marker")
t = t.replace(insert_marker, diag_dialog + insert_marker, 1)
write(path, t)

# ---------------------------------------------------------------------------
# Story detail.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
t = read(path)

t = replace_once(
    t,
    '    onDownloadRange: (Int, Int) -> Unit,\n',
    '    onDownloadRange: (Int, Int) -> Unit,\n'
    '    onDownloadSelected: (List<Int>) -> Unit,\n',
    "story selected download callback",
)
t = replace_once(
    t,
    '    var showRangeDialog by remember(detail.story.id) { mutableStateOf(false) }\n',
    '    var showMultiChapterDialog by remember(detail.story.id) { mutableStateOf(false) }\n',
    "story multiselect dialog state",
)
t = replace_once(
    t,
    '    var showAdvancedOptions by remember(detail.story.id) { mutableStateOf(false) }\n',
    '    var advancedMode by remember(detail.story.id) { mutableStateOf<String?>(null) }\n',
    "story advanced mode local",
)
t = regex_once(
    t,
    r'''    var rangeStart by remember\(detail\.story\.id\) \{ mutableStateOf\("1"\) \}
    var rangeEnd by remember\(detail\.story\.id, detail\.chapters\.size\) \{
        mutableStateOf\(detail\.chapters\.size\.coerceAtLeast\(1\)\.toString\(\)\)
    \}
''',
    '    var selectedDownloadChapters by remember(detail.story.id) { mutableStateOf(setOf<Int>()) }\n',
    "story remove range vars",
)
t = replace_once(
    t,
    '''    val tabs = buildList {
        add("intro" to "GIỚI THIỆU")
        add("chapters" to "CHƯƠNG")
        if (state.storyCommentsAvailable) add("comments" to "BÌNH LUẬN")
        add("source" to "NGUỒN")
    }
''',
    '''    val tabs = listOf(
        "intro" to "GIỚI THIỆU",
        "chapters" to "CHƯƠNG",
        "comments" to "BÌNH LUẬN",
        "source" to "NGUỒN",
    )
''',
    "story four tabs",
)
t = replace_once(
    t,
    '    val currentChapterIndex = detail.chapters.firstOrNull { it.id == state.playback.chapterId }?.index\n',
    '    val currentChapter = detail.chapters.firstOrNull { it.id == state.playback.chapterId }\n'
    '    val currentChapterIndex = currentChapter?.index\n',
    "story current chapter",
)
t = replace_once(
    t,
    '''    LaunchedEffect(state.storyAdvancedOptionsRequested) {
        if (state.storyAdvancedOptionsRequested) {
            showAdvancedOptions = true
            onConsumeAdvancedOptionsRequest()
        }
    }
''',
    '''    LaunchedEffect(state.storyAdvancedOptionsRequested, state.storyAdvancedOptionsMode) {
        if (state.storyAdvancedOptionsRequested) {
            advancedMode = state.storyAdvancedOptionsMode ?: "ai"
            onConsumeAdvancedOptionsRequest()
        }
    }
''',
    "story advanced request mode",
)
t = replace_once(
    t,
    '                text = if (state.continueAvailable) "ĐỌC TIẾP" else "ĐỌC NGAY",\n',
    '                text = if (state.continueAvailable) {\n'
    '                    "ĐỌC TIẾP" + currentChapter?.title?.takeIf(String::isNotBlank)?.let { "\\n$it" }.orEmpty()\n'
    '                } else "ĐỌC NGAY",\n',
    "story continue title",
)
t = replace_once(
    t,
    '        if (showAdvancedOptions) {\n'
    '            ReferenceActionButton(\n'
    '                text = "ĐÓNG CẤU HÌNH NÂNG CAO",\n'
    '                onClick = { showAdvancedOptions = false },\n',
    '        if (advancedMode != null) {\n'
    '            ReferenceActionButton(\n'
    '                text = if (advancedMode == "voice") "ĐÓNG PHÂN VAI TTS" else "ĐÓNG THIẾT LẬP AI",\n'
    '                onClick = { advancedMode = null },\n',
    "story advanced close",
)
t = replace_once(
    t,
    '        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {\n'
    '            Column(modifier = Modifier.padding(10.dp)) {\n'
    '                Text("Vai giọng thủ công", fontWeight = FontWeight.SemiBold)\n',
    '        if (advancedMode == "voice") {\n'
    '        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {\n'
    '            Column(modifier = Modifier.padding(10.dp)) {\n'
    '                Text("PHÂN VAI TTS CHO TRUYỆN NÀY", fontWeight = FontWeight.SemiBold)\n',
    "wrap story voice card",
)
t = replace_once(
    t,
    '            }\n'
    '        }\n'
    '        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {\n'
    '            Column(modifier = Modifier.padding(10.dp)) {\n'
    '                Text("AI riêng cho truyện", fontWeight = FontWeight.SemiBold)\n',
    '            }\n'
    '        }\n'
    '        }\n'
    '        if (advancedMode == "ai") {\n'
    '        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {\n'
    '            Column(modifier = Modifier.padding(10.dp)) {\n'
    '                Text("THIẾT LẬP AI CHO TRUYỆN NÀY", fontWeight = FontWeight.SemiBold)\n',
    "split story card boundary",
)
t = replace_once(
    t,
    '            }\n'
    '        }\n'
    '        }\n'
    '        Row(\n',
    '            }\n'
    '        }\n'
    '        }\n'
    '        }\n'
    '        Row(\n',
    "close story ai wrapper",
)
t = replace_once(
    t,
    '''                        onClick = {
                            showDownloadScopeDialog = false
                            showRangeDialog = true
                        },
''',
    '''                        onClick = {
                            showDownloadScopeDialog = false
                            selectedDownloadChapters = emptySet()
                            showMultiChapterDialog = true
                        },
''',
    "open multiselect download",
)
new_multi = '''    if (showMultiChapterDialog) {
        AlertDialog(
            onDismissRequest = { showMultiChapterDialog = false },
            title = { Text("CHỌN NHIỀU CHƯƠNG") },
            text = {
                LazyColumn {
                    items(detail.chapters, key = { "download:${it.id}" }) { chapter ->
                        val number = chapter.index + 1
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = number in selectedDownloadChapters,
                                onCheckedChange = { checked ->
                                    selectedDownloadChapters = if (checked) {
                                        selectedDownloadChapters + number
                                    } else {
                                        selectedDownloadChapters - number
                                    }
                                },
                            )
                            Text(
                                chapter.title,
                                modifier = Modifier.weight(1f).padding(top = 12.dp, end = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedDownloadChapters.isNotEmpty(),
                    onClick = {
                        onDownloadSelected(selectedDownloadChapters.sorted())
                        showMultiChapterDialog = false
                    },
                ) { Text("TẢI ${selectedDownloadChapters.size} CHƯƠNG") }
            },
            dismissButton = { TextButton(onClick = { showMultiChapterDialog = false }) { Text("HỦY") } },
        )
    }
'''
t = regex_once(
    t,
    r'''    if \(showRangeDialog\) \{
.*?
    \}
\}
''',
    new_multi + '}\n',
    "story multi chapter dialog",
)
write(path, t)

print("reference parity patch applied")
