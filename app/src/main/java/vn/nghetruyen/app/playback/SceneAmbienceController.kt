package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import vn.nghetruyen.app.audio.AudioDirectionAsset
import kotlin.math.pow

/** Voice-first loop player for AI-selected environmental ambience. */
class SceneAmbienceController(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var activeAssetId: String? = null
    private var paused = false

    @Synchronized
    fun play(asset: AudioDirectionAsset, masterVolume: Float) {
        val targetVolume = effectiveVolume(asset, masterVolume)
        if (activeAssetId == asset.id && player != null) {
            player?.setVolume(targetVolume, targetVolume)
            if (paused) resume()
            return
        }
        stop()
        val created = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(appContext, Uri.parse(asset.uri))
                isLooping = true
                prepare()
            }
        }.getOrElse { return }
        created.setVolume(targetVolume, targetVolume)
        created.setOnErrorListener { mediaPlayer, _, _ ->
            runCatching { mediaPlayer.release() }
            synchronized(this) {
                if (player === mediaPlayer) {
                    player = null
                    activeAssetId = null
                    paused = false
                }
            }
            true
        }
        player = created
        activeAssetId = asset.id
        paused = false
        runCatching { created.start() }.onFailure { stop() }
    }

    @Synchronized
    fun pause() {
        player?.takeIf { runCatching { it.isPlaying }.getOrDefault(false) }?.let { runCatching { it.pause() } }
        paused = player != null
    }

    @Synchronized
    fun resume() {
        if (!paused) return
        player?.let { runCatching { it.start() } }
        paused = false
    }

    @Synchronized
    fun stop() {
        val old = player
        player = null
        activeAssetId = null
        paused = false
        old?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    @Synchronized
    fun activeId(): String? = activeAssetId

    private fun effectiveVolume(asset: AudioDirectionAsset, masterVolume: Float): Float {
        val normalization = 10.0.pow(asset.normalizationGainDb.coerceIn(-18f, 12f) / 20.0).toFloat()
        // Ambience lives continuously below narration; cap it aggressively to keep TTS authoritative.
        return (asset.volume * masterVolume.coerceIn(0f, 1f) * normalization * VOICE_PRIORITY_DUCK)
            .coerceIn(0f, MAX_AMBIENCE_VOLUME)
    }

    companion object {
        private const val VOICE_PRIORITY_DUCK = 0.58f
        private const val MAX_AMBIENCE_VOLUME = 0.34f
    }
}
