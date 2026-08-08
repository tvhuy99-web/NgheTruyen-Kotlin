from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text()


def write(path, text):
    (ROOT / path).write_text(text)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)


def regex_once(text, pattern, repl, label):
    result, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 regex match, found {count}")
    return result


# ---------------------------------------------------------------------------
# Pronunciation persistence: XPK supports editing an existing rule.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt"
text = read(path)
text = replace_once(
    text,
    '''    @Query("SELECT * FROM tts_pronunciations ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")\n    suspend fun listAll(): List<PronunciationEntity>\n\n    @Query("SELECT * FROM tts_pronunciations WHERE enabled = 1 ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")\n''',
    '''    @Query("SELECT * FROM tts_pronunciations ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")\n    suspend fun listAll(): List<PronunciationEntity>\n\n    @Query("SELECT * FROM tts_pronunciations WHERE id = :id LIMIT 1")\n    suspend fun get(id: Long): PronunciationEntity?\n\n    @Query("SELECT * FROM tts_pronunciations WHERE enabled = 1 ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")\n''',
    "PronunciationDao get",
)
write(path, text)

path = "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt"
text = read(path)
text = replace_once(
    text,
    '''    suspend fun setPronunciationEnabled(id: Long, enabled: Boolean) {\n        db.pronunciationDao().setEnabled(id, enabled, System.currentTimeMillis())\n    }\n''',
    '''    suspend fun updatePronunciation(id: Long, original: String, replacement: String): Result<Unit> = runCatching {\n        val current = requireNotNull(db.pronunciationDao().get(id)) { "Không tìm thấy cách đọc." }\n        val cleanOriginal = original.trim()\n        val cleanReplacement = replacement.trim()\n        require(cleanOriginal.isNotEmpty() && cleanReplacement.isNotEmpty()) { "Hãy nhập đầy đủ từ gốc và cách đọc." }\n        require(cleanOriginal.length <= 120) { "Từ gốc quá dài." }\n        require(cleanReplacement.length <= 240) { "Cách đọc quá dài." }\n        db.pronunciationDao().upsert(\n            current.copy(\n                original = cleanOriginal,\n                replacement = cleanReplacement,\n                enabled = true,\n                updatedAt = System.currentTimeMillis(),\n            ),\n        )\n        Unit\n    }\n\n    suspend fun setPronunciationEnabled(id: Long, enabled: Boolean) {\n        db.pronunciationDao().setEnabled(id, enabled, System.currentTimeMillis())\n    }\n''',
    "pronunciation update repository",
)
text = replace_once(
    text,
    '''    suspend fun setVietPhraseDictionaryEnabled(id: String, enabled: Boolean) =\n        db.vietPhraseDictionaryStateDao().setEnabled(id, enabled)\n\n    suspend fun previewVietPhraseImport(\n''',
    '''    suspend fun setVietPhraseDictionaryEnabled(id: String, enabled: Boolean) =\n        db.vietPhraseDictionaryStateDao().setEnabled(id, enabled)\n\n    suspend fun deleteVietPhraseDictionary(kind: VietPhraseDictionaryKind) = db.withTransaction {\n        db.vietPhraseDao().deleteDictionary(kind.name, VietPhraseScope.GLOBAL.name, null)\n        db.vietPhraseDictionaryStateDao().deleteKinds(listOf(kind.name))\n    }\n\n    suspend fun clearAllVietPhraseDictionaries() = db.withTransaction {\n        db.vietPhraseDao().deleteAll()\n        db.vietPhraseDictionaryStateDao().deleteAll()\n    }\n\n    suspend fun previewVietPhraseImport(\n''',
    "VietPhrase delete repository",
)
write(path, text)

# ---------------------------------------------------------------------------
# VietPhrase reference settings and fallback-Han-Viet semantics.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/ReferenceVietPhraseRuntime.kt"
text = read(path)
text = text.replace("var enabled: Boolean = true", "var enabled: Boolean = false")
text = text.replace("prefs.getBoolean(KEY_ENABLED, true)", "prefs.getBoolean(KEY_ENABLED, false)")
write(path, text)

path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseModels.kt"
text = read(path)
text = replace_once(
    text,
    '''    val capitalizeSentences: Boolean = true,\n    val traceLimit: Int = 2_000,\n''',
    '''    val capitalizeSentences: Boolean = true,\n    val fallbackHanViet: Boolean = true,\n    val traceLimit: Int = 2_000,\n''',
    "VietPhrase fallback option",
)
write(path, text)

