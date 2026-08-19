package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import kotlin.math.roundToInt
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.PcmLoudnessEstimator

/** Bounded foreground-SFX player with cue-scoped stop/repeat/loop control. */
class SceneSfxController(context: Context) {
    private data class ActiveSfx(val cueKey: String, val player: MediaPlayer)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val activePlayers = ArrayDeque<ActiveSfx>()
    private val positiveBoosts = mutableMapOf<MediaPlayer, LoudnessEnhancer>()
    private val pendingCallbacks = linkedMapOf<String, MutableList<Runnable>>()

    @Synchronized
    fun play(
        asset: AudioDirectionAsset,
        masterVolume: Float,
        maxConcurrent: Int,
        cueKey: String = "",
        loopUntilStopped: Boolean = false,
        repeatCount: Int = 1,
        repeatIntervalMillis: Long = 550L,
    ): Boolean {
        val key = cueKey.ifBlank { "one-shot:${System.nanoTime()}" }
        val limit = maxConcurrent.coerceIn(1, AudioDirectionLimits.MAX_CONCURRENT_SFX)
        val safeRepeatCount = repeatCount.coerceIn(1, AudioDirectionLimits.MAX_SFX_REPEAT_COUNT)
        val safeInterval = repeatIntervalMillis.coerceIn(120L, 2_000L)

        if (loopUntilStopped) stopCue(key)
        val started = startOne(asset, masterVolume, limit, key, looping = loopUntilStopped)
        if (!started) return false
        if (!loopUntilStopped && safeRepeatCount > 1) {
            for (repeatIndex in 1 until safeRepeatCount) {
                lateinit var task: Runnable
                task = Runnable {
                    synchronized(this@SceneSfxController) {
                        pendingCallbacks[key]?.let { callbacks ->
                            callbacks.remove(task)
                            if (callbacks.isEmpty()) pendingCallbacks.remove(key)
                        }
                        startOne(asset, masterVolume, limit, key, looping = false)
                    }
                }
                pendingCallbacks.getOrPut(key) { mutableListOf() }.add(task)
                handler.postDelayed(task, safeInterval * repeatIndex)
            }
        }
        return true
    }

    @Synchronized
    fun stopCue(cueKey: String) {
        if (cueKey.isBlank()) return
        pendingCallbacks.remove(cueKey).orEmpty().forEach(handler::removeCallbacks)
        activePlayers.filter { it.cueKey == cueKey }.map { it.player }.forEach(::release)
    }

    @Synchronized
    fun isCueActive(cueKey: String): Boolean =
        cueKey.isNotBlank() && (
            activePlayers.any { it.cueKey == cueKey } ||
                pendingCallbacks[cueKey].orEmpty().isNotEmpty()
            )

    @Synchronized
    fun stopAll() {
        pendingCallbacks.values.flatten().forEach(handler::removeCallbacks)
        pendingCallbacks.clear()
        while (activePlayers.isNotEmpty()) releaseOldest()
    }

    @Synchronized
    fun activeCount(): Int = activePlayers.size

    private fun startOne(
        asset: AudioDirectionAsset,
        masterVolume: Float,
        limit: Int,
        cueKey: String,
        looping: Boolean,
    ): Boolean {
        while (activePlayers.size >= limit) releaseOldest()
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(appContext, Uri.parse(asset.uri))
                isLooping = looping
                prepare()
            }
        }.getOrElse { return false }

        val volume = effectiveVolume(asset, masterVolume)
        player.setVolume(volume, volume)
        installPositiveBoost(player, asset.normalizationGainDb)
        player.setOnCompletionListener { completed -> release(completed) }
        player.setOnErrorListener { failed, _, _ ->
            release(failed)
            true
        }
        activePlayers.addLast(ActiveSfx(cueKey, player))
        return runCatching { player.start() }
            .fold(
                onSuccess = { true },
                onFailure = {
                    release(player)
                    false
                },
            )
    }

    @Synchronized
    private fun release(player: MediaPlayer) {
        val iterator = activePlayers.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().player === player) {
                iterator.remove()
                break
            }
        }
        releasePositiveBoost(player)
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun releaseOldest() {
        val active = activePlayers.pollFirst() ?: return
        releasePositiveBoost(active.player)
        runCatching { active.player.stop() }
        runCatching { active.player.release() }
    }

    private fun effectiveVolume(asset: AudioDirectionAsset, masterVolume: Float): Float {
        val attenuationDb = asset.normalizationGainDb.coerceAtMost(0f)
        val normalization = PcmLoudnessEstimator.gainDbToLinear(attenuationDb)
        return (asset.volume * masterVolume.coerceIn(0f, 1f) * normalization)
            .coerceIn(0f, 1f)
    }

    private fun installPositiveBoost(player: MediaPlayer, gainDb: Float) {
        val positiveDb = gainDb.coerceIn(0f, PcmLoudnessEstimator.MAX_GAIN_DB)
        if (positiveDb <= 0.001f) return
        val enhancer = runCatching {
            LoudnessEnhancer(player.audioSessionId).apply {
                setTargetGain((positiveDb * 100f).roundToInt())
                enabled = true
            }
        }.getOrNull() ?: return
        positiveBoosts[player] = enhancer
    }

    private fun releasePositiveBoost(player: MediaPlayer) {
        positiveBoosts.remove(player)?.let { enhancer ->
            runCatching { enhancer.enabled = false }
            runCatching { enhancer.release() }
        }
    }
}
