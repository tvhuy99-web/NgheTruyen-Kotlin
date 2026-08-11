#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def edit(path: str, old: str, new: str) -> None:
    target = ROOT / path
    source = target.read_text(encoding="utf-8")
    if new in source:
        return
    if old not in source:
        raise SystemExit(f"LUA_DIAGNOSTICS_PATCH missing anchor in {path}: {old[:160]!r}")
    target.write_text(source.replace(old, new, 1), encoding="utf-8")


def replace_all(path: str, old: str, new: str) -> None:
    target = ROOT / path
    source = target.read_text(encoding="utf-8")
    if old not in source:
        return
    target.write_text(source.replace(old, new), encoding="utf-8")


# Compose RowScope.weight used by the global log dialog.
edit(
    "app/src/main/java/vn/nghetruyen/app/ui/components/ReferenceDiagnosticsChrome.kt",
    "import androidx.compose.foundation.layout.padding\n",
    "import androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.weight\n",
)

# Global Lua-style diagnostic bar immediately above bottom tabs, for every app destination.
app = "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt"
edit(app, "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Row\n", "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Row\n")
edit(app, "import vn.nghetruyen.app.ui.components.ReferenceDivider\n", "import vn.nghetruyen.app.ui.components.ReferenceDiagnosticsChrome\nimport vn.nghetruyen.app.ui.components.ReferenceDivider\n")
edit(
    app,
    '''        bottomBar = {
            if (state.destination == Destination.Root) {
                ReferencePrimaryBottomBar(selected = state.rootTab, onSelect = viewModel::setRootTab)
            }
        },''',
    '''        bottomBar = {
            Column {
                ReferenceDiagnosticsChrome(
                    state = state,
                    onExport = onExportSourceDiagnostics,
                    onClear = viewModel::clearSourceDiagnostics,
                )
                if (state.destination == Destination.Root) {
                    ReferencePrimaryBottomBar(selected = state.rootTab, onSelect = viewModel::setRootTab)
                }
            }
        },''',
)

# Reader must not own an always-visible private log button/dialog. The global bar owns it.
reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
edit(reader, "    var showChapterInfoDialog by remember { mutableStateOf(false) }\n    var showDiagnosticLogDialog by remember { mutableStateOf(false) }\n", "    var showChapterInfoDialog by remember { mutableStateOf(false) }\n")
edit(
    reader,
    '''            Row(Modifier.fillMaxWidth()) {
                ReaderButton("XEM NHẬT KÝ", { showDiagnosticLogDialog = true }, Modifier.weight(1f), normalColor = ReferenceGray)
                ReaderButton(if (state.aiBusy) "AI ĐANG CHẠY…" else if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) "DỊCH LẠI" else "DỊCH AI", onAiTranslate, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = ReferencePurple)
                ReaderButton("PHÂN VAI AI", onVoiceCast, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = Color(0xFFAF52DE))
            }''',
    '''            Row(Modifier.fillMaxWidth()) {
                ReaderButton(if (state.aiBusy) "AI ĐANG CHẠY…" else if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) "DỊCH LẠI" else "DỊCH AI", onAiTranslate, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = ReferencePurple)
                ReaderButton("PHÂN VAI AI", onVoiceCast, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = Color(0xFFAF52DE))
            }''',
)
edit(
    reader,
    '''    if (showDiagnosticLogDialog) {
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

''',
    "",
)