path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseEngine.kt"
text = read(path)
text = replace_once(
    text,
    '''    private val baseLiteralRules = normalizedRules\n        .filter { it.kind != VietPhraseDictionaryKind.AI_REPLACE && it.matchMode == VietPhraseMatchMode.LITERAL }\n        .sortedWith(RULE_ORDER)\n    private val baseLiteralTrie = buildTrie(baseLiteralRules)\n''',
    '''    private val baseLiteralRules = normalizedRules\n        .filter {\n            it.kind != VietPhraseDictionaryKind.AI_REPLACE &&\n                it.kind != VietPhraseDictionaryKind.PHIEN_AM &&\n                it.matchMode == VietPhraseMatchMode.LITERAL\n        }\n        .sortedWith(RULE_ORDER)\n    private val baseLiteralTrie = buildTrie(baseLiteralRules)\n\n    // ChinesePhienAmWords is the legacy Hán-Việt fallback, not a peer dictionary layer.\n    // It is only consulted when no normal phrase/rule matched the current source span.\n    private val fallbackHanVietRules = normalizedRules\n        .filter { it.kind == VietPhraseDictionaryKind.PHIEN_AM && it.matchMode == VietPhraseMatchMode.LITERAL }\n        .sortedWith(RULE_ORDER)\n    private val fallbackHanVietTrie = buildTrie(fallbackHanVietRules)\n''',
    "VietPhrase Hán-Việt fallback trie",
)
old = '''            } else if (direct != null) {\n                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)\n                appendSmart(base, replacement)\n                counts[direct.rule.kind] = (counts[direct.rule.kind] ?: 0) + 1\n                if (options.traceLimit > 0 && trace.size < options.traceLimit) {\n                    trace += VietPhraseTraceEntry(cursor, direct.end, text.substring(cursor, direct.end), replacement, direct.rule.kind, direct.rule.id)\n                } else if (options.traceLimit > 0) truncated = true\n                cursor = direct.end\n            } else {\n                val raw = text.substring(cursor, cursor + chLength)\n                base.append(if (options.normalizePunctuation) PUNCTUATION[raw] ?: raw else raw)\n                cursor += chLength\n            }\n'''
new = '''            } else if (direct != null) {\n                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)\n                appendSmart(base, replacement)\n                counts[direct.rule.kind] = (counts[direct.rule.kind] ?: 0) + 1\n                if (options.traceLimit > 0 && trace.size < options.traceLimit) {\n                    trace += VietPhraseTraceEntry(cursor, direct.end, text.substring(cursor, direct.end), replacement, direct.rule.kind, direct.rule.id)\n                } else if (options.traceLimit > 0) truncated = true\n                cursor = direct.end\n            } else {\n                val fallback = if (options.fallbackHanViet) bestLiteral(text, cursor, fallbackHanVietTrie, options) else null\n                if (fallback != null) {\n                    val replacement = resolveMeaning(fallback.replacement, fallback.rule.kind, options.oneMeaning)\n                    appendSmart(base, replacement)\n                    counts[fallback.rule.kind] = (counts[fallback.rule.kind] ?: 0) + 1\n                    if (options.traceLimit > 0 && trace.size < options.traceLimit) {\n                        trace += VietPhraseTraceEntry(cursor, fallback.end, text.substring(cursor, fallback.end), replacement, fallback.rule.kind, fallback.rule.id)\n                    } else if (options.traceLimit > 0) truncated = true\n                    cursor = fallback.end\n                } else {\n                    val raw = text.substring(cursor, cursor + chLength)\n                    base.append(if (options.normalizePunctuation) PUNCTUATION[raw] ?: raw else raw)\n                    cursor += chLength\n                }\n            }\n'''
text = replace_once(text, old, new, "VietPhrase fallback execution")
write(path, text)

