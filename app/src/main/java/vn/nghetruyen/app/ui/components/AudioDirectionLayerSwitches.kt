package vn.nghetruyen.app.ui.components

import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

@Composable
fun AudioDirectionLayerSwitches(
    musicTrackCount: Int = 0,
    onManageMusic: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val settingsRepository = application.container.settingsRepository
    val preferences = remember(context) { AudioDirectionPreferences(context) }
    val scope = rememberCoroutineScope()
    val tracks by repository.observeSceneMusicTracks().collectAsState(initial = emptyList())
    var snapshot by remember(preferences) { mutableStateOf(preferences.snapshot()) }
    var musicEnabled by remember { mutableStateOf(false) }
    var normalizationTarget by remember { mutableStateOf(-24f) }
    var attackMillis by remember { mutableIntStateOf(1850) }
    var releaseMillis by remember { mutableIntStateOf(2050) }
    var normalizationSettingsDirty by remember { mutableStateOf(false) }
    var managerKind by remember { mutableStateOf<AudioAssetKind?>(null) }
    var showNormalizationProgress by remember { mutableStateOf(false) }
    var normalizationWorkIds by remember { mutableStateOf<List<UUID>>(emptyList()) }
    var normalizationDone by remember { mutableIntStateOf(0) }
    var normalizationFailed by remember { mutableIntStateOf(0) }
    var normalizationCancelled by remember { mutableIntStateOf(0) }

    val latestNormalizationTarget by rememberUpdatedState(normalizationTarget)
    val latestAttackMillis by rememberUpdatedState(attackMillis)
    val latestReleaseMillis by rememberUpdatedState(releaseMillis)
    val latestNormalizationDirty by rememberUpdatedState(normalizationSettingsDirty)
    val latestTracks by rememberUpdatedState(tracks)

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = preferences.snapshot()
        }
        preferences.addChangeListener(listener)
        onDispose { preferences.removeChangeListener(listener) }
    }

    DisposableEffect(settingsRepository, context.applicationContext) {
        onDispose {
            if (latestNormalizationDirty) {
                val target = latestNormalizationTarget
                val attack = latestAttackMillis
                val release = latestReleaseMillis
                val musicTracks = latestTracks.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    settingsRepository.setSceneMusicTargetLufs(target)
                    settingsRepository.setBackgroundMusicAttackMillis(attack)
                    settingsRepository.setBackgroundMusicReleaseMillis(release)
                    musicTracks.forEach { track ->
                        SceneMusicAnalysisWorker.enqueue(context.applicationContext, track.id, target)
                    }
                }
            }
        }
    }

    LaunchedEffect(settingsRepository) {
        val settings = settingsRepository.snapshot()
        musicEnabled = settings.autoSceneMusicEnabled
        normalizationTarget = settings.sceneMusicTargetLufs.coerceIn(-36f, -18f)
        attackMillis = settings.backgroundMusicAttackMillis.coerceIn(0, 2_000)
        releaseMillis = settings.backgroundMusicReleaseMillis.coerceIn(0, 5_000)
        normalizationSettingsDirty = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AudioLayerSwitchRow(
            title = "Nhạc cảnh AI",
            checked = musicEnabled,
            onCheckedChange = { enabled ->
                musicEnabled = enabled
                scope.launch { settingsRepository.setAutoSceneMusicEnabled(enabled) }
            },
        )
        AudioLayerSwitchRow(
            title = "Âm thanh môi trường AI",
            checked = snapshot.ambienceEnabled,
            onCheckedChange = preferences::setAmbienceEnabled,
        )
        AudioLayerSwitchRow(
            title = "Hiệu ứng âm thanh AI",
            checked = snapshot.soundEffectsEnabled,
            onCheckedChange = preferences::setSoundEffectsEnabled,
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        AudioFloatSlider(
            title = "Mức chuẩn hóa",
            value = normalizationTarget,
            range = -36f..-18f,
            shown = { "%.0f LUFS".format(it) },
            onValueChange = { value ->
                normalizationTarget = value
                normalizationSettingsDirty = true
            },
        )
        AudioIntSlider(
            title = "Attack",
            value = attackMillis,
            maximum = 2_000,
            onValueChange = { value ->
                attackMillis = value
                normalizationSettingsDirty = true
            },
        )
        AudioIntSlider(
            title = "Release",
            value = releaseMillis,
            maximum = 5_000,
            onValueChange = { value ->
                releaseMillis = value
                normalizationSettingsDirty = true
            },
        )
        AudioManagerButton(
            label = "CHUẨN HÓA TOÀN BỘ KHO NHẠC",
            enabled = tracks.any { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC },
            onClick = {
                val musicTracks = tracks.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
                if (musicTracks.isNotEmpty()) {
                    val target = normalizationTarget
                    normalizationSettingsDirty = true
                    normalizationDone = 0
                    normalizationFailed = 0
                    normalizationCancelled = 0
                    val workIds = musicTracks.map { track ->
                        SceneMusicAnalysisWorker.enqueue(context, track.id, target)
                    }
                    normalizationWorkIds = workIds
                    showNormalizationProgress = true
                    scope.launch {
                        val workManager = WorkManager.getInstance(context.applicationContext)
                        while (showNormalizationProgress) {
                            val infos = withContext(Dispatchers.IO) {
                                workIds.mapNotNull { id ->
                                    runCatching { workManager.getWorkInfoById(id).get() }.getOrNull()
                                }
                            }
                            normalizationDone = infos.count { it.state == WorkInfo.State.SUCCEEDED }
                            normalizationFailed = infos.count { it.state == WorkInfo.State.FAILED }
                            normalizationCancelled = infos.count { it.state == WorkInfo.State.CANCELLED }
                            if (infos.size == workIds.size && infos.all { it.state.isFinished }) break
                            delay(300)
                        }
                    }
                }
            },
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        AudioManagerButton(
            label = "QUẢN LÝ NHẠC ($musicTrackCount)",
            enabled = onManageMusic != null,
            onClick = { onManageMusic?.invoke() },
        )
        AudioManagerButton(
            label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }})",
            onClick = { managerKind = AudioAssetKind.AMBIENCE },
        )
        AudioManagerButton(
            label = "QUẢN LÝ HIỆU ỨNG ÂM THANH (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }})",
            onClick = { managerKind = AudioAssetKind.SFX },
        )
    }

    if (showNormalizationProgress) {
        val total = normalizationWorkIds.size
        val finished = normalizationDone + normalizationFailed + normalizationCancelled
        AlertDialog(
            onDismissRequest = {},
            title = { Text("CHUẨN HÓA KHO NHẠC") },
            text = {
                Column {
                    Text("Mục tiêu: %.0f LUFS".format(normalizationTarget))
                    Text("Hoàn tất: $finished / $total")
                    if (normalizationFailed > 0) Text("Lỗi: $normalizationFailed")
                    if (normalizationCancelled > 0) Text("Đã hủy: $normalizationCancelled")
                }
            },
            confirmButton = {
                if (total > 0 && finished >= total) {
                    TextButton(onClick = {
                        showNormalizationProgress = false
                        normalizationWorkIds = emptyList()
                    }) { Text("ĐÓNG") }
                }
            },
            dismissButton = {
                if (finished < total) {
                    TextButton(onClick = {
                        normalizationWorkIds.forEach { SceneMusicAnalysisWorker.cancel(context, it) }
                        showNormalizationProgress = false
                    }) { Text("HỦY") }
                }
            },
        )
    }

    managerKind?.let { kind ->
        AudioAssetManagerDialog(
            kind = kind,
            tracks = tracks.filter { AudioAssetClassifier.classify(it) == kind },
            onDismiss = { managerKind = null },
        )
    }
}

