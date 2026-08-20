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
        val candidates = previewCandidates(previewUrl)
        if (candidates.isEmpty()) {
            onError()
            return
        }
        activeSoundId = soundId
        playAttempt(soundId, candidates, 0, onStarted, onStopped, onError)
    }

    private fun playAttempt(
        soundId: Int,
        candidates: List<String>,
        index: Int,
        onStarted: () -> Unit,
        onStopped: () -> Unit,
        onError: () -> Unit,
    ) {
        if (activeSoundId != soundId) return
        if (index !in candidates.indices) {
            activeSoundId = null
            onError()
            return
        }
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
                            fallbackOrFail(prepared, soundId, candidates, index, onStarted, onStopped, onError)
                        }
                }
            }
            setOnCompletionListener { completed ->
                if (activeSoundId == soundId && player === completed) {
                    releaseAttempt(completed)
                    activeSoundId = null
                    onStopped()
                }
            }
            setOnErrorListener { failed, _, _ ->
                if (activeSoundId == soundId && player === failed) {
                    fallbackOrFail(failed, soundId, candidates, index, onStarted, onStopped, onError)
                } else {
                    runCatching { failed.release() }
                }
                true
            }
        }
        player = mediaPlayer
        runCatching {
            mediaPlayer.setDataSource(candidates[index])
            mediaPlayer.prepareAsync()
        }.onFailure {
            fallbackOrFail(mediaPlayer, soundId, candidates, index, onStarted, onStopped, onError)
        }
    }

    private fun fallbackOrFail(
        failed: MediaPlayer,
        soundId: Int,
        candidates: List<String>,
        index: Int,
        onStarted: () -> Unit,
        onStopped: () -> Unit,
        onError: () -> Unit,
    ) {
        releaseAttempt(failed)
        if (activeSoundId != soundId) return
        if (index + 1 < candidates.size) {
            playAttempt(soundId, candidates, index + 1, onStarted, onStopped, onError)
        } else {
            activeSoundId = null
            onError()
        }
    }

    fun stop() {
        activeSoundId = null
        releaseCurrent()
    }

    fun release() {
        stop()
    }

    private fun releaseAttempt(candidate: MediaPlayer) {
        if (player === candidate) player = null
        runCatching { if (candidate.isPlaying) candidate.stop() }
        runCatching { candidate.reset() }
        runCatching { candidate.release() }
    }

    private fun releaseCurrent() {
        player?.let(::releaseAttempt)
        player = null
    }

    companion object {
        internal fun previewCandidates(previewUrl: String): List<String> {
            val primary = previewUrl.trim().takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: return emptyList()
            return listOf(primary)
        }
    }
}