# ---------------------------------------------------------------------------
# AppViewModel: reference state, direct file import, exact backup scopes/log.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
text = replace_once(
    text,
    "import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine\n",
    "import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine\nimport vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime\n",
    "AppViewModel ReferenceVietPhraseRuntime import",
)
text = replace_once(
    text,
    '''    val vietPhraseOnlineBusy: Boolean = false,\n    val vietPhraseOnlineStatus: String = "",\n    val backupComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),\n    val backupHistory: List<BackupHistoryEntry> = emptyList(),\n''',
    '''    val vietPhraseOnlineBusy: Boolean = false,\n    val vietPhraseOnlineStatus: String = "",\n    val vietPhraseEnabled: Boolean = false,\n    val vietPhraseFallbackHanViet: Boolean = true,\n    val backupComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),\n    val backupHistory: List<BackupHistoryEntry> = emptyList(),\n    val backupLogPath: String = "",\n    val backupLogText: String = "",\n''',
    "MainUiState reference VP and backup log",
)
text = replace_once(
    text,
    '''class AppViewModel(application: Application) : AndroidViewModel(application) {\n    private val app = application as NgheTruyenApplication\n    private val container = app.container\n    private val mutableState = MutableStateFlow(\n''',
    '''class AppViewModel(application: Application) : AndroidViewModel(application) {\n    private val app = application as NgheTruyenApplication\n    private val container = app.container\n    private val referenceVietPhraseRuntime = ReferenceVietPhraseRuntime.also { it.load(application) }\n    private val mutableState = MutableStateFlow(\n''',
    "AppViewModel reference VP runtime",
)
text = replace_once(
    text,
    '''            sourceTraces = container.sourcePlatformManager.diagnosticTraces(),\n            backupHistory = container.backupHistoryStore.entries(),\n        ),\n''',
    '''            sourceTraces = container.sourcePlatformManager.diagnosticTraces(),\n            backupHistory = container.backupHistoryStore.entries(),\n            backupLogPath = container.backupHistoryStore.logPath(),\n            backupLogText = container.backupHistoryStore.logText(),\n            vietPhraseEnabled = referenceVietPhraseRuntime.enabled,\n            vietPhraseFallbackHanViet = referenceVietPhraseRuntime.fallbackHanViet,\n        ),\n''',
    "AppViewModel initial reference state",
)
text = replace_once(
    text,
    '''    fun setPronunciationEnabled(id: Long, enabled: Boolean) {\n''',
    '''    fun updatePronunciation(id: Long, original: String, replacement: String) {\n        viewModelScope.launch {\n            container.libraryRepository.updatePronunciation(id, original, replacement)\n                .onSuccess { showMessage("Đã cập nhật.") }\n                .onFailure { showMessage(it.message ?: "Không thể cập nhật cách đọc.") }\n        }\n    }\n\n    fun setPronunciationEnabled(id: Long, enabled: Boolean) {\n''',
    "AppViewModel pronunciation update",
)
# Exact auto-VietPhrase display before AI auto processing.
text = replace_once(
    text,
    '''                    val autoTranslate = aiProfile?.autoRunOnOpen == true && aiProfile.mode == "TRANSLATE"\n                    PlaybackQueueStore.loadContent(\n                        sourceId = sourceId,\n                        content = enriched,\n''',
    '''                    val autoTranslate = aiProfile?.autoRunOnOpen == true && aiProfile.mode == "TRANSLATE"\n                    val initialVietPhraseContent = if (referenceVietPhraseRuntime.enabled && !autoTranslate) {\n                        val rules = container.libraryRepository.listEnabledVietPhrase(enriched.chapter.storyId)\n                        if (rules.isEmpty()) null else {\n                            val engine = withContext(Dispatchers.Default) { VietPhraseEngine(rules) }\n                            val options = VietPhraseOptions(\n                                storyId = enriched.chapter.storyId,\n                                fallbackHanViet = referenceVietPhraseRuntime.fallbackHanViet,\n                                traceLimit = 0,\n                            )\n                            val translated = withContext(Dispatchers.Default) {\n                                enriched.paragraphs.map { engine.translate(it, options) }\n                            }\n                            enriched.copy(paragraphs = translated)\n                        }\n                    } else null\n                    val initialContent = initialVietPhraseContent ?: enriched\n                    val initialTextMode = if (initialVietPhraseContent != null) ChapterTextMode.VIETPHRASE else ChapterTextMode.ORIGINAL\n                    PlaybackQueueStore.loadContent(\n                        sourceId = sourceId,\n                        content = initialContent,\n''',
    "auto VietPhrase initial content",
)
text = replace_once(
    text,
    '''                            chapterContent = enriched,\n                            originalChapterContent = enriched,\n                            chapterTextMode = ChapterTextMode.ORIGINAL,\n                            continueAvailable = true,\n                            message = if (autoTranslate) "Đang chuẩn bị bản dịch AI trước khi phát…" else it.message,\n''',
    '''                            chapterContent = initialContent,\n                            originalChapterContent = enriched,\n                            chapterTextMode = initialTextMode,\n                            continueAvailable = true,\n                            message = when {\n                                autoTranslate -> "Đang chuẩn bị bản dịch AI trước khi phát…"\n                                initialVietPhraseContent != null -> "Đã áp dụng VietPhrase."\n                                else -> it.message\n                            },\n''',
    "auto VietPhrase state",
)
# Backup scope/log APIs before legacy per-component toggle.
text = replace_once(
    text,
    '''    fun setBackupComponentEnabled(component: BackupComponent, enabled: Boolean) {\n''',
    '''    fun setBackupComponents(components: Set<BackupComponent>) {\n        val normalized = components.ifEmpty { BackupComponent.entries.toSet() }\n        mutableState.update { it.copy(backupComponents = normalized) }\n    }\n\n    fun refreshBackupLog(): Boolean {\n        val path = container.backupHistoryStore.logPath()\n        val text = container.backupHistoryStore.logText()\n        mutableState.update { it.copy(backupLogPath = path, backupLogText = text) }\n        if (text.isBlank()) showMessage("Chưa có nhật ký sao lưu hoặc khôi phục.")\n        return text.isNotBlank()\n    }\n\n    fun clearBackupLog() {\n        container.backupHistoryStore.clear()\n        mutableState.update {\n            it.copy(\n                backupHistory = emptyList(),\n                backupLogPath = container.backupHistoryStore.logPath(),\n                backupLogText = "",\n                message = "Đã xóa nhật ký sao lưu và khôi phục.",\n            )\n        }\n    }\n\n    fun setBackupComponentEnabled(component: BackupComponent, enabled: Boolean) {\n''',
    "backup exact scope/log APIs",
)
# VP visible settings helpers before online updater.
text = replace_once(
    text,
    '''    fun checkVietPhraseOnlineUpdates() {\n''',
    '''    fun setVietPhraseMasterEnabled(enabled: Boolean) {\n        ReferenceVietPhraseRuntime.setEnabled(getApplication(), enabled)\n        mutableState.update { it.copy(vietPhraseEnabled = enabled) }\n        showMessage(if (enabled) "Đã bật VietPhrase." else "Đã tắt VietPhrase.")\n    }\n\n    fun setVietPhraseFallbackHanViet(enabled: Boolean) {\n        ReferenceVietPhraseRuntime.setFallbackHanViet(getApplication(), enabled)\n        mutableState.update { it.copy(vietPhraseFallbackHanViet = enabled) }\n    }\n\n    fun prepareVietPhraseImport(kind: VietPhraseDictionaryKind?) {\n        ReferenceVietPhraseRuntime.prepareImport(kind)\n    }\n\n    fun deleteVietPhraseDictionary(kind: VietPhraseDictionaryKind) {\n        viewModelScope.launch {\n            container.libraryRepository.deleteVietPhraseDictionary(kind)\n            showMessage("Đã xóa bộ dữ liệu.")\n        }\n    }\n\n    fun clearAllVietPhraseDictionaries() {\n        viewModelScope.launch {\n            container.libraryRepository.clearAllVietPhraseDictionaries()\n            showMessage("Đã xóa toàn bộ dữ liệu VietPhrase.")\n        }\n    }\n\n    fun checkVietPhraseOnlineUpdates() {\n''',
    "reference VP settings helpers",
)
# Backup log state must refresh after every operation.
text = text.replace(
    'backupHistory = container.backupHistoryStore.entries())',
    'backupHistory = container.backupHistoryStore.entries(), backupLogPath = container.backupHistoryStore.logPath(), backupLogText = container.backupHistoryStore.logText())',
)
# Manual/improve VP must honor fallback setting.
text = text.replace(
    'VietPhraseOptions(storyId = original.chapter.storyId, traceLimit = 0)',
    'VietPhraseOptions(storyId = original.chapter.storyId, fallbackHanViet = state.value.vietPhraseFallbackHanViet, traceLimit = 0)',
)
# Visible import is direct like the XPK file picker flow, not preview/diff management UI.
text = regex_once(
    text,
    r'''    fun importVietPhrase\(uri: Uri\) \{.*?\n    \}\n\n    fun confirmVietPhraseImport\(\)''',
    '''    fun importVietPhrase(uri: Uri) {\n        viewModelScope.launch {\n            mutableState.update { it.copy(loading = true, pendingVietPhraseImport = null, message = "Đang nhập dữ liệu VietPhrase…") }\n            when (val result = container.vietPhraseTransferManager.importFrom(uri)) {\n                is AppResult.Success -> mutableState.update {\n                    it.copy(\n                        loading = false,\n                        pendingVietPhraseImport = null,\n                        message = "Đã nhập ${result.value} quy tắc VietPhrase.",\n                    )\n                }\n                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }\n            }\n        }\n    }\n\n    fun confirmVietPhraseImport()''',
    "direct VietPhrase import",
)
write(path, text)

