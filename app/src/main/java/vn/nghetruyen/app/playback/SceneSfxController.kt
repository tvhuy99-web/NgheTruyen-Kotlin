package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import vn.nghetruyen.app.audio.AudioDirectionAsset
import java.util.ArrayDeque
import kotlin.math.pow

/** Bounded one-shot player. Runtime guardrails remain authoritative even if AI over-selects cues. */
class SceneSfxController(context: Context) {
    private val appContext = context.applicationContext
    private val activePlayers = ArrayDeque<MediaPlayer>()

    @Synchronized
    fun play(asset: AudioDirectionAsset, masterVolume: Float, maxConcurrent: Int) {
        val limit = maxConcurrent.coerceIn(1, 4)
        while (activePlayers.size >= limit) releaseOldest()

        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(appContext, Uri.parse(asset.uri))
                isLooping = false
                prepare()
            }
        }.getOrElse { return }

        val volume = effectiveVolume(asset, masterVolume)
        player.setVolume(volume, volume)
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
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun releaseOldest() {
        val player = activePlayers.removeFirstOrNull() ?: return
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun effectiveVolume(asset: AudioDirectionAsset, masterVolume: Float): Float {
        val normalization = 10.0.pow(asset.normalizationGainDb.coerceIn(-18f, 12f) / 20.0).toFloat()
        return (asset.volume * masterVolume.coerceIn(0f, 1f) * normalization)
            .coerceIn(0f, MAX_SFX_VOLUME)
    }

    companion object {
        private const val MAX_SFX_VOLUME = 0.58f
    }
}