@Composable
private fun AudioManagerButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) { Text(label) }
}

@Composable
private fun AudioFloatSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    val safe = value.coerceIn(range.start, range.endInclusive)
    Text("$title: ${shown(safe)}")
    Slider(
        value = safe,
        onValueChange = onValueChange,
        valueRange = range,
        steps = 17,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AudioIntSlider(
    title: String,
    value: Int,
    maximum: Int,
    onValueChange: (Int) -> Unit,
) {
    val safe = value.coerceIn(0, maximum)
    Text("$title: $safe ms")
    Slider(
        value = safe.toFloat(),
        onValueChange = { onValueChange(it.toInt().coerceIn(0, maximum)) },
        valueRange = 0f..maximum.toFloat(),
        steps = ((maximum / 10) - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AudioAssetManagerDialog(
    kind: AudioAssetKind,
    tracks: List<SceneMusicTrackEntity>,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val scope = rememberCoroutineScope()
    var selectedTrackId by remember(kind) { mutableStateOf<String?>(null) }
    val title = when (kind) {
        AudioAssetKind.MUSIC -> "QUẢN LÝ NHẠC"
        AudioAssetKind.AMBIENCE -> "QUẢN LÝ ÂM THANH MÔI TRƯỜNG"
        AudioAssetKind.SFX -> "QUẢN LÝ HIỆU ỨNG ÂM THANH"
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.distinct().forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val displayName = displayName(context, uri)
                repository.saveSceneMusicTrack(
                    title = displayName.substringBeforeLast('.', displayName).ifBlank { defaultAssetName(kind) },
                    uri = uri.toString(),
                    tagsCsv = typeMarker(kind),
                ).onSuccess { trackId ->
                    SceneMusicAnalysisWorker.enqueue(context, trackId, normalizationTarget(kind))
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                if (tracks.isEmpty()) {
                    Text("Chưa có tệp nào.", modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    tracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                        .forEach { track ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { selectedTrackId = track.id },
                                    modifier = Modifier.weight(1f),
                                ) { Text(track.title) }
                                Switch(
                                    checked = track.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch { repository.setSceneMusicTrackEnabled(track.id, enabled) }
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { launcher.launch(arrayOf("audio/*")) }) { Text("THÊM NHIỀU TỆP") }
                TextButton(onClick = onDismiss) { Text("ĐÓNG") }
            }
        },
    )

    selectedTrackId?.let { selectedId ->
        tracks.firstOrNull { it.id == selectedId }?.let { track ->
            AudioAssetEditorDialog(
                track = track,
                kind = kind,
                onDismiss = { selectedTrackId = null },
            )
        }
    }
}

@Composable
private fun AudioAssetEditorDialog(
    track: SceneMusicTrackEntity,
    kind: AudioAssetKind,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val scope = rememberCoroutineScope()
    var title by remember(track.id, track.updatedAt) { mutableStateOf(track.title) }
    var description by remember(track.id, track.updatedAt) { mutableStateOf(assetDescription(kind, track.tagsCsv)) }
    var previewPlayer by remember(track.id) { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        runCatching { previewPlayer?.stop() }
        runCatching { previewPlayer?.release() }
        previewPlayer = null
    }

    DisposableEffect(track.id) {
        onDispose { stopPreview() }
    }

    AlertDialog(
        onDismissRequest = {
            stopPreview()
            onDismiss()
        },
        title = { Text(track.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("Tên") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(300) },
                    label = { Text("Mô tả") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            stopPreview()
                            previewPlayer = runCatching { MediaPlayer.create(context, Uri.parse(track.uri)) }
                                .getOrNull()
                                ?.also { player ->
                                    val volume = track.volume.coerceIn(0f, 1f)
                                    player.setVolume(volume, volume)
                                    player.setOnCompletionListener { completed ->
                                        runCatching { completed.release() }
                                        if (previewPlayer === completed) previewPlayer = null
                                    }
                                    player.start()
                                }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (previewPlayer == null) "NGHE THỬ" else "NGHE LẠI") }
                    TextButton(
                        onClick = { stopPreview() },
                        enabled = previewPlayer != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("DỪNG") }
                    TextButton(
                        onClick = { SceneMusicAnalysisWorker.enqueue(context, track.id, normalizationTarget(kind)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("CHUẨN HÓA") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.updateSceneMusicTrackMetadata(
                                    track.id,
                                    title.trim().ifBlank { track.title },
                                    tagsWithDescription(kind, description),
                                )
                            }
                            stopPreview()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("LƯU") }
                    TextButton(
                        onClick = {
                            stopPreview()
                            scope.launch { repository.deleteSceneMusicTrack(track.id) }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("XÓA") }
                    TextButton(
                        onClick = {
                            stopPreview()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("ĐÓNG") }
                }
            }
        },
    )
}

@Composable
private fun AudioLayerSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
    return fromProvider?.trim()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "audio"
}

private fun defaultAssetName(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Nhạc"
    AudioAssetKind.AMBIENCE -> "Âm thanh môi trường"
    AudioAssetKind.SFX -> "Hiệu ứng âm thanh"
}

private fun typeMarker(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "type:music"
    AudioAssetKind.AMBIENCE -> "type:ambience"
    AudioAssetKind.SFX -> "type:sfx"
}

private fun assetDescription(kind: AudioAssetKind, tagsCsv: String): String {
    val marker = typeMarker(kind)
    return tagsCsv.replace(marker, "", ignoreCase = true)
        .trim()
        .trimStart(',', ';')
        .trim()
}

private fun tagsWithDescription(kind: AudioAssetKind, description: String): String {
    val marker = typeMarker(kind)
    val cleanDescription = description.trim().take(300)
    return if (cleanDescription.isBlank()) marker else "$marker, $cleanDescription"
}

private fun normalizationTarget(kind: AudioAssetKind): Float = when (kind) {
    AudioAssetKind.MUSIC -> -24f
    AudioAssetKind.AMBIENCE -> -27f
    AudioAssetKind.SFX -> -20f
}