# ---------------------------------------------------------------------------
# PersonalScreen: replace backend-management UI with exact XPK-facing choices.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
text = read(path)
text = replace_once(
    text,
    '''    onAddPronunciation: (String, String) -> Unit,\n    onPronunciationEnabledChange: (Long, Boolean) -> Unit,\n    onDeletePronunciation: (Long) -> Unit,\n''',
    '''    onAddPronunciation: (String, String) -> Unit,\n    onUpdatePronunciation: (Long, String, String) -> Unit,\n    onPronunciationEnabledChange: (Long, Boolean) -> Unit,\n    onDeletePronunciation: (Long) -> Unit,\n''',
    "Personal pronunciation callback",
)
text = replace_once(
    text,
    '''    onRejectVietPhraseSuggestion: (String) -> Unit,\n    onRefreshAiModels: (AiProvider, String, String) -> Unit,\n''',
    '''    onRejectVietPhraseSuggestion: (String) -> Unit,\n    onPrepareVietPhraseImport: (VietPhraseDictionaryKind?) -> Unit,\n    onDeleteVietPhraseDictionary: (VietPhraseDictionaryKind) -> Unit,\n    onClearAllVietPhrase: () -> Unit,\n    onVietPhraseMasterEnabledChange: (Boolean) -> Unit,\n    onVietPhraseFallbackChange: (Boolean) -> Unit,\n    onRefreshAiModels: (AiProvider, String, String) -> Unit,\n''',
    "Personal VP reference callbacks",
)
text = replace_once(
    text,
    '''    onBackupComponentChange: (BackupComponent, Boolean) -> Unit,\n    onExportBackup: () -> Unit,\n    onRestoreBackup: () -> Unit,\n''',
    '''    onBackupComponentChange: (BackupComponent, Boolean) -> Unit,\n    onBackupComponentsChange: (Set<BackupComponent>) -> Unit,\n    onRefreshBackupLog: () -> Boolean,\n    onClearBackupLog: () -> Unit,\n    onExportBackup: () -> Unit,\n    onRestoreBackup: () -> Unit,\n''',
    "Personal backup reference callbacks",
)
text = replace_once(
    text,
    '''    var showFactoryResetFinal by remember { mutableStateOf(false) }\n    var showAddRepositoryDialog by remember { mutableStateOf(false) }\n''',
    '''    var showFactoryResetFinal by remember { mutableStateOf(false) }\n    var backupScopeOperation by remember { mutableStateOf<String?>(null) }\n    var showAddRepositoryDialog by remember { mutableStateOf(false) }\n''',
    "Personal backup scope state",
)
text = replace_once(
    text,
    '''            PronunciationCard(\n                rules = state.pronunciations,\n                onAdd = onAddPronunciation,\n                onEnabledChange = onPronunciationEnabledChange,\n                onDelete = onDeletePronunciation,\n            )\n''',
    '''            PronunciationCard(\n                rules = state.pronunciations,\n                onAdd = onAddPronunciation,\n                onUpdate = onUpdatePronunciation,\n                onDelete = onDeletePronunciation,\n            )\n''',
    "Personal pronunciation card route",
)
# Replace VietPhrase route call with clean reference callbacks while retaining old backend callbacks in signature only.
text = regex_once(
    text,
    r'''        "settings_vietphrase" -> PersonalSubPage\("VIETPHRASE / CHUYỂN NGỮ".*?\n        \}\n        "settings_automation"''',
    '''        "settings_vietphrase" -> PersonalSubPage("VIETPHRASE / CHUYỂN NGỮ", "QUAY LẠI CÀI ĐẶT", { returnToSettings() }) {\n            VietPhraseCard(\n                state = state,\n                onImport = onImportVietPhrase,\n                onExport = onExportVietPhrase,\n                onPrepareImport = onPrepareVietPhraseImport,\n                onDeleteDictionary = onDeleteVietPhraseDictionary,\n                onClearAll = onClearAllVietPhrase,\n                onEnabledChange = onVietPhraseMasterEnabledChange,\n                onFallbackChange = onVietPhraseFallbackChange,\n                onDownloadRecommended = onInstallRecommendedVietPhrase,\n            )\n        }\n        "settings_automation"''',
    "Personal VP route",
)
# Exact helper wording in both dead subpage and visible dialog.
text = text.replace(
    'Mặc định tắt. Khi bật, TTS tiếp tục ở mức âm lượng giảm khi Android báo gián đoạn âm thanh tạm thời.',
    'Mặc định tắt. Khi bật, TTS không tự tạm dừng khi Android báo gián đoạn âm thanh tạm thời. Truyện có thể phát chồng lên chuông, thông báo hoặc âm thanh cuộc gọi.',
)
text = text.replace(
    'Mặc định tắt. Khi bật, ứng dụng cố gắng tiếp tục đọc ở mức âm lượng giảm khi có gián đoạn âm thanh tạm thời.',
    'Mặc định tắt. Khi bật, TTS không tự tạm dừng khi Android báo gián đoạn âm thanh tạm thời. Truyện có thể phát chồng lên chuông, thông báo hoặc âm thanh cuộc gọi.',
)
# Main settings callbacks now open exact scope selector and actual log.
text = replace_once(
    text,
    '''                            "settings_backup_log" -> showBackupLogDialog = true\n''',
    '''                            "settings_backup_log" -> {\n                                if (onRefreshBackupLog()) showBackupLogDialog = true else showSettingsDialog = true\n                            }\n''',
    "backup log open",
)
text = replace_once(
    text,
    '''                    onExportBackup = onExportBackup,\n                    onRestoreBackup = onRestoreBackup,\n''',
    '''                    onExportBackup = { backupScopeOperation = "backup" },\n                    onRestoreBackup = { backupScopeOperation = "restore" },\n''',
    "backup scope intercept",
)
# Replace backup log body with XPK file path + text and exact delete action.
text = regex_once(
    text,
    r'''    if \(showBackupLogDialog\) \{.*?\n    \}\n\n    if \(showClearDownloadsDialog\)''',
    '''    if (showBackupLogDialog) {\n        AlertDialog(\n            onDismissRequest = { showBackupLogDialog = false; showSettingsDialog = true },\n            title = { Text("NHẬT KÝ SAO LƯU VÀ KHÔI PHỤC") },\n            text = {\n                Text(\n                    "Tệp: ${state.backupLogPath}\\n\\n${state.backupLogText.takeLast(40_000)}",\n                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),\n                )\n            },\n            confirmButton = {\n                TextButton(onClick = { showBackupLogDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") }\n            },\n            dismissButton = {\n                TextButton(onClick = {\n                    onClearBackupLog()\n                    showBackupLogDialog = false\n                    showSettingsDialog = true\n                }) { Text("XÓA NHẬT KÝ") }\n            },\n        )\n    }\n\n    if (showClearDownloadsDialog)''',
    "reference backup log dialog",
)
# Exact six backup/restore scope choices.
insert_before = '''    if (showClearDownloadsDialog) {\n'''
scope_dialog = '''    backupScopeOperation?.let { operation ->\n        val scopes = listOf(\n            "TẤT CẢ" to BackupComponent.entries.toSet(),\n            "CÀI ĐẶT CHUNG, GIỌNG ĐỌC VÀ AI" to setOf(BackupComponent.SETTINGS, BackupComponent.AI_VOICE),\n            "DỮ LIỆU ĐỌC, DẤU TRANG, TỪ ĐIỂN VÀ TRUYỆN ĐÃ TẢI" to setOf(BackupComponent.LIBRARY, BackupComponent.READING),\n            "NHẠC NỀN" to setOf(BackupComponent.SCENE_MUSIC),\n            "VIETPHRASE" to setOf(BackupComponent.VIETPHRASE),\n            "TIỆN ÍCH VÀ NGUỒN TRUYỆN" to setOf(BackupComponent.SOURCES_EXTENSIONS),\n        )\n        AlertDialog(\n            onDismissRequest = { backupScopeOperation = null; showSettingsDialog = true },\n            title = { Text(if (operation == "backup") "CHỌN DỮ LIỆU SAO LƯU" else "CHỌN DỮ LIỆU KHÔI PHỤC") },\n            text = {\n                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {\n                    scopes.forEach { (label, components) ->\n                        ReferenceActionButton(\n                            text = label,\n                            onClick = {\n                                onBackupComponentsChange(components)\n                                backupScopeOperation = null\n                                if (operation == "backup") onExportBackup() else onRestoreBackup()\n                            },\n                            normalColor = ReferencePanelBackground,\n                            normalContentColor = ReferenceText,\n                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),\n                        )\n                    }\n                }\n            },\n            confirmButton = {},\n            dismissButton = {\n                TextButton(onClick = { backupScopeOperation = null; showSettingsDialog = true }) { Text("HỦY") }\n            },\n        )\n    }\n\n'''
text = replace_once(text, insert_before, scope_dialog + insert_before, "backup scope dialog")
# Replace pronunciation function entirely.
text = regex_once(
    text,
    r'''@Composable\nprivate fun PronunciationCard\(.*?\n\}\n\n\n@Composable\nprivate fun VietPhraseCard''',
    '''@Composable\nprivate fun PronunciationCard(\n    rules: List<PronunciationEntity>,\n    onAdd: (String, String) -> Unit,\n    onUpdate: (Long, String, String) -> Unit,\n    onDelete: (Long) -> Unit,\n) {\n    var addOpen by remember { mutableStateOf(false) }\n    var selected by remember { mutableStateOf<PronunciationEntity?>(null) }\n    var editOpen by remember { mutableStateOf(false) }\n    var original by remember { mutableStateOf("") }\n    var replacement by remember { mutableStateOf("") }\n\n    Column(Modifier.fillMaxWidth()) {\n        ReferenceActionButton(\n            text = "＋ THÊM CÁCH ĐỌC",\n            onClick = { original = ""; replacement = ""; addOpen = true },\n            normalColor = ReferencePanelBackground,\n            normalContentColor = ReferenceText,\n            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),\n        )\n        rules.forEach { row ->\n            ReferenceActionButton(\n                text = "${row.original} → ${row.replacement}",\n                onClick = { selected = row },\n                normalColor = ReferencePanelBackground,\n                normalContentColor = ReferenceText,\n                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),\n            )\n        }\n    }\n\n    if (addOpen) {\n        AlertDialog(\n            onDismissRequest = { addOpen = false },\n            title = { Text("THÊM CÁCH ĐỌC") },\n            text = { Column {\n                OutlinedTextField(original, { original = it.take(120) }, placeholder = { Text("Từ hoặc cụm từ gốc") }, modifier = Modifier.fillMaxWidth())\n                OutlinedTextField(replacement, { replacement = it.take(240) }, placeholder = { Text("TTS sẽ đọc thành") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))\n            } },\n            confirmButton = { TextButton(onClick = { onAdd(original, replacement); addOpen = false }) { Text("LƯU") } },\n            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("HỦY") } },\n        )\n    }\n\n    selected?.let { row ->\n        AlertDialog(\n            onDismissRequest = { selected = null },\n            title = { Text("${row.original} → ${row.replacement}") },\n            text = { Column {\n                ReferenceActionButton("SỬA", { original = row.original; replacement = row.replacement; editOpen = true; selected = null }, Modifier.fillMaxWidth())\n                ReferenceActionButton("XÓA", { onDelete(row.id); selected = null }, Modifier.fillMaxWidth().padding(top = 4.dp))\n            } },\n            confirmButton = {},\n            dismissButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } },\n        )\n    }\n\n    if (editOpen) {\n        val target = rules.firstOrNull { it.original == original }\n        val editingId = target?.id ?: rules.firstOrNull { it.replacement == replacement }?.id\n        AlertDialog(\n            onDismissRequest = { editOpen = false },\n            title = { Text("SỬA CÁCH ĐỌC") },\n            text = { Column {\n                OutlinedTextField(original, { original = it.take(120) }, modifier = Modifier.fillMaxWidth())\n                OutlinedTextField(replacement, { replacement = it.take(240) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))\n            } },\n            confirmButton = { TextButton(onClick = { editingId?.let { onUpdate(it, original, replacement) }; editOpen = false }) { Text("LƯU") } },\n            dismissButton = { TextButton(onClick = { editOpen = false }) { Text("HỦY") } },\n        )\n    }\n}\n\n\n@Composable\nprivate fun VietPhraseCard''',
    "reference pronunciation card",
)
# Fix edit ID robustly by preserving ID separately in a follow-up replacement.
text = text.replace(
    'var editOpen by remember { mutableStateOf(false) }\n    var original',
    'var editOpen by remember { mutableStateOf(false) }\n    var editingId by remember { mutableStateOf<Long?>(null) }\n    var original',
    1,
)
text = text.replace(
    'original = row.original; replacement = row.replacement; editOpen = true; selected = null',
    'editingId = row.id; original = row.original; replacement = row.replacement; editOpen = true; selected = null',
    1,
)
text = text.replace(
    '''        val target = rules.firstOrNull { it.original == original }\n        val editingId = target?.id ?: rules.firstOrNull { it.replacement == replacement }?.id\n        AlertDialog(''',
    '''        AlertDialog(''',
    1,
)
# Replace VietPhrase function until AI dialog.
text = regex_once(
    text,
    r'''@Composable\nprivate fun VietPhraseCard\(.*?\n\}\n\n@Composable\nprivate fun AiReferenceSettingsDialog''',
    '''@Composable\nprivate fun VietPhraseCard(\n    state: MainUiState,\n    onImport: () -> Unit,\n    onExport: () -> Unit,\n    onPrepareImport: (VietPhraseDictionaryKind?) -> Unit,\n    onDeleteDictionary: (VietPhraseDictionaryKind) -> Unit,\n    onClearAll: () -> Unit,\n    onEnabledChange: (Boolean) -> Unit,\n    onFallbackChange: (Boolean) -> Unit,\n    onDownloadRecommended: () -> Unit,\n) {\n    val orderedKinds = listOf(\n        VietPhraseDictionaryKind.NAMES,\n        VietPhraseDictionaryKind.VIET_PHRASE,\n        VietPhraseDictionaryKind.PRONOUNS,\n        VietPhraseDictionaryKind.LUAT_NHAN,\n        VietPhraseDictionaryKind.PHIEN_AM,\n        VietPhraseDictionaryKind.LAC_VIET,\n        VietPhraseDictionaryKind.AI_REPLACE,\n    )\n    var selectedKind by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }\n    var deleteKind by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }\n    var clearAllConfirm by remember { mutableStateOf(false) }\n    var downloadConfirm by remember { mutableStateOf(false) }\n\n    fun status(kind: VietPhraseDictionaryKind): String {\n        val states = state.vietPhraseDictionaryStates.filter { it.kind == kind.name && it.scope == VietPhraseScope.GLOBAL.name }\n        val stateCount = states.sumOf { it.entryCount }\n        val ruleCount = state.vietPhraseRules.count { it.kind == kind.name && it.scope == VietPhraseScope.GLOBAL.name }\n        val count = maxOf(stateCount, ruleCount)\n        return when {\n            count > 0 -> String.format(Locale.getDefault(), "%,d từ", count).replace(',', '.')\n            kind == VietPhraseDictionaryKind.AI_REPLACE -> "Áp dụng ở bước cuối"\n            else -> "Chưa thiết lập"\n        }\n    }\n\n    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {\n        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n            Text("Bật VietPhrase", Modifier.weight(1f))\n            Switch(state.vietPhraseEnabled, onEnabledChange)\n        }\n        ReferenceActionButton("NHẬP FILE ZIP", { onPrepareImport(null); onImport() }, Modifier.fillMaxWidth().padding(top = 8.dp))\n        ReferenceActionButton("XUẤT TỪ ĐIỂN ZIP", onExport, Modifier.fillMaxWidth().padding(top = 4.dp))\n        orderedKinds.forEach { kind ->\n            ReferenceActionButton(\n                text = "${kind.fileName}\\n${status(kind)}",\n                onClick = { selectedKind = kind },\n                normalColor = ReferencePanelBackground,\n                normalContentColor = ReferenceText,\n                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),\n            )\n        }\n        ReferenceActionButton("XÓA TẤT CẢ", { clearAllConfirm = true }, Modifier.fillMaxWidth().padding(top = 8.dp))\n        ReferenceActionButton("TẢI TỰ ĐỘNG TỪ MẠNG", { downloadConfirm = true }, Modifier.fillMaxWidth().padding(top = 4.dp))\n        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {\n            Text("Dùng Hán Việt khi không tìm thấy cụm", Modifier.weight(1f))\n            Switch(state.vietPhraseFallbackHanViet, onFallbackChange)\n        }\n    }\n\n    selectedKind?.let { kind ->\n        AlertDialog(\n            onDismissRequest = { selectedKind = null },\n            title = { Text(kind.fileName) },\n            text = { Column {\n                ReferenceActionButton("NHẬP / THAY THẾ (TXT hoặc DIC)", { selectedKind = null; onPrepareImport(kind); onImport() }, Modifier.fillMaxWidth())\n                ReferenceActionButton("XÓA DỮ LIỆU FILE NÀY", { selectedKind = null; deleteKind = kind }, Modifier.fillMaxWidth().padding(top = 4.dp))\n            } },\n            confirmButton = {},\n            dismissButton = { TextButton(onClick = { selectedKind = null }) { Text("ĐÓNG") } },\n        )\n    }\n    deleteKind?.let { kind ->\n        AlertDialog(\n            onDismissRequest = { deleteKind = null },\n            title = { Text("XÓA ${kind.fileName}") },\n            text = { Text("Xóa dữ liệu của file này?") },\n            confirmButton = { TextButton(onClick = { onDeleteDictionary(kind); deleteKind = null }) { Text("XÓA") } },\n            dismissButton = { TextButton(onClick = { deleteKind = null }) { Text("HỦY") } },\n        )\n    }\n    if (clearAllConfirm) {\n        AlertDialog(\n            onDismissRequest = { clearAllConfirm = false },\n            title = { Text("XÓA TOÀN BỘ VIETPHRASE") },\n            text = { Text("Xóa tất cả dữ liệu VietPhrase?") },\n            confirmButton = { TextButton(onClick = { onClearAll(); clearAllConfirm = false }) { Text("XÓA") } },\n            dismissButton = { TextButton(onClick = { clearAllConfirm = false }) { Text("HỦY") } },\n        )\n    }\n    if (downloadConfirm) {\n        AlertDialog(\n            onDismissRequest = { downloadConfirm = false },\n            title = { Text("TẢI TỰ ĐỘNG TỪ MẠNG") },\n            text = { Text("Tải và cài bộ dữ liệu VietPhrase từ mạng?") },\n            confirmButton = { TextButton(onClick = { onDownloadRecommended(); downloadConfirm = false }) { Text("TẢI") } },\n            dismissButton = { TextButton(onClick = { downloadConfirm = false }) { Text("HỦY") } },\n        )\n    }\n}\n\n@Composable\nprivate fun AiReferenceSettingsDialog''',
    "reference VietPhrase card",
)
write(path, text)

