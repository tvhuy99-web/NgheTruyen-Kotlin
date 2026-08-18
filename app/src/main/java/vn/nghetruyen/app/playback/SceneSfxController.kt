package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import java.util.ArrayDeque
import kotlin.math.roundToInt
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.PcmLoudnessEstimator

/** Bounded one-shot player. Runtime guardrails remain authoritative even if AI over-selects cues. */
class SceneSfxController(context: Context) {
    private val appContext = context.applicationContext
    private val activePlayers = ArrayDeque<MediaPlayer>()
    private val positiveBoosts = mutableMapOf<MediaPlayer, LoudnessEnhancer>()

    @Synchronized
    fun play(asset: AudioDirectionAsset, masterVolume: Float, maxConcurrent: Int) {
        val limit = maxConcurrent.coerceIn(1, 4)
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
                isLooping = false
                prepare()
            }
        }.getOrElse { return }

        val volume = effectiveVolume(asset, masterVolume)
        player.setVolume(volume, volume)
        installPositiveBoost(player, asset.normalizationGainDb)
        player.setOnCompletionListener { completed -> release(completed) }
        player.setOnErrorListener { failed, _, _ ->
            release(failed)
            true
        }
        activePlayers.addLast(player)
        runCatching { player.start() }.onFailure { release(player) }
    }

    @Synchronized
    fun stopAll() {
        while (activePlayers.isNotEmpty()) releaseOldest()
    }

    @Synchronized
    fun activeCount(): Int = activePlayers.size

    @Synchronized
    private fun release(player: MediaPlayer) {
        activePlayers.remove(player)
        releasePositiveBoost(player)
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun releaseOldest() {
        val player = activePlayers.pollFirst() ?: return
        releasePositiveBoost(player)
        runCatching { player.stop() }
        runCatching { player.release() }
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
