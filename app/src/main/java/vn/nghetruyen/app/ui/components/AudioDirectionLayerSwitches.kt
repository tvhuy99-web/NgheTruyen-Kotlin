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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import vn.nghetruyen.app.audio.StoryAudioSourceMode
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderPlaybackService

private val audioSettingsPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Reader audio-layer controls. The selected source mode owns its visible options: manual playback,
 * AI-local planning and AI-Freesound planning are deliberately not rendered together.
 *
 * [onManageMusic] is retained only for source compatibility with the existing ReaderScreen call.
 * MUSIC uses [UnifiedAudioAssetManagerDialog] just like AMBIENCE and SFX.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AudioDirectionLayerSwitches(
    musicTrackCount: Int = 0,
    onManageMusic: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onSourceModeChanged: (StoryAudioSourceMode) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val settingsRepository = application.container.settingsRepository
    val preferences = remember(context) { AudioDirectionPreferences.shared(context) }
    val scope = rememberCoroutineScope()
    val tracks by repository.observeSceneMusicTracks().collectAsState(initial = emptyList())
    var snapshot by remember(preferences) { mutableStateOf(preferences.snapshot()) }
    var sourceMode by remember { mutableStateOf(application.container.storyAudioSourceModeStore.get()) }
    var musicEnabled by remember { mutableStateOf(false) }
    var musicNormalizationTarget by remember { mutableStateOf(-24f) }
    var attackMillis by remember { mutableIntStateOf(1850) }
    var releaseMillis by remember { mutableIntStateOf(2050) }
    var dynamicsSettingsDirty by remember { mutableStateOf(false) }
    var managerKind by remember { mutableStateOf<AudioAssetKind?>(null) }

    var showNormalizationDialog by remember { mutableStateOf(false) }
    var normalizationKinds by remember { mutableStateOf(setOf(AudioAssetKind.MUSIC)) }
    var normalizationMusicDraft by remember { mutableStateOf(-24f) }
    var normalizationAmbienceDraft by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS) }
    var normalizationSfxDraft by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_SFX_NORMALIZATION_TARGET_LUFS) }
    var normalizationSaving by remember { mutableStateOf(false) }
    var normalizationPersistenceError by remember { mutableStateOf<String?>(null) }
    var showNormalizationProgress by remember { mutableStateOf(false) }
    var normalizationWorkIds by remember { mutableStateOf<List<UUID>>(emptyList()) }
    var normalizationDone by remember { mutableIntStateOf(0) }
    var normalizationFailed by remember { mutableIntStateOf(0) }
    var normalizationCancelled by remember { mutableIntStateOf(0) }
    var normalizationRunKinds by remember { mutableStateOf(setOf(AudioAssetKind.MUSIC)) }
    var normalizationRunMusicTarget by remember { mutableStateOf(-24f) }
    var normalizationRunAmbienceTarget by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS) }
    var normalizationRunSfxTarget by remember { mutableStateOf(AudioDirectionPreferences.DEFAULT_SFX_NORMALIZATION_TARGET_LUFS) }
    var normalizationRunForceRemeasure by remember { mutableStateOf(false) }

    val latestAttackMillis by rememberUpdatedState(attackMillis)
    val latestReleaseMillis by rememberUpdatedState(releaseMillis)
    val latestDynamicsDirty by rememberUpdatedState(dynamicsSettingsDirty)

    suspend fun persistNormalizationTargets(
        musicTarget: Float,
        ambienceTarget: Float,
        sfxTarget: Float,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            settingsRepository.setSceneMusicTargetLufs(musicTarget)
            preferences.setAmbienceNormalizationTargetLufs(ambienceTarget)
            preferences.setSoundEffectsNormalizationTargetLufs(sfxTarget)

            val savedMusic = settingsRepository.snapshot().sceneMusicTargetLufs
            val savedAudio = preferences.snapshot()
            check(abs(savedMusic - musicTarget) < 0.01f) {
                "Mức chuẩn hóa nhạc chưa được lưu đúng: yêu cầu $musicTarget, đọc lại $savedMusic."
            }
            check(abs(savedAudio.ambienceNormalizationTargetLufs - ambienceTarget) < 0.01f) {
                "Mức chuẩn hóa môi trường chưa được lưu đúng."
            }
            check(abs(savedAudio.soundEffectsNormalizationTargetLufs - sfxTarget) < 0.01f) {
                "Mức chuẩn hóa hiệu ứng chưa được lưu đúng."
            }
        }
    }

    fun openNormalization(kinds: Set<AudioAssetKind>) {
        normalizationKinds = kinds
        normalizationMusicDraft = musicNormalizationTarget
        normalizationAmbienceDraft = snapshot.ambienceNormalizationTargetLufs
        normalizationSfxDraft = snapshot.soundEffectsNormalizationTargetLufs
        normalizationPersistenceError = null
        normalizationSaving = false
        showNormalizationDialog = true
    }

    fun startNormalization(forceRemeasure: Boolean) {
        if (normalizationSaving) return
        val musicTarget = normalizationMusicDraft
            .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS)
        val ambienceTarget = normalizationAmbienceDraft
            .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS)
        val sfxTarget = normalizationSfxDraft
            .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS)
        val runKinds = normalizationKinds
        val runTracks = tracks.filter { AudioAssetClassifier.classify(it) in runKinds }

        normalizationSaving = true
        normalizationPersistenceError = null
        scope.launch {
            val persisted = persistNormalizationTargets(musicTarget, ambienceTarget, sfxTarget)
            if (persisted.isFailure) {
                normalizationSaving = false
                normalizationPersistenceError = persisted.exceptionOrNull()?.message
                    ?: "Không lưu được mức chuẩn hóa."
                return@launch
            }

            musicNormalizationTarget = musicTarget
            snapshot = preferences.snapshot()
            normalizationRunKinds = runKinds
            normalizationRunMusicTarget = musicTarget
            normalizationRunAmbienceTarget = ambienceTarget
            normalizationRunSfxTarget = sfxTarget
            normalizationRunForceRemeasure = forceRemeasure
            normalizationDone = 0
            normalizationFailed = 0
            normalizationCancelled = 0

            val workIds = runTracks.map { track ->
                val target = when (AudioAssetClassifier.classify(track)) {
                    AudioAssetKind.MUSIC -> musicTarget
                    AudioAssetKind.AMBIENCE -> ambienceTarget
                    AudioAssetKind.SFX -> sfxTarget
                }
                SceneMusicAnalysisWorker.enqueue(
                    context = context,
                    trackId = track.id,
                    targetLufs = target,
                    forceRemeasure = forceRemeasure,
                )
            }
            normalizationWorkIds = workIds
            normalizationSaving = false
            showNormalizationDialog = false
            showNormalizationProgress = workIds.isNotEmpty()

            if (workIds.isNotEmpty()) {
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
    }

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
        StoryAudioSourceModeSelector(
            onModeChanged = { mode ->
                sourceMode = mode
                managerKind = null
                showNormalizationDialog = false
                onSourceModeChanged(mode)
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        when (sourceMode) {
            StoryAudioSourceMode.LOCAL_MANUAL -> {
                Text("MODE 1 · PHÁT THỦ CÔNG TỪ THƯ VIỆN LOCAL")
                Text("Mode này không dùng AI chọn MUSIC, AMBIENCE hoặc SFX.")
                AudioManagerButton(
                    label = "QUẢN LÝ NHẠC ($musicTrackCount)",
                    onClick = { managerKind = AudioAssetKind.MUSIC },
                )
                AudioManagerButton(
                    label = "CHUẨN HÓA KHO NHẠC",
                    enabled = tracks.any { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC },
                    onClick = { openNormalization(setOf(AudioAssetKind.MUSIC)) },
                )
            }

            StoryAudioSourceMode.AI_LOCAL -> {
                Text("MODE 2 · AI CHỌN TỪ THƯ VIỆN LOCAL")
                Text("Chỉ Mode 2 gửi danh sách asset local đang bật cho AI để chọn track_id.")
                AudioLayerSwitchRow(
                    title = "AI chọn nhạc cảnh local",
                    checked = musicEnabled,
                    onCheckedChange = { enabled ->
                        musicEnabled = enabled
                        scope.launch { settingsRepository.setAutoSceneMusicEnabled(enabled) }
                    },
                )
                AudioLayerSwitchRow(
                    title = "AI chọn âm thanh môi trường local",
                    checked = snapshot.ambienceEnabled,
                    onCheckedChange = preferences::setAmbienceEnabled,
                )
                AudioLayerSwitchRow(
                    title = "AI chọn hiệu ứng âm thanh local",
                    checked = snapshot.soundEffectsEnabled,
                    onCheckedChange = preferences::setSoundEffectsEnabled,
                )
                if (musicEnabled) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    AudioIntSlider(
                        title = "Attack nhạc AI local",
                        value = attackMillis,
                        maximum = 2_000,
                        onValueChange = { value ->
                            attackMillis = value
                            dynamicsSettingsDirty = true
                        },
                    )
                    AudioIntSlider(
                        title = "Release nhạc AI local",
                        value = releaseMillis,
                        maximum = 5_000,
                        onValueChange = { value ->
                            releaseMillis = value
                            dynamicsSettingsDirty = true
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                AudioManagerButton(
                    label = "CHUẨN HÓA THƯ VIỆN AI LOCAL",
                    enabled = tracks.isNotEmpty(),
                    onClick = { openNormalization(AudioAssetKind.entries.toSet()) },
                )
                AudioManagerButton(
                    label = "QUẢN LÝ NHẠC LOCAL ($musicTrackCount)",
                    onClick = { managerKind = AudioAssetKind.MUSIC },
                )
                AudioManagerButton(
                    label = "QUẢN LÝ MÔI TRƯỜNG LOCAL (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }})",
                    onClick = { managerKind = AudioAssetKind.AMBIENCE },
                )
                AudioManagerButton(
                    label = "QUẢN LÝ SFX LOCAL (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }})",
                    onClick = { managerKind = AudioAssetKind.SFX },
                )
            }

            StoryAudioSourceMode.AI_FREESOUND -> {
                Text("MODE 3 · AI TỰ TÌM TRÊN FREESOUND")
                Text("AI chỉ mô tả nhu cầu tìm kiếm theo timeline; Mode 3 không gửi catalog local và không dùng quy tắc chọn track_id của Mode 2.")
                AudioLayerSwitchRow(
                    title = "Tự tìm nhạc nền trên Freesound",
                    checked = musicEnabled,
                    onCheckedChange = { enabled ->
                        musicEnabled = enabled
                        scope.launch { settingsRepository.setAutoSceneMusicEnabled(enabled) }
                    },
                )
                AudioLayerSwitchRow(
                    title = "Tự tìm môi trường trên Freesound",
                    checked = snapshot.ambienceEnabled,
                    onCheckedChange = preferences::setAmbienceEnabled,
                )
                AudioLayerSwitchRow(
                    title = "Tự tìm SFX trên Freesound",
                    checked = snapshot.soundEffectsEnabled,
                    onCheckedChange = preferences::setSoundEffectsEnabled,
                )
                if (musicEnabled) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    AudioIntSlider(
                        title = "Attack nhạc Mode 3",
                        value = attackMillis,
                        maximum = 2_000,
                        onValueChange = { value ->
                            attackMillis = value
                            dynamicsSettingsDirty = true
                        },
                    )
                    AudioIntSlider(
                        title = "Release nhạc Mode 3",
                        value = releaseMillis,
                        maximum = 5_000,
                        onValueChange = { value ->
                            releaseMillis = value
                            dynamicsSettingsDirty = true
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                AudioManagerButton(
                    label = "CHUẨN HÓA / BẢO TRÌ FILE ÂM THANH MODE 3",
                    enabled = tracks.isNotEmpty(),
                    onClick = { openNormalization(AudioAssetKind.entries.toSet()) },
                )
                AudioManagerButton(
                    label = "KHO NHẠC ĐÃ TẢI / FALLBACK ($musicTrackCount)",
                    onClick = { managerKind = AudioAssetKind.MUSIC },
                )
                AudioManagerButton(
                    label = "KHO MÔI TRƯỜNG ĐÃ TẢI / FALLBACK (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }})",
                    onClick = { managerKind = AudioAssetKind.AMBIENCE },
                )
                AudioManagerButton(
                    label = "KHO SFX ĐÃ TẢI / FALLBACK (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }})",
                    onClick = { managerKind = AudioAssetKind.SFX },
                )
            }
        }
    }

    if (showNormalizationDialog) {
        val musicCount = tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        val ambienceCount = tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        val sfxCount = tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
        AlertDialog(
            onDismissRequest = {
                if (!normalizationSaving) showNormalizationDialog = false
            },
            title = { Text(if (normalizationKinds == setOf(AudioAssetKind.MUSIC)) "CHUẨN HÓA KHO NHẠC" else "CHUẨN HÓA KHO ÂM THANH") },
            text = {
                Column {
                    Text("Chỉ các loại âm thanh thuộc mode hiện tại mới xuất hiện trong hộp thoại này.")
                    Text("CHUẨN HÓA dùng lại phép đo loudness/peak hợp lệ và chỉ tính gain mới. ĐO LẠI TỪ ĐẦU bỏ kết quả chuẩn hóa cũ, giải mã và đo lại tệp được chọn.")
                    if (AudioAssetKind.MUSIC in normalizationKinds) {
                        AudioFloatSlider(
                            title = "Nhạc nền ($musicCount tệp)",
                            value = normalizationMusicDraft,
                            range = PcmLoudnessEstimator.MIN_TARGET_LUFS..PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS,
                            shown = { "%.0f LUFS".format(it) },
                            onValueChange = { normalizationMusicDraft = it },
                        )
                    }
                    if (AudioAssetKind.AMBIENCE in normalizationKinds) {
                        AudioFloatSlider(
                            title = "Âm thanh môi trường ($ambienceCount tệp)",
                            value = normalizationAmbienceDraft,
                            range = PcmLoudnessEstimator.MIN_TARGET_LUFS..PcmLoudnessEstimator.MAX_TARGET_LUFS,
                            shown = { "%.0f LUFS".format(it) },
                            onValueChange = { normalizationAmbienceDraft = it },
                        )
                    }
                    if (AudioAssetKind.SFX in normalizationKinds) {
                        AudioFloatSlider(
                            title = "Hiệu ứng âm thanh ($sfxCount tệp)",
                            value = normalizationSfxDraft,
                            range = PcmLoudnessEstimator.MIN_TARGET_LUFS..PcmLoudnessEstimator.MAX_TARGET_LUFS,
                            shown = { "%.0f LUFS".format(it) },
                            onValueChange = { normalizationSfxDraft = it },
                        )
                    }
                    normalizationPersistenceError?.let { error ->
                        Text("Lỗi lưu: $error")
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(
                        enabled = !normalizationSaving,
                        onClick = { startNormalization(forceRemeasure = false) },
                    ) { Text(if (normalizationSaving) "ĐANG LƯU…" else "CHUẨN HÓA") }
                    TextButton(
                        enabled = !normalizationSaving,
                        onClick = { startNormalization(forceRemeasure = true) },
                    ) { Text("ĐO LẠI TỪ ĐẦU") }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !normalizationSaving,
                    onClick = { showNormalizationDialog = false },
                ) { Text("HỦY") }
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
                    Text(
                        if (normalizationRunForceRemeasure) {
                            "Chế độ: ĐO LẠI TỪ ĐẦU"
                        } else {
                            "Chế độ: DÙNG LẠI PHÉP ĐO HỢP LỆ"
                        },
                    )
                    if (AudioAssetKind.MUSIC in normalizationRunKinds) Text("Nhạc nền: %.0f LUFS".format(normalizationRunMusicTarget))
                    if (AudioAssetKind.AMBIENCE in normalizationRunKinds) Text("Môi trường: %.0f LUFS".format(normalizationRunAmbienceTarget))
                    if (AudioAssetKind.SFX in normalizationRunKinds) Text("Hiệu ứng: %.0f LUFS".format(normalizationRunSfxTarget))
                    Text("Hoàn tất: $finished / $total")
                    if (normalizationFailed > 0) Text("Lỗi: $normalizationFailed")
                    if (normalizationCancelled > 0) Text("Đã hủy: $normalizationCancelled")
                    normalizationPersistenceError?.let { error -> Text("Lỗi lưu: $error") }
                }
            },
            confirmButton = {
                if (total > 0 && finished >= total) {
                    TextButton(
                        enabled = !normalizationSaving,
                        onClick = {
                            normalizationSaving = true
                            normalizationPersistenceError = null
                            scope.launch {
                                val persisted = persistNormalizationTargets(
                                    normalizationRunMusicTarget,
                                    normalizationRunAmbienceTarget,
                                    normalizationRunSfxTarget,
                                )
                                normalizationSaving = false
                                if (persisted.isFailure) {
                                    normalizationPersistenceError = persisted.exceptionOrNull()?.message
                                        ?: "Không lưu được mức chuẩn hóa."
                                    return@launch
                                }
                                musicNormalizationTarget = normalizationRunMusicTarget
                                snapshot = preferences.snapshot()
                                if (PlaybackQueueStore.state.value.isPlaying) {
                                    ReaderPlaybackService.command(
                                        application,
                                        ReaderPlaybackService.ACTION_REFRESH,
                                    )
                                }
                                showNormalizationProgress = false
                                normalizationWorkIds = emptyList()
                            }
                        },
                    ) { Text(if (normalizationSaving) "ĐANG LƯU…" else "LƯU") }
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
