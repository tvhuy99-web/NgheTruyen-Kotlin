package vn.nghetruyen.app.freesound

import android.media.AudioAttributes
import android.media.MediaPlayer

class FreesoundPreviewPlayer {
    private var player: MediaPlayer? = null
    private var activeSoundId: Int? = null

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
                if (activeSoundId == soundId && player === prepared) {
                    runCatching { prepared.start() }
                        .onSuccess { onStarted() }
                        .onFailure {
                            releaseIfCurrent(prepared)
                            onError()
                        }
                }
            }
            setOnCompletionListener { completed ->
                if (activeSoundId == soundId && player === completed) {
                    releaseIfCurrent(completed)
                    onStopped()
                }
            }
            setOnErrorListener { failed, _, _ ->
                if (activeSoundId == soundId && player === failed) {
                    releaseIfCurrent(failed)
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
            releaseIfCurrent(mediaPlayer)
            onError()
        }
    }

    fun stop() {
        releaseCurrent()
    }

    fun release() {
        releaseCurrent()
    }

    private fun releaseIfCurrent(candidate: MediaPlayer) {
        if (player === candidate) releaseCurrent()
        else runCatching { candidate.release() }
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
