#!/usr/bin/env python3
from pathlib import Path

MARKER = "NAVIGATION_AUDIT_V3"


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def add_import(path: str, anchor: str, line: str) -> None:
    text = read(path)
    if line in text:
        return
    if anchor not in text:
        raise SystemExit(f"{path}: import anchor not found: {anchor!r}")
    write(path, text.replace(anchor, anchor + line, 1))


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def audit_library() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt"
    text = read(path)
    if f"// {MARKER}_LIBRARY" in text:
        print("LIBRARY_NAVIGATION_AUDIT_ALREADY_APPLIED")
        return

    text = text.replace(
        "        LibrarySection.BOOKMARKS -> state.bookmarks.size\n        LibrarySection.NOTES -> state.notes.size\n",
        "        LibrarySection.BOOKMARKS -> state.bookmarks.size + state.notes.size\n        LibrarySection.NOTES -> state.bookmarks.size + state.notes.size\n",
        1,
    )
    text = text.replace(
        '        LibrarySection.NOTES -> "Ghi chú"\n',
        '        LibrarySection.NOTES -> "Đánh dấu"\n',
        1,
    )

    secondary_tabs = '''        if (state.librarySection == LibrarySection.BOOKMARKS || state.librarySection == LibrarySection.NOTES) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                ReferenceTabButton(
                    text = "ĐÁNH DẤU",
                    selected = state.librarySection == LibrarySection.BOOKMARKS,
                    onClick = { onSectionSelected(LibrarySection.BOOKMARKS) },
                    accessibilityLabel = "Đánh dấu",
                    minHeight = 48.dp,
                    unselectedColor = ReferenceDivider,
                    unselectedContentColor = ReferenceText,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
                ReferenceTabButton(
                    text = "GHI CHÚ",
                    selected = state.librarySection == LibrarySection.NOTES,
                    onClick = { onSectionSelected(LibrarySection.NOTES) },
                    accessibilityLabel = "Ghi chú",
                    minHeight = 48.dp,
                    unselectedColor = ReferenceDivider,
                    unselectedContentColor = ReferenceText,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }
'''
    if secondary_tabs not in text:
        raise SystemExit("LibraryScreen.kt: secondary bookmark/note tab block not found")
    text = text.replace(secondary_tabs, "", 1)

    old_branches = '''            LibrarySection.BOOKMARKS -> BookmarkList(
                items = state.bookmarks,
                onOpen = onBookmarkClick,
                onDelete = onDeleteBookmark,
            )
            LibrarySection.NOTES -> NoteList(
                items = state.notes,
                onOpen = onNoteClick,
                onDelete = onDeleteNote,
            )
'''
    new_branches = '''            LibrarySection.BOOKMARKS, LibrarySection.NOTES -> BookmarkAndNoteList(
                bookmarks = state.bookmarks,
                notes = state.notes,
                onBookmarkOpen = onBookmarkClick,
                onBookmarkDelete = onDeleteBookmark,
                onNoteOpen = onNoteClick,
                onNoteDelete = onDeleteNote,
            )
'''
    if old_branches not in text:
        raise SystemExit("LibraryScreen.kt: bookmark/note when branches not found")
    text = text.replace(old_branches, new_branches, 1)

    insert_at = text.find("\n@Composable\nprivate fun BookmarkList(")
    if insert_at < 0:
        raise SystemExit("LibraryScreen.kt: BookmarkList insertion marker not found")
    combined = r'''

// NAVIGATION_AUDIT_V3_LIBRARY: the reference tool exposes exactly four library tabs.
// Notes remain available inside the ĐÁNH DẤU tab instead of creating a fifth navigation level.
@Composable
private fun BookmarkAndNoteList(
    bookmarks: List<BookmarkEntity>,
    notes: List<ChapterNoteEntity>,
    onBookmarkOpen: (BookmarkEntity) -> Unit,
    onBookmarkDelete: (String) -> Unit,
    onNoteOpen: (ChapterNoteEntity) -> Unit,
    onNoteDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty() && notes.isEmpty()) {
        Text("Chưa có đánh dấu hoặc ghi chú.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (bookmarks.isNotEmpty()) {
            item(key = "bookmark-heading") {
                Text(
                    "ĐÁNH DẤU • ${bookmarks.size}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            items(bookmarks, key = { "bookmark:${it.id}" }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onBookmarkOpen(item) },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(item.label.ifBlank { "Đánh dấu đoạn ${item.paragraphIndex + 1}" }, fontWeight = FontWeight.SemiBold)
                        Text("Đoạn ${item.paragraphIndex + 1}")
                        ReferenceActionButton(
                            text = "XÓA ĐÁNH DẤU",
                            onClick = { onBookmarkDelete(item.id) },
                            accessibilityLabel = "Xóa ${item.label.ifBlank { "đánh dấu đoạn ${item.paragraphIndex + 1}" }}",
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (notes.isNotEmpty()) {
            item(key = "note-heading") {
                Text(
                    "GHI CHÚ • ${notes.size}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            items(notes, key = { "note:${it.id}" }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onNoteOpen(item) },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Ghi chú đoạn ${item.paragraphIndex + 1}", fontWeight = FontWeight.SemiBold)
                        Text(item.text)
                        ReferenceActionButton(
                            text = "XÓA GHI CHÚ",
                            onClick = { onNoteDelete(item.id) },
                            accessibilityLabel = "Xóa ghi chú đoạn ${item.paragraphIndex + 1}",
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
'''
    text = text[:insert_at] + combined + text[insert_at:]
    write(path, text)
    print("LIBRARY_NAVIGATION_AUDIT_APPLIED")