# Join VietPhrase's dedicated diagnostic operation into the shared black box too.
edit(
    reader,
    '''        vietPhraseDiagnosticBusy = true
        scope.launch {''',
    '''        vietPhraseDiagnosticBusy = true
        app.container.sourceDiagnostics.mark(
            name = "VIETPHRASE_DIAGNOSTIC_START",
            sourceId = storyDetail?.story?.sourceId ?: "vietphrase",
            attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "rules" to rules.size.toString()),
        )
        scope.launch {''',
)
edit(
    reader,
    '''            vietPhraseDiagnosticBusy = false
            exported.onSuccess { vietPhraseDiagnosticResult = it }
                .onFailure { onMessage(it.message ?: "Lỗi tạo nhật ký VietPhrase.") }''',
    '''            vietPhraseDiagnosticBusy = false
            exported.onSuccess {
                vietPhraseDiagnosticResult = it
                app.container.sourceDiagnostics.mark(
                    name = "VIETPHRASE_DIAGNOSTIC_COMPLETED",
                    sourceId = storyDetail?.story?.sourceId ?: "vietphrase",
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id),
                )
            }.onFailure { error ->
                app.container.sourceDiagnostics.mark(
                    name = "VIETPHRASE_DIAGNOSTIC_FAILED",
                    severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR,
                    sourceId = storyDetail?.story?.sourceId ?: "vietphrase",
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "error" to (error.message ?: error.javaClass.simpleName)),
                )
                onMessage(error.message ?: "Lỗi tạo nhật ký VietPhrase.")
            }''',
)

# Settings has the same full source/browser diagnostic tools as Extensions; log card vanishes while OFF.
personal = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
edit(
    personal,
    '''        "settings_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN") {
            PerformanceCard(state.performanceReport, onRunPerformanceDiagnostics)
        }''',
    '''        "settings_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN") {
            PerformanceCard(state.performanceReport, onRunPerformanceDiagnostics)
            SourceDiagnosticsSection(
                state = state,
                selectorBaseUrl = selectorBaseUrl,
                onSelectorBaseUrlChange = { selectorBaseUrl = it },
                selector = selector,
                onSelectorChange = { selector = it },
                selectorHtml = selectorHtml,
                onSelectorHtmlChange = { selectorHtml = it },
                onInspectSelector = onInspectSourceSelector,
                onExportDiagnostics = onExportSourceDiagnostics,
                onClearDiagnostics = onClearSourceDiagnostics,
                onCheckSource = onCheckSource,
                onCheckAll = onCheckAllSources,
                onOpenLogin = onOpenSourceLogin,
                onOpenDiagnosticBrowser = onOpenSourceDiagnosticBrowser,
                onClearSession = onClearSourceSession,
            )
        }''',
)
edit(
    personal,
    '''    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("NHẬT KÝ & TRACE", fontWeight = FontWeight.Bold)
            Text(
                when {
                    state.diagnosticsMode == "off" -> "CHƯA BẬT NHẬT KÝ"
                    state.sourceDiagnosticCount == 0 -> "ĐANG GHI NHẬT KÝ..."
                    else -> "XEM NHẬT KÝ • ${state.sourceDiagnosticCount} sự kiện"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(onExportDiagnostics, Modifier.weight(1f).padding(2.dp)) {
                    Text(if (state.diagnosticsMode == "advanced") "XUẤT HỘP ĐEN" else "XUẤT CHẨN ĐOÁN")
                }
                Button(onClearDiagnostics, Modifier.weight(1f).padding(2.dp)) { Text("XÓA NHẬT KÝ") }
            }
            state.sourceDiagnostics.take(8).forEach { event ->
                val duration = event.durationMs?.let { " • ${it} ms" }.orEmpty()
                Text("${event.severity} ${event.category}/${event.name}$duration", style = MaterialTheme.typography.bodySmall, fontWeight = if (event.severity == "ERROR") FontWeight.SemiBold else FontWeight.Normal)
                Text("${event.sourceId} • trace ${event.traceId.take(8)}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            state.sourceTraces.take(8).forEach { trace ->
                Text("${if (trace.failed) "LỖI" else "OK"} • ${trace.sourceId} • ${trace.eventCount} sự kiện • ${trace.endedAtEpochMs - trace.startedAtEpochMs} ms", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {''',
    '''    if (state.diagnosticsMode != "off") {
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("NHẬT KÝ & TRACE", fontWeight = FontWeight.Bold)
                Text(
                    if (state.sourceDiagnosticCount == 0) "CHƯA CÓ NHẬT KÝ" else "XEM NHẬT KÝ • ${state.sourceDiagnosticCount} sự kiện",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button(onExportDiagnostics, Modifier.weight(1f).padding(2.dp)) {
                        Text(if (state.diagnosticsMode == "advanced") "XUẤT HỘP ĐEN" else "XUẤT CHẨN ĐOÁN")
                    }
                    Button(onClearDiagnostics, Modifier.weight(1f).padding(2.dp)) { Text("XÓA NHẬT KÝ") }
                }
                state.sourceDiagnostics.take(30).forEach { event ->
                    val duration = event.durationMs?.let { " • ${it} ms" }.orEmpty()
                    Text("${event.severity} ${event.category}/${event.name}$duration", style = MaterialTheme.typography.bodySmall, fontWeight = if (event.severity == "ERROR") FontWeight.SemiBold else FontWeight.Normal)
                    Text("${event.sourceId} • trace ${event.traceId.take(16)}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
                state.sourceTraces.take(20).forEach { trace ->
                    Text("${if (trace.failed) "LỖI" else "OK"} • ${trace.sourceId} • ${trace.eventCount} sự kiện • ${trace.endedAtEpochMs - trace.startedAtEpochMs} ms", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {''',
)

