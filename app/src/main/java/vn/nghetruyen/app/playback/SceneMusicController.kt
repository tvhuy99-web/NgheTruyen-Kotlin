package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    private var speaking = false
    private var duckFactor = 0.63095734f
    private var duckMultiplier = 1f
    private var sfxDuckMultiplier = 1f
    private var duckAttackMillis = 1850
    private var duckReleaseMillis = 2050
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
        val normalizedVolume = volume.coerceIn(0f, 1f)
        this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
        if (speaking && duckJob?.isActive != true) duckMultiplier = this.duckFactor
        val current = active
        if (current?.trackId == trackId && current.uri == uri) {
            current.baseVolume = normalizedVolume
            applyLevel(current)
            return
        }
        val nextPlayer = createPlayer(trackId, uri, looping, onCompletion) ?: return
        nextPlayer.setOnPreparedListener { prepared ->
            val next = Slot(trackId, uri, prepared, normalizedVolume)
            val requested = crossfadeMillis.coerceIn(0, 8_000)
            val duration = if (XpkPlaybackRuntime.isCanonicalScenePlanActive() && requested == 0) {
                XPK_DEFAULT_CROSSFADE_MILLIS
            } else requested
            startCrossfadeTransition(next, duration)
        }
        runCatching { nextPlayer.prepareAsync() }.onFailure {
            nextPlayer.release()
            onError("Không chuẩn bị được nhạc nền đã chọn.")
            onCompletion?.invoke()
        }
    }

    fun keepCurrent(duckFactor: Float) {
        this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
        animateDuck(if (speaking) this.duckFactor else 1f, if (speaking) duckAttackMillis else duckReleaseMillis)
    }

    fun setSpeaking(value: Boolean) {
        speaking = value
        animateDuck(
            target = if (value) duckFactor else 1f,
            durationMillis = if (value) duckAttackMillis else duckReleaseMillis,
        )
    }

    fun pause() {
        active?.player?.runCatching { if (isPlaying) pause() }
        outgoing?.player?.runCatching { if (isPlaying) pause() }
    }

    fun resume() {
        val slot = active ?: return
        runCatching {
            applyLevel(slot)
            if (!slot.player.isPlaying) slot.player.start()
        }.onFailure {
            onError("Không tiếp tục được nhạc nền hiện tại.")
        }
    }

    fun stop(clearTrack: Boolean = true) {
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
            duckMultiplier = if (speaking) duckFactor else 1f
        } else {
            pause()
        }
    }

    fun release() {
        SceneMusicSfxDuckBus.detach(sfxDuckListener)
        stop(clearTrack = true)
    }

    private fun duckForImportantSfx(factor: Float, holdMillis: Long) {
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
                val current = active
                if (current?.player === completed) {
                    active = null
                    runCatching { completed.release() }
                    onCompletion?.invoke()
                }
            }
            setOnErrorListener { failed, _, _ ->
                val wasActive = active?.player === failed
                val wasOutgoing = outgoing?.player === failed
                if (wasActive) active = null
                if (wasOutgoing) outgoing = null
                runCatching { failed.release() }
                onError("Không phát được nhạc nền '$trackId'.")
                if (wasActive) onCompletion?.invoke()
                true
            }
        }
    }.getOrElse {
        onError("Không mở được tệp nhạc nền đã chọn.")
        null
    }

    private fun startCrossfadeTransition(next: Slot, durationMillis: Int) {
        transitionJob?.cancel()
        outgoing?.let(::release)
        outgoing = active
        active = next
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

    private fun release(slot: Slot?) {
        slot ?: return
        runCatching { slot.player.stop() }
        runCatching { slot.player.release() }
    }

    companion object {
        private const val XPK_DEFAULT_CROSSFADE_MILLIS = 2_200
        private const val SFX_DUCK_ATTACK_MS = 120
        private const val SFX_DUCK_RELEASE_MS = 360
    }
}