def audit_personal() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
    text = read(path)
    if f"// {MARKER}_PERSONAL" in text:
        print("PERSONAL_NAVIGATION_AUDIT_ALREADY_APPLIED")
        return

    imports = [
        ("import androidx.compose.foundation.background\n", "import androidx.activity.compose.BackHandler\n"),
        ("import androidx.compose.runtime.Composable\n", "import androidx.compose.runtime.LaunchedEffect\n"),
        ("import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.platform.LocalView\n"),
        ("import vn.nghetruyen.app.ui.components.ReferenceText\n", "import vn.nghetruyen.app.ui.components.ScreenHeading\n"),
    ]
    for anchor, line in imports:
        if line not in text:
            if anchor not in text:
                raise SystemExit(f"PersonalScreen.kt: import anchor missing: {anchor!r}")
            text = text.replace(anchor, anchor + line, 1)

    start = text.find('    var personalPage by remember { mutableStateOf("home") }\n')
    end = text.find("\n\nprivate val MEDIA_ACTION_ORDER", start)
    if start < 0 or end < 0:
        raise SystemExit("PersonalScreen.kt: top-level personal navigation block markers not found")

    body = r'''    // NAVIGATION_AUDIT_V3_PERSONAL: reference-style hierarchical navigation.
    var personalPage by remember { mutableStateOf("home") }
    var repositoryUrl by remember { mutableStateOf("") }
    var trustKeyId by remember { mutableStateOf("") }
    var trustAlgorithm by remember { mutableStateOf("ECDSA_P256_SHA256") }
    var trustPublicKey by remember { mutableStateOf("") }
    var trustFingerprint by remember { mutableStateOf("") }
    var selectorBaseUrl by remember { mutableStateOf("https://example.invalid/") }
    var selector by remember { mutableStateOf("") }
    var selectorHtml by remember { mutableStateOf("") }
    val view = LocalView.current

    fun parentPage(page: String): String = when {
        page == "settings_home" || page == "extensions_home" -> "home"
        page.startsWith("settings_") -> "settings_home"
        page.startsWith("extensions_") -> "extensions_home"
        else -> "home"
    }
    fun pageTitle(page: String): String = when (page) {
        "home" -> "Cá nhân"
        "settings_home" -> "Cài đặt ứng dụng"
        "settings_pronunciation" -> "Từ điển phát âm TTS"
        "settings_vietphrase" -> "VietPhrase / Chuyển ngữ"
        "settings_ai" -> "Thiết lập AI"
        "settings_automation" -> "Phân vai TTS và tự động hóa"
        "settings_tts" -> "Cài đặt TTS"
        "settings_music" -> "Nhạc nền và nhạc cảnh"
        "settings_following" -> "Theo dõi chương mới"
        "settings_storage" -> "Dung lượng ngoại tuyến"
        "settings_export" -> "Xuất sách nói"
        "settings_backup" -> "Sao lưu và khôi phục"
        "settings_diagnostics" -> "Chẩn đoán và hiệu năng"
        "extensions_home" -> "Tiện ích mở rộng"
        "extensions_installed" -> "Tiện ích đã cài"
        "extensions_repositories" -> "Kho tiện ích"
        "extensions_add" -> "Thêm kho hoặc liên kết"
        "extensions_diagnostics" -> "Chẩn đoán tiện ích"
        else -> "Cá nhân"
    }

    BackHandler(enabled = personalPage != "home") {
        personalPage = parentPage(personalPage)
    }
    LaunchedEffect(personalPage) {
        view.announceForAccessibility(pageTitle(personalPage))
    }

    when (personalPage) {
        "home" -> PersonalMenuPage(
            title = "CÁ NHÂN",
            items = listOf(
                "settings_home" to "Cài đặt",
                "extensions_home" to "Tiện ích mở rộng",
            ),
            onSelect = { personalPage = it },
        )
        "settings_home" -> PersonalMenuPage(
            title = "CÀI ĐẶT ỨNG DỤNG",
            backLabel = "QUAY LẠI CÁ NHÂN",
            onBack = { personalPage = "home" },
            items = listOf(
                "settings_pronunciation" to "TỪ ĐIỂN PHÁT ÂM TTS",
                "settings_vietphrase" to "VIETPHRASE / CHUYỂN NGỮ",
                "settings_ai" to "THIẾT LẬP AI",
                "settings_automation" to "PHÂN VAI TTS & TỰ ĐỘNG HÓA",
                "settings_tts" to "CÀI ĐẶT TTS",
                "settings_music" to "NHẠC NỀN & NHẠC CẢNH",
                "settings_following" to "THEO DÕI CHƯƠNG MỚI",
                "settings_storage" to "DUNG LƯỢNG NGOẠI TUYẾN",
                "settings_export" to "XUẤT SÁCH NÓI",
                "settings_backup" to "SAO LƯU & KHÔI PHỤC",
                "settings_diagnostics" to "CHẨN ĐOÁN & HIỆU NĂNG",
            ),
            onSelect = { personalPage = it },
        )
        "settings_pronunciation" -> PersonalSubPage("TỪ ĐIỂN PHÁT ÂM TTS", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            PronunciationCard(
                rules = state.pronunciations,
                onAdd = onAddPronunciation,
                onEnabledChange = onPronunciationEnabledChange,
                onDelete = onDeletePronunciation,
            )
        }
        "settings_vietphrase" -> PersonalSubPage("VIETPHRASE / CHUYỂN NGỮ", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            VietPhraseCard(
                state = state,
                onAdd = onAddVietPhrase,
                onImport = onImportVietPhrase,
                onExport = onExportVietPhrase,
                onCheckOnline = onCheckVietPhraseOnline,
                onInstallRecommended = onInstallRecommendedVietPhrase,
                onEnabledChange = onVietPhraseEnabledChange,
                onDictionaryEnabledChange = onVietPhraseDictionaryEnabledChange,
                onDelete = onDeleteVietPhrase,
                onConfirmImport = onConfirmVietPhraseImport,
                onCancelImport = onCancelVietPhraseImport,
                onRollback = onRollbackVietPhrase,
                onAcceptSuggestion = onAcceptVietPhraseSuggestion,
                onRejectSuggestion = onRejectVietPhraseSuggestion,
            )
        }
        "settings_ai" -> PersonalSubPage("THIẾT LẬP AI", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            AiOnlineCard(
                state = state,
                onEnabledChange = onAiEnabledChange,
                onConsentChange = onAiConsentChange,
                onProviderChange = onAiProviderChange,
                onRefreshGeminiModels = onRefreshGeminiModels,
                onEndpointChange = onAiEndpointChange,
                onModelChange = onAiModelChange,
                onTemperatureChange = onAiTemperatureChange,
                onInstructionChange = onAiInstructionChange,
                onDailyRequestLimitChange = onAiDailyRequestLimitChange,
                onDailyInputCharsLimitChange = onAiDailyInputCharsLimitChange,
                onMaxRetriesChange = onAiMaxRetriesChange,
                onRetryBaseDelayChange = onAiRetryBaseDelayChange,
                onSaveApiKey = onSaveAiApiKey,
                onClearApiKey = onClearAiApiKey,
            )
        }
        "settings_automation" -> PersonalSubPage("PHÂN VAI TTS & TỰ ĐỘNG HÓA", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            PlaybackAutomationCard(
                state = state,
                onHeadsetMultiClickChange = onHeadsetMultiClickChange,
                onHeadsetSingleActionChange = onHeadsetSingleActionChange,
                onHeadsetDoubleActionChange = onHeadsetDoubleActionChange,
                onHeadsetTripleActionChange = onHeadsetTripleActionChange,
                onHeadsetLongActionChange = onHeadsetLongActionChange,
                onPauseOnHeadsetDisconnectChange = onPauseOnHeadsetDisconnectChange,
                onRestorePlaybackChange = onRestorePlaybackChange,
                onAutoVoiceCastChange = onAutoVoiceCastChange,
                onAutoSceneMusicChange = onAutoSceneMusicChange,
                onPrefetchNarrationPlansChange = onPrefetchNarrationPlansChange,
                onNarrationPrefetchWindowChange = onNarrationPrefetchWindowChange,
                onSceneMusicCrossfadeChange = onSceneMusicCrossfadeChange,
                onSceneMusicContinueChange = onSceneMusicContinueChange,
                onSceneMusicPlaybackModeChange = onSceneMusicPlaybackModeChange,
                onSceneMusicTargetLufsChange = onSceneMusicTargetLufsChange,
                onSceneMusicAvoidRepeatWindowChange = onSceneMusicAvoidRepeatWindowChange,
                onSonicProcessingEnabledChange = onSonicProcessingEnabledChange,
                onSonicDefaultSpeedChange = onSonicDefaultSpeedChange,
                onSonicDefaultPitchChange = onSonicDefaultPitchChange,
                onTtsCacheEnabledChange = onTtsCacheEnabledChange,
                onTtsCacheLimitChange = onTtsCacheLimitChange,
                onNormalizeTtsVolumeChange = onNormalizeTtsVolumeChange,
                onTtsTargetLufsChange = onTtsTargetLufsChange,
            )
        }
        "settings_tts" -> PersonalSubPage("CÀI ĐẶT TTS", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            VoiceSettingsCard(
                rate = state.playback.rate,
                pitch = state.playback.pitch,
                volume = state.ttsVolume,
                autoNext = state.autoPlayNextChapter,
                engines = state.ttsEngines,
                voices = state.ttsVoices,
                selectedEnginePackage = state.selectedTtsEnginePackage,
                selectedVoiceName = state.selectedTtsVoiceName,
                loadingVoices = state.ttsVoiceLoading,
                onRateChange = onRateChange,
                onPitchChange = onPitchChange,
                onVolumeChange = onVolumeChange,
                onAutoNextChange = onAutoNextChange,
                onEngineSelected = onEngineSelected,
                onVoiceSelected = onVoiceSelected,
                onRefreshVoices = onRefreshVoices,
                onPreviewVoice = onPreviewVoice,
                onOpenTtsSettings = onOpenTtsSettings,
                interruptionMode = state.audioInterruptionMode,
                onInterruptionModeChange = onInterruptionModeChange,
            )
        }
        "settings_music" -> PersonalSubPage("NHẠC NỀN & NHẠC CẢNH", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            BackgroundMusicCard(
                uri = state.backgroundMusicUri,
                enabled = state.backgroundMusicEnabled,
                volume = state.backgroundMusicVolume,
                duckFactor = state.backgroundMusicDuckFactor,
                onSelect = onSelectBackgroundMusic,
                onClear = onClearBackgroundMusic,
                onEnabledChange = onBackgroundMusicEnabledChange,
                onVolumeChange = onBackgroundMusicVolumeChange,
                onDuckChange = onBackgroundMusicDuckChange,
            )
            SceneMusicLibraryCard(
                tracks = state.sceneMusicTracks,
                onSelect = onSelectSceneMusic,
                onUpdate = onUpdateSceneMusic,
                onEnabledChange = onSceneMusicEnabledChange,
                onDelete = onDeleteSceneMusic,
            )
        }
        "settings_following" -> PersonalSubPage("THEO DÕI CHƯƠNG MỚI", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            FollowingSettingsCard(
                enabled = state.followingUpdatesEnabled,
                onEnabledChange = onFollowingUpdatesChange,
                onCheckNow = onCheckFollowingNow,
            )
        }
        "settings_storage" -> PersonalSubPage("DUNG LƯỢNG NGOẠI TUYẾN", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            StorageCard(
                state = state,
                onCacheLimitChange = onCacheLimitChange,
                onTrimCache = onTrimReaderCache,
                onClearCache = onClearReaderCache,
            )
        }
        "settings_export" -> PersonalSubPage("XUẤT SÁCH NÓI", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            AudioExportCard(state, onCancelAudioExport, onResumeAudioExport, onOpenAudioExport)
        }
        "settings_backup" -> PersonalSubPage("SAO LƯU & KHÔI PHỤC", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            TransferCard(
                state = state,
                onComponentChange = onBackupComponentChange,
                onExportBackup = onExportBackup,
                onRestoreBackup = onRestoreBackup,
            )
        }
        "settings_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN & HIỆU NĂNG", "QUAY LẠI CÀI ĐẶT", { personalPage = "settings_home" }) {
            PerformanceCard(state.performanceReport, onRunPerformanceDiagnostics)
            SettingsCard("Kiến trúc ứng dụng", "Kotlin, Compose, Room, DataStore, WorkManager và foreground TTS service. Lua Native Source API 2 chạy trong LuaJ sandbox; không AndroLua, không luajava và không nạp DEX động.")
        }
        "extensions_home" -> PersonalMenuPage(
            title = "TIỆN ÍCH MỞ RỘNG",
            backLabel = "QUAY LẠI CÁ NHÂN",
            onBack = { personalPage = "home" },
            items = listOf(
                "extensions_installed" to "Đã cài (${state.sourcePacks.size})",
                "extensions_repositories" to "Kho tiện ích (${state.sourceRepositories.size})",
                "extensions_add" to "Thêm kho / liên kết",
                "extensions_install" to "Cài từ file",
                "extensions_diagnostics" to "Chẩn đoán",
            ),
            onSelect = { target ->
                if (target == "extensions_install") onInstallSourcePack() else personalPage = target
            },
            extraContent = {
                if (state.pendingSourceInstall != null) {
                    PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
                }
            },
        )
        "extensions_installed" -> PersonalSubPage("TIỆN ÍCH ĐÃ CÀI", "QUAY LẠI TIỆN ÍCH", { personalPage = "extensions_home" }) {
            PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
            InstalledSourcesSection(state, onSourcePackEnabledChange, onRollbackSourcePack)
        }
        "extensions_repositories" -> PersonalSubPage("KHO TIỆN ÍCH", "QUAY LẠI TIỆN ÍCH", { personalPage = "extensions_home" }) {
            PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
            SourceRepositorySection(
                state = state,
                onRefresh = onRefreshSourceRepository,
                onRemove = onRemoveSourceRepository,
                onPrepareInstall = onPrepareRepositorySourceInstall,
            )
        }
        "extensions_add" -> PersonalSubPage("THÊM KHO / LIÊN KẾT", "QUAY LẠI TIỆN ÍCH", { personalPage = "extensions_home" }) {
            SourceAddLinkSection(
                state = state,
                repositoryUrl = repositoryUrl,
                onRepositoryUrlChange = { repositoryUrl = it },
                onRefresh = onRefreshSourceRepository,
                trustKeyId = trustKeyId,
                onTrustKeyIdChange = { trustKeyId = it },
                trustAlgorithm = trustAlgorithm,
                onTrustAlgorithmChange = { trustAlgorithm = it },
                trustPublicKey = trustPublicKey,
                onTrustPublicKeyChange = { trustPublicKey = it },
                trustFingerprint = trustFingerprint,
                onTrustFingerprintChange = { trustFingerprint = it },
                onEnrollKey = onEnrollSourceTrustKey,
                onRevokeKey = onRevokeSourceTrustKey,
                onImportRotation = onImportSourceTrustRotation,
            )
        }
        "extensions_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN TIỆN ÍCH", "QUAY LẠI TIỆN ÍCH", { personalPage = "extensions_home" }) {
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
        }
    }
}

@Composable
private fun PersonalMenuPage(
    title: String,
    items: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    extraContent: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        if (backLabel != null && onBack != null) {
            ReferenceActionButton(
                text = backLabel,
                onClick = onBack,
                normalColor = ReferenceGray,
                accessibilityLabel = backLabel.lowercase(),
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            )
        }
        ScreenHeading(title)
        items.forEach { (id, label) ->
            ReferenceActionButton(
                text = label,
                onClick = { onSelect(id) },
                accessibilityLabel = label,
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 64.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        extraContent()
    }
}

@Composable
private fun PersonalSubPage(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ReferenceActionButton(
            text = backLabel,
            onClick = onBack,
            normalColor = ReferenceGray,
            accessibilityLabel = backLabel.lowercase(),
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
        ScreenHeading(title)
        content()
    }
}

@Composable
private fun PendingSourceInstallSection(
    state: MainUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val preview = state.pendingSourceInstall ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("CHỜ PHÊ DUYỆT", fontWeight = FontWeight.Bold)
            Text("${preview.name} ${preview.version}", fontWeight = FontWeight.SemiBold)
            Text("ID: ${preview.sourceId}", style = MaterialTheme.typography.bodySmall)
            Text("Khóa ký: ${preview.signerKeyId}", style = MaterialTheme.typography.bodySmall)
            Text("Self-test: ${preview.fixtureCount} fixture đã đạt", style = MaterialTheme.typography.bodySmall)
            preview.permissionSummary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            state.pendingSourceInstallWarnings.forEach { warning ->
                Text("Cảnh báo: $warning", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(onConfirm, Modifier.weight(1f).padding(2.dp)) { Text("CHẤP NHẬN & CÀI") }
                Button(onCancel, Modifier.weight(1f).padding(2.dp)) { Text("HỦY") }
            }
        }
    }
}

@Composable
private fun InstalledSourcesSection(
    state: MainUiState,
    onEnabledChange: (String, Boolean) -> Unit,
    onRollback: (String) -> Unit,
) {
    if (state.sourcePacks.isEmpty()) {
        Text("Chưa cài tiện ích nguồn nào.", modifier = Modifier.padding(16.dp))
        return
    }
    state.sourcePacks.forEach { pack ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(pack.name, fontWeight = FontWeight.SemiBold)
                Text("${pack.id} • ${pack.version} • ${pack.runtimeMode}", style = MaterialTheme.typography.bodySmall)
                Text("Ký bởi ${pack.signerKeyId} • giữ ${pack.installedVersions.size} phiên bản", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (pack.enabled) "Đang bật" else "Đang tắt", Modifier.weight(1f))
                    Switch(checked = pack.enabled, onCheckedChange = { onEnabledChange(pack.id, it) })
                }
                Button(
                    onClick = { onRollback(pack.id) },
                    enabled = pack.canRollback,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text("ROLLBACK PHIÊN BẢN NGUỒN") }
            }
        }
    }
}

@Composable
private fun SourceRepositorySection(
    state: MainUiState,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
) {
    if (state.sourceRepositories.isEmpty()) {
        Text("Chưa có kho tiện ích. Mở mục Thêm kho / liên kết để thêm repository HTTPS.", modifier = Modifier.padding(16.dp))
        return
    }
    state.sourceRepositories.forEach { repository ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(repository.name, fontWeight = FontWeight.SemiBold)
                Text("${repository.packageCount} gói • ký bởi ${repository.signerKeyId}", style = MaterialTheme.typography.bodySmall)
                Text(repository.url, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button(
                        onClick = { onRefresh(repository.url) },
                        enabled = !state.sourceRepositoryRefreshing,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("LÀM MỚI") }
                    Button(onClick = { onRemove(repository.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("GỠ KHO") }
                }
                state.sourceRepositoryPackages.filter { it.repositoryId == repository.id }.forEach { item ->
                    HorizontalDivider(Modifier.padding(vertical = 7.dp))
                    Text("${item.name} ${item.version}", fontWeight = FontWeight.SemiBold)
                    Text("${item.sourceId} • ${item.status}${item.installedVersion?.let { " • đang cài $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    item.description.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = { onPrepareInstall(item.repositoryId, item.sourceId) },
                        enabled = item.canInstall && !state.sourceRepositoryRefreshing,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text(if (item.status == "UPDATE_AVAILABLE") "TẢI BẢN CẬP NHẬT" else "TẢI & KIỂM TRA GÓI") }
                }
            }
        }
    }
}

@Composable
private fun SourceAddLinkSection(
    state: MainUiState,
    repositoryUrl: String,
    onRepositoryUrlChange: (String) -> Unit,
    onRefresh: (String) -> Unit,
    trustKeyId: String,
    onTrustKeyIdChange: (String) -> Unit,
    trustAlgorithm: String,
    onTrustAlgorithmChange: (String) -> Unit,
    trustPublicKey: String,
    onTrustPublicKeyChange: (String) -> Unit,
    trustFingerprint: String,
    onTrustFingerprintChange: (String) -> Unit,
    onEnrollKey: (String, String, String, String) -> Unit,
    onRevokeKey: (String) -> Unit,
    onImportRotation: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("THÊM KHO / LIÊN KẾT", fontWeight = FontWeight.Bold)
            Text("Chỉ repository HTTPS có chữ ký hợp lệ mới được lưu.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = repositoryUrl,
                onValueChange = { onRepositoryUrlChange(it.take(4096)) },
                label = { Text("URL HTTPS của repository index") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                onClick = { onRefresh(repositoryUrl) },
                enabled = repositoryUrl.isNotBlank() && !state.sourceRepositoryRefreshing,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(if (state.sourceRepositoryRefreshing) "ĐANG XÁC MINH…" else "THÊM / LÀM MỚI KHO") }
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("KHÓA TIN CẬY NÂNG CAO", fontWeight = FontWeight.Bold)
            state.sourceTrustKeys.forEach { key ->
                Text("${if (key.builtin) "TÍCH HỢP" else "NGƯỜI DÙNG"} • ${key.keyId} • ${key.algorithm}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text("Fingerprint: ${key.fingerprint}", style = MaterialTheme.typography.bodySmall)
                if (!key.builtin) {
                    Button(onClick = { onRevokeKey(key.keyId) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text("THU HỒI KHÓA NÀY") }
                }
            }
            OutlinedTextField(trustKeyId, { onTrustKeyIdChange(it.take(160)) }, label = { Text("Key ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(trustAlgorithm, { onTrustAlgorithmChange(it.take(64)) }, label = { Text("Thuật toán") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(trustPublicKey, { onTrustPublicKeyChange(it.take(16_384)) }, label = { Text("Public key X.509 Base64") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(trustFingerprint, { onTrustFingerprintChange(it.take(256)) }, label = { Text("Fingerprint xác nhận ngoài kênh") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Button(
                onClick = { onEnrollKey(trustKeyId, trustAlgorithm, trustPublicKey, trustFingerprint) },
                enabled = trustKeyId.isNotBlank() && trustPublicKey.isNotBlank() && trustFingerprint.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("ĐỐI CHIẾU & THÊM KHÓA") }
            Button(onClick = onImportRotation, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("NHẬP TỆP XOAY KHÓA ĐÃ KÝ") }
        }
    }
}

@Composable
private fun SourceDiagnosticsSection(
    state: MainUiState,
    selectorBaseUrl: String,
    onSelectorBaseUrlChange: (String) -> Unit,
    selector: String,
    onSelectorChange: (String) -> Unit,
    selectorHtml: String,
    onSelectorHtmlChange: (String) -> Unit,
    onInspectSelector: (String, String, String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onCheckSource: (String) -> Unit,
    onCheckAll: () -> Unit,
    onOpenLogin: (String) -> Unit,
    onOpenDiagnosticBrowser: (String) -> Unit,
    onClearSession: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("NHẬT KÝ & TRACE", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(onExportDiagnostics, Modifier.weight(1f).padding(2.dp)) { Text("XUẤT CHẨN ĐOÁN") }
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("SELECTOR INSPECTOR", fontWeight = FontWeight.Bold)
            OutlinedTextField(selectorBaseUrl, { onSelectorBaseUrlChange(it.take(4096)) }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(selector, { onSelectorChange(it.take(512)) }, label = { Text("CSS selector") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(selectorHtml, { onSelectorHtmlChange(it.take(2 * 1024 * 1024)) }, label = { Text("HTML snapshot") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Button(
                onClick = { onInspectSelector(selectorHtml, selector, selectorBaseUrl) },
                enabled = selector.isNotBlank() && selectorHtml.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("KIỂM TRA SELECTOR") }
            state.sourceSelectorInspection?.let { inspection ->
                Text("${inspection.selector}: ${inspection.matchCount} phần tử khớp", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                inspection.samples.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("KIỂM TRA NGUỒN", fontWeight = FontWeight.Bold)
            Button(onClick = onCheckAll, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("KIỂM TRA TẤT CẢ NGUỒN") }
            state.sources.forEach { source ->
                HorizontalDivider(Modifier.padding(vertical = 7.dp))
                val report = state.sourceHealthReports[source.id]
                val checking = source.id in state.sourceHealthChecking
                val sessionActive = source.id in state.sourceSessions
                Text(source.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        checking -> "Đang kiểm tra…"
                        report != null -> "${report.resolvedHealth.name} • ${report.passedSteps}/${report.totalSteps} bước đạt"
                        else -> "Khai báo: ${source.health.name}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                report?.steps?.forEach { step ->
                    val mark = when (step.status) {
                        SourceCheckStatus.PASS -> "✓"
                        SourceCheckStatus.FAIL -> "✕"
                        SourceCheckStatus.SKIPPED -> "–"
                    }
                    Text("$mark ${step.name}: ${step.detail} (${step.elapsedMillis} ms)", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button(
                        onClick = { onCheckSource(source.id) },
                        enabled = !checking && source.health != SourceHealth.NOT_PORTED,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text(if (checking) "ĐANG KIỂM TRA" else "KIỂM TRA") }
                    if (source.loginUrl != null) {
                        Button(onClick = { onOpenLogin(source.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text(if (sessionActive) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP") }
                    }
                }
                if (source.allowedHosts.isNotEmpty() && source.baseUrl.startsWith("https://")) {
                    Button(onClick = { onOpenDiagnosticBrowser(source.id) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) { Text("TRÌNH DUYỆT CHẨN ĐOÁN") }
                }
                if (source.loginUrl != null && sessionActive) {
                    Button(onClick = { onClearSession(source.id) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) { Text("XÓA PHIÊN ĐÃ LƯU") }
                }
            }
        }
    }
}
'''

    text = text[:start] + body + text[end:]
    write(path, text)
    print("PERSONAL_NAVIGATION_AUDIT_APPLIED")


audit_library()
audit_personal()
print("NAVIGATION_AUDIT_V3_APPLIED")