# Larger live history and richer exported black box.
vm = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
replace_all(vm, "container.sourcePlatformManager.diagnosticSummaries(),", "container.sourcePlatformManager.diagnosticSummaries(200),")
replace_all(vm, "container.sourcePlatformManager.diagnosticTraces(),", "container.sourcePlatformManager.diagnosticTraces(100),")
edit(
    vm,
    '''    fun exportSourceDiagnostics(uri: Uri) {
        viewModelScope.launch {''',
    '''    private fun diagnosticsRuntimeSnapshot(): Map<String, String> {
        val snapshot = state.value
        val playback = snapshot.playback
        return linkedMapOf(
            "destination" to snapshot.destination.toString(),
            "rootTab" to snapshot.rootTab.name,
            "selectedSourceId" to snapshot.selectedSourceId,
            "loading" to snapshot.loading.toString(),
            "aiBusy" to snapshot.aiBusy.toString(),
            "storyId" to playback.storyId,
            "chapterId" to playback.chapterId,
            "chapterIndex" to playback.chapterIndex.toString(),
            "paragraphIndex" to playback.paragraphIndex.toString(),
            "speechChunkIndex" to playback.speechChunkIndex.toString(),
            "unitId" to playback.currentUnitId.orEmpty(),
            "playbackPlaying" to playback.isPlaying.toString(),
            "playbackPreparation" to playback.preparationState.name,
            "ttsRate" to playback.rate.toString(),
            "ttsPitch" to playback.pitch.toString(),
            "ttsVolume" to playback.volume.toString(),
            "sonicEnabled" to snapshot.sonicProcessingEnabled.toString(),
            "downloadJobs" to snapshot.downloads.size.toString(),
            "downloadFailures" to snapshot.downloadFailures.size.toString(),
            "audioExportJobs" to snapshot.audioExports.size.toString(),
            "vietPhraseRules" to snapshot.vietPhraseRules.size.toString(),
            "vietPhraseDictionaries" to snapshot.vietPhraseDictionaryStates.size.toString(),
            "sourceSessions" to snapshot.sourceSessions.size.toString(),
            "sourcePacks" to snapshot.sourcePacks.size.toString(),
        )
    }

    fun exportSourceDiagnostics(uri: Uri) {
        viewModelScope.launch {''',
)
edit(
    vm,
    '''                repositories = container.sourcePlatformManager.repositories(),
            )''',
    '''                repositories = container.sourcePlatformManager.repositories(),
                runtimeState = diagnosticsRuntimeSnapshot(),
                backupLogTail = state.value.backupLogText.takeLast(64_000),
            )''',
)

