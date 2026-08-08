package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseScope
import vn.nghetruyen.app.data.local.AudioExportJobEntity
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.sources.SourceCheckStatus
import vn.nghetruyen.app.transfer.BackupComponent
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceText
import vn.nghetruyen.app.ui.components.ScreenHeading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PersonalScreen(
    state: MainUiState,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onEngineSelected: (TtsEngineOption?) -> Unit,
    onVoiceSelected: (TtsVoiceOption?) -> Unit,
    onRefreshVoices: () -> Unit,
    onPreviewVoice: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onInterruptionModeChange: (AudioInterruptionMode) -> Unit,
    onDiagnosticsModeChange: (String) -> Unit,
    onHeadsetMultiClickChange: (Boolean) -> Unit,
    onHeadsetSingleActionChange: (String) -> Unit,
    onHeadsetDoubleActionChange: (String) -> Unit,
    onHeadsetTripleActionChange: (String) -> Unit,
    onHeadsetLongActionChange: (String) -> Unit,
    onPauseOnHeadsetDisconnectChange: (Boolean) -> Unit,
    onRestorePlaybackChange: (Boolean) -> Unit,
    onAutoVoiceCastChange: (Boolean) -> Unit,
    onSaveGlobalVoiceRole: (VoiceRoleDraft) -> Unit,
    onGlobalVoiceRoleEnabledChange: (String, Boolean) -> Unit,
    onDeleteGlobalVoiceRole: (String) -> Unit,
    onRestoreGlobalVoiceProfiles: () -> Unit,
    onPreviewGlobalVoiceRole: (VoiceRoleDraft) -> Unit,
    onAutoSceneMusicChange: (Boolean) -> Unit,
    onPrefetchNarrationPlansChange: (Boolean) -> Unit,
    onNarrationPrefetchWindowChange: (Int) -> Unit,
    onSceneMusicCrossfadeChange: (Int) -> Unit,
    onSceneMusicContinueChange: (Boolean) -> Unit,
    onSceneMusicPlaybackModeChange: (SceneMusicPlaybackMode) -> Unit,
    onSceneMusicTargetLufsChange: (Float) -> Unit,
    onSceneMusicAvoidRepeatWindowChange: (Int) -> Unit,
    onSonicProcessingEnabledChange: (Boolean) -> Unit,
    onSonicDefaultSpeedChange: (Float) -> Unit,
    onSonicDefaultPitchChange: (Float) -> Unit,
    onTtsCacheEnabledChange: (Boolean) -> Unit,
    onTtsCacheLimitChange: (Int) -> Unit,
    onNormalizeTtsVolumeChange: (Boolean) -> Unit,
    onTtsTargetLufsChange: (Float) -> Unit,
    onSelectBackgroundMusic: () -> Unit,
    onClearBackgroundMusic: () -> Unit,
    onBackgroundMusicEnabledChange: (Boolean) -> Unit,
    onBackgroundMusicVolumeChange: (Float) -> Unit,
    onBackgroundMusicDuckChange: (Float) -> Unit,
    onAddPronunciation: (String, String) -> Unit,
    onUpdatePronunciation: (Long, String, String) -> Unit,
    onPronunciationEnabledChange: (Long, Boolean) -> Unit,
    onDeletePronunciation: (Long) -> Unit,
    onAddVietPhrase: (String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit,
    onUpdateVietPhrase: (Long, String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit,
    onImportVietPhrase: () -> Unit,
    onExportVietPhrase: () -> Unit,
    onCheckVietPhraseOnline: () -> Unit,
    onInstallRecommendedVietPhrase: () -> Unit,
    onVietPhraseEnabledChange: (Long, Boolean) -> Unit,
    onVietPhraseDictionaryEnabledChange: (String, Boolean) -> Unit,
    onDeleteVietPhrase: (Long) -> Unit,
    onConfirmVietPhraseImport: () -> Unit,
    onCancelVietPhraseImport: () -> Unit,
    onRollbackVietPhrase: (String) -> Unit,
    onAcceptVietPhraseSuggestion: (String, String) -> Unit,
    onRejectVietPhraseSuggestion: (String) -> Unit,
    onPrepareVietPhraseImport: (VietPhraseDictionaryKind?) -> Unit,
    onDeleteVietPhraseDictionary: (VietPhraseDictionaryKind) -> Unit,
    onClearAllVietPhrase: () -> Unit,
    onVietPhraseMasterEnabledChange: (Boolean) -> Unit,
    onVietPhraseFallbackChange: (Boolean) -> Unit,
    onRefreshAiModels: (AiProvider, String, String) -> Unit,
    onSaveAiSettings: (AiOnlineSettings, String?, String?) -> Unit,
    onSelectSceneMusic: () -> Unit,
    onUpdateSceneMusic: (String, String, String) -> Unit,
    onSceneMusicEnabledChange: (String, Boolean) -> Unit,
    onDeleteSceneMusic: (String) -> Unit,
    onFollowingUpdatesChange: (Boolean) -> Unit,
    onCheckFollowingNow: () -> Unit,
    onCacheLimitChange: (Int) -> Unit,
    onTrimReaderCache: () -> Unit,
    onClearReaderCache: () -> Unit,
    onCancelAudioExport: (String) -> Unit,
    onResumeAudioExport: (String) -> Unit,
    onOpenAudioExport: (AudioExportJobEntity) -> Unit,
    onRunPerformanceDiagnostics: () -> Unit,
    onBackupComponentChange: (BackupComponent, Boolean) -> Unit,
    onBackupComponentsChange: (Set<BackupComponent>) -> Unit,
    onRefreshBackupLog: () -> Boolean,
    onClearBackupLog: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onClearDownloadedStories: () -> Unit,
    onFactoryResetApplication: () -> Unit,
    onInstallSourcePack: () -> Unit,
    onImportSourceTrustRotation: () -> Unit,
    onRefreshSourceRepository: (String) -> Unit,
    onRemoveSourceRepository: (String) -> Unit,
    onPrepareRepositorySourceInstall: (String, String) -> Unit,
    onConfirmSourcePackInstall: () -> Unit,
    onCancelSourcePackInstall: () -> Unit,
    onSourcePackEnabledChange: (String, Boolean) -> Unit,
    onRollbackSourcePack: (String) -> Unit,
    onUpdateSourcePack: (String) -> Unit,
    onExportSourcePack: (String, String) -> Unit,
    onRemoveSourcePack: (String) -> Unit,
    onEnrollSourceTrustKey: (String, String, String, String) -> Unit,
    onRevokeSourceTrustKey: (String) -> Unit,
    onInspectSourceSelector: (String, String, String) -> Unit,
    onExportSourceDiagnostics: () -> Unit,
    onClearSourceDiagnostics: () -> Unit,
    onCheckSource: (String) -> Unit,
    onCheckAllSources: () -> Unit,
    onOpenSourceLogin: (String) -> Unit,
    onOpenSourceDiagnosticBrowser: (String) -> Unit,
    onClearSourceSession: (String) -> Unit,
) {
    var personalPage by remember { mutableStateOf("home") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAiSettingsDialog by remember { mutableStateOf(false) }
    var showOtherSettingsDialog by remember { mutableStateOf(false) }
    var showBackupLogDialog by remember { mutableStateOf(false) }
    var showClearDownloadsDialog by remember { mutableStateOf(false) }
    var showFactoryResetFirst by remember { mutableStateOf(false) }
    var showFactoryResetFinal by remember { mutableStateOf(false) }
    var backupScopeOperation by remember { mutableStateOf<String?>(null) }
    var showAddRepositoryDialog by remember { mutableStateOf(false) }
    var repositoryName by remember { mutableStateOf("") }
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
        "settings_automation" -> "Phân vai TTS bằng AI"
        "settings_other" -> "Cài đặt khác"
        "settings_tts" -> "Cài đặt TTS"
        "settings_playback" -> "Tai nghe và tự động hóa"
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

    fun returnToSettings() {
        personalPage = "home"
        showSettingsDialog = true
    }

    BackHandler(enabled = personalPage != "home") {
        if (personalPage.startsWith("settings_")) returnToSettings()
        else personalPage = parentPage(personalPage)
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
            onSelect = { target ->
                if (target == "settings_home") showSettingsDialog = true
                else personalPage = target
            },
        )
        "settings_pronunciation" -> PersonalSubPage("TỪ ĐIỂN PHÁT ÂM TTS") {
            PronunciationCard(
                rules = state.pronunciations,
                onAdd = onAddPronunciation,
                onUpdate = onUpdatePronunciation,
                onEnabledChange = onPronunciationEnabledChange,
                onDelete = onDeletePronunciation,
            )
        }
        "settings_vietphrase" -> PersonalSubPage("VIETPHRASE / CHUYỂN NGỮ") {
            VietPhraseCard(
                state = state,
                onImport = onImportVietPhrase,
                onExport = onExportVietPhrase,
                onAddRule = onAddVietPhrase,
                onUpdateRule = onUpdateVietPhrase,
                onCheckOnline = onCheckVietPhraseOnline,
                onRuleEnabledChange = onVietPhraseEnabledChange,
                onDictionaryEnabledChange = onVietPhraseDictionaryEnabledChange,
                onDeleteRule = onDeleteVietPhrase,
                onConfirmImport = onConfirmVietPhraseImport,
                onCancelImport = onCancelVietPhraseImport,
                onRollback = onRollbackVietPhrase,
                onAcceptSuggestion = onAcceptVietPhraseSuggestion,
                onRejectSuggestion = onRejectVietPhraseSuggestion,
                onPrepareImport = onPrepareVietPhraseImport,
                onDeleteDictionary = onDeleteVietPhraseDictionary,
                onClearAll = onClearAllVietPhrase,
                onEnabledChange = onVietPhraseMasterEnabledChange,
                onFallbackChange = onVietPhraseFallbackChange,
                onDownloadRecommended = onInstallRecommendedVietPhrase,
            )
        }
        "settings_automation" -> PersonalSubPage("PHÂN VAI TTS BẰNG AI") {
            ReferenceVoiceCastSettingsCard(
                state = state,
                onAutoVoiceCastChange = onAutoVoiceCastChange,
                onSaveGlobalVoiceRole = onSaveGlobalVoiceRole,
                onGlobalVoiceRoleEnabledChange = onGlobalVoiceRoleEnabledChange,
                onDeleteGlobalVoiceRole = onDeleteGlobalVoiceRole,
                onRestoreGlobalVoiceProfiles = onRestoreGlobalVoiceProfiles,
                onPreviewGlobalVoiceRole = onPreviewGlobalVoiceRole,
            )
        }
        "settings_other" -> PersonalSubPage("CÀI ĐẶT KHÁC") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingSwitch(
                        "Đọc liên tục khi có cuộc gọi hoặc tin nhắn",
                        state.audioInterruptionMode == AudioInterruptionMode.CONTINUE_DUCKED,
                    ) { enabled ->
                        onInterruptionModeChange(if (enabled) AudioInterruptionMode.CONTINUE_DUCKED else AudioInterruptionMode.PAUSE)
                    }
                }
            }
        }
        "settings_tts" -> PersonalSubPage("CÀI ĐẶT TTS") {
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
        "settings_playback" -> PersonalSubPage("TAI NGHE & TỰ ĐỘNG HÓA") {
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
        "settings_music" -> PersonalSubPage("NHẠC NỀN & NHẠC CẢNH") {
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
        "settings_following" -> PersonalSubPage("THEO DÕI CHƯƠNG MỚI") {
            FollowingSettingsCard(
                enabled = state.followingUpdatesEnabled,
                onEnabledChange = onFollowingUpdatesChange,
                onCheckNow = onCheckFollowingNow,
            )
        }
        "settings_storage" -> PersonalSubPage("DUNG LƯỢNG NGOẠI TUYẾN") {
            StorageCard(
                state = state,
                onCacheLimitChange = onCacheLimitChange,
                onTrimCache = onTrimReaderCache,
                onClearCache = onClearReaderCache,
            )
        }
        "settings_export" -> PersonalSubPage("XUẤT SÁCH NÓI") {
            AudioExportCard(state, onCancelAudioExport, onResumeAudioExport, onOpenAudioExport)
        }
        "settings_backup" -> PersonalSubPage("SAO LƯU & KHÔI PHỤC") {
            TransferCard(
                state = state,
                onComponentChange = onBackupComponentChange,
                onExportBackup = onExportBackup,
                onRestoreBackup = onRestoreBackup,
            )
        }
        "settings_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN") {
            PerformanceCard(state.performanceReport, onRunPerformanceDiagnostics)
        }
        "extensions_home" -> PersonalMenuPage(
            title = "TIỆN ÍCH MỞ RỘNG",
            items = listOf(
                "extensions_installed" to "Đã cài (${state.sourcePacks.size})",
                "extensions_repositories" to "Kho tiện ích (${state.sourceRepositories.size})",
                "extensions_add" to "Thêm kho / liên kết",
                "extensions_install" to "Cài từ file",
                "extensions_diagnostics" to "Chẩn đoán",
            ),
            onSelect = { target ->
                when (target) {
                    "extensions_install" -> onInstallSourcePack()
                    "extensions_add" -> showAddRepositoryDialog = true
                    else -> personalPage = target
                }
            },
            extraContent = {
                if (state.pendingSourceInstall != null) {
                    PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
                }
            },
        )
        "extensions_installed" -> PersonalSubPage("TIỆN ÍCH ĐÃ CÀI") {
            PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
            InstalledSourcesSection(
                state = state,
                onEnabledChange = onSourcePackEnabledChange,
                onRollback = onRollbackSourcePack,
                onUpdate = onUpdateSourcePack,
                onExport = onExportSourcePack,
                onRemove = onRemoveSourcePack,
            )
        }
        "extensions_repositories" -> PersonalSubPage("KHO TIỆN ÍCH") {
            PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
            SourceRepositorySection(
                state = state,
                onRefresh = onRefreshSourceRepository,
                onRemove = onRemoveSourceRepository,
                onPrepareInstall = onPrepareRepositorySourceInstall,
                onAddRepository = { showAddRepositoryDialog = true },
            )
        }
        "extensions_add" -> PersonalSubPage("THÊM KHO / LIÊN KẾT") {
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
        "extensions_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN TIỆN ÍCH") {
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

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("CÀI ĐẶT ỨNG DỤNG") },
            text = {
                ReferenceSettingsHomePage(
                    diagnosticsMode = state.diagnosticsMode,
                    onDiagnosticsModeChange = onDiagnosticsModeChange,
                    onSelect = { target ->
                        showSettingsDialog = false
                        when (target) {
                            "settings_ai" -> showAiSettingsDialog = true
                            "settings_other" -> showOtherSettingsDialog = true
                            "settings_backup_log" -> {
                                if (onRefreshBackupLog()) showBackupLogDialog = true else showSettingsDialog = true
                            }
                            "settings_clear_downloads" -> showClearDownloadsDialog = true
                            "settings_factory_reset" -> showFactoryResetFirst = true
                            else -> personalPage = target
                        }
                    },
                    onExportBackup = { backupScopeOperation = "backup" },
                    onRestoreBackup = { backupScopeOperation = "restore" },
                )
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showAiSettingsDialog) {
        AiReferenceSettingsDialog(
            state = state,
            onDismiss = { showAiSettingsDialog = false; showSettingsDialog = true },
            onSave = onSaveAiSettings,
            onRefreshModels = onRefreshAiModels,
        )
    }

    if (showOtherSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showOtherSettingsDialog = false; showSettingsDialog = true },
            title = { Text("CÀI ĐẶT KHÁC") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Đọc liên tục khi có cuộc gọi hoặc tin nhắn", Modifier.weight(1f))
                        Switch(
                            checked = state.audioInterruptionMode == AudioInterruptionMode.CONTINUE_DUCKED,
                            onCheckedChange = { enabled ->
                                onInterruptionModeChange(
                                    if (enabled) AudioInterruptionMode.CONTINUE_DUCKED else AudioInterruptionMode.PAUSE,
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOtherSettingsDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") }
            },
        )
    }

    if (showBackupLogDialog) {
        AlertDialog(
            onDismissRequest = { showBackupLogDialog = false; showSettingsDialog = true },
            title = { Text("NHẬT KÝ SAO LƯU") },
            text = {
                Text(
                    "Tệp: ${state.backupLogPath}\n\n${state.backupLogText.takeLast(40_000)}",
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showBackupLogDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onClearBackupLog()
                    showBackupLogDialog = false
                    showSettingsDialog = true
                }) { Text("XÓA NHẬT KÝ") }
            },
        )
    }

    backupScopeOperation?.let { operation ->
        val scopes = listOf(
            "TẤT CẢ" to BackupComponent.entries.toSet(),
            "CÀI ĐẶT CHUNG, GIỌNG ĐỌC VÀ AI" to setOf(BackupComponent.SETTINGS, BackupComponent.AI_VOICE),
            "DỮ LIỆU ĐỌC, DẤU TRANG, TỪ ĐIỂN VÀ TRUYỆN ĐÃ TẢI" to setOf(BackupComponent.LIBRARY, BackupComponent.READING),
            "NHẠC NỀN" to setOf(BackupComponent.SCENE_MUSIC),
            "VIETPHRASE" to setOf(BackupComponent.VIETPHRASE),
            "TIỆN ÍCH VÀ NGUỒN TRUYỆN" to setOf(BackupComponent.SOURCES_EXTENSIONS),
        )
        AlertDialog(
            onDismissRequest = { backupScopeOperation = null; showSettingsDialog = true },
            title = { Text(if (operation == "backup") "CHỌN DỮ LIỆU SAO LƯU" else "CHỌN DỮ LIỆU KHÔI PHỤC") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    scopes.forEach { (label, components) ->
                        ReferenceActionButton(
                            text = label,
                            onClick = {
                                onBackupComponentsChange(components)
                                backupScopeOperation = null
                                if (operation == "backup") onExportBackup() else onRestoreBackup()
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { backupScopeOperation = null; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }

    if (showClearDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsDialog = false; showSettingsDialog = true },
            title = { Text("XÓA TRUYỆN ĐÃ TẢI") },
            text = {
                Text("Xóa toàn bộ truyện đã tải?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDownloadsDialog = false
                    onClearDownloadedStories()
                    showSettingsDialog = true
                }) { Text("XÓA") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloadsDialog = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }

    if (showFactoryResetFirst) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFirst = false; showSettingsDialog = true },
            title = { Text("ĐẶT LẠI ỨNG DỤNG NHƯ MỚI") },
            text = {
                Text("Xóa toàn bộ dữ liệu và cài đặt?")
            },
            confirmButton = {
                TextButton(onClick = { showFactoryResetFirst = false; showFactoryResetFinal = true }) { Text("TIẾP TỤC") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetFirst = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }

    if (showFactoryResetFinal) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFinal = false; showSettingsDialog = true },
            title = { Text("XÁC NHẬN LẦN CUỐI") },
            text = { Text("Đặt lại ứng dụng ngay?") },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryResetFinal = false
                    onFactoryResetApplication()
                }) { Text("ĐẶT LẠI NGAY") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetFinal = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }

    if (showAddRepositoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddRepositoryDialog = false },
            title = { Text("THÊM KHO / CÀI TỪ LIÊN KẾT") },
            text = {
                Column {
                    OutlinedTextField(
                        value = repositoryUrl,
                        onValueChange = { repositoryUrl = it.take(4096) },
                        label = { Text("Liên kết HTTPS") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repositoryName,
                        onValueChange = { repositoryName = it.take(120) },
                        label = { Text("Tên kho (tùy chọn)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = repositoryUrl.startsWith("https://"),
                    onClick = {
                        onRefreshSourceRepository(repositoryUrl)
                        showAddRepositoryDialog = false
                    },
                ) { Text("KIỂM TRA") }
            },
            dismissButton = { TextButton(onClick = { showAddRepositoryDialog = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun ReferenceSettingsHomePage(
    diagnosticsMode: String,
    onDiagnosticsModeChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        listOf(
            "settings_tts" to "TTS & GIỌNG ĐỌC",
            "settings_playback" to "TAI NGHE & TỰ ĐỘNG",
            "settings_pronunciation" to "TỪ ĐIỂN PHÁT ÂM TTS",
            "settings_vietphrase" to "VIETPHRASE / CHUYỂN NGỮ",
            "settings_ai" to "THIẾT LẬP AI",
            "settings_automation" to "PHÂN VAI TTS BẰNG AI",
            "settings_music" to "NHẠC NỀN & NHẠC CẢNH",
            "settings_following" to "THEO DÕI CHƯƠNG MỚI",
            "settings_storage" to "DUNG LƯỢNG NGOẠI TUYẾN",
            "settings_export" to "XUẤT SÁCH NÓI",
            "settings_diagnostics" to "CHẨN ĐOÁN",
        ).forEach { (id, label) ->
            ReferenceActionButton(
                text = label,
                onClick = { onSelect(id) },
                accessibilityLabel = label,
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 50.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
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
        ReferenceActionButton(
            text = "SAO LƯU DỮ LIỆU",
            onClick = onExportBackup,
            minHeight = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = "KHÔI PHỤC DỮ LIỆU",
            onClick = onRestoreBackup,
            minHeight = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = "NHẬT KÝ SAO LƯU",
            onClick = { onSelect("settings_backup_log") },
            minHeight = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = "XÓA TRUYỆN ĐÃ TẢI",
            onClick = { onSelect("settings_clear_downloads") },
            minHeight = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        )
        ReferenceActionButton(
            text = "ĐẶT LẠI ỨNG DỤNG NHƯ MỚI",
            onClick = { onSelect("settings_factory_reset") },
            minHeight = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PersonalMenuPage(
    title: String,
    items: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    extraContent: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ScreenHeading(title)
        items.forEach { (id, label) ->
            ReferenceActionButton(
                text = label,
                onClick = { onSelect(id) },
                accessibilityLabel = label,
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 52.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        extraContent()
    }
}

@Composable
private fun PersonalSubPage(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
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
    onUpdate: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var removePackId by remember { mutableStateOf<String?>(null) }
    val filtered = state.sourcePacks.filter { pack ->
        query.isBlank() || pack.name.contains(query, ignoreCase = true) || pack.id.contains(query, ignoreCase = true)
    }

    ReferenceActionButton(
        text = if (query.isBlank()) "TÌM TIỆN ÍCH" else "TÌM: $query ✓",
        onClick = { showSearch = true },
        normalColor = ReferenceGray,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
    if (query.isNotBlank()) {
        ReferenceActionButton(
            text = "HIỆN TẤT CẢ",
            onClick = { query = "" },
            normalColor = ReferenceGray,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    if (state.sourcePacks.isEmpty()) {
        Text("Chưa cài tiện ích nguồn nào.", modifier = Modifier.padding(16.dp))
    } else if (filtered.isEmpty()) {
        Text("Không tìm thấy tiện ích phù hợp.", modifier = Modifier.padding(16.dp))
    } else {
        filtered.forEach { pack ->
            ReferenceActionButton(
                text = buildString {
                    append(pack.name)
                    append("\n").append(pack.version)
                    if (!pack.enabled) append(" • Đã tắt")
                },
                onClick = { selectedPackId = pack.id },
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("TÌM TIỆN ÍCH") },
            text = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    label = { Text("Tên hoặc ID tiện ích") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text("TÌM") } },
            dismissButton = { TextButton(onClick = { query = ""; showSearch = false }) { Text("HIỆN TẤT CẢ") } },
        )
    }

    selectedPackId?.let { selectedId ->
        state.sourcePacks.firstOrNull { it.id == selectedId }?.let { pack ->
            AlertDialog(
                onDismissRequest = { selectedPackId = null },
                title = { Text(pack.name) },
                text = {
                    Column {
                        Text("${pack.id} • ${pack.version}", style = MaterialTheme.typography.bodySmall)
                        ReferenceActionButton(
                            text = if (pack.enabled) "TẮT" else "BẬT",
                            onClick = {
                                onEnabledChange(pack.id, !pack.enabled)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        if (pack.canRollback) {
                            ReferenceActionButton(
                                text = "ROLLBACK PHIÊN BẢN NGUỒN",
                                onClick = {
                                    onRollback(pack.id)
                                    selectedPackId = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        ReferenceActionButton(
                            text = "CẬP NHẬT",
                            onClick = {
                                onUpdate(pack.id)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        ReferenceActionButton(
                            text = "XUẤT GÓI",
                            onClick = {
                                onExport(pack.id, pack.name)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        if (pack.removable) {
                            ReferenceActionButton(
                                text = "GỠ TIỆN ÍCH",
                                onClick = {
                                    removePackId = pack.id
                                    selectedPackId = null
                                },
                                normalColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedPackId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    removePackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        AlertDialog(
            onDismissRequest = { removePackId = null },
            title = { Text("GỠ TIỆN ÍCH") },
            text = { Text("Gỡ ${pack?.name ?: packId} khỏi thiết bị?") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(packId)
                    removePackId = null
                }) { Text("GỠ") }
            },
            dismissButton = { TextButton(onClick = { removePackId = null }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun SourceRepositorySection(
    state: MainUiState,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
    onAddRepository: () -> Unit,
) {
    var repositoryQuery by remember { mutableStateOf("") }
    var showRepositorySearch by remember { mutableStateOf(false) }
    var packageQuery by remember { mutableStateOf("") }
    var packageFilter by remember { mutableStateOf("ALL") }

    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
        ReferenceActionButton(
            text = if (repositoryQuery.isBlank()) "TÌM KHO" else "TÌM: $repositoryQuery ✓",
            onClick = { showRepositorySearch = true },
            normalColor = ReferenceGray,
            modifier = Modifier.weight(1f).padding(1.dp),
        )
        ReferenceActionButton(
            text = "THÊM KHO MỚI",
            onClick = onAddRepository,
            normalColor = ReferenceGray,
            modifier = Modifier.weight(1f).padding(1.dp),
        )
    }
    if (repositoryQuery.isNotBlank()) {
        ReferenceActionButton(
            text = "HIỆN TẤT CẢ",
            onClick = { repositoryQuery = "" },
            normalColor = ReferenceGray,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    val repositories = state.sourceRepositories.filter { repository ->
        repositoryQuery.isBlank() || repository.name.contains(repositoryQuery, ignoreCase = true) ||
            repository.url.contains(repositoryQuery, ignoreCase = true)
    }
    if (repositories.isEmpty()) {
        Text("Chưa có kho tiện ích phù hợp.", modifier = Modifier.padding(16.dp))
    }
    repositories.forEach { repository ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(repository.name, fontWeight = FontWeight.SemiBold)
                Text("${repository.packageCount} gói • ký bởi ${repository.signerKeyId}", style = MaterialTheme.typography.bodySmall)
                Text(repository.url, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button({ showRepositorySearch = true }, Modifier.weight(1f).padding(1.dp)) { Text("TÌM KIẾM") }
                    Button({ packageFilter = "ALL" }, Modifier.weight(1f).padding(1.dp)) { Text((if (packageFilter == "ALL") "✓ " else "") + "TẤT CẢ") }
                    Button({ packageFilter = "INSTALLED" }, Modifier.weight(1f).padding(1.dp)) { Text((if (packageFilter == "INSTALLED") "✓ " else "") + "ĐÃ CÀI") }
                    Button({ packageFilter = "UPDATE" }, Modifier.weight(1f).padding(1.dp)) { Text((if (packageFilter == "UPDATE") "✓ " else "") + "CẬP NHẬT") }
                }
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onRefresh(repository.url) },
                        enabled = !state.sourceRepositoryRefreshing,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("LÀM MỚI") }
                    Button(onClick = { onRemove(repository.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("XÓA KHO") }
                }
                state.sourceRepositoryPackages
                    .filter { it.repositoryId == repository.id }
                    .filter { item -> packageQuery.isBlank() || item.name.contains(packageQuery, true) || item.sourceId.contains(packageQuery, true) }
                    .filter { item ->
                        when (packageFilter) {
                            "INSTALLED" -> item.installedVersion != null
                            "UPDATE" -> item.status == "UPDATE_AVAILABLE"
                            else -> true
                        }
                    }
                    .forEach { item ->
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

    if (showRepositorySearch) {
        AlertDialog(
            onDismissRequest = { showRepositorySearch = false },
            title = { Text("TÌM KIẾM") },
            text = {
                Column {
                    OutlinedTextField(
                        value = repositoryQuery,
                        onValueChange = { repositoryQuery = it.take(120) },
                        label = { Text("Tên hoặc URL kho") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = packageQuery,
                        onValueChange = { packageQuery = it.take(120) },
                        label = { Text("Tên hoặc ID tiện ích trong kho") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showRepositorySearch = false }) { Text("TÌM") } },
            dismissButton = {
                TextButton(onClick = {
                    repositoryQuery = ""
                    packageQuery = ""
                    showRepositorySearch = false
                }) { Text("HIỆN TẤT CẢ") }
            },
        )
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
                label = { Text("URL kho") },
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
            ) { Text("THÊM KHÓA") }
            Button(onClick = onImportRotation, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("NHẬP XOAY KHÓA") }
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

private val MEDIA_ACTION_ORDER = listOf("TOGGLE", "NEXT", "PREVIOUS", "PLAY", "PAUSE", "FORWARD", "REWIND", "STOP")

private fun nextMediaAction(current: String): String {
    val index = MEDIA_ACTION_ORDER.indexOf(current).takeIf { it >= 0 } ?: 0
    return MEDIA_ACTION_ORDER[(index + 1) % MEDIA_ACTION_ORDER.size]
}

private fun mediaActionLabel(value: String): String = when (value) {
    "PLAY" -> "Phát"
    "PAUSE" -> "Tạm dừng"
    "NEXT" -> "Đoạn sau"
    "PREVIOUS" -> "Đoạn trước"
    "FORWARD" -> "Tiến"
    "REWIND" -> "Lùi"
    "STOP" -> "Dừng hẳn"
    else -> "Phát / dừng"
}

@Composable
private fun MediaMappingButton(label: String, value: String, onChange: (String) -> Unit) {
    Button(
        onClick = { onChange(nextMediaAction(value)) },
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    ) { Text("$label: ${mediaActionLabel(value)}") }
}

@Composable
private fun ReferenceVoiceCastSettingsCard(
    state: MainUiState,
    onAutoVoiceCastChange: (Boolean) -> Unit,
    onSaveGlobalVoiceRole: (VoiceRoleDraft) -> Unit,
    onGlobalVoiceRoleEnabledChange: (String, Boolean) -> Unit,
    onDeleteGlobalVoiceRole: (String) -> Unit,
    onRestoreGlobalVoiceProfiles: () -> Unit,
    onPreviewGlobalVoiceRole: (VoiceRoleDraft) -> Unit,
) {
    val roles = state.voiceRoles.filter { it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID }.take(10)
    var editDraft by remember { mutableStateOf<VoiceRoleDraft?>(null) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật mặc định", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = state.autoVoiceCastEnabled, onCheckedChange = onAutoVoiceCastChange)
            }
            roles.forEach { role ->
                ReferenceActionButton(
                    text = (if (role.enabled) "" else "TẮT • ") +
                        (if (role.isNarrator) "Người kể chuyện" else role.roleName) + "\n" +
                        (role.enginePackage ?: "Hệ thống") + " • " + (role.voiceName ?: "Mặc định"),
                    onClick = {
                        editDraft = VoiceRoleDraft(
                            roleName = role.roleName,
                            originalRoleId = role.id,
                            aliases = role.aliasesCsv,
                            description = role.description,
                            isNarrator = role.isNarrator,
                            enginePackage = role.enginePackage,
                            voiceName = role.voiceName,
                            languageTag = role.languageTag,
                            rate = role.rate,
                            pitch = role.pitch,
                            volume = role.volume,
                            expression = runCatching { VoiceExpression.valueOf(role.expression) }.getOrDefault(VoiceExpression.NEUTRAL),
                            expressionStrength = role.expressionStrength,
                            sonicSpeed = role.sonicSpeed,
                            sonicPitch = role.sonicPitch,
                            enabled = role.enabled,
                        )
                    },
                    normalColor = ReferencePanelBackground,
                    normalContentColor = ReferenceText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
            Button(
                onClick = {
                    editDraft = VoiceRoleDraft(
                        roleName = "Giọng mới ${roles.size + 1}",
                        enginePackage = state.selectedTtsEnginePackage,
                        voiceName = state.selectedTtsVoiceName,
                        languageTag = state.selectedTtsLanguageTag,
                        rate = state.playback.rate,
                        pitch = state.playback.pitch,
                        volume = state.ttsVolume,
                        sonicSpeed = state.sonicDefaultSpeed,
                        sonicPitch = state.sonicDefaultPitch,
                        processingMethod = if (state.sonicProcessingEnabled) "sonic" else "system",
                    )
                },
                enabled = roles.size < 10,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("THÊM GIỌNG") }
            Button(
                onClick = onRestoreGlobalVoiceProfiles,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("KHÔI PHỤC 7 HỒ SƠ MẪU") }
        }
    }

    editDraft?.let { draft ->
        vn.nghetruyen.app.ui.components.GlobalVoiceRoleEditorDialog(
            draft = draft,
            engines = state.ttsEngines,
            onDraftChange = { editDraft = it },
            onPreview = onPreviewGlobalVoiceRole,
            onSave = {
                onSaveGlobalVoiceRole(it)
                editDraft = null
            },
            onDelete = if (!draft.isNarrator && draft.originalRoleId != null) {
                { onDeleteGlobalVoiceRole(draft.originalRoleId); editDraft = null }
            } else null,
            onDismiss = { editDraft = null },
        )
    }
}

@Composable
private fun PlaybackAutomationCard(
    state: MainUiState,
    onHeadsetMultiClickChange: (Boolean) -> Unit,
    onHeadsetSingleActionChange: (String) -> Unit,
    onHeadsetDoubleActionChange: (String) -> Unit,
    onHeadsetTripleActionChange: (String) -> Unit,
    onHeadsetLongActionChange: (String) -> Unit,
    onPauseOnHeadsetDisconnectChange: (Boolean) -> Unit,
    onRestorePlaybackChange: (Boolean) -> Unit,
    onAutoVoiceCastChange: (Boolean) -> Unit,
    onAutoSceneMusicChange: (Boolean) -> Unit,
    onPrefetchNarrationPlansChange: (Boolean) -> Unit,
    onNarrationPrefetchWindowChange: (Int) -> Unit,
    onSceneMusicCrossfadeChange: (Int) -> Unit,
    onSceneMusicContinueChange: (Boolean) -> Unit,
    onSceneMusicPlaybackModeChange: (SceneMusicPlaybackMode) -> Unit,
    onSceneMusicTargetLufsChange: (Float) -> Unit,
    onSceneMusicAvoidRepeatWindowChange: (Int) -> Unit,
    onSonicProcessingEnabledChange: (Boolean) -> Unit,
    onSonicDefaultSpeedChange: (Float) -> Unit,
    onSonicDefaultPitchChange: (Float) -> Unit,
    onTtsCacheEnabledChange: (Boolean) -> Unit,
    onTtsCacheLimitChange: (Int) -> Unit,
    onNormalizeTtsVolumeChange: (Boolean) -> Unit,
    onTtsTargetLufsChange: (Float) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Tai nghe & tự động", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SettingSwitch("Bấm nhiều lần", state.headsetMultiClickEnabled, onHeadsetMultiClickChange)
            MediaMappingButton("Một lần", state.headsetSingleClickAction, onHeadsetSingleActionChange)
            MediaMappingButton("Hai lần", state.headsetDoubleClickAction, onHeadsetDoubleActionChange)
            MediaMappingButton("Ba lần", state.headsetTripleClickAction, onHeadsetTripleActionChange)
            MediaMappingButton("Nhấn giữ", state.headsetLongPressAction, onHeadsetLongActionChange)
            SettingSwitch("Dừng khi ngắt tai nghe", state.pauseOnHeadsetDisconnect, onPauseOnHeadsetDisconnectChange)
            SettingSwitch("Khôi phục phiên nghe", state.restorePlaybackAfterProcessDeath, onRestorePlaybackChange)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingSwitch("Tự phân vai AI", state.autoVoiceCastEnabled, onAutoVoiceCastChange)
            SettingSwitch("Tự lập nhạc cảnh", state.autoSceneMusicEnabled, onAutoSceneMusicChange)
            SettingSwitch("Chuẩn bị AI trước", state.prefetchNarrationPlansEnabled, onPrefetchNarrationPlansChange)
            Text("Chuẩn bị trước: ${state.narrationPrefetchWindowChapters} chương", modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onNarrationPrefetchWindowChange(state.narrationPrefetchWindowChapters - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("ÍT HƠN") }
                Button({ onNarrationPrefetchWindowChange(state.narrationPrefetchWindowChapters + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("NHIỀU HƠN") }
            }
            SettingSwitch("Dùng Sonic", state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
            Text("Sonic mặc định: tốc độ ${"%.2f".format(state.sonicDefaultSpeed)}× • cao độ ${"%.2f".format(state.sonicDefaultPitch)}×")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSonicDefaultSpeedChange(state.sonicDefaultSpeed - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("TỐC ĐỘ -") }
                Button({ onSonicDefaultSpeedChange(state.sonicDefaultSpeed + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("TỐC ĐỘ +") }
            }
            Row(Modifier.fillMaxWidth()) {
                Button({ onSonicDefaultPitchChange(state.sonicDefaultPitch - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("CAO ĐỘ -") }
                Button({ onSonicDefaultPitchChange(state.sonicDefaultPitch + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("CAO ĐỘ +") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingSwitch("Cache TTS/Sonic", state.ttsCacheEnabled, onTtsCacheEnabledChange)
            Text("Giới hạn cache TTS: ${state.ttsCacheLimitMiB} MiB")
            Row(Modifier.fillMaxWidth()) {
                Button({ onTtsCacheLimitChange(state.ttsCacheLimitMiB / 2) }, Modifier.weight(1f).padding(2.dp)) { Text("CACHE -") }
                Button({ onTtsCacheLimitChange(state.ttsCacheLimitMiB * 2) }, Modifier.weight(1f).padding(2.dp)) { Text("CACHE +") }
            }
            SettingSwitch("Chuẩn hóa âm lượng", state.normalizeTtsVolumeEnabled, onNormalizeTtsVolumeChange)
            Text("Mức giọng mục tiêu: ${"%.1f".format(state.ttsTargetLufs)} LUFS")
            Row(Modifier.fillMaxWidth()) {
                Button({ onTtsTargetLufsChange(state.ttsTargetLufs - 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIỌNG NHỎ") }
                Button({ onTtsTargetLufsChange(state.ttsTargetLufs + 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIỌNG LỚN") }
            }
            SettingSwitch("Giữ nhạc qua chương", state.sceneMusicContinueAcrossChapters, onSceneMusicContinueChange)
            Text("Chế độ nhạc cảnh", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            SceneMusicPlaybackMode.entries.forEach { mode ->
                Button({ onSceneMusicPlaybackModeChange(mode) }, Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    val label = when (mode) {
                        SceneMusicPlaybackMode.SEQUENTIAL -> "TUẦN TỰ"
                        SceneMusicPlaybackMode.SHUFFLE -> "NGẪU NHIÊN"
                        SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "TRÁNH LẶP"
                    }
                    Text((if (state.sceneMusicPlaybackMode == mode) "✓ " else "") + label)
                }
            }
            Text("Nhạc: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSceneMusicTargetLufsChange(state.sceneMusicTargetLufs - 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHỎ HƠN") }
                Button({ onSceneMusicTargetLufsChange(state.sceneMusicTargetLufs + 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("LỚN HƠN") }
            }
            Text("Tránh lặp: ${state.sceneMusicAvoidRepeatWindow} bài")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSceneMusicAvoidRepeatWindowChange(state.sceneMusicAvoidRepeatWindow - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("GIẢM") }
                Button({ onSceneMusicAvoidRepeatWindowChange(state.sceneMusicAvoidRepeatWindow + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("TĂNG") }
            }
            Text("Crossfade: ${state.sceneMusicCrossfadeMillis} ms", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSceneMusicCrossfadeChange((state.sceneMusicCrossfadeMillis - 400).coerceAtLeast(0)) },
                    modifier = Modifier.weight(1f).padding(end = 2.dp),
                ) { Text("-400 ms") }
                Button(
                    onClick = { onSceneMusicCrossfadeChange((state.sceneMusicCrossfadeMillis + 400).coerceAtMost(8_000)) },
                    modifier = Modifier.weight(1f).padding(start = 2.dp),
                ) { Text("+400 ms") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VoiceSettingsCard(
    rate: Float, pitch: Float, volume: Float, autoNext: Boolean,
    engines: List<TtsEngineOption>, voices: List<TtsVoiceOption>,
    selectedEnginePackage: String?, selectedVoiceName: String?, loadingVoices: Boolean,
    interruptionMode: AudioInterruptionMode,
    onRateChange: (Float) -> Unit, onPitchChange: (Float) -> Unit, onVolumeChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit, onEngineSelected: (TtsEngineOption?) -> Unit,
    onVoiceSelected: (TtsVoiceOption?) -> Unit, onRefreshVoices: () -> Unit,
    onPreviewVoice: () -> Unit, onOpenTtsSettings: () -> Unit,
    onInterruptionModeChange: (AudioInterruptionMode) -> Unit,
) {
    var showAllVoices by remember { mutableStateOf(false) }
    var showAllEngines by remember { mutableStateOf(false) }
    val visibleVoices = if (showAllVoices) voices else voices.take(MAX_VISIBLE_VOICES)
    val visibleEngines = if (showAllEngines) engines else engines.take(6)
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("TTS & giọng đọc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Tốc độ ${"%.1f".format(rate)}×", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onRateChange(rate - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("CHẬM") }
                Button({ onRateChange(rate + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHANH") }
            }
            Text("Cao độ ${"%.1f".format(pitch)}×", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onPitchChange(pitch - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("TRẦM") }
                Button({ onPitchChange(pitch + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("CAO") }
            }
            Text("Âm lượng giọng ${"%.0f".format(volume * 100)}%", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onVolumeChange(volume - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIẢM") }
                Button({ onVolumeChange(volume + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("TĂNG") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tự đọc chương sau", Modifier.weight(1f))
                Switch(autoNext, onAutoNextChange)
            }
            Text("Âm thanh khác", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    { onInterruptionModeChange(AudioInterruptionMode.PAUSE) },
                    Modifier.weight(1f).padding(2.dp),
                ) { Text((if (interruptionMode == AudioInterruptionMode.PAUSE) "✓ " else "") + "TẠM DỪNG") }
                Button(
                    { onInterruptionModeChange(AudioInterruptionMode.CONTINUE_DUCKED) },
                    Modifier.weight(1f).padding(2.dp),
                ) { Text((if (interruptionMode == AudioInterruptionMode.CONTINUE_DUCKED) "✓ " else "") + "TIẾP TỤC") }
            }
            Text("Bộ máy TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Button({ onEngineSelected(null) }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (selectedEnginePackage == null) "✓ MẶC ĐỊNH HỆ THỐNG" else "MẶC ĐỊNH HỆ THỐNG")
            }
            visibleEngines.forEach { engine ->
                Button({ onEngineSelected(engine) }, Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text((if (engine.packageName == selectedEnginePackage) "✓ " else "") + engine.label)
                }
            }
            if (engines.size > 6) Button({ showAllEngines = !showAllEngines }, Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(if (showAllEngines) "THU GỌN BỘ MÁY" else "HIỂN THỊ TẤT CẢ ${engines.size} BỘ MÁY")
            }
            Text("Giọng TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Button({ onVoiceSelected(null) }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (selectedVoiceName == null) "✓ GIỌNG MẶC ĐỊNH" else "GIỌNG MẶC ĐỊNH")
            }
            visibleVoices.forEach { voice ->
                Button({ onVoiceSelected(voice) }, Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text((if (voice.name == selectedVoiceName) "✓ " else "") + voice.displayName)
                }
            }
            if (voices.size > MAX_VISIBLE_VOICES) {
                Button({ showAllVoices = !showAllVoices }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (showAllVoices) "THU GỌN GIỌNG" else "HIỂN THỊ TẤT CẢ ${voices.size} GIỌNG")
                }
            }
            Button(onPreviewVoice, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("NGHE THỬ GIỌNG ĐANG CHỌN") }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Button(onRefreshVoices, Modifier.weight(1f).padding(2.dp)) { Text(if (loadingVoices) "ĐANG QUÉT…" else "QUÉT LẠI") }
                Button(onOpenTtsSettings, Modifier.weight(1f).padding(2.dp)) { Text("CÀI ĐẶT TTS") }
            }
        }
    }
}

@Composable
private fun BackgroundMusicCard(
    uri: String?, enabled: Boolean, volume: Float, duckFactor: Float,
    onSelect: () -> Unit, onClear: () -> Unit, onEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit, onDuckChange: (Float) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Nhạc nền cục bộ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Phát lặp một tệp nhạc do bạn chọn và tự hạ âm lượng khi TTS đang đọc. Không phân tích hoặc gửi tệp ra ngoài.")
            Text(if (uri.isNullOrBlank()) "Chưa chọn tệp." else "Đã chọn: ${uri.takeLast(80)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật nhạc nền", Modifier.weight(1f))
                Switch(enabled, onEnabledChange)
            }
            Text("Âm lượng nền ${"%.0f".format(volume * 100)}%")
            Row(Modifier.fillMaxWidth()) {
                Button({ onVolumeChange(volume - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHỎ") }
                Button({ onVolumeChange(volume + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("LỚN") }
            }
            Text("Mức còn lại khi TTS đọc ${"%.0f".format(duckFactor * 100)}%", modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onDuckChange(duckFactor - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("HẠ THÊM") }
                Button({ onDuckChange(duckFactor + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("HẠ ÍT") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Button(onSelect, Modifier.weight(1f).padding(2.dp)) { Text("CHỌN TỆP NHẠC") }
                Button(onClear, Modifier.weight(1f).padding(2.dp), enabled = !uri.isNullOrBlank()) { Text("BỎ TỆP") }
            }
        }
    }
}

@Composable
private fun PronunciationCard(
    rules: List<PronunciationEntity>,
    onAdd: (String, String) -> Unit,
    onUpdate: (Long, String, String) -> Unit,
    onEnabledChange: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PronunciationEntity?>(null) }
    var editOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var original by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        ReferenceActionButton(
            text = "＋ THÊM CÁCH ĐỌC",
            onClick = { original = ""; replacement = ""; addOpen = true },
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        )
        rules.forEach { row ->
            ReferenceActionButton(
                text = (if (row.enabled) "" else "TẮT • ") + "${row.original} → ${row.replacement}",
                onClick = { selected = row },
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    if (addOpen) {
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("THÊM CÁCH ĐỌC") },
            text = { Column {
                OutlinedTextField(original, { original = it.take(120) }, placeholder = { Text("Từ hoặc cụm từ gốc") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replacement, { replacement = it.take(240) }, placeholder = { Text("TTS sẽ đọc thành") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            } },
            confirmButton = { TextButton(onClick = { onAdd(original, replacement); addOpen = false }) { Text("LƯU") } },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("HỦY") } },
        )
    }

    selected?.let { selectedRow ->
        val row = rules.firstOrNull { it.id == selectedRow.id } ?: selectedRow
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${row.original} → ${row.replacement}") },
            text = { Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật quy tắc", Modifier.weight(1f))
                    Switch(row.enabled, { onEnabledChange(row.id, it) })
                }
                ReferenceActionButton("SỬA", { editingId = row.id; original = row.original; replacement = row.replacement; editOpen = true; selected = null }, Modifier.fillMaxWidth())
                ReferenceActionButton("XÓA", { onDelete(row.id); selected = null }, Modifier.fillMaxWidth().padding(top = 4.dp))
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } },
        )
    }

    if (editOpen) {
        AlertDialog(
            onDismissRequest = { editOpen = false },
            title = { Text("SỬA CÁCH ĐỌC") },
            text = { Column {
                OutlinedTextField(original, { original = it.take(120) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replacement, { replacement = it.take(240) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            } },
            confirmButton = { TextButton(onClick = { editingId?.let { onUpdate(it, original, replacement) }; editOpen = false }) { Text("LƯU") } },
            dismissButton = { TextButton(onClick = { editOpen = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun VietPhraseCard(
    state: MainUiState,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAddRule: (String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit,
    onUpdateRule: (Long, String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit,
    onCheckOnline: () -> Unit,
    onRuleEnabledChange: (Long, Boolean) -> Unit,
    onDictionaryEnabledChange: (String, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onRollback: (String) -> Unit,
    onAcceptSuggestion: (String, String) -> Unit,
    onRejectSuggestion: (String) -> Unit,
    onPrepareImport: (VietPhraseDictionaryKind?) -> Unit,
    onDeleteDictionary: (VietPhraseDictionaryKind) -> Unit,
    onClearAll: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onFallbackChange: (Boolean) -> Unit,
    onDownloadRecommended: () -> Unit,
) {
    val orderedKinds = listOf(
        VietPhraseDictionaryKind.NAMES,
        VietPhraseDictionaryKind.VIET_PHRASE,
        VietPhraseDictionaryKind.PRONOUNS,
        VietPhraseDictionaryKind.LUAT_NHAN,
        VietPhraseDictionaryKind.PHIEN_AM,
        VietPhraseDictionaryKind.LAC_VIET,
        VietPhraseDictionaryKind.AI_REPLACE,
    )
    var selectedKind by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }
    var deleteKind by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    var downloadConfirm by remember { mutableStateOf(false) }
    var showAddRule by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showSnapshots by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var selectedRuleId by remember { mutableStateOf<Long?>(null) }
    var editRuleId by remember { mutableStateOf<Long?>(null) }
    var rollbackSnapshotId by remember { mutableStateOf<String?>(null) }
    var selectedSuggestionId by remember { mutableStateOf<String?>(null) }

    fun status(kind: VietPhraseDictionaryKind): String {
        val states = state.vietPhraseDictionaryStates.filter { it.kind == kind.name && it.scope == VietPhraseScope.GLOBAL.name }
        val stateCount = states.sumOf { it.entryCount }
        val ruleCount = state.vietPhraseRules.count { it.kind == kind.name && it.scope == VietPhraseScope.GLOBAL.name }
        val count = maxOf(stateCount, ruleCount)
        return when {
            count > 0 -> String.format(Locale.getDefault(), "%,d từ", count).replace(',', '.')
            kind == VietPhraseDictionaryKind.AI_REPLACE -> "Áp dụng ở bước cuối"
            else -> "Chưa thiết lập"
        }
    }

    val orderedRules = state.vietPhraseRules
    val pendingSuggestions = state.vietPhraseSuggestions.filter { it.status == "PENDING" }
    val latestSnapshots = state.vietPhraseSnapshots

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Bật VietPhrase", Modifier.weight(1f))
            Switch(state.vietPhraseEnabled, onEnabledChange)
        }
        ReferenceActionButton("THÊM QUY TẮC", { showAddRule = true }, Modifier.fillMaxWidth().padding(top = 8.dp))
        ReferenceActionButton("QUY TẮC (${orderedRules.size})", { showRules = true }, Modifier.fillMaxWidth().padding(top = 4.dp))
        if (pendingSuggestions.isNotEmpty()) {
            ReferenceActionButton("GỢI Ý AI (${pendingSuggestions.size})", { showSuggestions = true }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (latestSnapshots.isNotEmpty()) {
            ReferenceActionButton("KHÔI PHỤC (${latestSnapshots.size})", { showSnapshots = true }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        ReferenceActionButton("NHẬP ZIP", { onPrepareImport(null); onImport() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        ReferenceActionButton("XUẤT ZIP", onExport, Modifier.fillMaxWidth().padding(top = 4.dp))
        ReferenceActionButton(
            if (state.vietPhraseOnlineBusy) "ĐANG KIỂM TRA…" else "CẬP NHẬT",
            onCheckOnline,
            enabled = !state.vietPhraseOnlineBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (state.vietPhraseOnlineStatus.isNotBlank()) {
            Text(state.vietPhraseOnlineStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        orderedKinds.forEach { kind ->
            ReferenceActionButton(
                text = "${kind.fileName}\n${status(kind)}",
                onClick = { selectedKind = kind },
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        ReferenceActionButton("XÓA TẤT CẢ", { clearAllConfirm = true }, Modifier.fillMaxWidth().padding(top = 8.dp))
        ReferenceActionButton("TẢI TỪ MẠNG", { downloadConfirm = true }, Modifier.fillMaxWidth().padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hán Việt khi thiếu cụm", Modifier.weight(1f))
            Switch(state.vietPhraseFallbackHanViet, onFallbackChange)
        }
    }

    selectedKind?.let { kind ->
        val dictionaryStates = state.vietPhraseDictionaryStates.filter { it.kind == kind.name }
        AlertDialog(
            onDismissRequest = { selectedKind = null },
            title = { Text(kind.fileName) },
            text = { Column {
                dictionaryStates.forEach { dictionary ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOf(dictionary.scope, dictionary.sourceName).filter(String::isNotBlank).joinToString(" • "),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(dictionary.enabled, { onDictionaryEnabledChange(dictionary.id, it) })
                    }
                }
                ReferenceActionButton("NHẬP / THAY THẾ", { selectedKind = null; onPrepareImport(kind); onImport() }, Modifier.fillMaxWidth())
                ReferenceActionButton("XÓA FILE", { selectedKind = null; deleteKind = kind }, Modifier.fillMaxWidth().padding(top = 4.dp))
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selectedKind = null }) { Text("ĐÓNG") } },
        )
    }

    if (showAddRule) {
        var source by remember(showAddRule) { mutableStateOf("") }
        var target by remember(showAddRule) { mutableStateOf("") }
        var priorityText by remember(showAddRule) { mutableStateOf("0") }
        var kind by remember(showAddRule) { mutableStateOf(VietPhraseDictionaryKind.VIET_PHRASE) }
        var kindExpanded by remember { mutableStateOf(false) }
        var storyOnly by remember(showAddRule) { mutableStateOf(false) }
        var storyId by remember(showAddRule) { mutableStateOf("") }
        var ignoreCase by remember(showAddRule) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddRule = false },
            title = { Text("THÊM QUY TẮC") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(source, { source = it.take(2_000) }, label = { Text("Cụm nguồn") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(target, { target = it.take(4_000) }, label = { Text("Cụm thay thế") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    OutlinedTextField(
                        priorityText,
                        { value -> priorityText = value.filterIndexed { index, char -> char.isDigit() || (char == '-' && index == 0) }.take(4) },
                        label = { Text("Ưu tiên (-999 đến 999)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Button(onClick = { kindExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(kind.fileName) }
                        DropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                            orderedKinds.forEach { option ->
                                DropdownMenuItem(text = { Text(option.fileName) }, onClick = { kind = option; kindExpanded = false })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bỏ qua hoa thường", Modifier.weight(1f))
                        Switch(ignoreCase, { ignoreCase = it })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Chỉ truyện này", Modifier.weight(1f))
                        Switch(storyOnly, { storyOnly = it })
                    }
                    if (storyOnly) {
                        OutlinedTextField(storyId, { storyId = it.take(300) }, label = { Text("Story ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = source.isNotBlank() && target.isNotBlank() && (!storyOnly || storyId.isNotBlank()),
                    onClick = {
                        onAddRule(
                            source,
                            target,
                            priorityText.toIntOrNull()?.coerceIn(-999, 999) ?: 0,
                            kind,
                            if (storyOnly) VietPhraseScope.STORY else VietPhraseScope.GLOBAL,
                            storyId.takeIf { storyOnly && it.isNotBlank() },
                            ignoreCase,
                        )
                        showAddRule = false
                    },
                ) { Text("LƯU") }
            },
            dismissButton = { TextButton(onClick = { showAddRule = false }) { Text("HỦY") } },
        )
    }

    if (showRules) {
        var query by remember(showRules) { mutableStateOf("") }
        val needle = query.trim().lowercase()
        val matches = orderedRules.filter { rule ->
            needle.isBlank() || listOf(rule.source, rule.target, rule.kind, rule.scope, rule.storyId).any { it.lowercase().contains(needle) }
        }
        AlertDialog(
            onDismissRequest = { showRules = false },
            title = { Text("QUY TẮC VIETPHRASE") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(query, { query = it.take(160) }, placeholder = { Text("Tìm quy tắc") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    matches.take(100).forEach { rule ->
                        ReferenceActionButton(
                            text = (if (rule.enabled) "" else "TẮT • ") + "${rule.source} → ${rule.target}\n${rule.kind} • ${rule.scope} • ${rule.priority}",
                            onClick = { showRules = false; selectedRuleId = rule.id },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                    if (matches.size > 100) Text("Hiển thị 100/${matches.size} kết quả.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (matches.isEmpty()) Text("Không có quy tắc phù hợp.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showRules = false }) { Text("ĐÓNG") } },
        )
    }

    selectedRuleId?.let { id ->
        val rule = state.vietPhraseRules.firstOrNull { it.id == id }
        if (rule != null) {
            AlertDialog(
                onDismissRequest = { selectedRuleId = null },
                title = { Text(rule.source) },
                text = {
                    Column {
                        Text(rule.target, fontWeight = FontWeight.SemiBold)
                        Text("${rule.kind} • ${rule.scope} • ưu tiên ${rule.priority}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Bật quy tắc", Modifier.weight(1f))
                            Switch(rule.enabled, { onRuleEnabledChange(rule.id, it) })
                        }
                        ReferenceActionButton(
                            "SỬA QUY TẮC",
                            { selectedRuleId = null; editRuleId = rule.id },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { onDeleteRule(rule.id); selectedRuleId = null }) { Text("XÓA") } },
                dismissButton = { TextButton(onClick = { selectedRuleId = null; showRules = true }) { Text("DANH SÁCH") } },
            )
        }
    }

    editRuleId?.let { id ->
        val existing = state.vietPhraseRules.firstOrNull { it.id == id }
        if (existing != null) {
            var source by remember(id) { mutableStateOf(existing.source) }
            var target by remember(id) { mutableStateOf(existing.target) }
            var priorityText by remember(id) { mutableStateOf(existing.priority.toString()) }
            var kind by remember(id) {
                mutableStateOf(runCatching { VietPhraseDictionaryKind.valueOf(existing.kind) }.getOrDefault(VietPhraseDictionaryKind.VIET_PHRASE))
            }
            var kindExpanded by remember(id) { mutableStateOf(false) }
            var storyOnly by remember(id) { mutableStateOf(existing.scope == VietPhraseScope.STORY.name) }
            var storyId by remember(id) { mutableStateOf(existing.storyId) }
            var ignoreCase by remember(id) { mutableStateOf(existing.ignoreCase) }
            AlertDialog(
                onDismissRequest = { editRuleId = null },
                title = { Text("SỬA QUY TẮC") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        OutlinedTextField(source, { source = it.take(2_000) }, label = { Text("Cụm nguồn") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(target, { target = it.take(4_000) }, label = { Text("Cụm thay thế") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        OutlinedTextField(
                            priorityText,
                            { value -> priorityText = value.filterIndexed { index, char -> char.isDigit() || (char == '-' && index == 0) }.take(4) },
                            label = { Text("Ưu tiên (-999 đến 999)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Button(onClick = { kindExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(kind.fileName) }
                            DropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                                orderedKinds.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.fileName) }, onClick = { kind = option; kindExpanded = false })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Bỏ qua hoa thường", Modifier.weight(1f))
                            Switch(ignoreCase, { ignoreCase = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Chỉ truyện này", Modifier.weight(1f))
                            Switch(storyOnly, { storyOnly = it })
                        }
                        if (storyOnly) {
                            OutlinedTextField(storyId, { storyId = it.take(300) }, label = { Text("Story ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = source.isNotBlank() && target.isNotBlank() && (!storyOnly || storyId.isNotBlank()),
                        onClick = {
                            onUpdateRule(
                                id,
                                source,
                                target,
                                priorityText.toIntOrNull()?.coerceIn(-999, 999) ?: 0,
                                kind,
                                if (storyOnly) VietPhraseScope.STORY else VietPhraseScope.GLOBAL,
                                storyId.takeIf { storyOnly && it.isNotBlank() },
                                ignoreCase,
                            )
                            editRuleId = null
                        },
                    ) { Text("LƯU") }
                },
                dismissButton = { TextButton(onClick = { editRuleId = null; showRules = true }) { Text("HỦY") } },
            )
        }
    }

    if (showSnapshots) {
        AlertDialog(
            onDismissRequest = { showSnapshots = false },
            title = { Text("KHÔI PHỤC") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    latestSnapshots.take(30).forEach { snapshot ->
                        ReferenceActionButton(
                            text = "${snapshot.label}\n${snapshot.ruleCount} quy tắc • ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(snapshot.createdAt))}",
                            onClick = { showSnapshots = false; rollbackSnapshotId = snapshot.id },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSnapshots = false }) { Text("ĐÓNG") } },
        )
    }

    rollbackSnapshotId?.let { snapshotId ->
        AlertDialog(
            onDismissRequest = { rollbackSnapshotId = null },
            title = { Text("KHÔI PHỤC VIETPHRASE") },
            text = { Text("Thay dữ liệu hiện tại bằng bản đã chọn?") },
            confirmButton = { TextButton(onClick = { onRollback(snapshotId); rollbackSnapshotId = null }) { Text("KHÔI PHỤC") } },
            dismissButton = { TextButton(onClick = { rollbackSnapshotId = null }) { Text("HỦY") } },
        )
    }

    if (showSuggestions) {
        AlertDialog(
            onDismissRequest = { showSuggestions = false },
            title = { Text("GỢI Ý AI") },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    pendingSuggestions.take(50).forEach { suggestion ->
                        ReferenceActionButton(
                            text = "${suggestion.source} → ${suggestion.proposedTarget}",
                            onClick = { showSuggestions = false; selectedSuggestionId = suggestion.id },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSuggestions = false }) { Text("ĐÓNG") } },
        )
    }

    selectedSuggestionId?.let { id ->
        val suggestion = state.vietPhraseSuggestions.firstOrNull { it.id == id && it.status == "PENDING" }
        if (suggestion != null) {
            var editedTarget by remember(id) { mutableStateOf(suggestion.editedTarget.ifBlank { suggestion.proposedTarget }) }
            AlertDialog(
                onDismissRequest = { selectedSuggestionId = null },
                title = { Text(suggestion.source) },
                text = {
                    Column {
                        OutlinedTextField(editedTarget, { editedTarget = it.take(4_000) }, label = { Text("Cụm thay thế") }, modifier = Modifier.fillMaxWidth())
                        if (suggestion.reason.isNotBlank()) Text(suggestion.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = { TextButton(enabled = editedTarget.isNotBlank(), onClick = { onAcceptSuggestion(id, editedTarget); selectedSuggestionId = null }) { Text("CHẤP NHẬN") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { onRejectSuggestion(id); selectedSuggestionId = null }) { Text("TỪ CHỐI") }
                        TextButton(onClick = { selectedSuggestionId = null; showSuggestions = true }) { Text("DANH SÁCH") }
                    }
                },
            )
        }
    }

    state.pendingVietPhraseImport?.let { preview ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("XÁC NHẬN NHẬP") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("${preview.sourceName}\n${preview.incomingCount} mục • trùng ${preview.duplicateCount} • cảnh báo ${preview.warningCount} • lỗi ${preview.errorCount}")
                    (preview.warnings + preview.plan.conflicts.map { "${it.severity}: ${it.message}" })
                        .distinct()
                        .take(20)
                        .forEach { warning -> Text("• $warning", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            },
            confirmButton = { TextButton(enabled = preview.errorCount == 0, onClick = onConfirmImport) { Text("ÁP DỤNG") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("HỦY") } },
        )
    }

    deleteKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { deleteKind = null },
            title = { Text("XÓA ${kind.fileName}") },
            text = { Text("Xóa dữ liệu của file này?") },
            confirmButton = { TextButton(onClick = { onDeleteDictionary(kind); deleteKind = null }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { deleteKind = null }) { Text("HỦY") } },
        )
    }
    if (clearAllConfirm) {
        AlertDialog(
            onDismissRequest = { clearAllConfirm = false },
            title = { Text("XÓA TOÀN BỘ VIETPHRASE") },
            text = { Text("Xóa tất cả dữ liệu VietPhrase?") },
            confirmButton = { TextButton(onClick = { onClearAll(); clearAllConfirm = false }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { clearAllConfirm = false }) { Text("HỦY") } },
        )
    }
    if (downloadConfirm) {
        AlertDialog(
            onDismissRequest = { downloadConfirm = false },
            title = { Text("TẢI TỪ MẠNG") },
            text = { Text("Tải và cài bộ dữ liệu VietPhrase từ mạng?") },
            confirmButton = { TextButton(onClick = { onDownloadRecommended(); downloadConfirm = false }) { Text("TẢI") } },
            dismissButton = { TextButton(onClick = { downloadConfirm = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun AiReferenceSettingsDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
    onSave: (AiOnlineSettings, String?, String?) -> Unit,
    onRefreshModels: (AiProvider, String, String) -> Unit,
) {
    var enabled by remember(state.aiOnline.enabled) { mutableStateOf(state.aiOnline.enabled) }
    var provider by remember(state.aiOnline.provider) { mutableStateOf(state.aiOnline.provider) }
    var endpoint by remember(state.aiOnline.endpoint) { mutableStateOf(state.aiOnline.endpoint) }
    var geminiModel by remember(state.aiOnline.geminiModel) { mutableStateOf(state.aiOnline.geminiModel) }
    var proxyModel by remember(state.aiOnline.openAiModel) { mutableStateOf(state.aiOnline.openAiModel) }
    var mode by remember(state.aiOnline.mode) { mutableStateOf(if (state.aiOnline.mode == "improve") "improve" else "translate") }
    var translatePrompt by remember(state.aiOnline.translationPrompt) { mutableStateOf(state.aiOnline.translationPrompt) }
    var improvePrompt by remember(state.aiOnline.improvePrompt) { mutableStateOf(state.aiOnline.improvePrompt) }
    var timeoutText by remember(state.aiOnline.timeoutMillis) { mutableStateOf(state.aiOnline.timeoutMillis.toString()) }
    var temperatureText by remember(state.aiOnline.temperature) { mutableStateOf(state.aiOnline.temperature.toString()) }
    var geminiKey by remember { mutableStateOf("") }
    var proxyKey by remember { mutableStateOf("") }
    var geminiKeyTouched by remember { mutableStateOf(false) }
    var proxyKeyTouched by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var modelPickerRequested by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }

    LaunchedEffect(
        state.aiModelDiscoveryBusy,
        state.aiAvailableModels,
        state.aiModelDiscoveryStatus,
        modelPickerRequested,
    ) {
        if (modelPickerRequested && !state.aiModelDiscoveryBusy) {
            modelPickerRequested = false
            if (state.aiAvailableModels.isNotEmpty()) {
                validationMessage = ""
                modelPickerOpen = true
            } else if (state.aiModelDiscoveryStatus.isNotBlank()) {
                validationMessage = state.aiModelDiscoveryStatus
            }
        }
    }

    fun requestModels() {
        validationMessage = ""
        val key = if (provider == AiProvider.GEMINI) geminiKey else proxyKey
        if (provider == AiProvider.OPENAI_COMPATIBLE && !endpoint.trim().startsWith("https://", ignoreCase = true)) {
            validationMessage = "URL OpenAI-compatible phải dùng HTTPS."
            return
        }
        modelPickerRequested = true
        onRefreshModels(provider, endpoint, key)
    }

    fun saveSettings() {
        val timeout = timeoutText.trim().toIntOrNull()
        val temperature = temperatureText.trim().replace(',', '.').toFloatOrNull()
        validationMessage = when {
            provider == AiProvider.OPENAI_COMPATIBLE && !endpoint.trim().startsWith("https://", ignoreCase = true) -> "URL OpenAI-compatible phải dùng HTTPS."
            !translatePrompt.contains("{{CHAPTER_TEXT}}") -> "Lời nhắc dịch phải giữ biến {{CHAPTER_TEXT}}."
            !improvePrompt.contains("{{SOURCE_TEXT}}") || !improvePrompt.contains("{{VIETPHRASE_TEXT}}") -> "Lời nhắc cải thiện phải giữ {{SOURCE_TEXT}} và {{VIETPHRASE_TEXT}}."
            timeout == null || timeout < 10_000 -> "Timeout AI phải từ 10000 ms trở lên."
            temperature == null || temperature !in 0f..2f -> "Nhiệt độ AI phải trong khoảng 0.0 - 2.0."
            geminiKeyTouched && geminiKey.isNotBlank() && geminiKey.length < 8 -> "Gemini API Key không hợp lệ."
            proxyKeyTouched && proxyKey.isNotBlank() && proxyKey.length < 8 -> "OpenAI-compatible API Key không hợp lệ."
            else -> ""
        }
        if (validationMessage.isNotEmpty()) return
        val resolvedGemini = geminiModel.trim().ifBlank { "gemini-3.6-flash" }
        val resolvedProxy = proxyModel.trim()
        onSave(
            state.aiOnline.copy(
                enabled = enabled,
                consentGranted = enabled,
                provider = provider,
                endpoint = endpoint.trim().ifBlank { "https://openrouter.ai/api/v1/chat/completions" },
                geminiModel = resolvedGemini,
                openAiModel = resolvedProxy,
                model = if (provider == AiProvider.GEMINI) resolvedGemini else resolvedProxy,
                mode = if (mode == "improve") "improve" else "translate",
                translationPrompt = translatePrompt,
                improvePrompt = improvePrompt,
                timeoutMillis = timeout!!,
                temperature = temperature!!,
            ),
            geminiKey.takeIf { geminiKeyTouched },
            proxyKey.takeIf { proxyKeyTouched },
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("THIẾT LẬP AI") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nút AI trong màn hình đọc", Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }

                Text("Nhà cung cấp AI", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (provider == AiProvider.GEMINI) "Google Gemini" else "OpenAI-compatible / Proxy")
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Google Gemini") },
                            onClick = { provider = AiProvider.GEMINI; providerMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("OpenAI-compatible / Proxy") },
                            onClick = { provider = AiProvider.OPENAI_COMPATIBLE; providerMenu = false },
                        )
                    }
                }

                if (provider == AiProvider.GEMINI) {
                    Text("Gemini API Key", modifier = Modifier.padding(top = 10.dp))
                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKeyTouched = true; geminiKey = it.take(4096) },
                        placeholder = { Text(if (state.aiHasGeminiApiKey) "Đã lưu Gemini API Key" else "Nhập Gemini API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Model Gemini", Modifier.weight(1f))
                        Button(onClick = ::requestModels, enabled = !state.aiModelDiscoveryBusy) {
                            Text(if (state.aiModelDiscoveryBusy) "ĐANG TẢI" else "TẢI DS")
                        }
                    }
                    OutlinedTextField(
                        value = geminiModel,
                        onValueChange = { geminiModel = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("OpenAI-compatible URL", modifier = Modifier.padding(top = 10.dp))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.take(500) },
                        placeholder = { Text(".../v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("OpenAI-compatible API Key", modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = proxyKey,
                        onValueChange = { proxyKeyTouched = true; proxyKey = it.take(4096) },
                        placeholder = { Text(if (state.aiHasOpenAiApiKey) "Đã lưu API Key" else "Bearer key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Model OpenAI-compatible", Modifier.weight(1f))
                        Button(onClick = ::requestModels, enabled = !state.aiModelDiscoveryBusy) {
                            Text(if (state.aiModelDiscoveryBusy) "ĐANG TẢI" else "TẢI DS")
                        }
                    }
                    OutlinedTextField(
                        value = proxyModel,
                        onValueChange = { proxyModel = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text("Chế độ xử lý mặc định", modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { modeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (mode == "improve") "Cải thiện bản VietPhrase" else "Dịch chương gốc")
                    }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DropdownMenuItem(text = { Text("Dịch chương gốc") }, onClick = { mode = "translate"; modeMenu = false })
                        DropdownMenuItem(text = { Text("Cải thiện bản VietPhrase") }, onClick = { mode = "improve"; modeMenu = false })
                    }
                }

                Text("Lời nhắc mặc định: Dịch chương gốc", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                Text("Biến: {{CHAPTER_TITLE}}, {{CHAPTER_TEXT}}. Phải giữ {{CHAPTER_TEXT}} để AI nhận nội dung chương.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = translatePrompt,
                    onValueChange = { translatePrompt = it },
                    minLines = 9,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )

                Text("Lời nhắc mặc định: Cải thiện VietPhrase", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                Text("Biến: {{SOURCE_TITLE}}, {{SOURCE_TEXT}}, {{VIETPHRASE_TITLE}}, {{VIETPHRASE_TEXT}}. Hai biến nội dung là bắt buộc.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = improvePrompt,
                    onValueChange = { improvePrompt = it },
                    minLines = 11,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )

                Text("Timeout yêu cầu AI (ms)", modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit).take(9) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Nhiệt độ AI (0.0 - 2.0)", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { temperatureText = it.take(8) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (validationMessage.isNotBlank()) {
                    Text(
                        validationMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = ::saveSettings) { Text("LƯU") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("HỦY") } },
    )

    if (modelPickerOpen && state.aiAvailableModels.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { modelPickerOpen = false },
            title = { Text("CHỌN MODEL") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    state.aiAvailableModels.forEach { model ->
                        TextButton(
                            onClick = {
                                if (provider == AiProvider.GEMINI) geminiModel = model else proxyModel = model
                                modelPickerOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(model) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { modelPickerOpen = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun SceneMusicLibraryCard(
    tracks: List<SceneMusicTrackEntity>,
    onSelect: () -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Thư viện nhạc cảnh", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("AI chỉ chọn trong các tệp cục bộ dưới đây. Tệp nhạc không được tải lên; nhà cung cấp chỉ nhận ID, tên và tag.", style = MaterialTheme.typography.bodySmall)
            Button(onSelect, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("THÊM TỆP NHẠC CẢNH") }
            tracks.forEach { track ->
                var title by remember(track.id, track.title) { mutableStateOf(track.title) }
                var tags by remember(track.id, track.tagsCsv) { mutableStateOf(track.tagsCsv) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("Tên tệp nhạc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it.take(500) },
                    label = { Text("Tag, cách nhau bằng dấu phẩy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                )
                Text("Loudness ước tính ${"%.1f".format(track.loudnessLufsEstimate)} LUFS • đã phát ${track.playCount} lần • thứ tự ${track.orderIndex}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(track.enabled, { onEnabledChange(track.id, it) })
                    Button({ onUpdate(track.id, title, tags) }, Modifier.weight(1f).padding(2.dp)) { Text("LƯU TÊN/TAG") }
                    Button({ onDelete(track.id) }, Modifier.weight(1f).padding(2.dp)) { Text("XÓA") }
                }
            }
        }
    }
}

@Composable
private fun FollowingSettingsCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, onCheckNow: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Theo dõi chương mới", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tự kiểm tra khoảng 12 giờ một lần", Modifier.weight(1f))
                Switch(enabled, onEnabledChange)
            }
            Button(onCheckNow, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("KIỂM TRA NGAY") }
        }
    }
}

@Composable
private fun StorageCard(
    state: MainUiState,
    onCacheLimitChange: (Int) -> Unit,
    onTrimCache: () -> Unit,
    onClearCache: () -> Unit,
) {
    val usage = state.storageUsage
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Dung lượng ngoại tuyến", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Đã tải: ${usage.downloadedChapters} chương • ${formatBytes(usage.downloadedBytes)}")
            Text("Bộ nhớ đệm: ${usage.cachedChapters} chương • ${formatBytes(usage.cachedBytes)}")
            Text("Giới hạn cache: ${state.readerCacheLimitMiB} MiB", modifier = Modifier.padding(top = 8.dp))
            SettingsRepository.CACHE_LIMIT_OPTIONS_MIB.chunked(3).forEach { options ->
                Row(Modifier.fillMaxWidth()) {
                    options.forEach { option ->
                        Button(
                            onClick = { onCacheLimitChange(option) },
                            modifier = Modifier.weight(1f).padding(1.dp),
                        ) {
                            Text((if (option == state.readerCacheLimitMiB) "✓ " else "") + "$option MiB")
                        }
                    }
                    repeat(3 - options.size) {
                        Column(Modifier.weight(1f).padding(1.dp)) { }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(onTrimCache, Modifier.weight(1f).padding(2.dp), enabled = usage.cachedChapters > 0) {
                    Text("DỌN THEO HẠN MỨC")
                }
                Button(onClearCache, Modifier.weight(1f).padding(2.dp), enabled = usage.cachedChapters > 0) {
                    Text("XÓA TOÀN BỘ CACHE")
                }
            }
        }
    }
}

@Composable
private fun AudioExportCard(
    state: MainUiState,
    onCancel: (String) -> Unit,
    onResume: (String) -> Unit,
    onOpen: (AudioExportJobEntity) -> Unit,
) {
    val jobs = state.audioExports.take(8)
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Xuất sách nói WAV / M4A / MP3", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (jobs.isEmpty()) {
                Text("Chưa có tác vụ xuất. Mở truyện hoặc chương rồi chọn WAV, M4A hoặc MP3.")
            }
            jobs.forEach { job ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                val stateLabel = when (job.state) {
                    DownloadState.QUEUED.name -> "Đang chờ"
                    DownloadState.RUNNING.name -> "Đang xuất"
                    DownloadState.COMPLETED.name -> "Hoàn tất"
                    DownloadState.CANCELLED.name -> "Đã hủy"
                    else -> "Thất bại"
                }
                Text("${job.storyTitle} • ${job.outputFormat}", fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(if (job.packaging == "ONE_FILE_PER_CHAPTER") "Mỗi chương một tệp" else "Một tệp")
                        if (job.includeSceneMusic) append(" • Có nhạc cảnh")
                        if (job.chapterMarkers && job.outputFormat == "MP3") append(" • Có chapter marker")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (job.totalSegments > 0) "$stateLabel • ${job.completedSegments}/${job.totalSegments} đoạn • ${job.stage}"
                    else "$stateLabel • ${job.stage}",
                    style = MaterialTheme.typography.bodySmall,
                )
                job.errorMessage?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (job.state == DownloadState.QUEUED.name || job.state == DownloadState.RUNNING.name) {
                    Button(onClick = { onCancel(job.id) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("HỦY XUẤT ${job.outputFormat}")
                    }
                } else if (job.state == DownloadState.COMPLETED.name) {
                    Button(onClick = { onOpen(job) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("MỞ TỆP ${job.outputFormat}")
                    }
                } else {
                    Button(onClick = { onResume(job.id) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("TIẾP TỤC ${job.outputFormat} TỪ CHECKPOINT")
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceCard(
    report: String?,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Hiệu năng thiết bị", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Đo RAM PSS, heap, pin và thời gian dựng/tìm mục lục 10.000 chương. Báo cáo chỉ nằm trên thiết bị.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("CHẠY BENCHMARK NHANH")
            }
            report?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun SourceManagementCard(
    state: MainUiState,
    onInstallSourcePack: () -> Unit,
    onImportSourceTrustRotation: () -> Unit,
    onRefreshSourceRepository: (String) -> Unit,
    onRemoveSourceRepository: (String) -> Unit,
    onPrepareRepositorySourceInstall: (String, String) -> Unit,
    onConfirmSourcePackInstall: () -> Unit,
    onCancelSourcePackInstall: () -> Unit,
    onSourcePackEnabledChange: (String, Boolean) -> Unit,
    onRollbackSourcePack: (String) -> Unit,
    onEnrollSourceTrustKey: (String, String, String, String) -> Unit,
    onRevokeSourceTrustKey: (String) -> Unit,
    onInspectSourceSelector: (String, String, String) -> Unit,
    onExportSourceDiagnostics: () -> Unit,
    onClearSourceDiagnostics: () -> Unit,
    onCheckSource: (String) -> Unit,
    onCheckAllSources: () -> Unit,
    onOpenLogin: (String) -> Unit,
    onOpenDiagnosticBrowser: (String) -> Unit,
    onClearSession: (String) -> Unit,
) {
    var repositoryUrl by remember { mutableStateOf("") }
    var trustKeyId by remember { mutableStateOf("") }
    var trustAlgorithm by remember { mutableStateOf("ECDSA_P256_SHA256") }
    var trustPublicKey by remember { mutableStateOf("") }
    var trustFingerprint by remember { mutableStateOf("") }
    var selectorBaseUrl by remember { mutableStateOf("https://example.invalid/") }
    var selector by remember { mutableStateOf("") }
    var selectorHtml by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Nguồn truyện", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Health check kiểm tra lần lượt danh sách, chi tiết, mục lục và nội dung. Phiên đăng nhập được lưu mã hóa cục bộ.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            Text(
                "Source Platform 2: ${state.sourcePacks.size} gói đã cài • ${state.sourceDiagnosticCount} sự kiện chẩn đoán trong bộ đệm.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text("Repository nguồn đã ký", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Chỉ index có chữ ký hợp lệ mới được lưu. Gói tải về tiếp tục phải khớp SHA-256, chữ ký SourcePack và self-test.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = repositoryUrl,
                onValueChange = { repositoryUrl = it.take(4096) },
                label = { Text("URL kho") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Button(
                onClick = { onRefreshSourceRepository(repositoryUrl) },
                enabled = !state.sourceRepositoryRefreshing && repositoryUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(if (state.sourceRepositoryRefreshing) "ĐANG XÁC MINH…" else "THÊM / LÀM MỚI REPOSITORY") }
            state.sourceRepositories.forEach { repository ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(repository.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${repository.id} • ${repository.packageCount} gói • ký bởi ${repository.signerKeyId}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(repository.url, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { onRemoveSourceRepository(repository.id) },
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                ) { Text("GỠ REPOSITORY") }
                state.sourceRepositoryPackages.filter { it.repositoryId == repository.id }.forEach { item ->
                    Card(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${item.name} ${item.version}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${item.sourceId} • ${item.status}${item.installedVersion?.let { " • đang cài $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            item.description.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            item.changelog.takeIf(String::isNotBlank)?.let {
                                Text("Thay đổi: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = { onPrepareRepositorySourceInstall(item.repositoryId, item.sourceId) },
                                enabled = item.canInstall && !state.sourceRepositoryRefreshing,
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            ) {
                                Text(if (item.status == "UPDATE_AVAILABLE") "TẢI BẢN CẬP NHẬT" else "TẢI & KIỂM TRA GÓI")
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Khóa nhà phát hành", fontWeight = FontWeight.SemiBold)
            Text(
                "Khóa bên thứ ba chỉ được thêm khi fingerprint nhập tay khớp tuyệt đối. Xoay khóa phải có chữ ký hợp lệ của khóa cũ.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.sourceTrustKeys.forEach { key ->
                Text(
                    "${if (key.builtin) "TÍCH HỢP" else "NGƯỜI DÙNG"} • ${key.keyId} • ${key.algorithm}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Fingerprint: ${key.fingerprint}", style = MaterialTheme.typography.bodySmall)
                if (!key.builtin) {
                    Button(
                        onClick = { onRevokeSourceTrustKey(key.keyId) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 3.dp),
                    ) { Text("THU HỒI KHÓA NÀY") }
                }
            }
            OutlinedTextField(
                value = trustKeyId,
                onValueChange = { trustKeyId = it.take(160) },
                label = { Text("Key ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = trustAlgorithm,
                onValueChange = { trustAlgorithm = it.take(64) },
                label = { Text("Thuật toán: ECDSA_P256_SHA256 hoặc ED25519") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = trustPublicKey,
                onValueChange = { trustPublicKey = it.take(16_384) },
                label = { Text("Public key X.509 Base64") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = trustFingerprint,
                onValueChange = { trustFingerprint = it.take(256) },
                label = { Text("Fingerprint xác nhận ngoài kênh") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Button(
                onClick = { onEnrollSourceTrustKey(trustKeyId, trustAlgorithm, trustPublicKey, trustFingerprint) },
                enabled = trustKeyId.isNotBlank() && trustPublicKey.isNotBlank() && trustFingerprint.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("THÊM KHÓA") }
            Button(
                onClick = onImportSourceTrustRotation,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("NHẬP XOAY KHÓA") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Button(onInstallSourcePack, Modifier.fillMaxWidth()) { Text("CÀI .NTSOURCE / VBOOK / LUA API 2") }
            state.pendingSourceInstall?.let { preview ->
                Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Chờ phê duyệt: ${preview.name} ${preview.version}", fontWeight = FontWeight.SemiBold)
                        Text("ID: ${preview.sourceId}", style = MaterialTheme.typography.bodySmall)
                        Text("Khóa ký: ${preview.signerKeyId}", style = MaterialTheme.typography.bodySmall)
                        Text("Self-test: ${preview.fixtureCount} fixture đã đạt", style = MaterialTheme.typography.bodySmall)
                        preview.permissionSummary.forEach { item ->
                            Text("• $item", style = MaterialTheme.typography.bodySmall)
                        }
                        state.pendingSourceInstallWarnings.forEach { warning ->
                            Text("⚠ $warning", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Button(onConfirmSourcePackInstall, Modifier.weight(1f).padding(2.dp)) { Text("CHẤP NHẬN & CÀI") }
                            Button(onCancelSourcePackInstall, Modifier.weight(1f).padding(2.dp)) { Text("HỦY") }
                        }
                    }
                }
            }
            state.sourcePacks.forEach { pack ->
                HorizontalDivider(Modifier.padding(vertical = 7.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${pack.id} • ${pack.version} • ${pack.runtimeMode}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Ký bởi ${pack.signerKeyId}; giữ ${pack.installedVersions.size} phiên bản.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Bình luận: ${pack.commentCapability}${if (pack.commentFixtureCount > 0) " • ${pack.commentFixtureCount} fixture" else " • chưa có fixture"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = pack.enabled,
                        onCheckedChange = { onSourcePackEnabledChange(pack.id, it) },
                    )
                }
                Button(
                    onClick = { onRollbackSourcePack(pack.id) },
                    enabled = pack.canRollback,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                ) { Text("ROLLBACK PHIÊN BẢN NGUỒN") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(onExportSourceDiagnostics, Modifier.weight(1f).padding(2.dp)) { Text("XUẤT CHẨN ĐOÁN") }
                Button(onClearSourceDiagnostics, Modifier.weight(1f).padding(2.dp)) { Text("XÓA NHẬT KÝ") }
            }
            state.sourceDiagnostics.take(8).forEach { event ->
                val duration = event.durationMs?.let { " • ${it} ms" }.orEmpty()
                Text(
                    "${event.severity} ${event.category}/${event.name}$duration",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (event.severity == "ERROR") FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    "${event.sourceId} • trace ${event.traceId.take(8)}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Trace explorer", fontWeight = FontWeight.SemiBold)
            state.sourceTraces.take(12).forEach { trace ->
                Text(
                    "${if (trace.failed) "LỖI" else "OK"} • ${trace.sourceId} • ${trace.eventCount} sự kiện • ${trace.endedAtEpochMs - trace.startedAtEpochMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (trace.failed) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text("trace ${trace.traceId}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Selector inspector", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text(
                "HTML được bỏ script, style, comment và thuộc tính nhạy cảm trước khi kiểm tra selector.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = selectorBaseUrl,
                onValueChange = { selectorBaseUrl = it.take(4096) },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = selector,
                onValueChange = { selector = it.take(512) },
                label = { Text("CSS selector") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = selectorHtml,
                onValueChange = { selectorHtml = it.take(2 * 1024 * 1024) },
                label = { Text("HTML snapshot") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Button(
                onClick = { onInspectSourceSelector(selectorHtml, selector, selectorBaseUrl) },
                enabled = selector.isNotBlank() && selectorHtml.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("KIỂM TRA SELECTOR") }
            state.sourceSelectorInspection?.let { inspection ->
                Text(
                    "${inspection.selector}: ${inspection.matchCount} phần tử khớp",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                inspection.samples.forEach { sample -> Text("• $sample", style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Button(onCheckAllSources, Modifier.fillMaxWidth()) { Text("KIỂM TRA TẤT CẢ NGUỒN") }
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
                Text("Bình luận: ${source.commentCapability.label}", style = MaterialTheme.typography.bodySmall)
                source.privacyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button(
                        onClick = { onCheckSource(source.id) },
                        enabled = !checking && source.health != SourceHealth.NOT_PORTED,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text(if (checking) "ĐANG KIỂM TRA" else "KIỂM TRA") }
                    if (source.loginUrl != null) {
                        Button(
                            onClick = { onOpenLogin(source.id) },
                            modifier = Modifier.weight(1f).padding(2.dp),
                        ) { Text(if (sessionActive) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP") }
                    }
                }
                if (source.allowedHosts.isNotEmpty() && source.baseUrl.startsWith("https://")) {
                    Button(
                        onClick = { onOpenDiagnosticBrowser(source.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    ) { Text("TRÌNH DUYỆT CHẨN ĐOÁN ĐĂNG NHẬP") }
                }
                if (source.loginUrl != null && sessionActive) {
                    Button(
                        onClick = { onClearSession(source.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    ) { Text("XÓA PHIÊN ĐÃ LƯU") }
                }
            }
        }
    }
}

@Composable private fun SettingsCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    } }
}

@Composable
private fun TransferCard(
    state: MainUiState,
    onComponentChange: (BackupComponent, Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Sao lưu và khôi phục theo thành phần", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            BackupComponent.entries.forEach { component ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(component.label, fontWeight = FontWeight.SemiBold)
                        Text(backupComponentDescription(component), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = component in state.backupComponents,
                        onCheckedChange = { onComponentChange(component, it) },
                    )
                }
            }
            Text("Đã chọn ${state.backupComponents.size}/${BackupComponent.entries.size} nhóm.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(onExportBackup, Modifier.weight(1f).padding(2.dp), enabled = state.backupComponents.isNotEmpty()) { Text("TẠO BẢN SAO") }
                Button(onRestoreBackup, Modifier.weight(1f).padding(2.dp), enabled = state.backupComponents.isNotEmpty()) { Text("KHÔI PHỤC") }
            }
        }
    }
}

private fun backupComponentDescription(component: BackupComponent): String = when (component) {
    BackupComponent.SETTINGS -> "Thiết lập đọc, TTS, AI chung và tự động hóa."
    BackupComponent.LIBRARY -> "Truyện, chương, theo dõi và trạng thái tải."
    BackupComponent.READING -> "Lịch sử, tiến độ, đánh dấu, ghi chú và phát âm."
    BackupComponent.AI_VOICE -> "AI theo truyện, vai giọng, phân vai và bản biến đổi chương."
    BackupComponent.VIETPHRASE -> "Quy tắc, bộ từ điển, snapshot và suggestion AIReplace."
    BackupComponent.SOURCES_EXTENSIONS -> "SourcePack, extension Lua/vBook, repository, khóa tin cậy và bộ nhớ nguồn không chứa credential."
    BackupComponent.SCENE_MUSIC -> "Danh mục, cue và tệp nhạc cảnh vật lý có thể chuyển sang thiết bị khác."
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_VISIBLE_VOICES = 12
private const val MAX_VISIBLE_PRONUNCIATIONS = 30