# ---------------------------------------------------------------------------
# NgheTruyenApp callback wiring.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"
text = read(path)
text = replace_once(
    text,
    '''                        onAddPronunciation = viewModel::addPronunciation,\n                        onPronunciationEnabledChange = viewModel::setPronunciationEnabled,\n''',
    '''                        onAddPronunciation = viewModel::addPronunciation,\n                        onUpdatePronunciation = viewModel::updatePronunciation,\n                        onPronunciationEnabledChange = viewModel::setPronunciationEnabled,\n''',
    "wire pronunciation update",
)
text = replace_once(
    text,
    '''                        onRejectVietPhraseSuggestion = viewModel::rejectVietPhraseSuggestion,\n                        onRefreshAiModels = viewModel::refreshAiModels,\n''',
    '''                        onRejectVietPhraseSuggestion = viewModel::rejectVietPhraseSuggestion,\n                        onPrepareVietPhraseImport = viewModel::prepareVietPhraseImport,\n                        onDeleteVietPhraseDictionary = viewModel::deleteVietPhraseDictionary,\n                        onClearAllVietPhrase = viewModel::clearAllVietPhraseDictionaries,\n                        onVietPhraseMasterEnabledChange = viewModel::setVietPhraseMasterEnabled,\n                        onVietPhraseFallbackChange = viewModel::setVietPhraseFallbackHanViet,\n                        onRefreshAiModels = viewModel::refreshAiModels,\n''',
    "wire reference VP callbacks",
)
text = replace_once(
    text,
    '''                        onBackupComponentChange = viewModel::setBackupComponentEnabled,\n                        onExportBackup = onExportBackup,\n''',
    '''                        onBackupComponentChange = viewModel::setBackupComponentEnabled,\n                        onBackupComponentsChange = viewModel::setBackupComponents,\n                        onRefreshBackupLog = viewModel::refreshBackupLog,\n                        onClearBackupLog = viewModel::clearBackupLog,\n                        onExportBackup = onExportBackup,\n''',
    "wire reference backup callbacks",
)
write(path, text)

# ---------------------------------------------------------------------------
# Audio interruption: XPK continue mode must not pause on transient focus loss.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
text = read(path)
text = replace_once(
    text,
    '''            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {\n                resumeAfterTransientFocusLoss = PlaybackQueueStore.state.value.isPlaying\n                hasAudioFocus = false\n                pauseInternal(abandonFocus = false, preserveResumeIntent = true)\n            }\n''',
    '''            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {\n                hasAudioFocus = false\n                if (interruptionMode == AudioInterruptionMode.PAUSE) {\n                    resumeAfterTransientFocusLoss = PlaybackQueueStore.state.value.isPlaying\n                    pauseInternal(abandonFocus = false, preserveResumeIntent = true)\n                } else {\n                    resumeAfterTransientFocusLoss = false\n                    transitionMessage = "Đang tiếp tục đọc khi âm thanh khác phát xen."\n                    updateNotification()\n                }\n            }\n''',
    "continuous transient audio interruption",
)
write(path, text)

print("REFERENCE_PARITY_PHASE2_PATCH_OK")