runtime = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt"
edit(runtime, "import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink\nimport vn.nghetruyen.source.diagnostics.DiagnosticEvent\n", "import vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink\nimport vn.nghetruyen.source.diagnostics.DiagnosticEvent\n")
edit(runtime, "import vn.nghetruyen.source.diagnostics.DiagnosticRedactor\nimport vn.nghetruyen.source.diagnostics.DiagnosticSink\n", "import vn.nghetruyen.source.diagnostics.DiagnosticRedactor\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport vn.nghetruyen.source.diagnostics.DiagnosticSink\n")
edit(runtime, "import java.time.Instant\nimport java.util.zip.ZipEntry\n", "import java.time.Instant\nimport java.util.UUID\nimport java.util.zip.ZipEntry\n")
edit(
    runtime,
    '''    fun setMode(requested: String): String {
        val normalized = normalizeMode(requested)
        val enteringAdvanced = normalized == "advanced" && mode != "advanced"
        mode = normalized
        prefs.edit().putString(KEY_MODE, normalized).apply()
        applyMode(normalized, rotateAdvanced = enteringAdvanced)
        return normalized
    }
''',
    '''    fun setMode(requested: String): String {
        val normalized = normalizeMode(requested)
        val previous = mode
        val enteringAdvanced = normalized == "advanced" && mode != "advanced"
        if (normalized == "off" && previous != "off") {
            mark(
                name = "DIAGNOSTICS_MODE_CHANGED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf("from" to previous, "to" to normalized),
            )
        }
        mode = normalized
        prefs.edit().putString(KEY_MODE, normalized).apply()
        applyMode(normalized, rotateAdvanced = enteringAdvanced)
        if (normalized != "off") {
            mark(
                name = "DIAGNOSTICS_MODE_CHANGED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf("from" to previous, "to" to normalized),
            )
        }
        return normalized
    }

    fun mark(
        name: String,
        category: DiagnosticCategory = DiagnosticCategory.RUNTIME,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        sourceId: String = "app",
        traceId: String = "",
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (mode == "off") return
        recorder.emit(
            DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId.ifBlank { "app:${UUID.randomUUID()}" },
                sourceId = sourceId.ifBlank { "app" },
                category = category,
                name = name.take(160),
                severity = severity,
                durationMs = durationMs,
                attributes = attributes + ("diagnosticsMode" to mode),
            ),
        )
    }
''',
)
edit(
    runtime,
    '''    fun exportBundle(
        events: List<DiagnosticEvent>,
        installed: List<SourcePackUiInfo>,
        repositories: List<SourceRepositoryUiInfo>,
    ): ByteArray {''',
    '''    fun exportBundle(
        events: List<DiagnosticEvent>,
        installed: List<SourcePackUiInfo>,
        repositories: List<SourceRepositoryUiInfo>,
        runtimeState: Map<String, String> = emptyMap(),
        backupLogTail: String = "",
    ): ByteArray {''',
)
edit(
    runtime,
    '''            zip.addText("report/repositories.json", repositoriesJson(repositories).toString(2))
            zip.addText("report/traces.txt", traceReport(events))
''',
    '''            zip.addText("report/repositories.json", repositoriesJson(repositories).toString(2))
            zip.addText("report/app_runtime.json", JSONObject(DiagnosticRedactor.redact(runtimeState)).toString(2))
            if (backupLogTail.isNotBlank()) {
                zip.addText("report/backup_tail.log", DiagnosticRedactor.redactLongText(backupLogTail, 64_000))
            }
            zip.addText("report/traces.txt", traceReport(events))
''',
)
edit(runtime, '        appendLine("Advanced mode retains up to 64 MiB of evidence and a crash-safe rolling journal in private app storage.")\n', '        appendLine("Advanced mode retains up to 64 MiB of evidence and a crash-safe rolling journal in private app storage.")\n        appendLine("The report also includes a sanitized app runtime snapshot plus the tail of the backup/restore log when available.")\n')

