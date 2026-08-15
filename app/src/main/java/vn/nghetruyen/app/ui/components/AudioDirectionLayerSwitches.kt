package vn.nghetruyen.app.ui.components

import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

@Composable
fun AudioDirectionLayerSwitches(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val settingsRepository = application.container.settingsRepository
    val preferences = remember(context) { AudioDirectionPreferences(context) }
    val scope = rememberCoroutineScope()
    val tracks by repository.observeSceneMusicTracks().collectAsState(initial = emptyList())
    var snapshot by remember(preferences) { mutableStateOf(preferences.snapshot()) }
    var musicEnabled by remember { mutableStateOf(false) }
    var managerKind by remember { mutableStateOf<AudioAssetKind?>(null) }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = preferences.snapshot()
        }
        preferences.addChangeListener(listener)
        onDispose { preferences.removeChangeListener(listener) }
    }

    LaunchedEffect(settingsRepository) {
        musicEnabled = settingsRepository.snapshot().autoSceneMusicEnabled
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
        AudioManagerButton(
            label = "QUẢN LÝ NHẠC (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }})",
            onClick = { managerKind = AudioAssetKind.MUSIC },
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

    managerKind?.let { kind ->
        AudioAssetManagerDialog(
            kind = kind,
            tracks = tracks.filter { AudioAssetClassifier.classify(it) == kind },
            onDismiss = { managerKind = null },
        )
    }
}

@Composable
private fun AudioManagerButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) { Text(label) }
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
                Button(
                    onClick = { launcher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("THÊM NHIỀU TỆP") }
                if (tracks.isEmpty()) {
                    Text("Chưa có tệp nào.", modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    tracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                        .forEach { track ->
                            AudioAssetEditorRow(track = track, kind = kind)
                            HorizontalDivider()
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun AudioAssetEditorRow(track: SceneMusicTrackEntity, kind: AudioAssetKind) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val scope = rememberCoroutineScope()
    var title by remember(track.id, track.updatedAt) { mutableStateOf(track.title) }
    var description by remember(track.id, track.updatedAt) {
        mutableStateOf(stripTypeMarker(track.tagsCsv))
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (kind) {
                    AudioAssetKind.MUSIC -> "MUSIC"
                    AudioAssetKind.AMBIENCE -> "AMBIENCE"
                    AudioAssetKind.SFX -> "SFX"
                },
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = track.enabled,
                onCheckedChange = { enabled ->
                    scope.launch { repository.setSceneMusicTrackEnabled(track.id, enabled) }
                },
            )
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(120) },
            label = { Text("Tên") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(260) },
            label = { Text("Mô tả cho AI") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    scope.launch {
                        repository.updateSceneMusicTrackMetadata(
                            track.id,
                            title,
                            typedDescription(kind, description),
                        )
                    }
                },
            ) { Text("LƯU") }
            TextButton(
                onClick = { SceneMusicAnalysisWorker.enqueue(context, track.id, normalizationTarget(kind)) },
            ) { Text("CHUẨN HÓA") }
            TextButton(
                onClick = { scope.launch { repository.deleteSceneMusicTrack(track.id) } },
            ) { Text("XÓA") }
        }
    }
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
    AudioAssetKind.MUSIC -> ""
    AudioAssetKind.AMBIENCE -> "type:ambience"
    AudioAssetKind.SFX -> "type:sfx"
}

private fun typedDescription(kind: AudioAssetKind, description: String): String {
    val clean = stripTypeMarker(description).trim()
    val marker = typeMarker(kind)
    return listOf(marker, clean).filter(String::isNotBlank).joinToString("\n").take(300)
}

private fun stripTypeMarker(value: String): String = value
    .lineSequence()
    .filterNot { line ->
        val lower = line.trim().lowercase()
        lower.startsWith("type:") || lower.startsWith("type=") ||
            lower in setOf("[music]", "[ambience]", "[environment]", "[sfx]")
    }
    .joinToString("\n")
    .trim()

private fun normalizationTarget(kind: AudioAssetKind): Float = when (kind) {
    AudioAssetKind.MUSIC -> -24f
    AudioAssetKind.AMBIENCE -> -27f
    AudioAssetKind.SFX -> -20f
}
