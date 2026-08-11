package vn.nghetruyen.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vn.nghetruyen.app.audio.AudioExportPackaging
import vn.nghetruyen.app.audio.AudioExportRequest
import vn.nghetruyen.app.audio.ReferenceAudioExportRuntime
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioExportFormat
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.following.FollowingUpdateWorker
import vn.nghetruyen.app.playback.ReaderVolumeKeyPolicy
import vn.nghetruyen.app.sourceplatform.installExtensionHostKernel
import vn.nghetruyen.app.ui.AppViewModel
import vn.nghetruyen.app.ui.Destination
import vn.nghetruyen.app.ui.ReferenceNgheTruyenApp
import vn.nghetruyen.app.ui.theme.NgheTruyenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val snapshot = viewModel.state.value
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> ReaderVolumeKeyPolicy.Key.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> ReaderVolumeKeyPolicy.Key.VOLUME_DOWN
            else -> ReaderVolumeKeyPolicy.Key.OTHER
        }
        val delta = ReaderVolumeKeyPolicy.paragraphDelta(
            readerVisible = snapshot.destination == Destination.Reader,
            navigationEnabled = snapshot.readerMode == ReaderMode.TTS && snapshot.readerDisplay.volumeKeysNavigate,
            actionDown = true,
            repeatCount = event.repeatCount,
            key = key,
        )
        if (delta != null) {
            viewModel.moveParagraph(delta)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.installExtensionHostKernel()
        handleFollowingIntent(intent)
        setContent {
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    viewModel.importBook(uri)
                }
            }
            val backupExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri != null) viewModel.exportBackup(uri)
            }
            val backupRestoreLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    handleBackupRestoreSelection(uri)
                }
            }
            val sourceDiagnosticsExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri != null) viewModel.exportSourceDiagnostics(uri)
            }
            val sourcePackInstallLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) viewModel.prepareSourcePack(uri)
            }
            var pendingSourcePackExportId by remember { mutableStateOf<String?>(null) }
            val sourcePackExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                val sourceId = pendingSourcePackExportId
                pendingSourcePackExportId = null
                if (uri != null && sourceId != null) viewModel.exportSourcePack(sourceId, uri)
            }
            val sourceTrustRotationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) viewModel.applySourceTrustRotation(uri)
            }
            val vietPhraseImportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) viewModel.importVietPhrase(uri)
            }
            val vietPhraseExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri != null) viewModel.exportVietPhrase(uri)
            }
            var pendingAudioExportRequest by remember { mutableStateOf<AudioExportRequest?>(null) }
            fun finishAudioExport(uri: android.net.Uri?) {
                val request = pendingAudioExportRequest
                pendingAudioExportRequest = null
                if (uri != null && request != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    viewModel.exportAudio(uri, request)
                }
            }
            val wavExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument(AudioExportFormat.WAV.mimeType),
            ) { uri -> finishAudioExport(uri) }
            val m4aExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument(AudioExportFormat.M4A.mimeType),
            ) { uri -> finishAudioExport(uri) }
            val mp3ExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument(AudioExportFormat.MP3.mimeType),
            ) { uri -> finishAudioExport(uri) }
            val audioExportDirectoryLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri -> finishAudioExport(uri) }
            val backgroundMusicLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    viewModel.setBackgroundMusic(uri)
                }
            }
            val sceneMusicLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    viewModel.addSceneMusicTrack(uri)
                }
            }
            var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val action = pendingNotificationAction
                pendingNotificationAction = null
                if (granted) action?.invoke() else viewModel.notificationPermissionDenied()
            }
            val runWithNotificationPermission: ((() -> Unit) -> Unit) = remember(notificationPermissionLauncher) {
                { action ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingNotificationAction = action
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else action()
                }
            }
            val launchPlayback = remember(runWithNotificationPermission) {
                { runWithNotificationPermission { viewModel.togglePlayback() } }
            }
            val changeFollowingUpdates: (Boolean) -> Unit = remember(runWithNotificationPermission) {
                { enabled ->
                    if (enabled) runWithNotificationPermission { viewModel.setFollowingUpdatesEnabled(true) }
                    else viewModel.setFollowingUpdatesEnabled(false)
                }
            }
            val launchImport = remember(importLauncher) {
                {
                    importLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "application/epub+zip",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/x-mobipocket-ebook",
                            "application/vnd.amazon.ebook",
                            "application/octet-stream",
                        ),
                    )
                }
            }
            val launchBackupExport = remember(backupExportLauncher) {
                {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                    backupExportLauncher.launch("nghe-truyen-backup-$stamp.zip")
                }
            }
            val launchBackupRestore = remember(backupRestoreLauncher) {
                { backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "text/plain", "text/x-lua")) }
            }
            val launchVietPhraseImport = remember(vietPhraseImportLauncher) {
                { vietPhraseImportLauncher.launch(arrayOf("text/plain", "text/tab-separated-values", "application/zip", "application/octet-stream")) }
            }
            val launchVietPhraseExport = remember(vietPhraseExportLauncher) {
                {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                    vietPhraseExportLauncher.launch("vietphrase-bundle-$stamp.zip")
                }
            }
            val launchSourceDiagnosticsExport = remember(sourceDiagnosticsExportLauncher) {
                {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                    sourceDiagnosticsExportLauncher.launch("nghetruyen-diagnostics-$stamp.zip")
                }
            }
            val launchSourcePackInstall = remember(sourcePackInstallLauncher) {
                { sourcePackInstallLauncher.launch(arrayOf("application/zip", "application/octet-stream", "text/plain", "text/x-lua")) }
            }
            val launchSourcePackExport: (String, String) -> Unit = remember(sourcePackExportLauncher) {
                { sourceId, displayName ->
                    pendingSourcePackExportId = sourceId
                    val safeName = displayName.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "extension" }
                    sourcePackExportLauncher.launch("$safeName.ntsource")
                }
            }
            val launchSourceTrustRotation = remember(sourceTrustRotationLauncher) {
                { sourceTrustRotationLauncher.launch(arrayOf("application/json", "application/octet-stream")) }
            }
            val launchAudioExport: (AudioExportRequest) -> Unit = remember(
                wavExportLauncher,
                m4aExportLauncher,
                mp3ExportLauncher,
                audioExportDirectoryLauncher,
                runWithNotificationPermission,
            ) {
                { request ->
                    runWithNotificationPermission {
                        val normalized = request.normalized()
                        pendingAudioExportRequest = normalized
                        if (normalized.packaging == AudioExportPackaging.ONE_FILE_PER_CHAPTER) {
                            audioExportDirectoryLauncher.launch(null)
                        } else {
                            val name = ReferenceAudioExportRuntime.consumeNextFileName()
                                ?: viewModel.audioExportSuggestedName(normalized)
                            when (normalized.format) {
                                AudioExportFormat.WAV -> wavExportLauncher.launch(name)
                                AudioExportFormat.M4A -> m4aExportLauncher.launch(name)
                                AudioExportFormat.MP3 -> mp3ExportLauncher.launch(name)
                            }
                        }
                    }
                }
            }
            val launchBackgroundMusic = remember(backgroundMusicLauncher) {
                { backgroundMusicLauncher.launch(arrayOf("audio/*")) }
            }
            val launchSceneMusic = remember(sceneMusicLauncher) {
                { sceneMusicLauncher.launch(arrayOf("audio/*")) }
            }
            NgheTruyenTheme {
                ReferenceNgheTruyenApp(
                    viewModel = viewModel,
                    onImportFile = launchImport,
                    onExportBackup = launchBackupExport,
                    onRestoreBackup = launchBackupRestore,
                    onImportVietPhrase = launchVietPhraseImport,
                    onExportVietPhrase = launchVietPhraseExport,
                    onExportAudio = launchAudioExport,
                    onSelectBackgroundMusic = launchBackgroundMusic,
                    onSelectSceneMusic = launchSceneMusic,
                    onInstallSourcePack = launchSourcePackInstall,
                    onExportSourcePack = launchSourcePackExport,
                    onImportSourceTrustRotation = launchSourceTrustRotation,
                    onExportSourceDiagnostics = launchSourceDiagnosticsExport,
                    onTogglePlayback = launchPlayback,
                    onFollowingUpdatesChange = changeFollowingUpdates,
                )
            }
        }
    }

    private fun handleBackupRestoreSelection(uri: Uri) {
        val container = (application as NgheTruyenApplication).container
        lifecycleScope.launch {
            when (val inspection = container.legacyXpkBackupImporter.inspect(uri)) {
                is AppResult.Failure -> viewModel.readerActionMessage(inspection.message)
                is AppResult.Success -> {
                    val preview = inspection.value.preview
                    if (!inspection.value.isLegacyXpk || preview == null) {
                        // Current Kotlin backups keep the existing behavior and get no additional prompt.
                        viewModel.restoreBackup(uri)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("PHÁT HIỆN BẢN SAO LƯU XPK CŨ")
                            .setMessage(preview.confirmationMessage())
                            .setPositiveButton("CHUYỂN ĐỔI & KHÔI PHỤC") { _, _ ->
                                restoreLegacyXpkBackup(uri)
                            }
                            .setNegativeButton("HỦY", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun restoreLegacyXpkBackup(uri: Uri) {
        val container = (application as NgheTruyenApplication).container
        lifecycleScope.launch {
            val selected = viewModel.state.value.backupComponents
            viewModel.readerActionMessage("Đang chuyển đổi bản sao lưu XPK…")
            when (val result = container.legacyXpkBackupImporter.restoreFrom(uri, selected)) {
                is AppResult.Success -> {
                    val message = result.value.userMessage()
                    val complete = result.value.isComplete
                    container.backupHistoryStore.record(
                        operation = if (complete) "RESTORE_XPK" else "RESTORE_XPK_PARTIAL",
                        success = complete,
                        summary = result.value.diagnosticMessage(),
                        errorCode = if (complete) null else "PARTIAL_MIGRATION",
                        components = selected.map { it.name },
                    )
                    // Restore is launched from the existing Personal surface. Re-selecting the same
                    // root tab keeps the UI unchanged while forcing source/runtime state to refresh.
                    viewModel.setRootTab(viewModel.state.value.rootTab)
                    viewModel.refreshSourceSessions()
                    viewModel.readerActionMessage(message)
                }
                is AppResult.Failure -> {
                    container.backupHistoryStore.record(
                        operation = "RESTORE_XPK",
                        success = false,
                        summary = result.message,
                        errorCode = result.code,
                        components = selected.map { it.name },
                    )
                    viewModel.readerActionMessage(result.message)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSourceSessions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFollowingIntent(intent)
    }

    private fun handleFollowingIntent(intent: Intent?) {
        val storyId = intent?.getStringExtra(FollowingUpdateWorker.EXTRA_STORY_ID) ?: return
        viewModel.openFollowedStoryById(storyId)
        intent.removeExtra(FollowingUpdateWorker.EXTRA_STORY_ID)
        intent.removeExtra(FollowingUpdateWorker.EXTRA_STORY_TITLE)
    }
}
