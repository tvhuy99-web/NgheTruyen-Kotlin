package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.PcmLoudnessEstimator

/**
 * Process-local bridge from the application-level AudioDirectionRuntime to the active reader music
 * controller. There is only one ReaderPlaybackService at a time; attaching a new listener replaces
 * a stale service listener and release detaches by identity.
 */
object SceneMusicSfxDuckBus {
    @Volatile private var listener: ((Float, Long) -> Unit)? = null

    fun attach(value: (Float, Long) -> Unit) {
        listener = value
    }

    fun detach(value: (Float, Long) -> Unit) {
        if (listener === value) listener = null
    }

    fun duck(factor: Float, holdMillis: Long) {
        listener?.invoke(factor, holdMillis)
    }
}

/**
 * Owns MediaPlayer instances for background music. Scene changes use a real overlapping crossfade;
 * the current track stays audible while the requested track fades in, then the old player is released.
 * TTS ducking and brief SFX-priority ducking are independent multipliers so one cannot cancel the other.
 * All public calls are expected on the main thread.
 *
 * Async prepare requests are generation guarded. A slow, stale request can therefore never replace a
 * newer track or start playing after pause/stop/release.
 */
class SceneMusicController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private data class Slot(
        val trackId: String,
        val uri: String,
        val player: MediaPlayer,
        var baseVolume: Float,
        var fadeMultiplier: Float = 1f,
    )

    private var active: Slot? = null
    private var outgoing: Slot? = null
    private var transitionJob: Job? = null
    private var duckJob: Job? = null
    private var sfxDuckJob: Job? = null
    private val pendingPlayers = linkedSetOf<MediaPlayer>()
    private val positiveBoosts = mutableMapOf<MediaPlayer, LoudnessEnhancer>()
    private var transitionGeneration = 0L
    private var paused = false
    private var releasedController = false
    private var speaking = false
    private var duckFactor = 0.63095734f
    private var duckMultiplier = 1f
    private var sfxDuckMultiplier = 1f
    private var duckAttackMillis = 1850
    private var duckReleaseMillis = 2050
    private var positiveBoostUnavailableReported = false
    private val sfxDuckListener: (Float, Long) -> Unit = { factor, holdMillis ->
        duckForImportantSfx(factor, holdMillis)
    }

    init {
        SceneMusicSfxDuckBus.attach(sfxDuckListener)
    }

    val activeTrackId: String?
        get() = active?.trackId

    fun setDuckTiming(attackMillis: Int, releaseMillis: Int) {
        duckAttackMillis = attackMillis.coerceIn(0, 2_000)
        duckReleaseMillis = releaseMillis.coerceIn(0, 5_000)
    }

    fun transition(
        trackId: String,
        uri: String,
        volume: Float,
        duckFactor: Float,
        crossfadeMillis: Int,
        looping: Boolean = true,
        onCompletion: (() -> Unit)? = null,
    ) {
        if (releasedController) return
        if (trackId == SceneMusicSelector.SILENCE_TRACK_ID) {
            this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
            stop(clearTrack = true)
            return
        }
        val generation = ++transitionGeneration
        releasePendingPlayers()

        val normalizedVolume = volume.coerceIn(0f, 1f)
        this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
        if (speaking && duckJob?.isActive != true) duckMultiplier = this.duckFactor
        val current = active
        if (current?.trackId == trackId && current.uri == uri) {
            current.baseVolume = normalizedVolume
            applyLevel(current)
            refreshPositiveNormalizationBoost(current)
            return
        }

        val nextPlayer = createPlayer(trackId, uri, looping, onCompletion) ?: return
        pendingPlayers += nextPlayer
        nextPlayer.setOnPreparedListener { prepared ->
            pendingPlayers.remove(prepared)
            if (releasedController || generation != transitionGeneration) {
                releasePlayer(prepared)
                return@setOnPreparedListener
            }
            val next = Slot(trackId, uri, prepared, normalizedVolume)
            val requested = crossfadeMillis.coerceIn(0, 8_000)
            val duration = if (XpkPlaybackRuntime.isCanonicalScenePlanActive() && requested == 0) {
                XPK_DEFAULT_CROSSFADE_MILLIS
            } else requested
            if (paused) installPausedTransition(next) else startCrossfadeTransition(next, duration)
        }
        runCatching { nextPlayer.prepareAsync() }.onFailure {
            pendingPlayers.remove(nextPlayer)
            releasePlayer(nextPlayer)
            if (generation == transitionGeneration && !releasedController) {
                onError("Không chuẩn bị được nhạc nền đã chọn.")
                onCompletion?.invoke()
            }
        }
    }

    fun keepCurrent(duckFactor: Float) {
        if (releasedController) return
        this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
        animateDuck(if (speaking) this.duckFactor else 1f, if (speaking) duckAttackMillis else duckReleaseMillis)
    }

    fun setSpeaking(value: Boolean) {
        if (releasedController) return
        speaking = value
        animateDuck(
            target = if (value) duckFactor else 1f,
            durationMillis = if (value) duckAttackMillis else duckReleaseMillis,
        )
    }

    fun pause() {
        if (releasedController) return
        paused = true
        active?.player?.runCatching { if (isPlaying) pause() }
        outgoing?.player?.runCatching { if (isPlaying) pause() }
    }

    fun resume() {
        if (releasedController) return
        paused = false
        val slot = active ?: return
        runCatching {
            applyLevel(slot)
            if (!slot.player.isPlaying) slot.player.start()
        }.onFailure {
            onError("Không tiếp tục được nhạc nền hiện tại.")
        }
    }

    fun stop(clearTrack: Boolean = true) {
        ++transitionGeneration
        releasePendingPlayers()
        transitionJob?.cancel()
        transitionJob = null
        duckJob?.cancel()
        duckJob = null
        sfxDuckJob?.cancel()
        sfxDuckJob = null
        sfxDuckMultiplier = 1f
        release(outgoing)
        outgoing = null
        if (clearTrack) {
            release(active)
            active = null
            paused = false
            duckMultiplier = if (speaking) duckFactor else 1f
        } else {
            paused = true
            active?.let { slot ->
                slot.fadeMultiplier = 1f
                applyLevel(slot)
                runCatching { if (slot.player.isPlaying) slot.player.pause() }
            }
        }
    }

    fun release() {
        if (releasedController) return
        releasedController = true
        SceneMusicSfxDuckBus.detach(sfxDuckListener)
        stop(clearTrack = true)
    }

    private fun duckForImportantSfx(factor: Float, holdMillis: Long) {
        if (releasedController) return
        val target = factor.coerceIn(0.45f, 1f)
        sfxDuckJob?.cancel()
        sfxDuckJob = scope.launch(Dispatchers.Main.immediate) {
            animateSfxDuck(target, SFX_DUCK_ATTACK_MS)
            delay(holdMillis.coerceIn(120L, 3_000L))
            animateSfxDuck(1f, SFX_DUCK_RELEASE_MS)
        }
    }

    private fun createPlayer(
        trackId: String,
        uri: String,
        looping: Boolean,
        onCompletion: (() -> Unit)?,
    ): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(context, Uri.parse(uri))
            isLooping = looping
            setVolume(0f, 0f)
            setOnCompletionListener { completed ->
                pendingPlayers.remove(completed)
                val current = active
                if (current?.player === completed) {
                    active = null
                    releasePlayer(completed)
                    onCompletion?.invoke()
                }
            }
            setOnErrorListener { failed, _, _ ->
                val wasPending = pendingPlayers.remove(failed)
                val wasActive = active?.player === failed
                val wasOutgoing = outgoing?.player === failed
                if (wasActive) active = null
                if (wasOutgoing) outgoing = null
                releasePlayer(failed)
                if (!releasedController && (wasPending || wasActive || wasOutgoing)) {
                    onError("Không phát được nhạc nền '$trackId'.")
                }
                if (wasActive && !releasedController) onCompletion?.invoke()
                true
            }
        }
    }.getOrElse {
        onError("Không mở được tệp nhạc nền đã chọn.")
        null
    }

    private fun installPausedTransition(next: Slot) {
        transitionJob?.cancel()
        transitionJob = null
        release(outgoing)
        outgoing = null
        release(active)
        next.fadeMultiplier = 1f
        active = next
        applyLevel(next)
        refreshPositiveNormalizationBoost(next)
    }

    private fun startCrossfadeTransition(next: Slot, durationMillis: Int) {
        transitionJob?.cancel()
        outgoing?.let(::release)
        outgoing = active
        active = next
        refreshPositiveNormalizationBoost(next)
        val old = outgoing
        next.fadeMultiplier = if (durationMillis > 0 && old != null) 0f else 1f
        runCatching { next.player.start() }.onFailure {
            release(next)
            active = old
            outgoing = null
            onError("Không khởi động được nhạc nền đã chọn.")
            return
        }
        if (durationMillis <= 0 || old == null) {
            old?.let(::release)
            outgoing = null
            next.fadeMultiplier = 1f
            applyLevel(next)
            return
        }
        transitionJob = scope.launch(Dispatchers.Main.immediate) {
            val steps = (durationMillis / 40f).roundToInt().coerceIn(4, 120)
            repeat(steps + 1) { index ->
                val fraction = index.toFloat() / steps.toFloat()
                next.fadeMultiplier = fraction
                old.fadeMultiplier = 1f - fraction
                applyLevel(next)
                applyLevel(old)
                if (index < steps) delay(durationMillis.toLong() / steps)
            }
            release(old)
            if (outgoing === old) outgoing = null
            next.fadeMultiplier = 1f
            applyLevel(next)
        }
    }

    private fun refreshPositiveNormalizationBoost(slot: Slot) {
        scope.launch(Dispatchers.IO) {
            val application = context.applicationContext as? NgheTruyenApplication ?: return@launch
            val track = application.container.libraryRepository.getSceneMusicTrack(slot.trackId)
            val gainDb = if (
                track != null &&
                track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
                track.normalizationError.isBlank() &&
                track.loudnessLufsEstimate.isFinite() &&
                track.peakDbfs.isFinite()
            ) {
                val target = application.container.settingsRepository.snapshot().sceneMusicTargetLufs
                PcmLoudnessEstimator.calculateNormalization(
                    track.loudnessLufsEstimate,
                    track.peakDbfs,
                    target,
                ).gainDb
            } else {
                0f
            }
            val gainLinear = PcmLoudnessEstimator.gainDbToLinear(gainDb.coerceAtLeast(0f))
            withContext(Dispatchers.Main.immediate) {
                if (releasedController || (active !== slot && outgoing !== slot)) return@withContext
                if (gainDb > 0.001f) {
                    // ReaderPlaybackService already folds normalization into baseVolume until MediaPlayer's
                    // 1.0 ceiling. When it has not hit that ceiling, remove that folded positive part here
                    // and let LoudnessEnhancer apply it once. When it has hit the ceiling, the enhancer
                    // supplies the positive gain that MediaPlayer.setVolume could not represent.
                    if (slot.baseVolume < 0.999f && gainLinear > 1f) {
                        slot.baseVolume = (slot.baseVolume / gainLinear).coerceIn(0f, 1f)
                    }
                    installPositiveBoost(slot.player, gainDb)
                } else {
                    clearPositiveBoost(slot.player)
                }
                applyLevel(slot)
            }
        }
    }

    private fun installPositiveBoost(player: MediaPlayer, gainDb: Float) {
        clearPositiveBoost(player)
        val positiveDb = gainDb.coerceIn(0f, PcmLoudnessEstimator.MAX_GAIN_DB)
        if (positiveDb <= 0.001f) return
        val enhancer = runCatching {
            LoudnessEnhancer(player.audioSessionId).apply {
                setTargetGain((positiveDb * 100f).roundToInt())
                enabled = true
            }
        }.getOrElse {
            if (!positiveBoostUnavailableReported) {
                positiveBoostUnavailableReported = true
                onError("Thiết bị không áp dụng được gain dương của chuẩn hóa nhạc nền.")
            }
            return
        }
        positiveBoosts[player] = enhancer
    }

    private fun clearPositiveBoost(player: MediaPlayer) {
        positiveBoosts.remove(player)?.let { enhancer ->
            runCatching { enhancer.enabled = false }
            runCatching { enhancer.release() }
        }
    }

    private fun animateDuck(target: Float, durationMillis: Int) {
        duckJob?.cancel()
        val safeTarget = target.coerceIn(0.05f, 1f)
        if (durationMillis <= 0 || kotlin.math.abs(duckMultiplier - safeTarget) < 0.001f) {
            duckMultiplier = safeTarget
            active?.let(::applyLevel)
            outgoing?.let(::applyLevel)
            return
        }
        val start = duckMultiplier
        duckJob = scope.launch(Dispatchers.Main.immediate) {
            val steps = (durationMillis / 40f).roundToInt().coerceIn(4, 125)
            repeat(steps + 1) { index ->
                val fraction = index.toFloat() / steps.toFloat()
                duckMultiplier = start + (safeTarget - start) * fraction
                active?.let(::applyLevel)
                outgoing?.let(::applyLevel)
                if (index < steps) delay(durationMillis.toLong() / steps)
            }
            duckMultiplier = safeTarget
            active?.let(::applyLevel)
            outgoing?.let(::applyLevel)
        }
    }

    private suspend fun animateSfxDuck(target: Float, durationMillis: Int) {
        val start = sfxDuckMultiplier
        if (durationMillis <= 0) {
            sfxDuckMultiplier = target
            active?.let(::applyLevel)
            outgoing?.let(::applyLevel)
            return
        }
        val steps = (durationMillis / 30f).roundToInt().coerceIn(3, 30)
        repeat(steps + 1) { index ->
            val fraction = index.toFloat() / steps.toFloat()
            sfxDuckMultiplier = start + (target - start) * fraction
            active?.let(::applyLevel)
            outgoing?.let(::applyLevel)
            if (index < steps) delay((durationMillis.toLong() / steps).coerceAtLeast(1L))
        }
    }

    private fun applyLevel(slot: Slot) = setSlotLevel(slot, desiredLevel(slot))

    private fun desiredLevel(slot: Slot): Float =
        (slot.baseVolume * duckMultiplier * sfxDuckMultiplier * slot.fadeMultiplier).coerceIn(0f, 1f)

    private fun setSlotLevel(slot: Slot, level: Float) {
        runCatching { slot.player.setVolume(level, level) }
    }

    private fun releasePendingPlayers() {
        if (pendingPlayers.isEmpty()) return
        val stale = pendingPlayers.toList()
        pendingPlayers.clear()
        stale.forEach(::releasePlayer)
    }

    private fun release(slot: Slot?) {
        slot ?: return
        releasePlayer(slot.player)
    }

    private fun releasePlayer(player: MediaPlayer) {
        pendingPlayers.remove(player)
        clearPositiveBoost(player)
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    companion object {
        private const val XPK_DEFAULT_CROSSFADE_MILLIS = 2_200
        private const val SFX_DUCK_ATTACK_MS = 120
        private const val SFX_DUCK_RELEASE_MS = 360
    }
}
