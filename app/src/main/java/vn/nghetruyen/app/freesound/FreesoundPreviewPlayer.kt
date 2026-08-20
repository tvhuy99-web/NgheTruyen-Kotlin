package vn.nghetruyen.app.freesound

import android.media.AudioAttributes
import android.media.MediaPlayer

class FreesoundPreviewPlayer {
    private var player: MediaPlayer? = null
    private var activeSoundId: Int? = null

    fun isActive(soundId: Int): Boolean = activeSoundId == soundId

    fun play(
        soundId: Int,
        previewUrl: String,
        onStarted: () -> Unit,
        onStopped: () -> Unit,
        onError: () -> Unit,
    ) {
        stop()
        if (!previewUrl.startsWith("https://", ignoreCase = true)) {
            onError()
            return
        }
        activeSoundId = soundId
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setOnPreparedListener { prepared ->
                if (activeSoundId == soundId) {
                    prepared.start()
                    onStarted()
                }
            }
            setOnCompletionListener {
                if (activeSoundId == soundId) {
                    releaseCurrent()
                    onStopped()
                }
            }
            setOnErrorListener { _, _, _ ->
                if (activeSoundId == soundId) {
                    releaseCurrent()
                    onError()
                }
                true
            }
        }
        player = mediaPlayer
        runCatching {
            mediaPlayer.setDataSource(previewUrl)
            mediaPlayer.prepareAsync()
        }.onFailure {
            releaseCurrent()
            onError()
        }
    }

    fun stop() {
        val hadActive = activeSoundId != null
        releaseCurrent()
        if (hadActive) {
            // UI state is owned by the caller; no callback is required for an explicit stop.
        }
    }

    fun release() {
        releaseCurrent()
    }

    private fun releaseCurrent() {
        activeSoundId = null
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        player = null
    }
}