# TTS / Sonic / playback stages.
tts = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
edit(tts, "import vn.nghetruyen.app.audio.PcmLoudnessEstimator\n", "import vn.nghetruyen.app.audio.PcmLoudnessEstimator\nimport vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n")
edit(
    tts,
    '''    private var narrationPlanningChapterId: String = ""
    @Volatile private var narrationReloadPending = false
''',
    '''    private var narrationPlanningChapterId: String = ""
    @Volatile private var narrationReloadPending = false

    private fun diagnostic(
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val snapshot = PlaybackQueueStore.state.value
        container.sourceDiagnostics.mark(
            name = name,
            category = DiagnosticCategory.RUNTIME,
            severity = severity,
            sourceId = snapshot.sourceId.ifBlank { "tts" },
            traceId = "tts:$playbackSessionId",
            attributes = attributes + mapOf(
                "storyId" to snapshot.storyId,
                "chapterId" to snapshot.chapterId,
                "unitId" to snapshot.currentUnitId.orEmpty(),
                "speechChunkIndex" to snapshot.speechChunkIndex.toString(),
            ),
        )
    }
''',
)
edit(tts, '''                transitionMessage = "Đã tạm dừng vì tai nghe bị ngắt kết nối."
                pause()''', '''                transitionMessage = "Đã tạm dừng vì tai nghe bị ngắt kết nối."
                diagnostic("TTS_HEADSET_DISCONNECTED_PAUSE", DiagnosticSeverity.WARN)
                pause()''')
edit(tts, '''    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()''', '''    override fun onCreate() {
        super.onCreate()
        diagnostic("TTS_SERVICE_CREATED", DiagnosticSeverity.INFO)
        createNotificationChannel()''')
edit(tts, '''        val action = intent?.action
        when (action) {''', '''        val action = intent?.action
        diagnostic("TTS_COMMAND", attributes = mapOf("action" to (action ?: "RESTORE"), "startId" to startId.toString()))
        when (action) {''')
edit(tts, '''        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady && !currentEnginePackage.isNullOrBlank()) {''', '''        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            diagnostic("TTS_ENGINE_READY", DiagnosticSeverity.INFO, mapOf("engine" to currentEnginePackage.orEmpty()))
        } else {
            diagnostic("TTS_ENGINE_INIT_FAILED", DiagnosticSeverity.ERROR, mapOf("engine" to currentEnginePackage.orEmpty(), "status" to status.toString()))
        }
        if (!ttsReady && !currentEnginePackage.isNullOrBlank()) {''')
edit(tts, '''            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    if (utteranceId == activeSonicSynthesisId) onSonicSynthesisCompleted(false)
                    else onSpeechCompleted(utteranceId, false)
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == activeSonicSynthesisId) onSonicSynthesisCompleted(true)
                    else onSpeechCompleted(utteranceId, true)
                }
            })''', '''            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    diagnostic("TTS_UTTERANCE_START", attributes = mapOf("utteranceId" to utteranceId.orEmpty()))
                }
                override fun onError(utteranceId: String?) {
                    diagnostic("TTS_UTTERANCE_ERROR", DiagnosticSeverity.ERROR, mapOf("utteranceId" to utteranceId.orEmpty()))
                    if (utteranceId == activeSonicSynthesisId) onSonicSynthesisCompleted(false)
                    else onSpeechCompleted(utteranceId, false)
                }
                override fun onDone(utteranceId: String?) {
                    diagnostic("TTS_UTTERANCE_DONE", attributes = mapOf("utteranceId" to utteranceId.orEmpty()))
                    if (utteranceId == activeSonicSynthesisId) onSonicSynthesisCompleted(true)
                    else onSpeechCompleted(utteranceId, true)
                }
            })''')
edit(tts, '''        if (!requestAudioFocus()) {
            pendingPlay = false''', '''        if (!requestAudioFocus()) {
            diagnostic("TTS_AUDIO_FOCUS_FAILED", DiagnosticSeverity.WARN)
            pendingPlay = false''')
