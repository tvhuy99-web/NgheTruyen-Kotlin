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
    private var pendingSequential: Slot? = null
    private var transitionJob: Job? = null
    private var duckJob: Job? = null
    private var speaking = false
    private var duckFactor = 0.63095734f
    private var duckMultiplier = 1f
    private var duckAttackMillis = 1850
    private var duckReleaseMillis = 2050

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
            if (XpkPlaybackRuntime.isCanonicalScenePlanActive()) {
                startXpkSequentialTransition(next, XPK_SCENE_SWITCH_MILLIS)
            } else {
                startCrossfadeTransition(next, crossfadeMillis.coerceIn(0, 8_000))
            }
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
        pendingSequential?.player?.runCatching { if (isPlaying) pause() }
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
        release(outgoing)
        outgoing = null
        release(pendingSequential)
        pendingSequential = null
        if (clearTrack) {
            release(active)
            active = null
            duckMultiplier = if (speaking) duckFactor else 1f
        } else {
            pause()
        }
    }

    fun release() = stop(clearTrack = true)

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
                val wasPending = pendingSequential?.player === failed
                if (wasActive) active = null
                if (wasPending) pendingSequential = null
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
        release(pendingSequential)
        pendingSequential = null
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







    private fun startXpkSequentialTransition(next: Slot, durationMillis: Int) {
        transitionJob?.cancel()
        release(pendingSequential)
        pendingSequential = next
        outgoing?.let(::release)
        outgoing = null
        val old = active
        val duration = durationMillis.coerceIn(1_200, 4_000)
        val fadeOutMillis = if (old == null) 0 else duration / 2
        val fadeInMillis = if (old == null) duration else duration - fadeOutMillis

        transitionJob = scope.launch(Dispatchers.Main.immediate) {
            if (old != null) {
                outgoing = old
                animateSlotFade(old, old.fadeMultiplier, 0f, fadeOutMillis)
                if (active === old) active = null
                if (outgoing === old) outgoing = null
                release(old)
            }

            if (pendingSequential !== next) {
                release(next)
                return@launch
            }
            pendingSequential = null
            next.fadeMultiplier = 0f
            active = next
            val started = runCatching { next.player.start() }.isSuccess
            if (!started) {
                if (active === next) active = null
                release(next)
                onError("Không khởi động được nhạc nền đã chọn.")
                return@launch
            }
            applyLevel(next)
            animateSlotFade(next, 0f, 1f, fadeInMillis)
            next.fadeMultiplier = 1f
            applyLevel(next)
        }
    }

    private suspend fun animateSlotFade(slot: Slot, from: Float, to: Float, durationMillis: Int) {
        if (durationMillis <= 0) {
            slot.fadeMultiplier = to
            applyLevel(slot)
            return
        }
        val steps = (durationMillis / 40f).roundToInt().coerceIn(4, 100)
        repeat(steps + 1) { index ->
            val fraction = index.toFloat() / steps.toFloat()
            slot.fadeMultiplier = from + (to - from) * fraction
            applyLevel(slot)
            if (index < steps) delay(durationMillis.toLong() / steps)
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

    private fun applyLevel(slot: Slot) = setSlotLevel(slot, desiredLevel(slot))

    private fun desiredLevel(slot: Slot): Float =
        (slot.baseVolume * duckMultiplier * slot.fadeMultiplier).coerceIn(0f, 1f)

    private fun setSlotLevel(slot: Slot, level: Float) {
        runCatching { slot.player.setVolume(level, level) }
    }

    private fun release(slot: Slot?) {
        slot ?: return
        runCatching { slot.player.stop() }
        runCatching { slot.player.release() }
    }

    companion object {
        private const val XPK_SCENE_SWITCH_MILLIS = 2_200
    }
}
