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
        if (personalPage.startsWith("settings_")) returnToSettings() else personalPage = parentPage(personalPage)
    }
    LaunchedEffect(personalPage) {
        onDiagnosticScreenChanged(personalPage)
        view.announceForAccessibility(pageTitle(personalPage))
    }

    when (personalPage) {
        "home" -> PersonalMenuPage(
            title = "CÁ NHÂN",
            items = listOf("settings_home" to "Cài đặt", "extensions_home" to "Tiện ích mở rộng"),
            onSelect = { target -> if (target == "settings_home") showSettingsDialog = true else personalPage = target },
        )
        "settings_pronunciation" -> PersonalSubPage("TỪ ĐIỂN PHÁT ÂM TTS") {
            PronunciationCard(state.pronunciations, onAddPronunciation, onUpdatePronunciation, onPronunciationEnabledChange, onDeletePronunciation)
        }
        "settings_vietphrase" -> PersonalSubPage("VIETPHRASE / CHUYỂN NGỮ") {
            VietPhraseCard(
                state, onImportVietPhrase, onExportVietPhrase, onAddVietPhrase, onUpdateVietPhrase,
                onCheckVietPhraseOnline, onVietPhraseEnabledChange, onVietPhraseDictionaryEnabledChange,
                onDeleteVietPhrase, onConfirmVietPhraseImport, onCancelVietPhraseImport, onRollbackVietPhrase,
                onAcceptVietPhraseSuggestion, onRejectVietPhraseSuggestion, onPrepareVietPhraseImport,
                onDeleteVietPhraseDictionary, onClearAllVietPhrase, onVietPhraseMasterEnabledChange,
                onVietPhraseFallbackChange, onInstallRecommendedVietPhrase,
            )
        }
        "settings_automation" -> PersonalSubPage("PHÂN VAI TTS BẰNG AI") {
            ReferenceVoiceCastSettingsCard(
                state, onAutoVoiceCastChange, onSaveGlobalVoiceRole, onGlobalVoiceRoleEnabledChange,
                onDeleteGlobalVoiceRole, onRestoreGlobalVoiceProfiles, onPreviewGlobalVoiceRole,
            )
        }
        "settings_other" -> PersonalSubPage("CÀI ĐẶT KHÁC") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingSwitch(
                        "Đọc liên tục khi có cuộc gọi hoặc tin nhắn",
                        state.audioInterruptionMode == AudioInterruptionMode.CONTINUE_DUCKED,
                    ) { enabled -> onInterruptionModeChange(if (enabled) AudioInterruptionMode.CONTINUE_DUCKED else AudioInterruptionMode.PAUSE) }
                }
            }
        }
        "settings_tts" -> PersonalSubPage("CÀI ĐẶT TTS") {
            VoiceSettingsCard(
                state.playback.rate, state.playback.pitch, state.ttsVolume, state.autoPlayNextChapter,
                state.ttsEngines, state.ttsVoices, state.selectedTtsEnginePackage, state.selectedTtsVoiceName,
                state.ttsVoiceLoading, state.audioInterruptionMode, onRateChange, onPitchChange, onVolumeChange,
                onAutoNextChange, onEngineSelected, onVoiceSelected, onRefreshVoices, onPreviewVoice,
                onOpenTtsSettings, onInterruptionModeChange,
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
                state.backgroundMusicUri, state.backgroundMusicEnabled, state.backgroundMusicVolume,
                state.backgroundMusicDuckFactor, onSelectBackgroundMusic, onClearBackgroundMusic,
                onBackgroundMusicEnabledChange, onBackgroundMusicVolumeChange, onBackgroundMusicDuckChange,
            )
            SceneMusicLibraryCard(state.sceneMusicTracks, onSelectSceneMusic, onUpdateSceneMusic, onSceneMusicEnabledChange, onDeleteSceneMusic)
        }
        "settings_following" -> PersonalSubPage("THEO DÕI CHƯƠNG MỚI") {
            FollowingSettingsCard(state.followingUpdatesEnabled, onFollowingUpdatesChange, onCheckFollowingNow)
        }
        "settings_storage" -> PersonalSubPage("DUNG LƯỢNG NGOẠI TUYẾN") {
            StorageCard(state, onCacheLimitChange, onTrimReaderCache, onClearReaderCache)
        }
        "settings_export" -> PersonalSubPage("XUẤT SÁCH NÓI") { AudioExportCard(state, onCancelAudioExport, onResumeAudioExport, onOpenAudioExport) }
        "settings_backup" -> PersonalSubPage("SAO LƯU & KHÔI PHỤC") { TransferCard(state, onBackupComponentChange, onExportBackup, onRestoreBackup) }
        "settings_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN") {
            PerformanceCard(state.performanceReport, onRunPerformanceDiagnostics)
            SourceDiagnosticsSection(
                state, selectorBaseUrl, { selectorBaseUrl = it }, selector, { selector = it }, selectorHtml,
                { selectorHtml = it }, onInspectSourceSelector, onExportSourceDiagnostics, onClearSourceDiagnostics,
                onCheckSource, onCheckAllSources, onOpenSourceLogin, onOpenSourceDiagnosticBrowser, onClearSourceSession,
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
                state, onSourcePackEnabledChange, onRollbackSourcePack, onUpdateSourcePack, onExportSourcePack,
                onRemoveSourcePack, onCheckSourcePack, onSaveSourceConfig, onResetSourceConfig,
                onOpenSourceLogin, onExportSourceDiagnostics, onClearSourceDiagnostics,
            )
        }
        "extensions_repositories" -> PersonalSubPage("KHO TIỆN ÍCH") {
            SourceRepositorySection(state, onRefreshSourceRepository, onRemoveSourceRepository, onPrepareRepositorySourceInstall, onInstallRepositorySource) { showAddRepositoryDialog = true }
        }
        "extensions_add" -> PersonalSubPage("THÊM KHO / LIÊN KẾT") {
            SourceAddLinkSection(state, repositoryUrl, { repositoryUrl = it }, onRefreshSourceRepository)
        }
        "extensions_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN TIỆN ÍCH") {
            SourceDiagnosticsSection(
                state, selectorBaseUrl, { selectorBaseUrl = it }, selector, { selector = it }, selectorHtml,
                { selectorHtml = it }, onInspectSourceSelector, onExportSourceDiagnostics, onClearSourceDiagnostics,
                onCheckSource, onCheckAllSources, onOpenSourceLogin, onOpenSourceDiagnosticBrowser, onClearSourceSession,
            )
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("CÀI ĐẶT ỨNG DỤNG") },
            text = {
                ReferenceSettingsHomePage(
                    state.diagnosticsMode, onDiagnosticsModeChange,
                    { target ->
                        showSettingsDialog = false
                        when (target) {
                            "settings_ai" -> showAiSettingsDialog = true
                            "settings_other" -> showOtherSettingsDialog = true
                            "settings_backup_log" -> if (onRefreshBackupLog()) showBackupLogDialog = true else showSettingsDialog = true
                            "settings_clear_downloads" -> showClearDownloadsDialog = true
                            "settings_factory_reset" -> showFactoryResetFirst = true
                            else -> personalPage = target
                        }
                    },
                    { backupScopeOperation = "backup" },
                    { backupScopeOperation = "restore" },
                )
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showAiSettingsDialog) {
        AiReferenceSettingsDialog(state, { showAiSettingsDialog = false; showSettingsDialog = true }, onSaveAiSettings, onRefreshAiModels)
    }
    if (showOtherSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showOtherSettingsDialog = false; showSettingsDialog = true },
            title = { Text("CÀI ĐẶT KHÁC") },
            text = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Đọc liên tục khi có cuộc gọi hoặc tin nhắn", Modifier.weight(1f))
                    Switch(
                        state.audioInterruptionMode == AudioInterruptionMode.CONTINUE_DUCKED,
                        { enabled -> onInterruptionModeChange(if (enabled) AudioInterruptionMode.CONTINUE_DUCKED else AudioInterruptionMode.PAUSE) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showOtherSettingsDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") } },
        )
    }
    if (showBackupLogDialog) {
        AlertDialog(
            onDismissRequest = { showBackupLogDialog = false; showSettingsDialog = true },
            title = { Text("NHẬT KÝ SAO LƯU") },
            text = { Text("Tệp: ${state.backupLogPath}\n\n${state.backupLogText.takeLast(40_000)}", modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showBackupLogDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") } },
            dismissButton = { TextButton(onClick = { onClearBackupLog(); showBackupLogDialog = false; showSettingsDialog = true }) { Text("XÓA NHẬT KÝ") } },
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
            dismissButton = { TextButton(onClick = { backupScopeOperation = null; showSettingsDialog = true }) { Text("HỦY") } },
        )
    }
    if (showClearDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsDialog = false; showSettingsDialog = true },
            title = { Text("XÓA TRUYỆN ĐÃ TẢI") }, text = { Text("Xóa toàn bộ truyện đã tải?") },
            confirmButton = { TextButton(onClick = { showClearDownloadsDialog = false; onClearDownloadedStories(); showSettingsDialog = true }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { showClearDownloadsDialog = false; showSettingsDialog = true }) { Text("HỦY") } },
        )
    }
    if (showFactoryResetFirst) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFirst = false; showSettingsDialog = true },
            title = { Text("ĐẶT LẠI ỨNG DỤNG NHƯ MỚI") }, text = { Text("Xóa toàn bộ dữ liệu và cài đặt?") },
            confirmButton = { TextButton(onClick = { showFactoryResetFirst = false; showFactoryResetFinal = true }) { Text("TIẾP TỤC") } },
            dismissButton = { TextButton(onClick = { showFactoryResetFirst = false; showSettingsDialog = true }) { Text("HỦY") } },
        )
    }
    if (showFactoryResetFinal) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFinal = false; showSettingsDialog = true },
            title = { Text("XÁC NHẬN LẦN CUỐI") }, text = { Text("Đặt lại ứng dụng ngay?") },
            confirmButton = { TextButton(onClick = { showFactoryResetFinal = false; onFactoryResetApplication() }) { Text("ĐẶT LẠI NGAY") } },
            dismissButton = { TextButton(onClick = { showFactoryResetFinal = false; showSettingsDialog = true }) { Text("HỦY") } },
        )
    }
    if (showAddRepositoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddRepositoryDialog = false },
            title = { Text("THÊM KHO / LIÊN KẾT") },
            text = { OutlinedTextField(repositoryUrl, { repositoryUrl = it.take(4096) }, label = { Text("Liên kết") }, placeholder = { Text("repository.json / plugin.json / ZIP") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = repositoryUrl.trim().startsWith("https://"), onClick = { onRefreshSourceRepository(repositoryUrl.trim()); showAddRepositoryDialog = false }) { Text("THÊM") } },
            dismissButton = { TextButton(onClick = { showAddRepositoryDialog = false }) { Text("HỦY") } },
        )
    }
    if (state.pendingSourceInstall != null) SourceInstallDiagnosticDialog(state, onCancelSourcePackInstall)
    if (state.sourceInstallOutcome != null) SourceInstallOutcomeDialog(state, onCancelSourcePackInstall)
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
            ReferenceActionButton(text = label, onClick = { onSelect(id) }, accessibilityLabel = label, normalColor = ReferencePanelBackground, normalContentColor = ReferenceText, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text("Mức nhật ký chẩn đoán", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 14.dp, end = 10.dp, bottom = 4.dp))
        var diagnosticsExpanded by remember { mutableStateOf(false) }
        val diagnosticsLabel = when (diagnosticsMode) { "basic" -> "Gỡ lỗi theo màn hình"; "advanced", "advanced_crash" -> "Gỡ lỗi nối liền"; else -> "Tắt" }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Button(onClick = { diagnosticsExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$diagnosticsLabel ▼") }
            DropdownMenu(expanded = diagnosticsExpanded, onDismissRequest = { diagnosticsExpanded = false }) {
                listOf("off" to "Tắt", "basic" to "Gỡ lỗi theo màn hình", "advanced" to "Gỡ lỗi nối liền").forEach { (value, label) ->
                    DropdownMenuItem(text = { Text((if (diagnosticsMode == value) "✓ " else "") + label) }, onClick = { diagnosticsExpanded = false; onDiagnosticsModeChange(value) })
                }
            }
        }
        Text(
            when (diagnosticsMode) {
                "basic" -> "Chỉ giữ nhật ký của màn hình/ngữ cảnh hiện tại. Chuyển màn hình sẽ bắt đầu một nhật ký mới."
                "advanced", "advanced_crash" -> "Nối liền qua màn hình và lần mở ứng dụng. Chỉ nút XÓA mới xóa lịch sử đã lưu."
                else -> "Tắt hoàn toàn: không ghi event, trace hoặc evidence ngầm."
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
        ReferenceActionButton("SAO LƯU DỮ LIỆU", onExportBackup, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
        ReferenceActionButton("KHÔI PHỤC DỮ LIỆU", onRestoreBackup, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
        ReferenceActionButton("NHẬT KÝ SAO LƯU", { onSelect("settings_backup_log") }, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
        ReferenceActionButton("XÓA TRUYỆN ĐÃ TẢI", { onSelect("settings_clear_downloads") }, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp))
        ReferenceActionButton("ĐẶT LẠI ỨNG DỤNG NHƯ MỚI", { onSelect("settings_factory_reset") }, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun PersonalMenuPage(title: String, items: List<Pair<String, String>>, onSelect: (String) -> Unit, extraContent: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ScreenHeading(title)
        items.forEach { (id, label) -> ReferenceActionButton(label, { onSelect(id) }, accessibilityLabel = label, normalColor = ReferencePanelBackground, normalContentColor = ReferenceText, minHeight = 52.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) }
        extraContent()
    }
}

@Composable
private fun PersonalSubPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) { ScreenHeading(title); content() }
}

@Composable
private fun SourceInstallDiagnosticDialog(state: MainUiState, onDismiss: () -> Unit) {
    val preview = state.pendingSourceInstall ?: return
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("CHẨN ĐOÁN TIỆN ÍCH") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("${preview.name} ${preview.version}", fontWeight = FontWeight.SemiBold)
            Text("Nguồn: ${preview.sourceId}", style = MaterialTheme.typography.bodySmall)
            Text("Chữ ký: ${preview.signerKeyId}", style = MaterialTheme.typography.bodySmall)
            Text("Self-test: ${preview.fixtureCount} kiểm tra đạt", style = MaterialTheme.typography.bodySmall)
            preview.permissionSummary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            state.pendingSourceInstallWarnings.forEach { Text("Cảnh báo: $it", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun SourceInstallOutcomeDialog(state: MainUiState, onDismiss: () -> Unit) {
    val outcome = state.sourceInstallOutcome ?: return
    val versionSuffix = outcome.version.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (outcome.success) "CÀI ĐẶT THÀNH CÔNG" else "CÀI ĐẶT THẤT BẠI") },
        text = { Text(if (outcome.success) "Đã cài ${outcome.name}$versionSuffix và kích hoạt tiện ích." else "Không thể cài ${outcome.name}$versionSuffix.\n\nNguyên nhân: ${outcome.reason.ifBlank { "Không xác định được nguyên nhân." }}", modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

private fun extensionSearchNormalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
private fun extensionSearchScore(value: String, query: String): Int? {
    val normalizedQuery = extensionSearchNormalize(query); if (normalizedQuery.isBlank()) return 0
    val searchable = extensionSearchNormalize(value); if (searchable.isBlank()) return null
    val words = searchable.split(' ').filter(String::isNotBlank); val compact = words.joinToString(""); val acronym = words.mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    var score = 0
    normalizedQuery.split(' ').filter(String::isNotBlank).forEach { token -> score += extensionTokenSearchScore(token, searchable, words, compact, acronym) ?: return null }
    val phraseIndex = searchable.indexOf(normalizedQuery); if (phraseIndex >= 0) score -= minOf(40, 20 + normalizedQuery.length)
    return score.coerceAtLeast(0)
}
private fun extensionTokenSearchScore(token: String, searchable: String, words: List<String>, compact: String, acronym: String): Int? {
    val exactWord = words.indexOf(token); if (exactWord >= 0) return exactWord
    val prefixWord = words.indexOfFirst { it.startsWith(token) }; if (prefixWord >= 0) return 10 + prefixWord
    val substring = searchable.indexOf(token); if (substring >= 0) return 20 + substring
    if (token.length >= 2) { if (acronym.startsWith(token)) return 40 + acronym.length - token.length; extensionSubsequenceScore(token, acronym)?.let { return 50 + it } }
    if (token.length >= 3) extensionSubsequenceScore(token, compact)?.let { if (it <= token.length * 2 + 8) return 70 + it }
    val editLimit = when { token.length >= 7 -> 2; token.length >= 4 -> 1; else -> 0 }
    if (editLimit > 0) {
        var best: Int? = null
        words.forEach { word -> if (kotlin.math.abs(word.length - token.length) <= editLimit) extensionEditDistance(token, word, editLimit)?.let { best = minOf(best ?: it, it) } }
        best?.let { return 100 + it * 10 }
    }
    return null
}
private fun extensionSubsequenceScore(needle: String, haystack: String): Int? {
    if (needle.isEmpty()) return 0
    var ni = 0; var first = -1; var last = -1
    haystack.forEachIndexed { i, c -> if (ni < needle.length && c == needle[ni]) { if (first < 0) first = i; last = i; ni++ } }
    if (ni != needle.length) return null
    return (last - first + 1 - needle.length).coerceAtLeast(0) + first.coerceAtLeast(0)
}
private fun extensionEditDistance(left: String, right: String, limit: Int): Int? {
    if (kotlin.math.abs(left.length - right.length) > limit) return null
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { li, lc ->
        val current = IntArray(right.length + 1); current[0] = li + 1; var rowMinimum = current[0]
        right.forEachIndexed { ri, rc -> current[ri + 1] = minOf(current[ri] + 1, previous[ri + 1] + 1, previous[ri] + if (lc == rc) 0 else 1); rowMinimum = minOf(rowMinimum, current[ri + 1]) }
        if (rowMinimum > limit) return null; previous = current
    }
    return previous[right.length].takeIf { it <= limit }
}
private fun repositoryUpdatedLabel(epochMs: Long): String = if (epochMs > 0L) "Cập nhật: ${SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date(epochMs))}" else "Chưa tải danh mục"

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
    var removePackId by remember { mutableStateOf<String?>(null) }
    var logPackId by remember { mutableStateOf<String?>(null) }
    var installedQuery by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val retained = listOf(onRollback, onLogin)
    val ranked = state.sourcePacks.mapNotNull { pack -> extensionSearchScore(listOf(pack.name, pack.id, pack.version, pack.ecosystem, pack.contentType, pack.compatibilityProfile, pack.runtimeMode).joinToString(" "), installedQuery)?.let { it to pack } }.sortedBy { it.first }.map { it.second }
    OutlinedTextField(installedQuery, { installedQuery = it.take(240) }, label = { Text("Tìm tiện ích") }, placeholder = { Text("Nhập tên hoặc vài ký tự liên quan") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
    if (ranked.isEmpty()) Text("Không tìm thấy tiện ích phù hợp.", modifier = Modifier.padding(16.dp)) else ranked.forEach { pack ->
        val health = state.sources.firstOrNull { it.id == pack.id }?.health
        val label = when { health == SourceHealth.NOT_PORTED -> "${pack.name} - Không tương thích"; !pack.enabled -> "${pack.name} - Đã tắt"; else -> pack.name }
        ReferenceActionButton(label, { selectedPackId = pack.id }, accessibilityLabel = label, normalColor = ReferencePanelBackground, normalContentColor = ReferenceText, minHeight = 52.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
    }
    selectedPackId?.let { id -> state.sourcePacks.firstOrNull { it.id == id }?.let { pack ->
        AlertDialog(
            onDismissRequest = { selectedPackId = null }, title = { Text(pack.name) },
            text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Tên: ${pack.name}\nPhiên bản: ${pack.version}\nLoại: ${pack.ecosystem}\nNguồn: ${pack.id}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                ReferenceActionButton(if (pack.enabled) "TẮT" else "BẬT", { onEnabledChange(pack.id, !pack.enabled); selectedPackId = null }, modifier = Modifier.fillMaxWidth())
                if (pack.configFields.isNotEmpty()) ReferenceActionButton("CẤU HÌNH", { configurePackId = pack.id; selectedPackId = null }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                ReferenceActionButton("KIỂM TRA NGUỒN", { onCheck(pack.id); selectedPackId = null }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                ReferenceActionButton("NHẬT KÝ", { logPackId = pack.id; selectedPackId = null }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                ReferenceActionButton("CẬP NHẬT", { onUpdate(pack.id); selectedPackId = null }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                ReferenceActionButton("XUẤT", { onExport(pack.id, pack.name); selectedPackId = null }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                if (pack.removable) ReferenceActionButton("XÓA", { removePackId = pack.id; selectedPackId = null }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            } },
            confirmButton = { TextButton(onClick = { selectedPackId = null }) { Text("ĐÓNG") } },
        )
    } }
    configurePackId?.let { packId -> state.sourcePacks.firstOrNull { it.id == packId }?.let { pack -> SourcePackConfigDialog(pack, { changes -> onSaveConfig(pack.id, changes) }, { onResetConfig(pack.id) }, { configurePackId = null }) } }
    logPackId?.let { packId -> state.sourcePacks.firstOrNull { it.id == packId }?.let { pack ->
        val clipboard = LocalClipboardManager.current
        val logText = DiagnosticHumanFormatter.formatUi(state.sourceDiagnostics.filter { it.sourceId == pack.id }, state.diagnosticsMode, "NHẬT KÝ TIỆN ÍCH • ${pack.name}")
        AlertDialog(onDismissRequest = { logPackId = null }, title = { Text("NHẬT KÝ TIỆN ÍCH") }, text = { Text(logText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) }, confirmButton = { Row { TextButton({ clipboard.setText(AnnotatedString(logText)) }) { Text("SAO CHÉP") }; TextButton(onClearDiagnostics) { Text("XÓA") }; TextButton(onExportDiagnostics) { Text("XUẤT TỆP") } } })
    } }
    removePackId?.let { packId -> AlertDialog(onDismissRequest = { removePackId = null }, title = { Text("XÓA TIỆN ÍCH") }, text = { Text("Xóa ${state.sourcePacks.firstOrNull { it.id == packId }?.name ?: packId}? Dữ liệu truyện đã tải sẽ được giữ lại.") }, confirmButton = { TextButton({ onRemove(packId); removePackId = null }) { Text("XÓA") } }, dismissButton = { TextButton({ removePackId = null }) { Text("HỦY") } }) }
}

@Composable
private fun SourceRepositorySection(state: MainUiState, onRefresh: (String) -> Unit, onRemove: (String) -> Unit, onPrepareInstall: (String, String) -> Unit, onInstall: (String, String) -> Unit, onAddRepository: () -> Unit) {
    var selectedRepositoryId by remember { mutableStateOf<String?>(null) }
    var selectedPackageId by remember { mutableStateOf<String?>(null) }
    var removeRepositoryId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val selected = state.sourceRepositories.firstOrNull { it.id == selectedRepositoryId }
    BackHandler(enabled = selected != null) { selectedPackageId = null; selectedRepositoryId = null; query = ""; filter = "all" }
    if (selected == null) {
        OutlinedTextField(query, { query = it.take(240) }, label = { Text("Tìm kho") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
        ReferenceActionButton("THÊM KHO MỚI", onAddRepository, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
        state.sourceRepositories.filter { extensionSearchScore(listOf(it.name, it.url, it.id).joinToString(" "), query) != null }.forEach { repo ->
            ReferenceActionButton("${repo.name}\n${repositoryUpdatedLabel(repo.generatedAtEpochMs)}", { selectedRepositoryId = repo.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
        }
    } else {
        val packages = state.sourceRepositoryPackages.filter { it.repositoryId == selected.id }.filter { item -> when (filter) { "installed" -> item.installedVersion != null; "updates" -> item.status == "UPDATE_AVAILABLE"; else -> true } }.filter { extensionSearchScore(listOf(it.name, it.description, it.sourceId, it.changelog).joinToString(" "), query) != null }
        Text("KHO: ${selected.name}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
        OutlinedTextField(query, { query = it.take(240) }, label = { Text("Tìm trong kho") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Button({ filter = "all" }, Modifier.weight(1f)) { Text("TẤT CẢ") }
            Button({ filter = "installed" }, Modifier.weight(1f)) { Text("ĐÃ CÀI") }
            Button({ filter = "updates" }, Modifier.weight(1f)) { Text("CẬP NHẬT") }
        }
        Button({ onRefresh(selected.url) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sourceRepositoryRefreshing) { Text("LÀM MỚI") }
        Button({ removeRepositoryId = selected.id }, modifier = Modifier.fillMaxWidth()) { Text("XÓA KHO") }
        packages.forEach { item -> ReferenceActionButton("${item.name}\n${item.status}", { selectedPackageId = item.sourceId }, modifier = Modifier.fillMaxWidth().padding(4.dp)) }
    }
    if (selected != null && selectedPackageId != null) {
        state.sourceRepositoryPackages.firstOrNull { it.repositoryId == selected.id && it.sourceId == selectedPackageId }?.let { item ->
            AlertDialog(
                onDismissRequest = { selectedPackageId = null }, title = { Text(item.name) }, text = { Text("Phiên bản: ${item.version}\nNguồn: ${item.sourceId}\n${item.description}") },
                confirmButton = { TextButton(enabled = !state.sourceInstallBusy && (item.canInstall || item.installedVersion != null), onClick = { selectedPackageId = null; onInstall(item.repositoryId, item.sourceId) }) { Text("CÀI ĐẶT") } },
                dismissButton = { Row { TextButton(onClick = { selectedPackageId = null; onPrepareInstall(item.repositoryId, item.sourceId) }) { Text("CHẨN ĐOÁN") }; TextButton({ selectedPackageId = null }) { Text("HỦY") } } },
            )
        }
    }
    removeRepositoryId?.let { id -> AlertDialog(onDismissRequest = { removeRepositoryId = null }, title = { Text("XÓA KHO") }, text = { Text("Xóa kho này khỏi danh sách?") }, confirmButton = { TextButton({ onRemove(id); removeRepositoryId = null; selectedRepositoryId = null }) { Text("XÓA") } }, dismissButton = { TextButton({ removeRepositoryId = null }) { Text("HỦY") } }) }
}

@Composable
private fun SourceAddLinkSection(state: MainUiState, repositoryUrl: String, onRepositoryUrlChange: (String) -> Unit, onRefresh: (String) -> Unit) {
    OutlinedTextField(repositoryUrl, { onRepositoryUrlChange(it.take(4096)) }, label = { Text("Liên kết") }, placeholder = { Text("repository.json / plugin.json / ZIP") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
    ReferenceActionButton(if (state.sourceRepositoryRefreshing) "ĐANG KIỂM TRA…" else "THÊM", { onRefresh(repositoryUrl.trim()) }, enabled = repositoryUrl.trim().startsWith("https://") && !state.sourceRepositoryRefreshing, modifier = Modifier.fillMaxWidth().padding(8.dp))
}

@Composable
private fun SourceDiagnosticsSection(state: MainUiState, selectorBaseUrl: String, onSelectorBaseUrlChange: (String) -> Unit, selector: String, onSelectorChange: (String) -> Unit, selectorHtml: String, onSelectorHtmlChange: (String) -> Unit, onInspectSelector: (String, String, String) -> Unit, onExportDiagnostics: () -> Unit, onClearDiagnostics: () -> Unit, onCheckSource: (String) -> Unit, onCheckAll: () -> Unit, onOpenLogin: (String) -> Unit, onOpenDiagnosticBrowser: (String) -> Unit, onClearSession: (String) -> Unit) {
    if (state.diagnosticsMode != "off") {
        val clipboard = LocalClipboardManager.current; val logText = DiagnosticHumanFormatter.formatUi(state.sourceDiagnostics, state.diagnosticsMode)
        Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(14.dp)) {
            Text("NHẬT KÝ", fontWeight = FontWeight.Bold); Text(logText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            Row { Button({ clipboard.setText(AnnotatedString(logText)) }) { Text("SAO CHÉP") }; Button(onClearDiagnostics) { Text("XÓA") }; Button(onExportDiagnostics) { Text("XUẤT TỆP") } }
        } }
    }
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(14.dp)) {
        Text("SELECTOR INSPECTOR", fontWeight = FontWeight.Bold)
        OutlinedTextField(selectorBaseUrl, { onSelectorBaseUrlChange(it.take(4096)) }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(selector, { onSelectorChange(it.take(512)) }, label = { Text("CSS selector") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(selectorHtml, { onSelectorHtmlChange(it.take(2 * 1024 * 1024)) }, label = { Text("HTML snapshot") }, modifier = Modifier.fillMaxWidth())
        Button({ onInspectSelector(selectorHtml, selector, selectorBaseUrl) }, enabled = selector.isNotBlank() && selectorHtml.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("KIỂM TRA SELECTOR") }
    } }
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(14.dp)) {
        Text("KIỂM TRA NGUỒN", fontWeight = FontWeight.Bold); Button(onCheckAll, Modifier.fillMaxWidth()) { Text("KIỂM TRA TẤT CẢ NGUỒN") }
        state.sources.forEach { source ->
            HorizontalDivider(Modifier.padding(vertical = 7.dp)); val report = state.sourceHealthReports[source.id]; val checking = source.id in state.sourceHealthChecking; val sessionActive = source.id in state.sourceSessions
            Text(source.displayName, fontWeight = FontWeight.SemiBold); Text(if (checking) "Đang kiểm tra…" else report?.let { "${it.resolvedHealth.name} • ${it.passedSteps}/${it.totalSteps} bước đạt" } ?: "Khai báo: ${source.health.name}", style = MaterialTheme.typography.bodySmall)
            Row { Button({ onCheckSource(source.id) }, enabled = !checking && source.health != SourceHealth.NOT_PORTED) { Text("KIỂM TRA") }; if (source.loginUrl != null) Button({ onOpenLogin(source.id) }) { Text(if (sessionActive) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP") } }
            if (source.allowedHosts.isNotEmpty() && source.baseUrl.startsWith("https://")) Button({ onOpenDiagnosticBrowser(source.id) }) { Text("TRÌNH DUYỆT CHẨN ĐOÁN") }
            if (source.loginUrl != null && sessionActive) Button({ onClearSession(source.id) }) { Text("XÓA PHIÊN ĐÃ LƯU") }
        }
    } }
}

private val MEDIA_ACTION_ORDER = listOf("TOGGLE", "NEXT", "PREVIOUS", "PLAY", "PAUSE", "FORWARD", "REWIND", "STOP")
private fun nextMediaAction(current: String): String { val i = MEDIA_ACTION_ORDER.indexOf(current).takeIf { it >= 0 } ?: 0; return MEDIA_ACTION_ORDER[(i + 1) % MEDIA_ACTION_ORDER.size] }
private fun mediaActionLabel(value: String): String = when (value) { "PLAY" -> "Phát"; "PAUSE" -> "Tạm dừng"; "NEXT" -> "Đoạn sau"; "PREVIOUS" -> "Đoạn trước"; "FORWARD" -> "Tiến"; "REWIND" -> "Lùi"; "STOP" -> "Dừng hẳn"; else -> "Phát / dừng" }
@Composable private fun MediaMappingButton(label: String, value: String, onChange: (String) -> Unit) { Button({ onChange(nextMediaAction(value)) }, Modifier.fillMaxWidth().padding(top = 2.dp)) { Text("$label: ${mediaActionLabel(value)}") } }

@Composable
private fun ReferenceVoiceCastSettingsCard(state: MainUiState, onAutoVoiceCastChange: (Boolean) -> Unit, onSaveGlobalVoiceRole: (VoiceRoleDraft) -> Unit, onGlobalVoiceRoleEnabledChange: (String, Boolean) -> Unit, onDeleteGlobalVoiceRole: (String) -> Unit, onRestoreGlobalVoiceProfiles: () -> Unit, onPreviewGlobalVoiceRole: (VoiceRoleDraft) -> Unit) {
    val roles = state.voiceRoles.filter { it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID }.take(10)
    var editDraft by remember { mutableStateOf<VoiceRoleDraft?>(null) }
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Bật mặc định cho truyện dùng cấu hình chung", Modifier.weight(1f)); Switch(state.autoVoiceCastEnabled, onAutoVoiceCastChange) }
        roles.forEach { role -> ReferenceActionButton((if (role.enabled) "" else "TẮT • ") + (if (role.isNarrator) "Người kể chuyện" else role.roleName), { editDraft = VoiceRoleDraft(roleName = role.roleName, originalRoleId = role.id, aliases = role.aliasesCsv, description = role.description, isNarrator = role.isNarrator, enginePackage = role.enginePackage, voiceName = role.voiceName, languageTag = role.languageTag, rate = role.rate, pitch = role.pitch, volume = role.volume, expression = runCatching { VoiceExpression.valueOf(role.expression) }.getOrDefault(VoiceExpression.NEUTRAL), expressionStrength = role.expressionStrength, sonicSpeed = role.sonicSpeed, sonicPitch = role.sonicPitch, enabled = role.enabled) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
        Button({ editDraft = VoiceRoleDraft(roleName = "Giọng mới ${roles.size + 1}", enginePackage = state.selectedTtsEnginePackage, voiceName = state.selectedTtsVoiceName, languageTag = state.selectedTtsLanguageTag, rate = state.playback.rate, pitch = state.playback.pitch, volume = state.ttsVolume, sonicSpeed = state.sonicDefaultSpeed, sonicPitch = state.sonicDefaultPitch, processingMethod = if (state.sonicProcessingEnabled) "sonic" else "system") }, enabled = roles.size < 10, modifier = Modifier.fillMaxWidth()) { Text("THÊM GIỌNG") }
        Button(onRestoreGlobalVoiceProfiles, Modifier.fillMaxWidth()) { Text("KHÔI PHỤC 7 HỒ SƠ MẪU") }
    } }
    editDraft?.let { draft -> vn.nghetruyen.app.ui.components.GlobalVoiceRoleEditorDialog(draft, state.ttsEngines, { editDraft = it }, onPreviewGlobalVoiceRole, { onSaveGlobalVoiceRole(it); editDraft = null }, if (!draft.isNarrator && draft.originalRoleId != null) ({ onDeleteGlobalVoiceRole(draft.originalRoleId); editDraft = null }) else null, { editDraft = null }) }
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
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
        ReferenceIntSettingsSlider("Chuẩn bị trước", state.narrationPrefetchWindowChapters, 1, 5, suffix = " chương", onChange = onNarrationPrefetchWindowChange)
        SettingSwitch("Dùng Sonic", state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
        ReferenceFloatSettingsSlider("Tốc độ Sonic mặc định", state.sonicDefaultSpeed, 0.25f, 3f, 274, { "%.2f×".format(it) }, onSonicDefaultSpeedChange)
        ReferenceFloatSettingsSlider("Cao độ Sonic mặc định", state.sonicDefaultPitch, 0.5f, 2f, 149, { "%.2f×".format(it) }, onSonicDefaultPitchChange)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingSwitch("Cache TTS/Sonic", state.ttsCacheEnabled, onTtsCacheEnabledChange)
        Text("Giới hạn cache TTS", fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth()) { Button({ ttsCacheExpanded = true }, Modifier.fillMaxWidth()) { Text("${state.ttsCacheLimitMiB} MiB") }; DropdownMenu(ttsCacheExpanded, { ttsCacheExpanded = false }) { listOf(16, 32, 64, 128, 256, 512).forEach { value -> DropdownMenuItem({ Text("$value MiB") }, { ttsCacheExpanded = false; onTtsCacheLimitChange(value) }) } } }
        SettingSwitch("Chuẩn hóa âm lượng", state.normalizeTtsVolumeEnabled, onNormalizeTtsVolumeChange)
        ReferenceFloatSettingsSlider("Mức giọng mục tiêu", state.ttsTargetLufs, -36f, -12f, 23, { "%.1f LUFS".format(it) }, onTtsTargetLufsChange)
        SettingSwitch("Giữ nhạc qua chương", state.sceneMusicContinueAcrossChapters, onSceneMusicContinueChange)
        Text("Chế độ phát", fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth()) {
            val current = when (state.sceneMusicPlaybackMode) { SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"; SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"; SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp" }
            Button({ sceneModeExpanded = true }, Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(sceneModeExpanded, { sceneModeExpanded = false }) { SceneMusicPlaybackMode.entries.forEach { mode -> DropdownMenuItem({ Text(mode.name) }, { sceneModeExpanded = false; onSceneMusicPlaybackModeChange(mode) }) } }
        }
        ReferenceIntSettingsSlider("Tránh lặp", state.sceneMusicAvoidRepeatWindow, 0, 20, suffix = " bài", onChange = onSceneMusicAvoidRepeatWindowChange)
        ReferenceIntSettingsSlider("Crossfade", state.sceneMusicCrossfadeMillis, 0, 8_000, step = 400, suffix = " ms", onChange = onSceneMusicCrossfadeChange)
    } }
}

@Composable private fun ReferenceFloatSettingsSlider(label: String, value: Float, minimum: Float, maximum: Float, steps: Int = 0, shown: (Float) -> String, onChange: (Float) -> Unit) {
    val safe = value.coerceIn(minimum, maximum); val description = "$label: ${shown(safe)}"; Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp)); Slider(safe, { onChange(it.coerceIn(minimum, maximum)) }, valueRange = minimum..maximum, steps = steps, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
}
@Composable private fun ReferenceIntSettingsSlider(label: String, value: Int, minimum: Int, maximum: Int, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    val safeStep = step.coerceAtLeast(1); val safe = value.coerceIn(minimum, maximum); val intervals = ((maximum - minimum) / safeStep).coerceAtLeast(1); val description = "$label: $safe$suffix"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp)); Slider(safe.toFloat(), { raw -> onChange((minimum + (((raw - minimum) / safeStep).toInt() * safeStep)).coerceIn(minimum, maximum)) }, valueRange = minimum.toFloat()..maximum.toFloat(), steps = (intervals - 1).coerceAtLeast(0), modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onChange) } }

@Composable
private fun VoiceSettingsCard(rate: Float, pitch: Float, volume: Float, autoNext: Boolean, engines: List<TtsEngineOption>, voices: List<TtsVoiceOption>, selectedEnginePackage: String?, selectedVoiceName: String?, loadingVoices: Boolean, interruptionMode: AudioInterruptionMode, onRateChange: (Float) -> Unit, onPitchChange: (Float) -> Unit, onVolumeChange: (Float) -> Unit, onAutoNextChange: (Boolean) -> Unit, onEngineSelected: (TtsEngineOption?) -> Unit, onVoiceSelected: (TtsVoiceOption?) -> Unit, onRefreshVoices: () -> Unit, onPreviewVoice: () -> Unit, onOpenTtsSettings: () -> Unit, onInterruptionModeChange: (AudioInterruptionMode) -> Unit) {
    var engineExpanded by remember { mutableStateOf(false) }; var voiceExpanded by remember { mutableStateOf(false) }
    val engineLabel = engines.firstOrNull { it.packageName == selectedEnginePackage }?.label ?: "Mặc định hệ thống"; val voiceLabel = voices.firstOrNull { it.name == selectedVoiceName }?.displayName ?: "Giọng mặc định"
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) {
        Text("TTS & giọng đọc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ReferenceFloatSettingsSlider("Tốc độ đọc", rate, 0.25f, 3f, 274, { "%.2f×".format(it) }, onRateChange); ReferenceFloatSettingsSlider("Cao độ", pitch, 0.5f, 2f, 149, { "%.2f×".format(it) }, onPitchChange); ReferenceFloatSettingsSlider("Âm lượng", volume, 0f, 1f, 99, { "%.0f%%".format(it * 100) }, onVolumeChange)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Tự đọc chương sau", Modifier.weight(1f)); Switch(autoNext, onAutoNextChange) }
        Row(Modifier.fillMaxWidth()) { Button({ onInterruptionModeChange(AudioInterruptionMode.PAUSE) }, Modifier.weight(1f)) { Text("TẠM DỪNG") }; Button({ onInterruptionModeChange(AudioInterruptionMode.CONTINUE_DUCKED) }, Modifier.weight(1f)) { Text("TIẾP TỤC") } }
        Box(Modifier.fillMaxWidth()) { Button({ engineExpanded = true }, Modifier.fillMaxWidth()) { Text(engineLabel) }; DropdownMenu(engineExpanded, { engineExpanded = false }) { DropdownMenuItem({ Text("Mặc định hệ thống") }, { engineExpanded = false; onEngineSelected(null) }); engines.forEach { e -> DropdownMenuItem({ Text(e.label) }, { engineExpanded = false; onEngineSelected(e) }) } } }
        Box(Modifier.fillMaxWidth()) { Button({ voiceExpanded = true }, Modifier.fillMaxWidth(), enabled = !loadingVoices) { Text(if (loadingVoices) "ĐANG QUÉT…" else voiceLabel) }; DropdownMenu(voiceExpanded, { voiceExpanded = false }) { DropdownMenuItem({ Text("Giọng mặc định") }, { voiceExpanded = false; onVoiceSelected(null) }); voices.forEach { v -> DropdownMenuItem({ Text(v.displayName) }, { voiceExpanded = false; onVoiceSelected(v) }) } } }
        Button(onPreviewVoice, Modifier.fillMaxWidth()) { Text("NGHE THỬ GIỌNG ĐANG CHỌN") }; Row { Button(onRefreshVoices, Modifier.weight(1f)) { Text("QUÉT LẠI") }; Button(onOpenTtsSettings, Modifier.weight(1f)) { Text("CÀI ĐẶT TTS") } }
    } }
}

@Composable private fun BackgroundMusicCard(uri: String?, enabled: Boolean, volume: Float, duckFactor: Float, onSelect: () -> Unit, onClear: () -> Unit, onEnabledChange: (Boolean) -> Unit, onVolumeChange: (Float) -> Unit, onDuckChange: (Float) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { Text("Nhạc nền cục bộ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(if (uri.isNullOrBlank()) "Chưa chọn tệp." else "Đã chọn: ${uri.takeLast(80)}"); Row(verticalAlignment = Alignment.CenterVertically) { Text("Bật nhạc nền", Modifier.weight(1f)); Switch(enabled, onEnabledChange) }; ReferenceFloatSettingsSlider("Âm lượng nền", volume, 0f, 1f, 99, { "%.0f%%".format(it * 100) }, onVolumeChange); ReferenceFloatSettingsSlider("Mức còn lại khi TTS đọc", duckFactor, 0f, 1f, 99, { "%.0f%%".format(it * 100) }, onDuckChange); Row { Button(onSelect, Modifier.weight(1f)) { Text("CHỌN TỆP NHẠC") }; Button(onClear, Modifier.weight(1f), enabled = !uri.isNullOrBlank()) { Text("BỎ TỆP") } } } }
}

@Composable private fun PronunciationCard(rules: List<PronunciationEntity>, onAdd: (String, String) -> Unit, onUpdate: (Long, String, String) -> Unit, onEnabledChange: (Long, Boolean) -> Unit, onDelete: (Long) -> Unit) {
    var addOpen by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<PronunciationEntity?>(null) }; var original by remember { mutableStateOf("") }; var replacement by remember { mutableStateOf("") }
    Column { ReferenceActionButton("＋ THÊM CÁCH ĐỌC", { original = ""; replacement = ""; addOpen = true }, modifier = Modifier.fillMaxWidth()); rules.forEach { row -> ReferenceActionButton("${row.original} → ${row.replacement}", { selected = row }, modifier = Modifier.fillMaxWidth()) } }
    if (addOpen) AlertDialog(onDismissRequest = { addOpen = false }, title = { Text("THÊM CÁCH ĐỌC") }, text = { Column { OutlinedTextField(original, { original = it.take(120) }); OutlinedTextField(replacement, { replacement = it.take(240) }) } }, confirmButton = { TextButton({ onAdd(original, replacement); addOpen = false }) { Text("LƯU") } }, dismissButton = { TextButton({ addOpen = false }) { Text("HỦY") } })
    selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(row.original) }, text = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Bật quy tắc", Modifier.weight(1f)); Switch(row.enabled, { onEnabledChange(row.id, it) }) } }, confirmButton = { TextButton({ onDelete(row.id); selected = null }) { Text("XÓA") } }, dismissButton = { TextButton({ selected = null }) { Text("ĐÓNG") } }) }
    @Suppress("UNUSED_VARIABLE") val retainedUpdate = onUpdate
}

@Composable
private fun VietPhraseCard(state: MainUiState, onImport: () -> Unit, onExport: () -> Unit, onAddRule: (String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit, onUpdateRule: (Long, String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit, onCheckOnline: () -> Unit, onRuleEnabledChange: (Long, Boolean) -> Unit, onDictionaryEnabledChange: (String, Boolean) -> Unit, onDeleteRule: (Long) -> Unit, onConfirmImport: () -> Unit, onCancelImport: () -> Unit, onRollback: (String) -> Unit, onAcceptSuggestion: (String, String) -> Unit, onRejectSuggestion: (String) -> Unit, onPrepareImport: (VietPhraseDictionaryKind?) -> Unit, onDeleteDictionary: (VietPhraseDictionaryKind) -> Unit, onClearAll: () -> Unit, onEnabledChange: (Boolean) -> Unit, onFallbackChange: (Boolean) -> Unit, onDownloadRecommended: () -> Unit) {
    val kinds = VietPhraseDictionaryKind.entries
    var selectedKind by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }; var clearConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Bật VietPhrase", Modifier.weight(1f)); Switch(state.vietPhraseEnabled, onEnabledChange) }
        ReferenceActionButton("NHẬP ZIP", { onPrepareImport(null); onImport() }, modifier = Modifier.fillMaxWidth()); ReferenceActionButton("XUẤT ZIP", onExport, modifier = Modifier.fillMaxWidth()); ReferenceActionButton(if (state.vietPhraseOnlineBusy) "ĐANG KIỂM TRA…" else "CẬP NHẬT", onCheckOnline, enabled = !state.vietPhraseOnlineBusy, modifier = Modifier.fillMaxWidth())
        kinds.forEach { kind -> ReferenceActionButton(kind.fileName, { selectedKind = kind }, modifier = Modifier.fillMaxWidth()) }
        ReferenceActionButton("XÓA TẤT CẢ", { clearConfirm = true }, modifier = Modifier.fillMaxWidth()); ReferenceActionButton("TẢI TỪ MẠNG", onDownloadRecommended, modifier = Modifier.fillMaxWidth()); Row { Text("Hán Việt khi thiếu cụm", Modifier.weight(1f)); Switch(state.vietPhraseFallbackHanViet, onFallbackChange) }
    }
    selectedKind?.let { kind -> AlertDialog(onDismissRequest = { selectedKind = null }, title = { Text(kind.fileName) }, text = { Column { state.vietPhraseDictionaryStates.filter { it.kind == kind.name }.forEach { d -> Row { Text(d.scope, Modifier.weight(1f)); Switch(d.enabled, { onDictionaryEnabledChange(d.id, it) }) } }; Button({ selectedKind = null; onPrepareImport(kind); onImport() }) { Text("NHẬP / THAY THẾ") }; Button({ onDeleteDictionary(kind); selectedKind = null }) { Text("XÓA FILE") } } }, confirmButton = {}, dismissButton = { TextButton({ selectedKind = null }) { Text("ĐÓNG") } }) }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("XÓA TOÀN BỘ VIETPHRASE") }, text = { Text("Xóa tất cả dữ liệu VietPhrase?") }, confirmButton = { TextButton({ onClearAll(); clearConfirm = false }) { Text("XÓA") } }, dismissButton = { TextButton({ clearConfirm = false }) { Text("HỦY") } })
    state.pendingVietPhraseImport?.let { preview -> AlertDialog(onDismissRequest = onCancelImport, title = { Text("XÁC NHẬN NHẬP") }, text = { Text("${preview.sourceName}\n${preview.incomingCount} mục") }, confirmButton = { TextButton(enabled = preview.errorCount == 0, onClick = onConfirmImport) { Text("ÁP DỤNG") } }, dismissButton = { TextButton(onCancelImport) { Text("HỦY") } }) }
    @Suppress("UNUSED_VARIABLE") val retained = listOf(onAddRule, onUpdateRule, onRuleEnabledChange, onDeleteRule, onRollback, onAcceptSuggestion, onRejectSuggestion)
}

@Composable
private fun AiReferenceSettingsDialog(state: MainUiState, onDismiss: () -> Unit, onSave: (AiOnlineSettings, String?, String?) -> Unit, onRefreshModels: (AiProvider, String, String) -> Unit) {
    var enabled by remember(state.aiOnline.enabled) { mutableStateOf(state.aiOnline.enabled) }; var provider by remember(state.aiOnline.provider) { mutableStateOf(state.aiOnline.provider) }; var endpoint by remember(state.aiOnline.endpoint) { mutableStateOf(state.aiOnline.endpoint) }; var model by remember { mutableStateOf(if (provider == AiProvider.GEMINI) state.aiOnline.geminiModel else state.aiOnline.openAiModel) }; var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("THIẾT LẬP AI") },
        text = { Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) { Row(verticalAlignment = Alignment.CenterVertically) { Text("Bật nút AI trong màn hình đọc", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }; Text(if (provider == AiProvider.GEMINI) "Google Gemini" else "OpenAI-compatible"); OutlinedTextField(endpoint, { endpoint = it.take(500) }, label = { Text("Endpoint") }); OutlinedTextField(model, { model = it.take(200) }, label = { Text("Model") }); OutlinedTextField(key, { key = it.take(4096) }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation()); Button({ onRefreshModels(provider, endpoint, key) }) { Text("TẢI DS MODEL") } } },
        confirmButton = { Button({ val updated = state.aiOnline.copy(enabled = enabled, consentGranted = enabled, provider = provider, endpoint = endpoint, model = model, geminiModel = if (provider == AiProvider.GEMINI) model else state.aiOnline.geminiModel, openAiModel = if (provider == AiProvider.OPENAI_COMPATIBLE) model else state.aiOnline.openAiModel); onSave(updated, key.takeIf { provider == AiProvider.GEMINI }, key.takeIf { provider == AiProvider.OPENAI_COMPATIBLE }); onDismiss() }) { Text("LƯU") } }, dismissButton = { TextButton(onDismiss) { Text("HỦY") } },
    )
}

@Composable private fun SceneMusicLibraryCard(tracks: List<SceneMusicTrackEntity>, onSelect: () -> Unit, onUpdate: (String, String, String) -> Unit, onEnabledChange: (String, Boolean) -> Unit, onDelete: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { Text("Thư viện nhạc cảnh", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Button(onSelect, Modifier.fillMaxWidth()) { Text("THÊM TỆP NHẠC CẢNH") }; tracks.forEach { track -> var title by remember(track.id, track.title) { mutableStateOf(track.title) }; var tags by remember(track.id, track.tagsCsv) { mutableStateOf(track.tagsCsv) }; HorizontalDivider(); OutlinedTextField(title, { title = it.take(120) }, label = { Text("Tên tệp nhạc") }); OutlinedTextField(tags, { tags = it.take(500) }, label = { Text("Tag") }); Text("Loudness ước tính ${"%.1f".format(track.loudnessLufsEstimate)} LUFS"); Row { Switch(track.enabled, { onEnabledChange(track.id, it) }); Button({ onUpdate(track.id, title, tags) }, Modifier.weight(1f)) { Text("LƯU") }; Button({ onDelete(track.id) }, Modifier.weight(1f)) { Text("XÓA") } } } } }
}
@Composable private fun FollowingSettingsCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, onCheckNow: () -> Unit) { Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { Row { Text("Tự kiểm tra khoảng 12 giờ một lần", Modifier.weight(1f)); Switch(enabled, onEnabledChange) }; Button(onCheckNow, Modifier.fillMaxWidth()) { Text("KIỂM TRA NGAY") } } } }
@Composable private fun StorageCard(state: MainUiState, onCacheLimitChange: (Int) -> Unit, onTrimCache: () -> Unit, onClearCache: () -> Unit) { val u = state.storageUsage; Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { Text("Đã tải: ${u.downloadedChapters} chương • ${formatBytes(u.downloadedBytes)}"); Text("Bộ nhớ đệm: ${u.cachedChapters} chương • ${formatBytes(u.cachedBytes)}"); SettingsRepository.CACHE_LIMIT_OPTIONS_MIB.forEach { Button({ onCacheLimitChange(it) }) { Text("$it MiB") } }; Row { Button(onTrimCache, Modifier.weight(1f)) { Text("DỌN CACHE") }; Button(onClearCache, Modifier.weight(1f)) { Text("XÓA CACHE") } } } } }
@Composable private fun AudioExportCard(state: MainUiState, onCancel: (String) -> Unit, onResume: (String) -> Unit, onOpen: (AudioExportJobEntity) -> Unit) { Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { state.audioExports.take(8).forEach { job -> Text("${job.storyTitle} • ${job.outputFormat} • ${job.state}"); when (job.state) { DownloadState.QUEUED.name, DownloadState.RUNNING.name -> Button({ onCancel(job.id) }) { Text("HỦY") }; DownloadState.COMPLETED.name -> Button({ onOpen(job) }) { Text("MỞ") }; else -> Button({ onResume(job.id) }) { Text("TIẾP TỤC") } } } } } }
@Composable private fun PerformanceCard(report: String?, onRun: () -> Unit) { Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { Text("Hiệu năng thiết bị", fontWeight = FontWeight.SemiBold); Button(onRun) { Text("CHẠY BENCHMARK NHANH") }; report?.let { Text(it) } } } }
@Composable private fun TransferCard(state: MainUiState, onComponentChange: (BackupComponent, Boolean) -> Unit, onExportBackup: () -> Unit, onRestoreBackup: () -> Unit) { Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(16.dp)) { BackupComponent.entries.forEach { c -> Row { Text(c.label, Modifier.weight(1f)); Switch(c in state.backupComponents, { onComponentChange(c, it) }) } }; Row { Button(onExportBackup, Modifier.weight(1f)) { Text("TẠO BẢN SAO") }; Button(onRestoreBackup, Modifier.weight(1f)) { Text("KHÔI PHỤC") } } } } }
private fun formatBytes(bytes: Long): String = when { bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024)); bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024)); bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0); else -> "$bytes B" }