edit(tts, '''        if (desiredEngine != currentEnginePackage) {
            pendingRoleEnginePackage = desiredEngine ?: "__DEFAULT__"''', '''        if (desiredEngine != currentEnginePackage) {
            diagnostic("TTS_VOICE_ENGINE_SWITCH", DiagnosticSeverity.INFO, mapOf("from" to currentEnginePackage.orEmpty(), "to" to desiredEngine.orEmpty()))
            pendingRoleEnginePackage = desiredEngine ?: "__DEFAULT__"''')

# Narration AI stages.
ai = "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt"
edit(ai, "import org.json.JSONObject\n", "import org.json.JSONObject\nimport vn.nghetruyen.app.NgheTruyenApplication\nimport vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n")
edit(ai, '''    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan> {
        val rawText = request.rawText.trim()''', '''    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan> {
        diagnostic(
            "AI_NARRATION_PLAN_START",
            attributes = mapOf("storyId" to request.storyId, "chapterId" to request.chapterId, "voiceCast" to request.includeVoiceCast.toString(), "sceneMusic" to request.includeSceneMusic.toString(), "inputChars" to request.rawText.length.toString()),
        )
        val rawText = request.rawText.trim()''')
edit(ai, '''            }.fold(
                { AppResult.Success(it) },
                { failure("AI_BAD_RESPONSE", it.message ?: "Kết quả kế hoạch kể chuyện không hợp lệ.", it) },
            )''', '''            }.fold(
                {
                    diagnostic("AI_NARRATION_PLAN_COMPLETED", DiagnosticSeverity.INFO, mapOf("storyId" to request.storyId, "chapterId" to request.chapterId))
                    AppResult.Success(it)
                },
                { failure("AI_BAD_RESPONSE", it.message ?: "Kết quả kế hoạch kể chuyện không hợp lệ.", it) },
            )''')
edit(ai, '''    private fun failure(code: String, message: String, cause: Throwable? = null) = AppResult.Failure(code, message, cause)
''', '''    private fun diagnostic(name: String, severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG, attributes: Map<String, String> = emptyMap()) {
        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(name = name, category = DiagnosticCategory.RUNTIME, severity = severity, sourceId = "ai", attributes = attributes)
    }

    private fun failure(code: String, message: String, cause: Throwable? = null): AppResult.Failure {
        diagnostic("AI_NARRATION_FAILURE", DiagnosticSeverity.WARN, mapOf("code" to code, "message" to message.take(500), "cause" to (cause?.javaClass?.simpleName ?: "")))
        return AppResult.Failure(code, message, cause)
    }
''')

# Download job/item/failure stages.
download = "app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt"
edit(download, '''        val repository = application.container.libraryRepository
        val source = application.container.sourceRegistry.get(sourceId)''', '''        val repository = application.container.libraryRepository
        val diagnostics = application.container.sourceDiagnostics
        diagnostics.mark(name = "DOWNLOAD_JOB_STARTED", sourceId = sourceId, traceId = "download:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO, attributes = mapOf("storyId" to storyId, "selectionMode" to selectionMode.name, "attempt" to runAttemptCount.toString()))
        val source = application.container.sourceRegistry.get(sourceId)''')
edit(download, '''                    is AppResult.Success -> {
                        repository.saveDownloadedChapter(content.value.copy(chapter = chapter))
                        repository.clearDownloadFailure(jobId, chapter.index)
                    }''', '''                    is AppResult.Success -> {
                        repository.saveDownloadedChapter(content.value.copy(chapter = chapter))
                        repository.clearDownloadFailure(jobId, chapter.index)
                        diagnostics.mark(name = "DOWNLOAD_ITEM_COMPLETED", sourceId = sourceId, traceId = "download:$jobId", attributes = mapOf("storyId" to storyId, "chapterIndex" to chapter.index.toString(), "chapterTitle" to chapter.title.take(160)))
                    }''')
