package vn.nghetruyen.app.ui.components

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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

/**
 * Reader audio-layer controls. MUSIC, AMBIENCE and SFX deliberately route to the same asset manager
 * so all three libraries expose an identical UI and identical actions.
 */
@Suppress("UNUSED_PARAMETER")
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
        UnifiedAudioAssetManagerDialog(
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
private fun AudioLayerSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
