package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseScope
import vn.nghetruyen.app.data.local.AudioExportJobEntity
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VietPhraseEntity
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.sources.SourceCheckStatus
import vn.nghetruyen.app.transfer.BackupComponent
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ScreenHeading

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
    onSelectBackgroundMusic: () -> Unit,
    onClearBackgroundMusic: () -> Unit,
    onBackgroundMusicEnabledChange: (Boolean) -> Unit,
    onBackgroundMusicVolumeChange: (Float) -> Unit,
    onBackgroundMusicDuckChange: (Float) -> Unit,
    onAddPronunciation: (String, String) -> Unit,
    onPronunciationEnabledChange: (Long, Boolean) -> Unit,
    onDeletePronunciation: (Long) -> Unit,
    onAddVietPhrase: (String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit,
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
    onAiEnabledChange: (Boolean) -> Unit,
    onAiConsentChange: (Boolean) -> Unit,
    onAiProviderChange: (AiProvider) -> Unit,
    onRefreshGeminiModels: () -> Unit,
    onAiEndpointChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiTemperatureChange: (Float) -> Unit,
    onAiInstructionChange: (String) -> Unit,
    onAiDailyRequestLimitChange: (Int) -> Unit,
    onAiDailyInputCharsLimitChange: (Int) -> Unit,
    onAiMaxRetriesChange: (Int) -> Unit,
    onAiRetryBaseDelayChange: (Int) -> Unit,
    onSaveAiApiKey: (String) -> Unit,
    onClearAiApiKey: () -> Unit,
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
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
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
    onOpenSourceLogin: (String) -> Unit,
    onOpenSourceDiagnosticBrowser: (String) -> Unit,
    onClearSourceSession: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("CÁ NHÂN")
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
        PronunciationCard(
            rules = state.pronunciations,
            onAdd = onAddPronunciation,
            onEnabledChange = onPronunciationEnabledChange,
            onDelete = onDeletePronunciation,
        )
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
        SceneMusicLibraryCard(
            tracks = state.sceneMusicTracks,
            onSelect = onSelectSceneMusic,
            onUpdate = onUpdateSceneMusic,
            onEnabledChange = onSceneMusicEnabledChange,
            onDelete = onDeleteSceneMusic,
        )
        FollowingSettingsCard(
            enabled = state.followingUpdatesEnabled,
            onEnabledChange = onFollowingUpdatesChange,
            onCheckNow = onCheckFollowingNow,
        )
        StorageCard(
            state = state,
            onCacheLimitChange = onCacheLimitChange,
            onTrimCache = onTrimReaderCache,
            onClearCache = onClearReaderCache,
        )
        AudioExportCard(state, onCancelAudioExport, onResumeAudioExport, onOpenAudioExport)
        PerformanceCard(state.performanceReport, onRunPerformanceDiagnostics)
        TransferCard(
            state = state,
            onComponentChange = onBackupComponentChange,
            onExportBackup = onExportBackup,
            onRestoreBackup = onRestoreBackup,
        )
        SourceManagementCard(
            state = state,
            onInstallSourcePack = onInstallSourcePack,
            onImportSourceTrustRotation = onImportSourceTrustRotation,
            onRefreshSourceRepository = onRefreshSourceRepository,
            onRemoveSourceRepository = onRemoveSourceRepository,
            onPrepareRepositorySourceInstall = onPrepareRepositorySourceInstall,
            onConfirmSourcePackInstall = onConfirmSourcePackInstall,
            onCancelSourcePackInstall = onCancelSourcePackInstall,
            onSourcePackEnabledChange = onSourcePackEnabledChange,
            onRollbackSourcePack = onRollbackSourcePack,
            onEnrollSourceTrustKey = onEnrollSourceTrustKey,
            onRevokeSourceTrustKey = onRevokeSourceTrustKey,
            onInspectSourceSelector = onInspectSourceSelector,
            onExportSourceDiagnostics = onExportSourceDiagnostics,
            onClearSourceDiagnostics = onClearSourceDiagnostics,
            onCheckSource = onCheckSource,
            onCheckAllSources = onCheckAllSources,
            onOpenLogin = onOpenSourceLogin,
            onOpenDiagnosticBrowser = onOpenSourceDiagnosticBrowser,
            onClearSession = onClearSourceSession,
        )
        SettingsCard("Kiến trúc ứng dụng", "Kotlin, Compose, Room, DataStore, WorkManager và foreground TTS service. Lua Native Source API 2 chạy trong LuaJ sandbox; không AndroLua, không luajava và không nạp DEX động.")
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
            Text("Điều khiển tai nghe và tự động hóa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SettingSwitch("Nhận thao tác bấm nhiều lần từ tai nghe", state.headsetMultiClickEnabled, onHeadsetMultiClickChange)
            MediaMappingButton("Một lần", state.headsetSingleClickAction, onHeadsetSingleActionChange)
            MediaMappingButton("Hai lần", state.headsetDoubleClickAction, onHeadsetDoubleActionChange)
            MediaMappingButton("Ba lần", state.headsetTripleClickAction, onHeadsetTripleActionChange)
            MediaMappingButton("Nhấn giữ", state.headsetLongPressAction, onHeadsetLongActionChange)
            SettingSwitch("Tạm dừng khi tai nghe hoặc Bluetooth bị ngắt", state.pauseOnHeadsetDisconnect, onPauseOnHeadsetDisconnectChange)
            SettingSwitch("Khôi phục phiên nghe sau khi tiến trình bị đóng", state.restorePlaybackAfterProcessDeath, onRestorePlaybackChange)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingSwitch("Tự phân vai AI khi chương chưa có kế hoạch", state.autoVoiceCastEnabled, onAutoVoiceCastChange)
            SettingSwitch("Tự lập nhạc cảnh AI khi chương chưa có kế hoạch", state.autoSceneMusicEnabled, onAutoSceneMusicChange)
            SettingSwitch("Chuẩn bị trước phân vai và nhạc cảnh", state.prefetchNarrationPlansEnabled, onPrefetchNarrationPlansChange)
            Text("Cửa sổ chuẩn bị AI: ${state.narrationPrefetchWindowChapters} chương", modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onNarrationPrefetchWindowChange(state.narrationPrefetchWindowChapters - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("ÍT HƠN") }
                Button({ onNarrationPrefetchWindowChange(state.narrationPrefetchWindowChapters + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("NHIỀU HƠN") }
            }
            SettingSwitch("Xử lý Sonic để đổi tốc độ/cao độ độc lập", state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
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
            SettingSwitch("Bộ nhớ đệm giọng TTS/Sonic có checksum", state.ttsCacheEnabled, onTtsCacheEnabledChange)
            Text("Giới hạn cache TTS: ${state.ttsCacheLimitMiB} MiB")
            Row(Modifier.fillMaxWidth()) {
                Button({ onTtsCacheLimitChange(state.ttsCacheLimitMiB / 2) }, Modifier.weight(1f).padding(2.dp)) { Text("CACHE -") }
                Button({ onTtsCacheLimitChange(state.ttsCacheLimitMiB * 2) }, Modifier.weight(1f).padding(2.dp)) { Text("CACHE +") }
            }
            SettingSwitch("Chuẩn hóa âm lượng giữa giọng và engine", state.normalizeTtsVolumeEnabled, onNormalizeTtsVolumeChange)
            Text("Mức giọng mục tiêu: ${"%.1f".format(state.ttsTargetLufs)} LUFS")
            Row(Modifier.fillMaxWidth()) {
                Button({ onTtsTargetLufsChange(state.ttsTargetLufs - 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIỌNG NHỎ") }
                Button({ onTtsTargetLufsChange(state.ttsTargetLufs + 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIỌNG LỚN") }
            }
            SettingSwitch("Giữ nhạc phù hợp khi chuyển chương", state.sceneMusicContinueAcrossChapters, onSceneMusicContinueChange)
            Text("Chế độ playlist nhạc cảnh", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            SceneMusicPlaybackMode.entries.forEach { mode ->
                Button({ onSceneMusicPlaybackModeChange(mode) }, Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    val label = when (mode) {
                        SceneMusicPlaybackMode.SEQUENTIAL -> "TUẦN TỰ"
                        SceneMusicPlaybackMode.SHUFFLE -> "NGẪU NHIÊN"
                        SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "THÔNG MINH, TRÁNH LẶP"
                    }
                    Text((if (state.sceneMusicPlaybackMode == mode) "✓ " else "") + label)
                }
            }
            Text("Mức âm lượng mục tiêu: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS ước tính")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSceneMusicTargetLufsChange(state.sceneMusicTargetLufs - 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHỎ HƠN") }
                Button({ onSceneMusicTargetLufsChange(state.sceneMusicTargetLufs + 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("LỚN HƠN") }
            }
            Text("Tránh lặp ${state.sceneMusicAvoidRepeatWindow} track gần nhất")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSceneMusicAvoidRepeatWindowChange(state.sceneMusicAvoidRepeatWindow - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("GIẢM") }
                Button({ onSceneMusicAvoidRepeatWindowChange(state.sceneMusicAvoidRepeatWindow + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("TĂNG") }
            }
            Text("Crossfade nhạc cảnh: ${state.sceneMusicCrossfadeMillis} ms", modifier = Modifier.padding(top = 8.dp))
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
            Text("AI chỉ chạy khi AI online và đồng ý gửi nội dung đã được bật.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
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
            Text("Bộ máy và giọng đọc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                Text("Tự chuyển và đọc chương kế tiếp", Modifier.weight(1f))
                Switch(autoNext, onAutoNextChange)
            }
            Text("Khi âm thanh khác phát xen", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
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
            Text("Giọng của bộ máy đang chọn", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
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
                    Text(if (showAllVoices) "THU GỌN DANH SÁCH GIỌNG" else "HIỂN THỊ TẤT CẢ ${voices.size} GIỌNG")
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
    onEnabledChange: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var original by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var showAllRules by remember { mutableStateOf(false) }
    val visibleRules = if (showAllRules) rules else rules.take(MAX_VISIBLE_PRONUNCIATIONS)
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Từ điển phát âm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Quy tắc được áp dụng cục bộ trước khi gửi văn bản cho TTS. Chuỗi dài hơn được ưu tiên và kết quả không bị thay thế lặp lại.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = original,
                onValueChange = { original = it.take(120) },
                label = { Text("Từ hoặc cụm từ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it.take(240) },
                label = { Text("Cách đọc thay thế") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                onClick = {
                    onAdd(original, replacement)
                    if (original.isNotBlank() && replacement.isNotBlank()) {
                        original = ""
                        replacement = ""
                    }
                },
                enabled = original.isNotBlank() && replacement.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("THÊM QUY TẮC") }

            visibleRules.forEach { rule ->
                HorizontalDivider(Modifier.padding(vertical = 7.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.original, fontWeight = FontWeight.SemiBold)
                        Text("Đọc thành: ${rule.replacement}", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onEnabledChange(rule.id, it) },
                    )
                    Button(onClick = { onDelete(rule.id) }, modifier = Modifier.padding(start = 4.dp)) {
                        Text("XÓA")
                    }
                }
            }
            if (rules.size > MAX_VISIBLE_PRONUNCIATIONS) {
                Button(
                    onClick = { showAllRules = !showAllRules },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(if (showAllRules) "THU GỌN QUY TẮC" else "HIỂN THỊ TẤT CẢ ${rules.size} QUY TẮC")
                }
            }
        }
    }
}


@Composable
private fun VietPhraseCard(
    state: MainUiState,
    onAdd: (String, String, Int, VietPhraseDictionaryKind, VietPhraseScope, String?, Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCheckOnline: () -> Unit,
    onInstallRecommended: () -> Unit,
    onEnabledChange: (Long, Boolean) -> Unit,
    onDictionaryEnabledChange: (String, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onRollback: (String) -> Unit,
    onAcceptSuggestion: (String, String) -> Unit,
    onRejectSuggestion: (String) -> Unit,
) {
    val rules = state.vietPhraseRules
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("0") }
    var selectedKind by remember { mutableStateOf(VietPhraseDictionaryKind.VIET_PHRASE) }
    var selectedScope by remember { mutableStateOf(VietPhraseScope.GLOBAL) }
    var storyId by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val visible = if (showAll) rules else rules.take(24)
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("VietPhrase nâng cao", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Bảy lớp từ điển, scope theo truyện, preview/diff, snapshot rollback và import TXT/DIC/DAT/ZIP. Mọi xử lý diễn ra trên thiết bị.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Button(onImport, Modifier.weight(1f).padding(2.dp)) { Text("NHẬP / XEM TRƯỚC") }
                Button(onExport, Modifier.weight(1f).padding(2.dp), enabled = rules.isNotEmpty()) { Text("XUẤT ZIP") }
            }
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = onCheckOnline,
                    enabled = !state.vietPhraseOnlineBusy,
                    modifier = Modifier.weight(1f).padding(2.dp),
                ) { Text(if (state.vietPhraseOnlineBusy) "ĐANG KIỂM TRA" else "TÌM BẢN ONLINE") }
                Button(
                    onClick = onInstallRecommended,
                    enabled = !state.vietPhraseOnlineBusy,
                    modifier = Modifier.weight(1f).padding(2.dp),
                ) { Text("TẢI VÀ CẬP NHẬT") }
            }
            if (state.vietPhraseOnlineStatus.isNotBlank()) {
                Text(state.vietPhraseOnlineStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                "Chỉ tải qua HTTPS từ nguồn được tin cậy; kiểm tra dung lượng, kiểu tệp và checksum trước khi nhập. Luôn tạo snapshot để rollback.",
                style = MaterialTheme.typography.bodySmall,
            )

            state.pendingVietPhraseImport?.let { preview ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Bản xem trước: ${preview.sourceName}", fontWeight = FontWeight.SemiBold)
                Text(
                    "${preview.sourceFormat} • nhận ${preview.incomingCount} • trùng ${preview.duplicateCount} • " +
                        "thêm ${preview.plan.diff.added.size} • sửa ${preview.plan.diff.changed.size} • xóa ${preview.plan.diff.removed.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Xung đột: ${preview.errorCount} lỗi, ${preview.warningCount} cảnh báo. Dữ liệu chưa thay đổi.", style = MaterialTheme.typography.bodySmall)
                preview.plan.conflicts.take(8).forEach { conflict ->
                    Text("${conflict.severity}: ${conflict.message}", style = MaterialTheme.typography.bodySmall)
                }
                preview.warnings.take(4).forEach { warning -> Text("Cảnh báo: $warning", style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth()) {
                    Button(onConfirmImport, Modifier.weight(1f).padding(2.dp), enabled = preview.plan.canCommit) { Text("XÁC NHẬN NHẬP") }
                    Button(onCancelImport, Modifier.weight(1f).padding(2.dp)) { Text("HỦY") }
                }
            }

            if (state.vietPhraseDictionaryStates.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Bộ từ điển đã nhập", fontWeight = FontWeight.SemiBold)
                state.vietPhraseDictionaryStates.take(12).forEach { dictionary ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${dictionary.kind} • ${dictionary.scope}${dictionary.storyId.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()} • ${dictionary.entryCount} mục • ${dictionary.sourceFormat}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(dictionary.enabled, { onDictionaryEnabledChange(dictionary.id, it) })
                    }
                }
            }

            OutlinedTextField(source, { source = it.take(2_000) }, label = { Text("Cụm nguồn") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(target, { target = it.take(4_000) }, label = { Text("Cụm tiếng Việt") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(priority, { priority = it.filter { ch -> ch == '-' || ch.isDigit() }.take(4) }, label = { Text("Ưu tiên -999 đến 999") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(
                    onClick = {
                        val kinds = VietPhraseDictionaryKind.entries
                        selectedKind = kinds[(kinds.indexOf(selectedKind) + 1) % kinds.size]
                    },
                    modifier = Modifier.weight(1f).padding(2.dp),
                ) { Text("LOẠI: ${selectedKind.name}") }
                Button(
                    onClick = { selectedScope = if (selectedScope == VietPhraseScope.GLOBAL) VietPhraseScope.STORY else VietPhraseScope.GLOBAL },
                    modifier = Modifier.weight(1f).padding(2.dp),
                ) { Text("PHẠM VI: ${selectedScope.name}") }
            }
            if (selectedScope == VietPhraseScope.STORY) {
                OutlinedTextField(storyId, { storyId = it.trim().take(500) }, label = { Text("Story ID bắt buộc") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Không phân biệt hoa thường", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(ignoreCase, { ignoreCase = it })
            }
            Button(
                onClick = {
                    onAdd(source, target, priority.toIntOrNull() ?: 0, selectedKind, selectedScope, storyId.ifBlank { null }, ignoreCase)
                    if (source.isNotBlank() && target.isNotBlank()) { source = ""; target = ""; priority = "0" }
                },
                enabled = source.isNotBlank() && target.isNotBlank() && (selectedScope == VietPhraseScope.GLOBAL || storyId.isNotBlank()),
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            ) { Text("THÊM ${selectedKind.name}") }

            visible.forEach { rule ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${rule.source} → ${rule.target}", fontWeight = FontWeight.SemiBold)
                        Text("${rule.kind} • ${rule.scope}${rule.storyId.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()} • ưu tiên ${rule.priority}", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(rule.enabled, { onEnabledChange(rule.id, it) })
                    Button({ onDelete(rule.id) }, Modifier.padding(start = 4.dp)) { Text("XÓA") }
                }
            }
            if (rules.size > 24) Button({ showAll = !showAll }, Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(if (showAll) "THU GỌN" else "HIỆN TẤT CẢ ${rules.size} QUY TẮC")
            }

            val pendingSuggestions = state.vietPhraseSuggestions.filter { it.status == "PENDING" }
            if (pendingSuggestions.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Suggestion AIReplace chờ duyệt", fontWeight = FontWeight.SemiBold)
                pendingSuggestions.take(12).forEach { suggestion ->
                    var edited by remember(suggestion.id, suggestion.editedTarget) { mutableStateOf(suggestion.editedTarget) }
                    Text(suggestion.source, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                    OutlinedTextField(edited, { edited = it.take(4_000) }, label = { Text("Kết quả sau khi duyệt") }, modifier = Modifier.fillMaxWidth())
                    if (suggestion.reason.isNotBlank()) Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth()) {
                        Button({ onAcceptSuggestion(suggestion.id, edited) }, Modifier.weight(1f).padding(2.dp), enabled = edited.isNotBlank()) { Text("CHẤP NHẬN") }
                        Button({ onRejectSuggestion(suggestion.id) }, Modifier.weight(1f).padding(2.dp)) { Text("TỪ CHỐI") }
                    }
                }
            }

            if (state.vietPhraseSnapshots.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Snapshot rollback", fontWeight = FontWeight.SemiBold)
                state.vietPhraseSnapshots.take(8).forEach { snapshot ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(snapshot.label)
                            Text("${snapshot.ruleCount} quy tắc • ${snapshot.checksum.take(12)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button({ onRollback(snapshot.id) }) { Text("ROLLBACK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiOnlineCard(
    state: MainUiState,
    onEnabledChange: (Boolean) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onProviderChange: (AiProvider) -> Unit,
    onRefreshGeminiModels: () -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onInstructionChange: (String) -> Unit,
    onDailyRequestLimitChange: (Int) -> Unit,
    onDailyInputCharsLimitChange: (Int) -> Unit,
    onMaxRetriesChange: (Int) -> Unit,
    onRetryBaseDelayChange: (Int) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
) {
    var endpoint by remember(state.aiOnline.endpoint) { mutableStateOf(state.aiOnline.endpoint) }
    var model by remember(state.aiOnline.model) { mutableStateOf(state.aiOnline.model) }
    var instruction by remember(state.aiOnline.translationInstruction) { mutableStateOf(state.aiOnline.translationInstruction) }
    var apiKey by remember(state.aiOnline.provider) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("AI online: dịch, phân vai và nhạc cảnh", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Nội dung chỉ được gửi khi cả consent và công tắc AI đều bật. API key được mã hóa bằng Android Keystore và không nằm trong backup.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tôi đồng ý gửi chương tới nhà cung cấp đã chọn", Modifier.weight(1f))
                Switch(state.aiOnline.consentGranted, onConsentChange)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật AI online", Modifier.weight(1f))
                Switch(state.aiOnline.enabled, onEnabledChange)
            }
            Text("Nhà cung cấp", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    { onProviderChange(AiProvider.GEMINI) },
                    Modifier.weight(1f).padding(2.dp),
                ) { Text((if (state.aiOnline.provider == AiProvider.GEMINI) "✓ " else "") + "GEMINI NATIVE") }
                Button(
                    { onProviderChange(AiProvider.OPENAI_COMPATIBLE) },
                    Modifier.weight(1f).padding(2.dp),
                ) { Text((if (state.aiOnline.provider == AiProvider.OPENAI_COMPATIBLE) "✓ " else "") + "OPENAI-COMPATIBLE") }
            }
            if (state.aiOnline.provider == AiProvider.OPENAI_COMPATIBLE) {
                OutlinedTextField(endpoint, { endpoint = it.take(500) }, label = { Text("HTTPS chat-completions endpoint") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                Text("Gemini dùng trực tiếp generateContent tại generativelanguage.googleapis.com; không đi qua endpoint OpenAI-compatible.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(model, { model = it.take(200) }, label = { Text(if (state.aiOnline.provider == AiProvider.GEMINI) "Gemini model" else "Tên model") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (state.aiOnline.provider == AiProvider.GEMINI) {
                Button(
                    onClick = onRefreshGeminiModels,
                    enabled = state.aiHasApiKey && !state.aiModelDiscoveryBusy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text(if (state.aiModelDiscoveryBusy) "ĐANG TẢI MODEL…" else "TẢI DANH SÁCH MODEL GEMINI") }
                state.aiAvailableModels.take(12).forEach { availableModel ->
                    Button(
                        onClick = {
                            model = availableModel
                            onModelChange(availableModel)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    ) { Text((if (state.aiOnline.model == availableModel) "✓ " else "") + availableModel) }
                }
                if (state.aiAvailableModels.size > 12) {
                    Text("Đang hiển thị 12/${state.aiAvailableModels.size} model; có thể nhập tên model khác ở ô trên.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Button({ onTemperatureChange(state.aiOnline.temperature - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("ÍT SÁNG TẠO") }
                Text("${"%.1f".format(state.aiOnline.temperature)}", Modifier.padding(vertical = 12.dp, horizontal = 4.dp))
                Button({ onTemperatureChange(state.aiOnline.temperature + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("SÁNG TẠO") }
            }
            OutlinedTextField(instruction, { instruction = it.take(2000) }, label = { Text("Yêu cầu dịch bổ sung") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Button({ onEndpointChange(endpoint); onModelChange(model); onInstructionChange(instruction) }, Modifier.fillMaxWidth().padding(top = 5.dp)) { Text("LƯU CẤU HÌNH AI") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val latestUsage = state.aiUsageRecent.firstOrNull()
            Text("Hạn mức AI trên thiết bị", fontWeight = FontWeight.SemiBold)
            Text("Hôm nay: ${latestUsage?.requestCount ?: 0}/${state.aiOnline.dailyRequestLimit} yêu cầu • ${latestUsage?.inputChars ?: 0}/${state.aiOnline.dailyInputCharsLimit} ký tự đầu vào • ${latestUsage?.retryCount ?: 0} lần thử lại", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                Button({ onDailyRequestLimitChange(state.aiOnline.dailyRequestLimit - 5) }, Modifier.weight(1f).padding(2.dp)) { Text("YÊU CẦU -") }
                Button({ onDailyRequestLimitChange(state.aiOnline.dailyRequestLimit + 5) }, Modifier.weight(1f).padding(2.dp)) { Text("YÊU CẦU +") }
            }
            Row(Modifier.fillMaxWidth()) {
                Button({ onDailyInputCharsLimitChange(state.aiOnline.dailyInputCharsLimit - 50_000) }, Modifier.weight(1f).padding(2.dp)) { Text("KÝ TỰ -") }
                Button({ onDailyInputCharsLimitChange(state.aiOnline.dailyInputCharsLimit + 50_000) }, Modifier.weight(1f).padding(2.dp)) { Text("KÝ TỰ +") }
            }
            Text("Thử lại tối đa ${state.aiOnline.maxRetries} lần • chờ gốc ${state.aiOnline.retryBaseDelayMillis} ms")
            Row(Modifier.fillMaxWidth()) {
                Button({ onMaxRetriesChange(state.aiOnline.maxRetries - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("RETRY -") }
                Button({ onMaxRetriesChange(state.aiOnline.maxRetries + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("RETRY +") }
            }
            Row(Modifier.fillMaxWidth()) {
                Button({ onRetryBaseDelayChange(state.aiOnline.retryBaseDelayMillis - 250) }, Modifier.weight(1f).padding(2.dp)) { Text("BACKOFF -") }
                Button({ onRetryBaseDelayChange(state.aiOnline.retryBaseDelayMillis + 250) }, Modifier.weight(1f).padding(2.dp)) { Text("BACKOFF +") }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.take(4096) },
                label = { Text((if (state.aiOnline.provider == AiProvider.GEMINI) "Gemini " else "OpenAI-compatible ") + if (state.aiHasApiKey) "key mới" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Button({ onSaveApiKey(apiKey); apiKey = "" }, Modifier.weight(1f).padding(2.dp), enabled = apiKey.length >= 8) { Text(if (state.aiHasApiKey) "THAY KHÓA" else "LƯU KHÓA") }
                Button(onClearApiKey, Modifier.weight(1f).padding(2.dp), enabled = state.aiHasApiKey) { Text("XÓA KHÓA") }
            }
            Text(if (state.aiHasApiKey) "Đã có API key mã hóa trên thiết bị." else "Chưa có API key.", style = MaterialTheme.typography.bodySmall)
        }
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
            Text(
                "Dùng TTS trên thiết bị, áp dụng vai giọng, lưu checkpoint theo đoạn và có thể tiếp tục sau khi bị gián đoạn.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
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
                label = { Text("URL HTTPS của repository index") },
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
            ) { Text("ĐỐI CHIẾU & THÊM KHÓA") }
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
            Text("Chọn nhóm áp dụng cho cả tệp xuất và lần khôi phục tiếp theo. Tệp ZIP có phiên bản, danh sách thành phần và SHA-256.", Modifier.padding(vertical = 6.dp))
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
    BackupComponent.READING -> "Tiến độ, bookmark, ghi chú và phát âm."
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
