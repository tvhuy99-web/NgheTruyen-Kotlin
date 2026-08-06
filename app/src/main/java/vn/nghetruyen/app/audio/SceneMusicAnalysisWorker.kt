package vn.nghetruyen.app.audio

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import vn.nghetruyen.app.NgheTruyenApplication
import java.io.File

/** Decodes a local track and stores a bounded loudness estimate for normalization. */
class SceneMusicAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container = (appContext.applicationContext as NgheTruyenApplication).container

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID).orEmpty()
        if (trackId.isBlank()) return Result.failure()
        val track = container.libraryRepository.getSceneMusicTrack(trackId) ?: return Result.failure()
        val temp = File(applicationContext.cacheDir, "scene-analysis/$trackId.wav")
        return runCatching {
            temp.parentFile?.mkdirs()
            AndroidAudioTrackDecoder.decodeToWave(
                context = applicationContext,
                uri = Uri.parse(track.uri),
                targetSampleRate = 44_100,
                targetChannels = 2,
                destination = temp,
            )
            val loudness = PcmLoudnessEstimator.estimateLufs(temp)
            container.libraryRepository.updateSceneMusicLoudness(trackId, loudness)
            Result.success(workDataOf(KEY_LOUDNESS to loudness))
        }.getOrElse { Result.failure(workDataOf(KEY_ERROR to (it.message ?: "Không phân tích được nhạc cảnh.").take(300))) }
            .also { temp.delete() }
    }

    companion object {
        private const val KEY_TRACK_ID = "track_id"
        private const val KEY_LOUDNESS = "loudness_lufs"
        private const val KEY_ERROR = "error"

        fun enqueue(context: Context, trackId: String) {
            val request = OneTimeWorkRequestBuilder<SceneMusicAnalysisWorker>()
                .setInputData(Data.Builder().putString(KEY_TRACK_ID, trackId).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "scene-music-analysis-$trackId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
