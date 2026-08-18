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
import kotlin.math.roundToInt
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
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderPlaybackService

private val audioSettingsPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Reader audio-layer controls. MUSIC, AMBIENCE and SFX deliberately route to the same asset manager
 * so all three libraries expose an identical UI and identical actions.
 *
 * [onManageMusic] is retained only for source compatibility with the existing ReaderScreen call.
 * MUSIC no longer invokes a separate legacy dialog; it uses [UnifiedAudioAssetManagerDialog].
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
    val preferences = remember(context) { AudioDirectionPreferences.shared(context) }
    val scope = rememberCoroutineScope()
    val tracks by repository.observeSceneMusicTracks().collectAsState(initial = emptyList())
    var snapshot by remember(preferences) { mutableStateOf(preferences.snapshot()) }
    var musicEnabled by remember { mutableStateOf(false) }
    var musicNormalizationTarget by remember { mutableStateOf(-24f) }
    var attackMillis by remember { mutableIntStateOf(1850) }
    var releaseMillis by remember { mutableIntStateOf(2050) }
    var dynamicsSettingsDirty by remember { mutableStateOf(false) }
    var managerKind by remember { mutableStateOf<AudioAssetKind?>(null) }

    var showNormalizationDialog by remember { mutableStateOf(false) }
    var normalizationMusicDraft by remember { mutableStateOf(-24f) }
    var normalizationAmbienceDraft by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS) }
    var normalizationSfxDraft by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_SFX_NORMALIZATION_TARGET_LUFS) }
    var showNormalizationProgress by remember { mutableStateOf(false) }
    var normalizationWorkIds by remember { mutableStateOf<List<UUID>>(emptyList()) }
    var normalizationDone by remember { mutableIntStateOf(0) }
    var normalizationFailed by remember { mutableIntStateOf(0) }
    var normalizationCancelled by remember { mutableIntStateOf(0) }
    var normalizationRunMusicTarget by remember { mutableStateOf(-24f) }
    var normalizationRunAmbienceTarget by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS) }
    var normalizationRunSfxTarget by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_SFX_NORMALIZATION_TARGET_LUFS) }

    val latestAttackMillis by rememberUpdatedState(attackMillis)
    val latestReleaseMillis by rememberUpdatedState(releaseMillis)
    val latestDynamicsDirty by rememberUpdatedState(dynamicsSettingsDirty)

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = preferences.snapshot()
        }
        preferences.addChangeListener(listener)
        onDispose { preferences.removeChangeListener(listener) }
    }

    DisposableEffect(settingsRepository) {
        onDispose {
            if (latestDynamicsDirty) {
                val attack = latestAttackMillis
                val release = latestReleaseMillis
                audioSettingsPersistenceScope.launch {
                    settingsRepository.setBackgroundMusicAttackMillis(attack)
                    settingsRepository.setBackgroundMusicReleaseMillis(release)
                }
            }
        }
    }

    LaunchedEffect(settingsRepository) {
        val settings = settingsRepository.snapshot()
        musicEnabled = settings.autoSceneMusicEnabled
        musicNormalizationTarget = settings.sceneMusicTargetLufs
            .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS)
        attackMillis = settings.backgroundMusicAttackMillis.coerceIn(0, 2_000)
        releaseMillis = settings.backgroundMusicReleaseMillis.coerceIn(0, 5_000)
        dynamicsSettingsDirty = false
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
        AudioIntSlider(
            title = "Attack",
            value = attackMillis,
            maximum = 2_000,
            onValueChange = { value ->
                attackMillis = value
                dynamicsSettingsDirty = true
            },
        )
        AudioIntSlider(
            title = "Release",
            value = releaseMillis,
            maximum = 5_000,
            onValueChange = { value ->
                releaseMillis = value
                dynamicsSettingsDirty = true
            },
        )
        AudioManagerButton(
            label = "CHUẨN HÓA TOÀN BỘ ÂM THANH",
            enabled = tracks.isNotEmpty(),
            onClick = {
                normalizationMusicDraft = musicNormalizationTarget
                normalizationAmbienceDraft = snapshot.ambienceNormalizationTargetLufs
                normalizationSfxDraft = snapshot.soundEffectsNormalizationTargetLufs
                showNormalizationDialog = true
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

    if (showNormalizationDialog) {
        val musicCount = tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        val ambienceCount = tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        val sfxCount = tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
        AlertDialog(
            onDismissRequest = { showNormalizationDialog = false },
            title = { Text("CHUẨN HÓA TOÀN BỘ ÂM THANH") },
            text = {
                Column {
                    Text("Mỗi loại âm thanh dùng một mức LUFS riêng. Nhạc giữ dải an toàn -36 đến -18 LUFS; môi trường và hiệu ứng có thể tăng tới -5 LUFS. Peak vẫn được chặn ở -1 dBFS.")
                    AudioFloatSlider(
                        title = "Nhạc nền ($musicCount tệp)",
                        value = normalizationMusicDraft,
                        range = PcmLoudnessEstimator.MIN_TARGET_LUFS..PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS,
                        shown = { "%.0f LUFS".format(it) },
                        onValueChange = { normalizationMusicDraft = it },
                    )
                    AudioFloatSlider(
                        title = "Âm thanh môi trường ($ambienceCount tệp)",
                        value = normalizationAmbienceDraft,
                        range = PcmLoudnessEstimator.MIN_TARGET_LUFS..PcmLoudnessEstimator.MAX_TARGET_LUFS,
                        shown = { "%.0f LUFS".format(it) },
                        onValueChange = { normalizationAmbienceDraft = it },
                    )
                    AudioFloatSlider(
                        title = "Hiệu ứng âm thanh ($sfxCount tệp)",
                        value = normalizationSfxDraft,
                        range = PcmLoudnessEstimator.MIN_TARGET_LUFS..PcmLoudnessEstimator.MAX_TARGET_LUFS,
                        shown = { "%.0f LUFS".format(it) },
                        onValueChange = { normalizationSfxDraft = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val musicTarget = normalizationMusicDraft
                        .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS)
                    val ambienceTarget = normalizationAmbienceDraft
                        .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS)
                    val sfxTarget = normalizationSfxDraft
                        .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS)

                    musicNormalizationTarget = musicTarget
                    preferences.setAmbienceNormalizationTargetLufs(ambienceTarget)
                    preferences.setSoundEffectsNormalizationTargetLufs(sfxTarget)
                    audioSettingsPersistenceScope.launch {
                        settingsRepository.setSceneMusicTargetLufs(musicTarget)
                    }

                    normalizationRunMusicTarget = musicTarget
                    normalizationRunAmbienceTarget = ambienceTarget
                    normalizationRunSfxTarget = sfxTarget
                    normalizationDone = 0
                    normalizationFailed = 0
                    normalizationCancelled = 0

                    val workIds = tracks.map { track ->
                        val target = when (AudioAssetClassifier.classify(track)) {
                            AudioAssetKind.MUSIC -> musicTarget
                            AudioAssetKind.AMBIENCE -> ambienceTarget
                            AudioAssetKind.SFX -> sfxTarget
                        }
                        SceneMusicAnalysisWorker.enqueue(context, track.id, target)
                    }
                    normalizationWorkIds = workIds
                    showNormalizationDialog = false
                    showNormalizationProgress = workIds.isNotEmpty()

                    if (workIds.isNotEmpty()) {
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
                }) { Text("CHUẨN HÓA") }
            },
            dismissButton = {
                TextButton(onClick = { showNormalizationDialog = false }) { Text("HỦY") }
            },
        )
    }

    if (showNormalizationProgress) {
        val total = normalizationWorkIds.size
        val finished = normalizationDone + normalizationFailed + normalizationCancelled
        AlertDialog(
            onDismissRequest = {},
            title = { Text("ĐANG CHUẨN HÓA ÂM THANH") },
            text = {
                Column {
                    Text("Nhạc nền: %.0f LUFS".format(normalizationRunMusicTarget))
                    Text("Môi trường: %.0f LUFS".format(normalizationRunAmbienceTarget))
                    Text("Hiệu ứng: %.0f LUFS".format(normalizationRunSfxTarget))
                    Text("Hoàn tất: $finished / $total")
                    if (normalizationFailed > 0) Text("Lỗi: $normalizationFailed")
                    if (normalizationCancelled > 0) Text("Đã hủy: $normalizationCancelled")
                }
            },
            confirmButton = {
                if (total > 0 && finished >= total) {
                    TextButton(onClick = {
                        val musicTarget = normalizationRunMusicTarget
                        musicNormalizationTarget = musicTarget
                        preferences.setAmbienceNormalizationTargetLufs(normalizationRunAmbienceTarget)
                        preferences.setSoundEffectsNormalizationTargetLufs(normalizationRunSfxTarget)
                        audioSettingsPersistenceScope.launch {
                            settingsRepository.setSceneMusicTargetLufs(musicTarget)
                            if (PlaybackQueueStore.state.value.isPlaying) {
                                ReaderPlaybackService.command(
                                    application,
                                    ReaderPlaybackService.ACTION_REFRESH,
                                )
                            }
                        }
                        showNormalizationProgress = false
                        normalizationWorkIds = emptyList()
                    }) { Text("LƯU") }
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
        val target = when (kind) {
            AudioAssetKind.MUSIC -> musicNormalizationTarget
            AudioAssetKind.AMBIENCE -> snapshot.ambienceNormalizationTargetLufs
            AudioAssetKind.SFX -> snapshot.soundEffectsNormalizationTargetLufs
        }
        UnifiedAudioAssetManagerDialog(
            kind = kind,
            tracks = tracks.filter { AudioAssetClassifier.classify(it) == kind },
            normalizationTargetLufs = target,
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
    val intervals = (range.endInclusive - range.start).roundToInt().coerceAtLeast(1)
    Text("$title: ${shown(safe)}")
    Slider(
        value = safe,
        onValueChange = { onValueChange(it.coerceIn(range.start, range.endInclusive)) },
        valueRange = range,
        steps = (intervals - 1).coerceAtLeast(0),
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
