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
 * Owns two MediaPlayer instances so scene changes can crossfade without a gap.
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
    )

    private var active: Slot? = null
    private var outgoing: Slot? = null
    private var transitionJob: Job? = null
    private var speaking = false
    private var duckFactor = 0.25f

    val activeTrackId: String?
        get() = active?.trackId

    fun transition(
        trackId: String,
        uri: String,
        volume: Float,
        duckFactor: Float,
        crossfadeMillis: Int,
    ) {
        val normalizedVolume = volume.coerceIn(0f, 0.6f)
        this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
        val current = active
        if (current?.trackId == trackId && current.uri == uri) {
            current.baseVolume = normalizedVolume
            applyLevel(current)
            return
        }
        val nextPlayer = createPlayer(trackId, uri, normalizedVolume) ?: return
        nextPlayer.setOnPreparedListener { prepared ->
            val next = Slot(trackId, uri, prepared, normalizedVolume)
            startTransition(next, crossfadeMillis.coerceIn(0, 8_000))
        }
        runCatching { nextPlayer.prepareAsync() }.onFailure {
            nextPlayer.release()
            onError("Không chuẩn bị được nhạc cảnh đã chọn.")
        }
    }

    fun keepCurrent(duckFactor: Float) {
        this.duckFactor = duckFactor.coerceIn(0.05f, 1f)
        active?.let(::applyLevel)
    }

    fun setSpeaking(value: Boolean) {
        speaking = value
        active?.let(::applyLevel)
        outgoing?.let(::applyLevel)
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
            onError("Không tiếp tục được nhạc cảnh hiện tại.")
        }
    }

    fun stop(clearTrack: Boolean = true) {
        transitionJob?.cancel()
        transitionJob = null
        release(outgoing)
        outgoing = null
        if (clearTrack) {
            release(active)
            active = null
        } else {
            pause()
        }
    }

    fun release() = stop(clearTrack = true)

    private fun createPlayer(trackId: String, uri: String, volume: Float): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(context, Uri.parse(uri))
            isLooping = true
            setVolume(0f, 0f)
            setOnErrorListener { failed, _, _ ->
                runCatching { failed.reset() }
                onError("Không phát được nhạc cảnh '$trackId'.")
                true
            }
        }
    }.getOrElse {
        onError("Không mở được tệp nhạc cảnh đã chọn.")
        null
    }

    private fun startTransition(next: Slot, durationMillis: Int) {
        transitionJob?.cancel()
        outgoing?.let(::release)
        outgoing = active
        active = next
        runCatching { next.player.start() }.onFailure {
            release(next)
            active = outgoing
            outgoing = null
            onError("Không khởi động được nhạc cảnh đã chọn.")
            return
        }
        if (durationMillis <= 0 || outgoing == null) {
            outgoing?.let(::release)
            outgoing = null
            applyLevel(next)
            return
        }
        transitionJob = scope.launch(Dispatchers.Main.immediate) {
            val steps = (durationMillis / 40f).roundToInt().coerceIn(4, 120)
            val old = outgoing
            repeat(steps + 1) { index ->
                val fraction = index.toFloat() / steps.toFloat()
                setSlotLevel(next, desiredLevel(next) * fraction)
                old?.let { setSlotLevel(it, desiredLevel(it) * (1f - fraction)) }
                if (index < steps) delay(durationMillis.toLong() / steps)
            }
            release(old)
            if (outgoing === old) outgoing = null
            applyLevel(next)
        }
    }

    private fun applyLevel(slot: Slot) = setSlotLevel(slot, desiredLevel(slot))

    private fun desiredLevel(slot: Slot): Float =
        (slot.baseVolume * if (speaking) duckFactor else 1f).coerceIn(0f, 1f)

    private fun setSlotLevel(slot: Slot, level: Float) {
        runCatching { slot.player.setVolume(level, level) }
    }

    private fun release(slot: Slot?) {
        slot ?: return
        runCatching { slot.player.stop() }
        runCatching { slot.player.release() }
    }
}