edit(download, '''            publishProgress(100, "Hoàn tất")
            Result.success(
''', '''            publishProgress(100, "Hoàn tất")
            diagnostics.mark(name = "DOWNLOAD_JOB_COMPLETED", sourceId = sourceId, traceId = "download:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO, attributes = mapOf("storyId" to storyId, "chapters" to completed.toString()))
            Result.success(
''')
edit(download, '''        } catch (error: Exception) {
            repository.updateDownloadJob(''', '''        } catch (error: Exception) {
            diagnostics.mark(name = "DOWNLOAD_RUNTIME_ERROR", sourceId = sourceId, traceId = "download:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR, attributes = mapOf("storyId" to storyId, "error" to (error.message ?: error.javaClass.simpleName), "attempt" to runAttemptCount.toString()))
            repository.updateDownloadJob(''')
edit(download, '''    ): Result {
        repository.updateDownloadJob(
            id = jobId,
            storyId = storyId,
            sourceId = sourceId,
            state = DownloadState.FAILED,''', '''    ): Result {
        (applicationContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(name = "DOWNLOAD_SOURCE_FAILURE", sourceId = sourceId, traceId = "download:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR, attributes = mapOf("storyId" to storyId, "code" to failure.code, "error" to failure.message.take(500), "completed" to completed.toString(), "total" to total.toString()))
        repository.updateDownloadJob(
            id = jobId,
            storyId = storyId,
            sourceId = sourceId,
            state = DownloadState.FAILED,''')

# Audio export lifecycle + segment diagnostics.
audio = "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt"
edit(audio, '''        val job = container.libraryRepository.getAudioExportJob(jobId) ?: return Result.failure()
        val outputFormat =''', '''        val job = container.libraryRepository.getAudioExportJob(jobId) ?: return Result.failure()
        container.sourceDiagnostics.mark(name = "AUDIO_EXPORT_STARTED", sourceId = "audio-export", traceId = "audio-export:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO, attributes = mapOf("storyId" to job.storyId, "format" to job.outputFormat, "scope" to job.scope, "packaging" to job.packaging))
        val outputFormat =''')
edit(audio, '''            completedSuccessfully = true
            Result.success(workDataOf(KEY_DESTINATION_URI to job.destinationUri))''', '''            completedSuccessfully = true
            container.sourceDiagnostics.mark(name = "AUDIO_EXPORT_COMPLETED", sourceId = "audio-export", traceId = "audio-export:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO, attributes = mapOf("storyId" to job.storyId, "segments" to chunks.size.toString(), "format" to outputFormat.name))
            Result.success(workDataOf(KEY_DESTINATION_URI to job.destinationUri))''')
edit(audio, '''        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {''', '''        } catch (cancelled: CancellationException) {
            container.sourceDiagnostics.mark(name = "AUDIO_EXPORT_CANCELLED", sourceId = "audio-export", traceId = "audio-export:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.WARN, attributes = mapOf("storyId" to job.storyId))
            withContext(NonCancellable) {''')
edit(audio, '''        } catch (error: Throwable) {
            val message = error.message?.take(500) ?: "Không xuất được tệp âm thanh."''', '''        } catch (error: Throwable) {
            val message = error.message?.take(500) ?: "Không xuất được tệp âm thanh."
            container.sourceDiagnostics.mark(name = "AUDIO_EXPORT_RUNTIME_ERROR", sourceId = "audio-export", traceId = "audio-export:$jobId", severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR, attributes = mapOf("storyId" to job.storyId, "error" to message, "type" to error.javaClass.simpleName, "attempt" to runAttemptCount.toString()))''')
edit(audio, '''                container.libraryRepository.updateAudioExportProgress(job.id, completed, chunks.size, DownloadState.RUNNING, null)
                setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to chunks.size))''', '''                container.libraryRepository.updateAudioExportProgress(job.id, completed, chunks.size, DownloadState.RUNNING, null)
                container.sourceDiagnostics.mark(name = "AUDIO_EXPORT_SEGMENT_COMPLETED", sourceId = "audio-export", traceId = "audio-export:${job.id}", attributes = mapOf("segment" to completed.toString(), "total" to chunks.size.toString(), "reused" to reusable.toString()))
                setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to chunks.size))''')

print("LUA_DIAGNOSTICS_PATCH=APPLIED")
