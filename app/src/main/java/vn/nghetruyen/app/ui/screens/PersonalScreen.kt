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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import vn.nghetruyen.app.sourceplatform.DiagnosticHumanFormatter
import vn.nghetruyen.app.transfer.BackupComponent
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceText
import vn.nghetruyen.app.ui.components.ScreenHeading
import java.text.Normalizer
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
    onDiagnosticScreenChanged: (String) -> Unit = {},
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
    onInstallRepositorySource: (String, String) -> Unit,
    onCancelSourcePackInstall: () -> Unit,
    onSourcePackEnabledChange: (String, Boolean) -> Unit,
    onRollbackSourcePack: (String) -> Unit,
    onUpdateSourcePack: (String) -> Unit,
    onExportSourcePack: (String, String) -> Unit,
    onRemoveSourcePack: (String) -> Unit,
    onCheckSourcePack: (String) -> Unit,
    onSaveSourceConfig: (String, Map<String, String>) -> Unit,
    onResetSourceConfig: (String) -> Unit,
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
        onDiagnosticScreenChanged(personalPage)
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
        )
        "extensions_installed" -> PersonalSubPage("TIỆN ÍCH ĐÃ CÀI") {
            InstalledSourcesSection(
                state = state,
                onEnabledChange = onSourcePackEnabledChange,
                onRollback = onRollbackSourcePack,
                onUpdate = onUpdateSourcePack,
                onExport = onExportSourcePack,
                onRemove = onRemoveSourcePack,
                onCheck = onCheckSourcePack,
                onSaveConfig = onSaveSourceConfig,
                onResetConfig = onResetSourceConfig,
                onLogin = onOpenSourceLogin,
                onExportDiagnostics = onExportSourceDiagnostics,
                onClearDiagnostics = onClearSourceDiagnostics,
            )
        }
        "extensions_repositories" -> PersonalSubPage("KHO TIỆN ÍCH") {
            SourceRepositorySection(
                state = state,
                onRefresh = onRefreshSourceRepository,
                onRemove = onRemoveSourceRepository,
                onPrepareInstall = onPrepareRepositorySourceInstall,
                onInstall = onInstallRepositorySource,
                onAddRepository = { showAddRepositoryDialog = true },
            )
        }
        "extensions_add" -> PersonalSubPage("THÊM KHO / LIÊN KẾT") {
  SourceAddLinkSection(
      state = state,
      repositoryUrl = repositoryUrl,
      onRepositoryUrlChange = { repositoryUrl = it },
      onRefresh = onRefreshSourceRepository,
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
  title = { Text("THÊM KHO / LIÊN KẾT") },
  text = {
      OutlinedTextField(
          value = repositoryUrl,
          onValueChange = { repositoryUrl = it.take(4096) },
          label = { Text("Liên kết") },
          placeholder = { Text("repository.json / plugin.json / ZIP") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
  },
  confirmButton = {
      TextButton(
          enabled = repositoryUrl.trim().startsWith("https://"),
          onClick = {
              onRefreshSourceRepository(repositoryUrl.trim())
              showAddRepositoryDialog = false
          },
      ) { Text("THÊM") }
  },
  dismissButton = { TextButton(onClick = { showAddRepositoryDialog = false }) { Text("HỦY") } },
        )
    }

    if (state.pendingSourceInstall != null) {
        SourceInstallDiagnosticDialog(state, onCancelSourcePackInstall)
    }
    if (state.sourceInstallOutcome != null) {
        SourceInstallOutcomeDialog(state, onCancelSourcePackInstall)
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
            "basic" -> "Gỡ lỗi theo màn hình"
            "advanced", "advanced_crash" -> "Gỡ lỗi nối liền"
            else -> "Tắt"
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Button(onClick = { diagnosticsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$diagnosticsLabel ▼")
            }
            DropdownMenu(expanded = diagnosticsExpanded, onDismissRequest = { diagnosticsExpanded = false }) {
                listOf(
                    "off" to "Tắt",
                    "basic" to "Gỡ lỗi theo màn hình",
                    "advanced" to "Gỡ lỗi nối liền",
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
        Text(
            when (diagnosticsMode) {
                "basic" -> "Chỉ giữ nhật ký của màn hình/ngữ cảnh hiện tại. Chuyển màn hình sẽ bắt đầu một nhật ký mới."
                "advanced", "advanced_crash" -> "Nối liền qua màn hình và lần mở ứng dụng. Chỉ nút XÓA mới xóa lịch sử đã lưu."
                else -> "Tắt hoàn toàn: không ghi event, trace hoặc evidence ngầm."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
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
private fun SourceInstallDiagnosticDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
) {
    val preview = state.pendingSourceInstall ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CHẨN ĐOÁN TIỆN ÍCH") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("${preview.name} ${preview.version}", fontWeight = FontWeight.SemiBold)
                Text("Nguồn: ${preview.sourceId}", style = MaterialTheme.typography.bodySmall)
                Text("Chữ ký: ${preview.signerKeyId}", style = MaterialTheme.typography.bodySmall)
                Text("Self-test: ${preview.fixtureCount} kiểm tra đạt", style = MaterialTheme.typography.bodySmall)
                preview.permissionSummary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                state.pendingSourceInstallWarnings.forEach { warning ->
                    Text("Cảnh báo: $warning", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (state.pendingSourceInstallWarnings.isEmpty()) "Kết luận: Có thể cài đặt."
                    else "Kết luận: Có thể cài đặt, nhưng có cảnh báo ở trên.",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun SourceInstallOutcomeDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
) {
    val outcome = state.sourceInstallOutcome ?: return
    val versionSuffix = outcome.version.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (outcome.success) "CÀI ĐẶT THÀNH CÔNG" else "CÀI ĐẶT THẤT BẠI") },
        text = {
            Text(
                if (outcome.success) {
                    "Đã cài ${outcome.name}$versionSuffix và kích hoạt tiện ích."
                } else {
                    "Không thể cài ${outcome.name}$versionSuffix.\n\nNguyên nhân: ${outcome.reason.ifBlank { "Không xác định được nguyên nhân." }}"
                },
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

private fun extensionSearchNormalize(value: String): String = Normalizer
    .normalize(value.lowercase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun extensionSearchScore(value: String, query: String): Int? {
    val normalizedQuery = extensionSearchNormalize(query)
    if (normalizedQuery.isBlank()) return 0

    val searchable = extensionSearchNormalize(value)
    if (searchable.isBlank()) return null
    val words = searchable.split(' ').filter(String::isNotBlank)
    val compact = words.joinToString("")
    val acronym = words.mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    val tokens = normalizedQuery.split(' ').filter(String::isNotBlank)

    var score = 0
    tokens.forEach { token ->
        val tokenScore = extensionTokenSearchScore(token, searchable, words, compact, acronym) ?: return null
        score += tokenScore
    }

    val phraseIndex = searchable.indexOf(normalizedQuery)
    if (phraseIndex >= 0) {
        score -= minOf(40, 20 + normalizedQuery.length)
    }
    return score.coerceAtLeast(0)
}

private fun extensionTokenSearchScore(
    token: String,
    searchable: String,
    words: List<String>,
    compact: String,
    acronym: String,
): Int? {
    val exactWord = words.indexOf(token)
    if (exactWord >= 0) return exactWord

    val prefixWord = words.indexOfFirst { it.startsWith(token) }
    if (prefixWord >= 0) return 10 + prefixWord

    val substring = searchable.indexOf(token)
    if (substring >= 0) return 20 + substring

    if (token.length >= 2) {
        if (acronym.startsWith(token)) return 40 + (acronym.length - token.length)
        extensionSubsequenceScore(token, acronym)?.let { return 50 + it }
    }

    if (token.length >= 3) {
        extensionSubsequenceScore(token, compact)?.let { gaps ->
            if (gaps <= token.length * 2 + 8) return 70 + gaps
        }
    }

    val editLimit = when {
        token.length >= 7 -> 2
        token.length >= 4 -> 1
        else -> 0
    }
    if (editLimit > 0) {
        var bestDistance: Int? = null
        words.forEach { word ->
            if (kotlin.math.abs(word.length - token.length) <= editLimit) {
                extensionEditDistance(token, word, editLimit)?.let { distance ->
                    bestDistance = minOf(bestDistance ?: distance, distance)
                }
            }
        }
        bestDistance?.let { return 100 + it * 10 }
    }

    return null
}

private fun extensionSubsequenceScore(needle: String, haystack: String): Int? {
    if (needle.isEmpty()) return 0
    var needleIndex = 0
    var firstMatch = -1
    var lastMatch = -1
    haystack.forEachIndexed { index, char ->
        if (needleIndex < needle.length && char == needle[needleIndex]) {
            if (firstMatch < 0) firstMatch = index
            lastMatch = index
            needleIndex += 1
        }
    }
    if (needleIndex != needle.length) return null
    return (lastMatch - firstMatch + 1 - needle.length).coerceAtLeast(0) + firstMatch.coerceAtLeast(0)
}

private fun extensionEditDistance(left: String, right: String, limit: Int): Int? {
    if (kotlin.math.abs(left.length - right.length) > limit) return null
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        right.forEachIndexed { rightIndex, rightChar ->
            val insert = current[rightIndex] + 1
            val delete = previous[rightIndex + 1] + 1
            val replace = previous[rightIndex] + if (leftChar == rightChar) 0 else 1
            current[rightIndex + 1] = minOf(insert, delete, replace)
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > limit) return null
        previous = current
    }
    return previous[right.length].takeIf { it <= limit }
}

private fun repositoryUpdatedLabel(epochMs: Long): String = if (epochMs > 0L) {
    "Cập nhật: ${SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date(epochMs))}"
} else {
    "Chưa tải danh mục"
}

@Composable
private fun InstalledSourcesSection(
    state: MainUiState,
    onEnabledChange: (String, Boolean) -> Unit,
    onRollback: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onCheck: (String) -> Unit,
    onSaveConfig: (String, Map<String, String>) -> Unit,
    onResetConfig: (String) -> Unit,
    onLogin: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
) {
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var configurePackId by remember { mutableStateOf<String?>(null) }
    var compatibilityPackId by remember { mutableStateOf<String?>(null) }
    var nativeReportPackId by remember { mutableStateOf<String?>(null) }
    var removePackId by remember { mutableStateOf<String?>(null) }
    var logPackId by remember { mutableStateOf<String?>(null) }
    var installedQuery by remember { mutableStateOf("") }

    @Suppress("UNUSED_VARIABLE")
    val retainedRuntimeActions = listOf(onRollback, onLogin)

    val rankedPacks = state.sourcePacks.mapNotNull { pack ->
        val searchable = listOf(
            pack.name,
            pack.id,
            pack.version,
            pack.ecosystem,
            pack.contentType,
            pack.compatibilityProfile,
            pack.runtimeMode,
        ).joinToString(" ")
        extensionSearchScore(searchable, installedQuery)?.let { it to pack }
    }.sortedBy { it.first }.map { it.second }

    OutlinedTextField(
        value = installedQuery,
        onValueChange = { installedQuery = it.take(240) },
        label = { Text("Tìm tiện ích") },
        placeholder = { Text("Nhập tên hoặc vài ký tự liên quan") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    )

    if (rankedPacks.isEmpty()) {
        Text("Không tìm thấy tiện ích phù hợp.", modifier = Modifier.padding(16.dp))
    } else {
        rankedPacks.forEach { pack ->
            val sourceHealth = state.sources.firstOrNull { it.id == pack.id }?.health
            val label = when {
                sourceHealth == SourceHealth.NOT_PORTED -> "${pack.name} - Không tương thích"
                !pack.enabled -> "${pack.name} - Đã tắt"
                else -> pack.name
            }
            ReferenceActionButton(
                text = label,
                onClick = { selectedPackId = pack.id },
                accessibilityLabel = label,
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 52.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }


    selectedPackId?.let { selectedId ->
        state.sourcePacks.firstOrNull { it.id == selectedId }?.let { pack ->
            AlertDialog(
                onDismissRequest = { selectedPackId = null },
                title = { Text(pack.name) },
                text = {
                    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "Tên: ${pack.name}\nPhiên bản: ${pack.version}\nLoại: ${pack.ecosystem}\nNguồn: ${pack.id}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ReferenceActionButton(
                            text = if (pack.enabled) "TẮT" else "BẬT",
                            onClick = {
                                onEnabledChange(pack.id, !pack.enabled)
                                selectedPackId = null
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        if (pack.configFields.isNotEmpty()) {
                            ReferenceActionButton(
                                text = "CẤU HÌNH",
                                onClick = {
                                    configurePackId = pack.id
                                    selectedPackId = null
                                },
                                normalColor = ReferencePanelBackground,
                                normalContentColor = ReferenceText,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                        if (pack.ecosystem == "VBOOK") {
                            ReferenceActionButton(
                                text = "TƯƠNG THÍCH",
                                onClick = {
                                    compatibilityPackId = pack.id
                                    selectedPackId = null
                                },
                                normalColor = ReferencePanelBackground,
                                normalContentColor = ReferenceText,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        } else if (pack.runtimeMode == "NATIVE_LUA_COMPAT") {
                            ReferenceActionButton(
                                text = "KIỂM TRA NATIVE",
                                onClick = {
                                    nativeReportPackId = pack.id
                                    selectedPackId = null
                                },
                                normalColor = ReferencePanelBackground,
                                normalContentColor = ReferenceText,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                        ReferenceActionButton(
                            text = "KIỂM TRA NGUỒN",
                            onClick = {
                                onCheck(pack.id)
                                selectedPackId = null
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        ReferenceActionButton(
                            text = "NHẬT KÝ",
                            onClick = {
                                logPackId = pack.id
                                selectedPackId = null
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        ReferenceActionButton(
                            text = "CẬP NHẬT",
                            onClick = {
                                onUpdate(pack.id)
                                selectedPackId = null
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        ReferenceActionButton(
                            text = "XUẤT",
                            onClick = {
                                onExport(pack.id, pack.name)
                                selectedPackId = null
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        if (pack.removable) {
                            ReferenceActionButton(
                                text = "XÓA",
                                onClick = {
                                    removePackId = pack.id
                                    selectedPackId = null
                                },
                                normalColor = ReferencePanelBackground,
                                normalContentColor = ReferenceText,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedPackId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    configurePackId?.let { packId ->
        state.sourcePacks.firstOrNull { it.id == packId }?.let { pack ->
            SourcePackConfigDialog(
                pack = pack,
                onSave = { changes -> onSaveConfig(pack.id, changes) },
                onReset = { onResetConfig(pack.id) },
                onDismiss = { configurePackId = null },
            )
        }
    }

    compatibilityPackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        val source = state.sources.firstOrNull { it.id == packId }
        if (pack != null) {
            AlertDialog(
                onDismissRequest = { compatibilityPackId = null },
                title = { Text("TƯƠNG THÍCH VBOOK") },
                text = {
                    Text(
                        buildString {
                            append("Tên: ${pack.name}\n")
                            append("Phiên bản: ${pack.version}\n")
                            append("Hồ sơ: ${pack.compatibilityProfile.ifBlank { "Chưa xác định" }}\n")
                            append("Runtime: ${pack.runtimeMode.ifBlank { "VBOOK_JS_COMPAT" }}\n")
                            append("Loại nội dung: ${pack.contentType.ifBlank { "Không rõ" }}\n")
                            append("Cấu hình: ${if (pack.configFields.isEmpty()) "Không" else "${pack.configFields.size} mục"}\n")
                            append("Đăng nhập: ${if (pack.loginAvailable) "Có" else "Không"}\n")
                            append("Trạng thái nguồn: ${source?.health?.name ?: "Chưa xác định"}")
                        },
                        modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { compatibilityPackId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    nativeReportPackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        val source = state.sources.firstOrNull { it.id == packId }
        if (pack != null) {
            AlertDialog(
                onDismissRequest = { nativeReportPackId = null },
                title = { Text("KIỂM TRA NATIVE") },
                text = {
                    Text(
                        buildString {
                            append("Tên: ${pack.name}\n")
                            append("Phiên bản: ${pack.version}\n")
                            append("Runtime: ${pack.runtimeMode.ifBlank { "NATIVE" }}\n")
                            append("Source ID: ${pack.id}\n")
                            append("Trạng thái nguồn: ${source?.health?.name ?: "Chưa xác định"}")
                        },
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { nativeReportPackId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    logPackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        if (pack != null) {
            val clipboard = LocalClipboardManager.current
            val events = state.sourceDiagnostics.filter { it.sourceId == packId }
            val logText = DiagnosticHumanFormatter.formatUi(
                events = events,
                mode = state.diagnosticsMode,
                title = "NHẬT KÝ TIỆN ÍCH • ${pack.name}",
            )
            AlertDialog(
                onDismissRequest = { logPackId = null },
                title = { Text("NHẬT KÝ TIỆN ÍCH") },
                text = {
                    Text(
                        logText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(logText)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("SAO CHÉP") }
                        TextButton(
                            onClick = onClearDiagnostics,
                            modifier = Modifier.weight(1f),
                        ) { Text("XÓA") }
                        TextButton(
                            onClick = onExportDiagnostics,
                            modifier = Modifier.weight(1f),
                        ) { Text("XUẤT TỆP") }
                    }
                },
            )
        }
    }

    removePackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        AlertDialog(
            onDismissRequest = { removePackId = null },
            title = { Text("XÓA TIỆN ÍCH") },
            text = { Text("Xóa ${pack?.name ?: packId}? Dữ liệu truyện đã tải sẽ được giữ lại.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(packId)
                    removePackId = null
                }) { Text("XÓA") }
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
    onInstall: (String, String) -> Unit,
    onAddRepository: () -> Unit,
) {
    var selectedRepositoryId by remember { mutableStateOf<String?>(null) }
    var selectedPackageId by remember { mutableStateOf<String?>(null) }
    var removeRepositoryId by remember { mutableStateOf<String?>(null) }
    var repositoryQuery by remember { mutableStateOf("") }
    var repositoryDetailQuery by remember { mutableStateOf("") }
    var repositoryFilter by remember { mutableStateOf("all") }

    val selectedRepository = state.sourceRepositories.firstOrNull { it.id == selectedRepositoryId }
    BackHandler(enabled = selectedRepository != null) {
        selectedPackageId = null
        selectedRepositoryId = null
        repositoryDetailQuery = ""
        repositoryFilter = "all"
    }

    if (selectedRepository == null) {
        val rankedRepositories = state.sourceRepositories.mapNotNull { repository ->
            val searchable = listOf(repository.name, repository.url, repository.id).joinToString(" ")
            extensionSearchScore(searchable, repositoryQuery)?.let { it to repository }
        }.sortedBy { it.first }.map { it.second }

        OutlinedTextField(
            value = repositoryQuery,
            onValueChange = { repositoryQuery = it.take(240) },
            label = { Text("Tìm kho") },
            placeholder = { Text("Nhập tên kho hoặc địa chỉ") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
        ReferenceActionButton(
            text = "THÊM KHO MỚI",
            onClick = onAddRepository,
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            minHeight = 52.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )

        if (state.sourceRepositoryRefreshing) {
            Text("Đang kiểm tra liên kết…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }

        when {
            state.sourceRepositories.isEmpty() -> Text("Chưa có kho tiện ích nào.", modifier = Modifier.padding(16.dp))
            rankedRepositories.isEmpty() -> Text("Không tìm thấy kho phù hợp với “${repositoryQuery.trim()}”.", modifier = Modifier.padding(16.dp))
            else -> rankedRepositories.forEach { repository ->
                val updateLabel = repositoryUpdatedLabel(repository.generatedAtEpochMs)
                ReferenceActionButton(
                    text = "${repository.name}\n$updateLabel",
                    onClick = {
                        selectedRepositoryId = repository.id
                        repositoryDetailQuery = ""
                        repositoryFilter = "all"
                    },
                    accessibilityLabel = "${repository.name}. $updateLabel",
                    normalColor = ReferencePanelBackground,
                    normalContentColor = ReferenceText,
                    minHeight = 52.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    } else {
        val packages = state.sourceRepositoryPackages.filter { it.repositoryId == selectedRepository.id }
        val rankedPackages = packages.mapNotNull { item ->
            val installed = item.installedVersion != null
            val hasUpdate = item.status == "UPDATE_AVAILABLE"
            val filterMatches = when (repositoryFilter) {
                "installed" -> installed
                "updates" -> hasUpdate
                else -> true
            }
            if (!filterMatches) return@mapNotNull null
            val searchable = listOf(
                item.name,
                item.description,
                item.sourceId,
                item.changelog,
                item.status,
            ).joinToString(" ")
            extensionSearchScore(searchable, repositoryDetailQuery)?.let { it to item }
        }.sortedBy { it.first }.map { it.second }

        Text(
            buildString {
                append("KHO: ${selectedRepository.name}")
                if (repositoryDetailQuery.isNotBlank()) append("\nĐang lọc: ${repositoryDetailQuery.trim()}")
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        )

        OutlinedTextField(
            value = repositoryDetailQuery,
            onValueChange = { repositoryDetailQuery = it.take(240) },
            label = { Text("Tìm trong kho") },
            placeholder = { Text("Nhập tên hoặc vài ký tự liên quan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
        ReferenceActionButton(
            text = if (repositoryFilter == "all") "TẤT CẢ ✓" else "TẤT CẢ",
            onClick = { repositoryFilter = "all" },
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = if (repositoryFilter == "installed") "ĐÃ CÀI ✓" else "ĐÃ CÀI",
            onClick = { repositoryFilter = "installed" },
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = if (repositoryFilter == "updates") "CẬP NHẬT ✓" else "CẬP NHẬT",
            onClick = { repositoryFilter = "updates" },
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = if (state.sourceRepositoryRefreshing) "ĐANG LÀM MỚI…" else "LÀM MỚI",
            onClick = { onRefresh(selectedRepository.url) },
            enabled = !state.sourceRepositoryRefreshing,
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        ReferenceActionButton(
            text = "XÓA KHO",
            onClick = { removeRepositoryId = selectedRepository.id },
            normalColor = ReferencePanelBackground,
            normalContentColor = ReferenceText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )

        if (rankedPackages.isEmpty()) {
            Text(
                if (repositoryDetailQuery.isNotBlank()) "Không tìm thấy tiện ích liên quan."
                else "Không có tiện ích phù hợp với bộ lọc này.",
                modifier = Modifier.padding(16.dp),
            )
        } else {
            rankedPackages.forEach { item ->
                val stateLabel = when {
                    item.status == "UPDATE_AVAILABLE" -> "Có cập nhật"
                    item.installedVersion != null -> "Đã cài"
                    else -> "Chưa cài"
                }
                ReferenceActionButton(
                    text = "${item.name}\n$stateLabel",
                    onClick = { selectedPackageId = item.sourceId },
                    accessibilityLabel = "${item.name}. $stateLabel",
                    normalColor = ReferencePanelBackground,
                    normalContentColor = ReferenceText,
                    minHeight = 52.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }


    if (selectedRepository != null && selectedPackageId != null) {
        val item = state.sourceRepositoryPackages.firstOrNull {
            it.repositoryId == selectedRepository.id && it.sourceId == selectedPackageId
        }
        if (item != null) {
            val installed = item.installedVersion != null
            val hasUpdate = item.status == "UPDATE_AVAILABLE"
            val dialogTitle = when {
                hasUpdate -> "CẬP NHẬT TIỆN ÍCH"
                installed -> "CÀI LẠI TIỆN ÍCH"
                else -> "CÀI TIỆN ÍCH"
            }
            AlertDialog(
                onDismissRequest = { selectedPackageId = null },
                title = { Text(dialogTitle) },
                text = {
                    Text(
                        buildString {
                            append("Tên: ${item.name}\n")
                            append("Phiên bản: ${item.version}\n")
                            append("Nguồn: ${item.sourceId}\n")
                            if (item.description.isNotBlank()) append("\n${item.description.trim()}")
                            if (item.installedVersion != null) append("\n\nPhiên bản đang cài: ${item.installedVersion}")
                        },
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !state.sourceRepositoryRefreshing && !state.sourceInstallBusy && (item.canInstall || installed),
                        onClick = {
                            selectedPackageId = null
                            onInstall(item.repositoryId, item.sourceId)
                        },
                    ) { Text("CÀI ĐẶT") }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            enabled = !state.sourceRepositoryRefreshing && !state.sourceInstallBusy && (item.canInstall || installed),
                            onClick = {
                                selectedPackageId = null
                                onPrepareInstall(item.repositoryId, item.sourceId)
                            },
                        ) { Text("CHẨN ĐOÁN") }
                        TextButton(onClick = { selectedPackageId = null }) { Text("HỦY") }
                    }
                },
            )
        }
    }

    removeRepositoryId?.let { repositoryId ->
        val repository = state.sourceRepositories.firstOrNull { it.id == repositoryId }
        AlertDialog(
            onDismissRequest = { removeRepositoryId = null },
            title = { Text("XÓA KHO") },
            text = { Text("Xóa ${repository?.name ?: "kho này"} khỏi danh sách?") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(repositoryId)
                    removeRepositoryId = null
                    selectedPackageId = null
                    selectedRepositoryId = null
                    repositoryDetailQuery = ""
                    repositoryFilter = "all"
                }) { Text("XÓA") }
            },
            dismissButton = { TextButton(onClick = { removeRepositoryId = null }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun SourceAddLinkSection(
    state: MainUiState,
    repositoryUrl: String,
    onRepositoryUrlChange: (String) -> Unit,
    onRefresh: (String) -> Unit,
) {
    OutlinedTextField(
        value = repositoryUrl,
        onValueChange = { onRepositoryUrlChange(it.take(4096)) },
        label = { Text("Liên kết") },
        placeholder = { Text("repository.json / plugin.json / ZIP") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    )
    ReferenceActionButton(
        text = if (state.sourceRepositoryRefreshing) "ĐANG KIỂM TRA…" else "THÊM",
        onClick = { onRefresh(repositoryUrl.trim()) },
        enabled = repositoryUrl.trim().startsWith("https://") && !state.sourceRepositoryRefreshing,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
    if (state.diagnosticsMode != "off") {
        val clipboard = LocalClipboardManager.current
        val logText = DiagnosticHumanFormatter.formatUi(
            events = state.sourceDiagnostics,
            mode = state.diagnosticsMode,
        )
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("NHẬT KÝ", fontWeight = FontWeight.Bold)
                Text(
                    logText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(logText)) },
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("SAO CHÉP") }
                    Button(
                        onClick = onClearDiagnostics,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("XÓA") }
                    Button(
                        onClick = onExportDiagnostics,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("XUẤT TỆP") }
                }
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
                Text("Bật mặc định cho truyện dùng cấu hình chung", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = state.autoVoiceCastEnabled, onCheckedChange = onAutoVoiceCastChange)
            }
            Text(
                "Bộ hồ sơ này là tiêu chuẩn dùng chung. Chỉ các hồ sơ đang bật, có ngôn ngữ và đã chọn giọng hợp lệ mới được gửi cho AI; có thể dùng giọng mặc định của bộ đọc.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (roles.isEmpty()) {
                Text(
                    "Đang nạp hồ sơ giọng…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
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
    var sceneModeExpanded by remember { mutableStateOf(false) }
    var ttsCacheExpanded by remember { mutableStateOf(false) }
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
            ReferenceIntSettingsSlider(
                label = "Chuẩn bị trước",
                value = state.narrationPrefetchWindowChapters,
                minimum = 1,
                maximum = 5,
                suffix = " chương",
                onChange = onNarrationPrefetchWindowChange,
            )
            SettingSwitch("Dùng Sonic", state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
            ReferenceFloatSettingsSlider(
                label = "Tốc độ Sonic mặc định",
                value = state.sonicDefaultSpeed,
                minimum = 0.25f,
                maximum = 3f,
                steps = 274,
                shown = { "%.2f×".format(it) },
                onChange = onSonicDefaultSpeedChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Cao độ Sonic mặc định",
                value = state.sonicDefaultPitch,
                minimum = 0.5f,
                maximum = 2f,
                steps = 149,
                shown = { "%.2f×".format(it) },
                onChange = onSonicDefaultPitchChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingSwitch("Cache TTS/Sonic", state.ttsCacheEnabled, onTtsCacheEnabledChange)
            Text("Giới hạn cache TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
            Box(Modifier.fillMaxWidth()) {
                Button(onClick = { ttsCacheExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${state.ttsCacheLimitMiB} MiB") }
                DropdownMenu(expanded = ttsCacheExpanded, onDismissRequest = { ttsCacheExpanded = false }) {
                    listOf(16, 32, 64, 128, 256, 512).forEach { value ->
                        DropdownMenuItem(text = { Text("$value MiB") }, onClick = { ttsCacheExpanded = false; onTtsCacheLimitChange(value) })
                    }
                }
            }
            SettingSwitch("Chuẩn hóa âm lượng", state.normalizeTtsVolumeEnabled, onNormalizeTtsVolumeChange)
            ReferenceFloatSettingsSlider(
                label = "Mức giọng mục tiêu",
                value = state.ttsTargetLufs,
                minimum = -36f,
                maximum = -12f,
                steps = 23,
                shown = { "%.1f LUFS".format(it) },
                onChange = onTtsTargetLufsChange,
            )
            SettingSwitch("Giữ nhạc qua chương", state.sceneMusicContinueAcrossChapters, onSceneMusicContinueChange)
            Text("Chế độ phát", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth()) {
                val currentModeLabel = when (state.sceneMusicPlaybackMode) {
                    SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"
                    SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"
                    SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp"
                }
                Button(onClick = { sceneModeExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(currentModeLabel) }
                DropdownMenu(expanded = sceneModeExpanded, onDismissRequest = { sceneModeExpanded = false }) {
                    SceneMusicPlaybackMode.entries.forEach { mode ->
                        val label = when (mode) {
                            SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"
                            SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"
                            SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp"
                        }
                        DropdownMenuItem(text = { Text(label) }, onClick = { sceneModeExpanded = false; onSceneMusicPlaybackModeChange(mode) })
                    }
                }
            }
            ReferenceFloatSettingsSlider(
                label = "Mức chuẩn hóa nhạc",
                value = state.sceneMusicTargetLufs,
                minimum = -36f,
                maximum = -18f,
                steps = 17,
                shown = { "%.1f LUFS".format(it) },
                onChange = onSceneMusicTargetLufsChange,
            )
            ReferenceIntSettingsSlider(
                label = "Tránh lặp",
                value = state.sceneMusicAvoidRepeatWindow,
                minimum = 0,
                maximum = 20,
                suffix = " bài",
                onChange = onSceneMusicAvoidRepeatWindowChange,
            )
            ReferenceIntSettingsSlider(
                label = "Crossfade",
                value = state.sceneMusicCrossfadeMillis,
                minimum = 0,
                maximum = 8_000,
                step = 400,
                suffix = " ms",
                onChange = onSceneMusicCrossfadeChange,
            )
        }
    }
}

@Composable
private fun ReferenceFloatSettingsSlider(
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
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
    Slider(
        value = safe,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        steps = steps,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun ReferenceIntSettingsSlider(
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
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
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
    var engineExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    val selectedEngineLabel = engines.firstOrNull { it.packageName == selectedEnginePackage }?.label ?: "Mặc định hệ thống"
    val selectedVoiceLabel = voices.firstOrNull { it.name == selectedVoiceName }?.displayName ?: "Giọng mặc định"
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("TTS & giọng đọc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ReferenceFloatSettingsSlider(
                label = "Tốc độ đọc",
                value = rate,
                minimum = 0.25f,
                maximum = 3f,
                steps = 274,
                shown = { "%.2f×".format(it) },
                onChange = onRateChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Cao độ",
                value = pitch,
                minimum = 0.5f,
                maximum = 2f,
                steps = 149,
                shown = { "%.2f×".format(it) },
                onChange = onPitchChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Âm lượng",
                value = volume,
                minimum = 0f,
                maximum = 1f,
                steps = 99,
                shown = { "%.0f%%".format(it * 100) },
                onChange = onVolumeChange,
            )
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
            Text("Bộ đọc TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(onClick = { engineExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedEngineLabel) }
                DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    DropdownMenuItem(text = { Text("Mặc định hệ thống") }, onClick = { engineExpanded = false; onEngineSelected(null) })
                    engines.forEach { engine ->
                        DropdownMenuItem(text = { Text(engine.label) }, onClick = { engineExpanded = false; onEngineSelected(engine) })
                    }
                }
            }
            Text("Giọng đọc", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(onClick = { voiceExpanded = true }, enabled = !loadingVoices, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loadingVoices) "ĐANG QUÉT…" else selectedVoiceLabel)
                }
                DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    DropdownMenuItem(text = { Text("Giọng mặc định") }, onClick = { voiceExpanded = false; onVoiceSelected(null) })
                    voices.forEach { voice ->
                        DropdownMenuItem(text = { Text(voice.displayName) }, onClick = { voiceExpanded = false; onVoiceSelected(voice) })
                    }
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
            ReferenceFloatSettingsSlider(
                label = "Âm lượng nền",
                value = volume,
                minimum = 0f,
                maximum = 1f,
                steps = 99,
                shown = { "%.0f%%".format(it * 100) },
                onChange = onVolumeChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Mức còn lại khi TTS đọc",
                value = duckFactor,
                minimum = 0f,
                maximum = 1f,
                steps = 99,
                shown = { "%.0f%%".format(it * 100) },
                onChange = onDuckChange,
            )
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
            ) { Text("NHẬP TỆP XOAY KHÓA ĐÃ KÝ") }
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
