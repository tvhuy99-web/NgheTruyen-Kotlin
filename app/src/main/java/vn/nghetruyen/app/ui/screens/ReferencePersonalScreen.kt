package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.AudioExportJobEntity
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.sourceplatform.SourceInstallPreview
import vn.nghetruyen.app.transfer.BackupComponent
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceText
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtra
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras

@Composable
fun ReferencePersonalScreen(
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
    onPronunciationEnabledChange: (Long, Boolean) -> Unit,
    onDeletePronunciation: (Long) -> Unit,
    onAddVietPhrase: (String, String, Int, VietPhraseDictionaryKind, vn.nghetruyen.app.ai.vietphrase.VietPhraseScope, String?, Boolean) -> Unit,
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
    val context = LocalContext.current
    val app = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf("home") }
    var showSettings by remember { mutableStateOf(false) }
    var showPronunciation by remember { mutableStateOf(false) }
    var showVietPhrase by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var showVoiceCast by remember { mutableStateOf(false) }
    var showOther by remember { mutableStateOf(false) }
    var showBackupScope by remember { mutableStateOf<String?>(null) }
    var showBackupLog by remember { mutableStateOf(false) }
    var showClearDownloads by remember { mutableStateOf(false) }
    var showResetFirst by remember { mutableStateOf(false) }
    var showResetFinal by remember { mutableStateOf(false) }
    var showExtensionsInstalled by remember { mutableStateOf(false) }
    var showExtensionsRepositories by remember { mutableStateOf(false) }
    var showAddRepository by remember { mutableStateOf(false) }
    var showExtensionDiagnostics by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        when (page) {
            "extensions" -> {
                PersonalBackButton("QUAY LẠI CÁ NHÂN") { page = "home" }
                PersonalTitle("TIỆN ÍCH MỞ RỘNG")
                PersonalMenuButton("ĐÃ CÀI (${state.sourcePacks.size})") { showExtensionsInstalled = true }
                PersonalMenuButton("KHO TIỆN ÍCH (${state.sourceRepositories.size})") { showExtensionsRepositories = true }
                PersonalMenuButton("THÊM KHO / LIÊN KẾT") { showAddRepository = true }
                PersonalMenuButton("CÀI TỪ FILE", onInstallSourcePack)
                PersonalMenuButton("CHẨN ĐOÁN") { showExtensionDiagnostics = true }
            }
            else -> {
                PersonalTitle("CÁ NHÂN")
                PersonalMenuButton("Cài đặt") { showSettings = true }
                PersonalMenuButton("Tiện ích mở rộng") { page = "extensions" }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("CÀI ĐẶT ỨNG DỤNG") },
            text = {
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    SettingsButton("TỪ ĐIỂN PHÁT ÂM TTS") { showSettings = false; showPronunciation = true }
                    SettingsButton("VIETPHRASE / CHUYỂN NGỮ") { showSettings = false; showVietPhrase = true }
                    SettingsButton("THIẾT LẬP AI") { showSettings = false; showAi = true }
                    SettingsButton("PHÂN VAI TTS BẰNG AI") { showSettings = false; showVoiceCast = true }
                    Text("Mức nhật ký chẩn đoán", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    DiagnosticsSelector(state.diagnosticsMode, onDiagnosticsModeChange)
                    SettingsButton("CÀI ĐẶT KHÁC") { showSettings = false; showOther = true }
                    SettingsButton("SAO LƯU DỮ LIỆU") { showSettings = false; showBackupScope = "backup" }
                    SettingsButton("KHÔI PHỤC DỮ LIỆU") { showSettings = false; showBackupScope = "restore" }
                    SettingsButton("NHẬT KÝ SAO LƯU VÀ KHÔI PHỤC") { showSettings = false; showBackupLog = true }
                    SettingsButton("XÓA TRUYỆN ĐÃ TẢI") { showSettings = false; showClearDownloads = true }
                    SettingsButton("ĐẶT LẠI ỨNG DỤNG NHƯ MỚI") { showSettings = false; showResetFirst = true }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("ĐÓNG") } },
        )
    }

    if (showPronunciation) {
        PronunciationReferenceDialog(
            items = state.pronunciations,
            onAdd = onAddPronunciation,
            onUpdate = { item, original, replacement ->
                scope.launch {
                    app.container.database.pronunciationDao().upsert(
                        item.copy(original = original.trim(), replacement = replacement.trim(), updatedAt = System.currentTimeMillis()),
                    )
                }
            },
            onDelete = onDeletePronunciation,
            onDismiss = { showPronunciation = false; showSettings = true },
        )
    }

    if (showVietPhrase) {
        VietPhraseReferenceDialog(
            state = state,
            onImport = onImportVietPhrase,
            onExport = onExportVietPhrase,
            onInstallOnline = onInstallRecommendedVietPhrase,
            onDeleteRule = onDeleteVietPhrase,
            onConfirmImport = onConfirmVietPhraseImport,
            onCancelImport = onCancelVietPhraseImport,
            onDismiss = { showVietPhrase = false; showSettings = true },
        )
    }

    if (showAi) {
        AiReferenceSettingsDialog(
            state = state,
            onDismiss = { showAi = false; showSettings = true },
            onSave = onSaveAiSettings,
            onRefreshModels = onRefreshAiModels,
        )
    }

    if (showVoiceCast) {
        GlobalVoiceCastReferenceDialog(
            state = state,
            onAutoVoiceCastChange = onAutoVoiceCastChange,
            onSave = onSaveGlobalVoiceRole,
            onEnabledChange = onGlobalVoiceRoleEnabledChange,
            onDelete = { role -> ReferenceVoiceRoleExtras.remove(context, role.id); onDeleteGlobalVoiceRole(role.id) },
            onRestore = onRestoreGlobalVoiceProfiles,
            onPreview = onPreviewGlobalVoiceRole,
            onDismiss = { showVoiceCast = false; showSettings = true },
        )
    }

    if (showOther) {
        AlertDialog(
            onDismissRequest = { showOther = false; showSettings = true },
            title = { Text("CÀI ĐẶT KHÁC") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Đọc liên tục khi có cuộc gọi hoặc tin nhắn", Modifier.weight(1f))
                        Switch(
                            checked = state.audioInterruptionMode != AudioInterruptionMode.PAUSE,
                            onCheckedChange = { enabled ->
                                onInterruptionModeChange(if (enabled) AudioInterruptionMode.CONTINUE_DUCKED else AudioInterruptionMode.PAUSE)
                            },
                        )
                    }
                    Text(
                        "Mặc định tắt. Khi bật, TTS không tự tạm dừng khi Android báo gián đoạn âm thanh tạm thời. Truyện có thể phát chồng lên chuông, thông báo hoặc âm thanh cuộc gọi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showOther = false; showSettings = true }) { Text("ĐÓNG") } },
        )
    }

    showBackupScope?.let { operation ->
        BackupScopeDialog(
            restore = operation == "restore",
            onSelect = { components ->
                BackupComponent.entries.forEach { component -> onBackupComponentChange(component, component in components) }
                showBackupScope = null
                if (operation == "restore") onRestoreBackup() else onExportBackup()
            },
            onDismiss = { showBackupScope = null; showSettings = true },
        )
    }

    if (showBackupLog) {
        var logText by remember(showBackupLog) { mutableStateOf(app.container.backupHistoryStore.logText()) }
        AlertDialog(
            onDismissRequest = { showBackupLog = false; showSettings = true },
            title = { Text("NHẬT KÝ SAO LƯU VÀ KHÔI PHỤC") },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    Text("Tệp: ${app.container.backupHistoryStore.logPath()}", fontWeight = FontWeight.SemiBold)
                    Text(logText.ifBlank { "Chưa có nhật ký sao lưu hoặc khôi phục." }, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showBackupLog = false; showSettings = true }) { Text("ĐÓNG") } },
            dismissButton = {
                TextButton(onClick = {
                    app.container.backupHistoryStore.clear()
                    logText = ""
                }) { Text("XÓA NHẬT KÝ") }
            },
        )
    }

    if (showClearDownloads) {
        AlertDialog(
            onDismissRequest = { showClearDownloads = false; showSettings = true },
            title = { Text("XÓA TRUYỆN ĐÃ TẢI") },
            text = { Text("Xóa toàn bộ nội dung truyện đã tải khỏi thiết bị? Tiến độ đọc, lịch sử và dấu trang vẫn được giữ lại.") },
            confirmButton = { TextButton(onClick = { showClearDownloads = false; onClearDownloadedStories(); showSettings = true }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { showClearDownloads = false; showSettings = true }) { Text("HỦY") } },
        )
    }

    if (showResetFirst) {
        AlertDialog(
            onDismissRequest = { showResetFirst = false; showSettings = true },
            title = { Text("ĐẶT LẠI ỨNG DỤNG NHƯ MỚI") },
            text = { Text("Thao tác này sẽ xóa toàn bộ dữ liệu và cài đặt của ứng dụng, gồm tiến độ đọc, dấu trang, truyện đã tải, từ điển, cấu hình AI và tiện ích. Bạn có muốn tiếp tục?") },
            confirmButton = { TextButton(onClick = { showResetFirst = false; showResetFinal = true }) { Text("TIẾP TỤC") } },
            dismissButton = { TextButton(onClick = { showResetFirst = false; showSettings = true }) { Text("HỦY") } },
        )
    }

    if (showResetFinal) {
        AlertDialog(
            onDismissRequest = { showResetFinal = false; showSettings = true },
            title = { Text("XÁC NHẬN LẦN CUỐI") },
            text = { Text("Dữ liệu sau khi xóa không thể khôi phục nếu bạn chưa sao lưu. Đặt lại ứng dụng ngay?") },
            confirmButton = { TextButton(onClick = { showResetFinal = false; onFactoryResetApplication() }) { Text("ĐẶT LẠI NGAY") } },
            dismissButton = { TextButton(onClick = { showResetFinal = false; showSettings = true }) { Text("HỦY") } },
        )
    }

    if (showExtensionsInstalled) {
        InstalledExtensionsReferenceDialog(
            state = state,
            onEnabledChange = onSourcePackEnabledChange,
            onCheckSource = onCheckSource,
            onUpdate = onUpdateSourcePack,
            onExport = onExportSourcePack,
            onRemove = onRemoveSourcePack,
            onDismiss = { showExtensionsInstalled = false },
        )
    }

    if (showExtensionsRepositories) {
        RepositoriesReferenceDialog(
            state = state,
            onRefresh = onRefreshSourceRepository,
            onRemove = onRemoveSourceRepository,
            onPrepareInstall = onPrepareRepositorySourceInstall,
            onAdd = { showAddRepository = true },
            onDismiss = { showExtensionsRepositories = false },
        )
    }

    if (showAddRepository) {
        AddRepositoryReferenceDialog(
            onSubmit = onRefreshSourceRepository,
            onDismiss = { showAddRepository = false },
        )
    }

    if (showExtensionDiagnostics) {
        AlertDialog(
            onDismissRequest = { showExtensionDiagnostics = false },
            title = { Text("NHẬT KÝ CHẨN ĐOÁN") },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    if (state.sourceDiagnostics.isEmpty()) Text("Chưa có nhật ký chẩn đoán tiện ích.")
                    state.sourceDiagnostics.take(100).forEach { event ->
                        Text(
                            "${event.severity} • ${event.sourceId} • ${event.category}/${event.name}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExtensionDiagnostics = false }) { Text("ĐÓNG") } },
            dismissButton = { TextButton(onClick = onClearSourceDiagnostics) { Text("XÓA NHẬT KÝ") } },
        )
    }

    state.pendingSourceInstall?.let { preview ->
        PendingInstallReferenceDialog(preview, state.pendingSourceInstallWarnings, onConfirmSourcePackInstall, onCancelSourcePackInstall)
    }
}

@Composable
private fun PersonalTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(14.dp))
}

@Composable
private fun PersonalMenuButton(text: String, onClick: () -> Unit) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        normalColor = ReferencePanelBackground,
        normalContentColor = ReferenceText,
        minHeight = 64.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PersonalBackButton(text: String, onClick: () -> Unit) {
    ReferenceActionButton(text, onClick, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(4.dp))
}

@Composable
private fun SettingsButton(text: String, onClick: () -> Unit) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        normalColor = ReferencePanelBackground,
        normalContentColor = ReferenceText,
        minHeight = 58.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun DiagnosticsSelector(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (value) { "basic" -> "Gỡ lỗi cơ bản"; "advanced" -> "Gỡ lỗi nâng cao"; else -> "Tắt" }
    Box(Modifier.fillMaxWidth()) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label ▼") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("off" to "Tắt", "basic" to "Gỡ lỗi cơ bản", "advanced" to "Gỡ lỗi nâng cao").forEach { (mode, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(mode) })
            }
        }
    }
}

@Composable
private fun PronunciationReferenceDialog(
    items: List<PronunciationEntity>,
    onAdd: (String, String) -> Unit,
    onUpdate: (PronunciationEntity, String, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var edit by remember { mutableStateOf<PronunciationEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menuItem by remember { mutableStateOf<PronunciationEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TỪ ĐIỂN PHÁT ÂM TTS") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                ReferenceActionButton("＋ THÊM CÁCH ĐỌC", { adding = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                items.forEach { item ->
                    ReferenceActionButton(
                        text = "${item.original} → ${item.replacement}",
                        onClick = { menuItem = item },
                        normalColor = ReferencePanelBackground,
                        normalContentColor = ReferenceText,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
                if (items.isEmpty()) Text("Chưa có cách đọc tùy chỉnh.", modifier = Modifier.padding(12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
    menuItem?.let { item ->
        AlertDialog(
            onDismissRequest = { menuItem = null },
            title = { Text(item.original) },
            text = { Text("${item.original} → ${item.replacement}") },
            confirmButton = { TextButton(onClick = { edit = item; menuItem = null }) { Text("SỬA") } },
            dismissButton = { Row {
                TextButton(onClick = { onDelete(item.id); menuItem = null }) { Text("XÓA") }
                TextButton(onClick = { menuItem = null }) { Text("ĐÓNG") }
            } },
        )
    }
    if (adding || edit != null) {
        val current = edit
        var original by remember(adding, current?.id) { mutableStateOf(current?.original.orEmpty()) }
        var replacement by remember(adding, current?.id) { mutableStateOf(current?.replacement.orEmpty()) }
        AlertDialog(
            onDismissRequest = { adding = false; edit = null },
            title = { Text(if (current == null) "THÊM CÁCH ĐỌC" else "SỬA CÁCH ĐỌC") },
            text = { Column {
                OutlinedTextField(original, { original = it.take(300) }, label = { Text("Từ hoặc cụm từ gốc") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replacement, { replacement = it.take(500) }, label = { Text("TTS sẽ đọc thành") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            } },
            confirmButton = { TextButton(enabled = original.isNotBlank() && replacement.isNotBlank(), onClick = {
                if (current == null) onAdd(original, replacement) else onUpdate(current, original, replacement)
                adding = false; edit = null
            }) { Text("LƯU") } },
            dismissButton = { TextButton(onClick = { adding = false; edit = null }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun VietPhraseReferenceDialog(
    state: MainUiState,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onInstallOnline: () -> Unit,
    onDeleteRule: (Long) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ReferenceVietPhraseRuntime.enabled) }
    var fallback by remember { mutableStateOf(ReferenceVietPhraseRuntime.fallbackHanViet) }
    var fileMenu by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }
    var deleteKind by remember { mutableStateOf<VietPhraseDictionaryKind?>(null) }
    var deleteAll by remember { mutableStateOf(false) }
    var downloadConfirm by remember { mutableStateOf(false) }
    val order = listOf(
        VietPhraseDictionaryKind.NAMES,
        VietPhraseDictionaryKind.VIET_PHRASE,
        VietPhraseDictionaryKind.PRONOUNS,
        VietPhraseDictionaryKind.LUAT_NHAN,
        VietPhraseDictionaryKind.PHIEN_AM,
        VietPhraseDictionaryKind.LAC_VIET,
        VietPhraseDictionaryKind.AI_REPLACE,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VIETPHRASE / CHUYỂN NGỮ") },
        text = {
            Column(Modifier.heightIn(max = 610.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật VietPhrase", Modifier.weight(1f))
                    Switch(enabled, { value -> enabled = value; ReferenceVietPhraseRuntime.setEnabled(context, value) })
                }
                SettingsButton("NHẬP FILE ZIP") { ReferenceVietPhraseRuntime.prepareImport(null); onImport() }
                SettingsButton("XUẤT TỪ ĐIỂN ZIP", onExport)
                order.forEach { kind -> SettingsButton(kind.fileName) { fileMenu = kind } }
                SettingsButton("XÓA TẤT CẢ") { deleteAll = true }
                SettingsButton("TẢI TỰ ĐỘNG TỪ MẠNG") { downloadConfirm = true }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dùng Hán Việt khi không tìm thấy cụm", Modifier.weight(1f))
                    Switch(fallback, { value -> fallback = value; ReferenceVietPhraseRuntime.setFallbackHanViet(context, value) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
    fileMenu?.let { kind ->
        AlertDialog(
            onDismissRequest = { fileMenu = null },
            title = { Text(kind.fileName) },
            text = { Column {
                SettingsButton("NHẬP / THAY THẾ (TXT hoặc DIC)") {
                    ReferenceVietPhraseRuntime.prepareImport(kind)
                    fileMenu = null
                    onImport()
                }
                SettingsButton("XÓA DỮ LIỆU FILE NÀY") { fileMenu = null; deleteKind = kind }
            } },
            confirmButton = { TextButton(onClick = { fileMenu = null }) { Text("ĐÓNG") } },
        )
    }
    deleteKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { deleteKind = null },
            title = { Text("XÓA ${kind.fileName}") },
            text = { Text("Xóa dữ liệu của file này?") },
            confirmButton = { TextButton(onClick = {
                state.vietPhraseRules.filter { it.kind == kind.name }.forEach { onDeleteRule(it.id) }
                deleteKind = null
            }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { deleteKind = null }) { Text("HỦY") } },
        )
    }
    if (deleteAll) {
        AlertDialog(
            onDismissRequest = { deleteAll = false },
            title = { Text("XÓA TOÀN BỘ VIETPHRASE") },
            text = { Text("Xóa tất cả dữ liệu VietPhrase?") },
            confirmButton = { TextButton(onClick = { state.vietPhraseRules.forEach { onDeleteRule(it.id) }; deleteAll = false }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { deleteAll = false }) { Text("HỦY") } },
        )
    }
    if (downloadConfirm) {
        AlertDialog(
            onDismissRequest = { downloadConfirm = false },
            title = { Text("TẢI TỰ ĐỘNG TỪ MẠNG") },
            text = { Text("Tải và cài bộ dữ liệu VietPhrase từ mạng?") },
            confirmButton = { TextButton(onClick = { downloadConfirm = false; onInstallOnline() }) { Text("TẢI") } },
            dismissButton = { TextButton(onClick = { downloadConfirm = false }) { Text("HỦY") } },
        )
    }
    state.pendingVietPhraseImport?.let { preview ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("XÁC NHẬN NHẬP VIETPHRASE") },
            text = { Text("${preview.sourceName}\n${preview.incomingCount} mục\nTrùng: ${preview.duplicateCount}\nCảnh báo: ${preview.warningCount}") },
            confirmButton = { TextButton(enabled = preview.errorCount == 0, onClick = onConfirmImport) { Text("ÁP DỤNG") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("HỦY") } },
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
    var geminiTouched by remember { mutableStateOf(false) }
    var proxyTouched by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var modelRequested by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf("") }

    LaunchedEffect(state.aiModelDiscoveryBusy, state.aiAvailableModels, state.aiModelDiscoveryStatus, modelRequested) {
        if (modelRequested && !state.aiModelDiscoveryBusy) {
            modelRequested = false
            if (state.aiAvailableModels.isNotEmpty()) {
                validation = ""
                modelPicker = true
            } else validation = state.aiModelDiscoveryStatus
        }
    }

    fun requestModels() {
        validation = ""
        if (provider == AiProvider.GEMINI && geminiKey.isBlank() && !state.aiHasGeminiApiKey) {
            validation = "Hãy nhập Gemini API Key trước."
            return
        }
        if (provider == AiProvider.OPENAI_COMPATIBLE && !endpoint.trim().startsWith("https://", true)) {
            validation = "URL OpenAI-compatible phải dùng HTTPS."
            return
        }
        modelRequested = true
        onRefreshModels(provider, endpoint, if (provider == AiProvider.GEMINI) geminiKey else proxyKey)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("THIẾT LẬP AI") },
        text = {
            Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nút AI trong màn hình đọc", Modifier.weight(1f)); Switch(enabled, { enabled = it })
                }
                Text("Nhà cung cấp AI", modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (provider == AiProvider.GEMINI) "Google Gemini" else "OpenAI-compatible / Proxy") }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        DropdownMenuItem({ Text("Google Gemini") }, { provider = AiProvider.GEMINI; providerMenu = false })
                        DropdownMenuItem({ Text("OpenAI-compatible / Proxy") }, { provider = AiProvider.OPENAI_COMPATIBLE; providerMenu = false })
                    }
                }
                if (provider == AiProvider.GEMINI) {
                    Text("Gemini API Key", modifier = Modifier.padding(top = 10.dp))
                    OutlinedTextField(geminiKey, { geminiTouched = true; geminiKey = it.take(4096) }, placeholder = { Text(if (state.aiHasGeminiApiKey) "Đã lưu Gemini API Key" else "Nhập Gemini API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Model Gemini", Modifier.weight(1f)); Button(::requestModels, enabled = !state.aiModelDiscoveryBusy) { Text(if (state.aiModelDiscoveryBusy) "ĐANG TẢI" else "TẢI DS") } }
                    OutlinedTextField(geminiModel, { geminiModel = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("OpenAI-compatible URL", modifier = Modifier.padding(top = 10.dp))
                    OutlinedTextField(endpoint, { endpoint = it.take(500) }, placeholder = { Text(".../v1/chat/completions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("OpenAI-compatible API Key", modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(proxyKey, { proxyTouched = true; proxyKey = it.take(4096) }, placeholder = { Text(if (state.aiHasOpenAiApiKey) "Đã lưu API Key" else "Bearer key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Model OpenAI-compatible", Modifier.weight(1f)); Button(::requestModels, enabled = !state.aiModelDiscoveryBusy) { Text(if (state.aiModelDiscoveryBusy) "ĐANG TẢI" else "TẢI DS") } }
                    OutlinedTextField(proxyModel, { proxyModel = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text("Chế độ xử lý mặc định", modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { modeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (mode == "improve") "Cải thiện bản VietPhrase" else "Dịch chương gốc") }
                    DropdownMenu(modeMenu, { modeMenu = false }) {
                        DropdownMenuItem({ Text("Dịch chương gốc") }, { mode = "translate"; modeMenu = false })
                        DropdownMenuItem({ Text("Cải thiện bản VietPhrase") }, { mode = "improve"; modeMenu = false })
                    }
                }
                Text("Lời nhắc mặc định: Dịch chương gốc", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                Text("Biến: {{CHAPTER_TITLE}}, {{CHAPTER_TEXT}}. Phải giữ {{CHAPTER_TEXT}} để AI nhận nội dung chương.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(translatePrompt, { translatePrompt = it }, minLines = 7, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("Lời nhắc mặc định: Cải thiện VietPhrase", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                Text("Biến: {{SOURCE_TITLE}}, {{SOURCE_TEXT}}, {{VIETPHRASE_TITLE}}, {{VIETPHRASE_TEXT}}. Hai biến nội dung là bắt buộc.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(improvePrompt, { improvePrompt = it }, minLines = 8, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("Timeout yêu cầu AI (ms)", modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(timeoutText, { timeoutText = it.filter(Char::isDigit).take(9) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Nhiệt độ AI (0.0 - 2.0)", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(temperatureText, { temperatureText = it.take(8) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = {
            val timeout = timeoutText.toIntOrNull()
            val temp = temperatureText.replace(',', '.').toFloatOrNull()
            validation = when {
                provider == AiProvider.OPENAI_COMPATIBLE && !endpoint.startsWith("https://", true) -> "URL OpenAI-compatible phải dùng HTTPS."
                !translatePrompt.contains("{{CHAPTER_TEXT}}") -> "Lời nhắc dịch phải giữ biến {{CHAPTER_TEXT}}."
                !improvePrompt.contains("{{SOURCE_TEXT}}") || !improvePrompt.contains("{{VIETPHRASE_TEXT}}") -> "Lời nhắc cải thiện phải giữ {{SOURCE_TEXT}} và {{VIETPHRASE_TEXT}}."
                timeout == null || timeout < 10_000 -> "Timeout AI phải từ 10000 ms trở lên."
                temp == null || temp !in 0f..2f -> "Nhiệt độ AI phải trong khoảng 0.0 - 2.0."
                else -> ""
            }
            if (validation.isBlank()) {
                val gm = geminiModel.trim().ifBlank { "gemini-3.6-flash" }
                val pm = proxyModel.trim()
                onSave(state.aiOnline.copy(enabled = enabled, consentGranted = enabled, provider = provider, endpoint = endpoint.trim().ifBlank { "https://openrouter.ai/api/v1/chat/completions" }, geminiModel = gm, openAiModel = pm, model = if (provider == AiProvider.GEMINI) gm else pm, mode = mode, translationPrompt = translatePrompt, improvePrompt = improvePrompt, timeoutMillis = timeout!!, temperature = temp!!), geminiKey.takeIf { geminiTouched }, proxyKey.takeIf { proxyTouched })
                onDismiss()
            }
        }) { Text("LƯU") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("HỦY") } },
    )
    if (modelPicker && state.aiAvailableModels.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { modelPicker = false },
            title = { Text("CHỌN MODEL") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                state.aiAvailableModels.forEach { model -> TextButton(onClick = { if (provider == AiProvider.GEMINI) geminiModel = model else proxyModel = model; modelPicker = false }, modifier = Modifier.fillMaxWidth()) { Text(model) } }
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { modelPicker = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun GlobalVoiceCastReferenceDialog(
    state: MainUiState,
    onAutoVoiceCastChange: (Boolean) -> Unit,
    onSave: (VoiceRoleDraft) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onDelete: (VoiceRoleEntity) -> Unit,
    onRestore: () -> Unit,
    onPreview: (VoiceRoleDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()
    val roles = state.voiceRoles.filter { it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID }.take(10)
    var draft by remember { mutableStateOf<VoiceRoleDraft?>(null) }
    var processing by remember { mutableStateOf("system") }
    var accurate by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }
    var deleteRole by remember { mutableStateOf<VoiceRoleEntity?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PHÂN VAI TTS BẰNG AI") },
        text = {
            Column(Modifier.heightIn(max = 610.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật mặc định cho truyện dùng cấu hình chung", Modifier.weight(1f)); Switch(state.autoVoiceCastEnabled, onAutoVoiceCastChange)
                }
                roles.forEach { role ->
                    ReferenceActionButton(
                        text = (if (role.isNarrator) "Người kể chuyện" else role.roleName) + role.description.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(),
                        onClick = {
                            val extra = ReferenceVoiceRoleExtras.load(context, role.id)
                            processing = extra.processingMethod
                            accurate = extra.sonicAccurate
                            draft = role.toVoiceDraft().copy(processingMethod = processing, sonicAccurate = accurate)
                            scope.launch { voices = (app.container.ttsVoiceCatalog.load(role.enginePackage) as? AppResult.Success)?.value.orEmpty() }
                        },
                        normalColor = ReferencePanelBackground,
                        normalContentColor = ReferenceText,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
                SettingsButton("THÊM GIỌNG") {
                    draft = VoiceRoleDraft(roleName = "", enginePackage = state.selectedTtsEnginePackage, voiceName = state.selectedTtsVoiceName, languageTag = state.selectedTtsLanguageTag, rate = 1f, pitch = 1f, volume = 1f)
                    processing = "system"; accurate = false
                    scope.launch { voices = (app.container.ttsVoiceCatalog.load(state.selectedTtsEnginePackage) as? AppResult.Success)?.value.orEmpty() }
                }
                SettingsButton("KHÔI PHỤC 7 HỒ SƠ MẪU") { restoreConfirm = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
    draft?.let { current ->
        var engineMenu by remember { mutableStateOf(false) }
        var languageMenu by remember { mutableStateOf(false) }
        var voiceMenu by remember { mutableStateOf(false) }
        val languages = voices.map { it.languageTag }.filter(String::isNotBlank).distinct().sorted()
        val visibleVoices = voices.filter { current.languageTag.isBlank() || it.languageTag == current.languageTag }
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("HỒ SƠ GIỌNG TTS") },
            text = { Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
                if (current.isNarrator) {
                    Text("Người kể chuyện", fontWeight = FontWeight.SemiBold)
                    Text("Người kể chuyện luôn được bật", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(current.roleName, { draft = current.copy(roleName = it.take(80)) }, label = { Text("Tên vai hoặc tên nhân vật") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(current.description, { draft = current.copy(description = it.take(1_000)) }, label = { Text("Mô tả để AI nhận biết") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bật hồ sơ này", Modifier.weight(1f))
                        Switch(current.enabled, { draft = current.copy(enabled = it) })
                    }
                }
                Text("Bộ đọc TTS", modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { engineMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(state.ttsEngines.firstOrNull { it.packageName == current.enginePackage }?.label ?: "Mặc định hệ thống") }
                DropdownMenu(engineMenu, { engineMenu = false }) {
                    DropdownMenuItem({ Text("Mặc định hệ thống") }, { draft = current.copy(enginePackage = null, voiceName = null); engineMenu = false; scope.launch { voices = (app.container.ttsVoiceCatalog.load(null) as? AppResult.Success)?.value.orEmpty() } })
                    state.ttsEngines.forEach { engine -> DropdownMenuItem({ Text(engine.label) }, { draft = current.copy(enginePackage = engine.packageName, voiceName = null); engineMenu = false; scope.launch { voices = (app.container.ttsVoiceCatalog.load(engine.packageName) as? AppResult.Success)?.value.orEmpty() } }) }
                }
                Text("Ngôn ngữ", modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(current.languageTag.ifBlank { "vi-VN" }) }
                DropdownMenu(languageMenu, { languageMenu = false }) { (if (languages.isEmpty()) listOf("vi-VN") else languages).forEach { lang -> DropdownMenuItem({ Text(lang) }, { draft = current.copy(languageTag = lang, voiceName = null); languageMenu = false }) } }
                Text("Giọng nói", modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(visibleVoices.firstOrNull { it.name == current.voiceName }?.displayName ?: "Giọng mặc định") }
                DropdownMenu(voiceMenu, { voiceMenu = false }) {
                    DropdownMenuItem({ Text("Giọng mặc định") }, { draft = current.copy(voiceName = null); voiceMenu = false })
                    visibleVoices.forEach { voice -> DropdownMenuItem({ Text(voice.displayName) }, { draft = current.copy(voiceName = voice.name, languageTag = voice.languageTag); voiceMenu = false }) }
                }
                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ processing = "system"; draft = current.copy(volume = current.volume.coerceAtMost(1f)) }, Modifier.weight(1f)) { Text((if (processing == "system") "✓ " else "") + "Android, tối đa 100%") }
                    TextButton({ processing = "sonic" }, Modifier.weight(1f)) { Text((if (processing == "sonic") "✓ " else "") + "Sonic, tối đa 200%") }
                }
                if (processing == "sonic") {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ accurate = false }, Modifier.weight(1f)) { Text((if (!accurate) "✓ " else "") + "Nhanh") }
                        TextButton({ accurate = true }, Modifier.weight(1f)) { Text((if (accurate) "✓ " else "") + "Chính xác") }
                    }
                }
                VoiceSlider("Tốc độ", current.rate, 0.25f, 3f) { draft = current.copy(rate = it) }
                VoiceSlider("Cao độ", current.pitch, 0.5f, 2f) { draft = current.copy(pitch = it) }
                VoiceSlider("Âm lượng", current.volume, 0f, if (processing == "sonic") 2f else 1f, true) { draft = current.copy(volume = it) }
                SettingsButton("NGHE THỬ") { onPreview(current.copy(processingMethod = processing, sonicAccurate = accurate)) }
            } },
            confirmButton = { TextButton(enabled = current.isNarrator || (current.roleName.isNotBlank() && current.description.isNotBlank() && !current.voiceName.isNullOrBlank()), onClick = {
                val normalized = current.copy(
                    roleName = if (current.isNarrator) "Người kể chuyện" else current.roleName,
                    volume = current.volume.coerceAtMost(if (processing == "sonic") 2f else 1f),
                    sonicSpeed = if (processing == "sonic") current.sonicSpeed else 1f,
                    sonicPitch = if (processing == "sonic") current.sonicPitch else 1f,
                )
                onSave(normalized.copy(processingMethod = processing, sonicAccurate = accurate))
                draft = null
            }) { Text("LƯU HỒ SƠ") } },
            dismissButton = { Row {
                if (!current.isNarrator && current.originalRoleId != null) TextButton(onClick = {
                    deleteRole = roles.firstOrNull { it.id == current.originalRoleId }
                    draft = null
                }) { Text("XÓA HỒ SƠ") }
                else if (current.isNarrator) TextButton(onClick = {}) { Text("NGƯỜI KỂ CHUYỆN BẮT BUỘC") }
                TextButton(onClick = { draft = null }) { Text("HỦY") }
            } },
        )
    }
    deleteRole?.let { role ->
        AlertDialog(
            onDismissRequest = { deleteRole = null },
            title = { Text("XÓA HỒ SƠ") },
            text = { Text("Xóa hồ sơ “${role.roleName}”? Kết quả cũ dùng hồ sơ này sẽ trở về Người kể chuyện.") },
            confirmButton = { TextButton(onClick = { onDelete(role); deleteRole = null }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { deleteRole = null }) { Text("HỦY") } },
        )
    }
    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text("KHÔI PHỤC HỒ SƠ MẪU") },
            text = { Text("Khôi phục tên và mô tả của 7 hồ sơ mẫu? Cấu hình âm thanh và các hồ sơ tùy chỉnh sẽ được giữ lại trong giới hạn 10 hồ sơ.") },
            confirmButton = { TextButton(onClick = { restoreConfirm = false; onRestore() }) { Text("KHÔI PHỤC") } },
            dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text("HỦY") } },
        )
    }
}

private fun VoiceRoleEntity.toVoiceDraft(): VoiceRoleDraft = VoiceRoleDraft(
    roleName = roleName,
    originalRoleId = id,
    aliases = aliasesCsv,
    description = description,
    isNarrator = isNarrator,
    enginePackage = enginePackage,
    voiceName = voiceName,
    languageTag = languageTag,
    rate = rate,
    pitch = pitch,
    volume = volume,
    expression = runCatching { VoiceExpression.valueOf(expression) }.getOrDefault(VoiceExpression.NEUTRAL),
    expressionStrength = expressionStrength,
    sonicSpeed = sonicSpeed,
    sonicPitch = sonicPitch,
    enabled = enabled,
)

@Composable
private fun VoiceSlider(label: String, value: Float, min: Float, max: Float, percent: Boolean = false, onChange: (Float) -> Unit) {
    val shown = value.coerceIn(min, max)
    Text(if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x", modifier = Modifier.padding(top = 6.dp))
    Slider(shown, onChange, valueRange = min..max)
}

@Composable
private fun BackupScopeDialog(
    restore: Boolean,
    onSelect: (Set<BackupComponent>) -> Unit,
    onDismiss: () -> Unit,
) {
    val all = BackupComponent.entries.toSet()
    val scopes = listOf(
        "TẤT CẢ" to all,
        "CÀI ĐẶT CHUNG, GIỌNG ĐỌC VÀ AI" to setOf(BackupComponent.SETTINGS, BackupComponent.AI_VOICE),
        "DỮ LIỆU ĐỌC, DẤU TRANG, TỪ ĐIỂN VÀ TRUYỆN ĐÃ TẢI" to setOf(BackupComponent.LIBRARY, BackupComponent.READING),
        "NHẠC NỀN" to setOf(BackupComponent.SCENE_MUSIC),
        "VIETPHRASE" to setOf(BackupComponent.VIETPHRASE),
        "TIỆN ÍCH VÀ NGUỒN TRUYỆN" to setOf(BackupComponent.SOURCES_EXTENSIONS),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (restore) "CHỌN DỮ LIỆU KHÔI PHỤC" else "CHỌN DỮ LIỆU SAO LƯU") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            scopes.forEach { (label, components) -> SettingsButton(label) { onSelect(components) } }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("HỦY") } },
    )
}

@Composable
private fun InstalledExtensionsReferenceDialog(
    state: MainUiState,
    onEnabledChange: (String, Boolean) -> Unit,
    onCheckSource: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<vn.nghetruyen.app.sourceplatform.SourcePackUiInfo?>(null) }
    val visible = state.sourcePacks.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TIỆN ÍCH ĐÃ CÀI") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(query, { query = it.take(120) }, placeholder = { Text("Tìm tiện ích") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            visible.forEach { pack -> SettingsButton(pack.name + "\n" + pack.version + if (!pack.enabled) " • Đã tắt" else "") { selected = pack.id } }
            if (visible.isEmpty()) Text("Không tìm thấy tiện ích phù hợp.")
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
    selected?.let { id ->
        state.sourcePacks.firstOrNull { it.id == id }?.let { pack ->
            val update = state.sourceRepositoryPackages.firstOrNull { it.sourceId == pack.id && it.status == "UPDATE_AVAILABLE" && it.canInstall }
            val source = state.sources.firstOrNull { it.id == pack.id }
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(pack.name) },
                text = { Column {
                    SettingsButton(if (pack.enabled) "TẮT" else "BẬT") { onEnabledChange(pack.id, !pack.enabled); selected = null }
                    if (pack.runtimeMode == "VBOOK_JS_COMPAT") SettingsButton("TƯƠNG THÍCH") { selected = null }
                    if (pack.runtimeMode == "NATIVE_LUA_COMPAT" && source != null) SettingsButton("KIỂM TRA NATIVE") { onCheckSource(source.id); selected = null }
                    if (source != null) SettingsButton("KIỂM TRA NGUỒN") { onCheckSource(source.id); selected = null }
                    SettingsButton("CẬP NHẬT") { onUpdate(pack.id); selected = null }
                    SettingsButton("XUẤT") { onExport(pack.id, pack.name); selected = null }
                    if (pack.removable) SettingsButton("XÓA") { selected = null; deleteTarget = pack }
                } },
                confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } },
            )
        }
    }
    deleteTarget?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("XÓA TIỆN ÍCH") },
            text = { Text("Xóa ${pack.name}? Dữ liệu truyện đã tải sẽ được giữ lại.") },
            confirmButton = { TextButton(onClick = { onRemove(pack.id); deleteTarget = null }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun RepositoriesReferenceDialog(
    state: MainUiState,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("KHO TIỆN ÍCH") },
        text = { Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f).padding(2.dp)) { Text("THÊM KHO MỚI") }
                Button(onClick = { state.sourceRepositories.forEach { onRefresh(it.url) } }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("LÀM MỚI") }
            }
            OutlinedTextField(query, { query = it.take(120) }, placeholder = { Text("Tìm kho hoặc tiện ích") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            state.sourceRepositories.filter { query.isBlank() || it.name.contains(query, true) || it.url.contains(query, true) }.forEach { repo ->
                Text(repo.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text(repo.url, style = MaterialTheme.typography.bodySmall)
                state.sourceRepositoryPackages.filter { it.repositoryId == repo.id && (query.isBlank() || it.name.contains(query, true) || it.sourceId.contains(query, true)) }.forEach { item ->
                    SettingsButton(item.name + " " + item.version) { if (item.canInstall) onPrepareInstall(item.repositoryId, item.sourceId) }
                }
                TextButton(onClick = { onRemove(repo.id) }) { Text("GỠ KHO") }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun AddRepositoryReferenceDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("THÊM KHO / CÀI TỪ LIÊN KẾT") },
        text = { Column {
            OutlinedTextField(url, { url = it.take(4096) }, label = { Text("Liên kết HTTPS của kho hoặc plugin.zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Tên kho, không bắt buộc") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        } },
        confirmButton = { TextButton(enabled = url.startsWith("https://"), onClick = { onSubmit(url); onDismiss() }) { Text("KIỂM TRA") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("HỦY") } },
    )
}

@Composable
private fun PendingInstallReferenceDialog(
    preview: SourceInstallPreview,
    warnings: List<String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("XÁC NHẬN CÀI TIỆN ÍCH") },
        text = { Column {
            Text("${preview.name} ${preview.version}", fontWeight = FontWeight.SemiBold)
            preview.permissionSummary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            warnings.forEach { Text("Cảnh báo: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("CÀI") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("HỦY") } },
    )
}
