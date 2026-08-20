package vn.nghetruyen.app.audio

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.nghetruyen.app.NgheTruyenApplication

/**
 * Measures a scene track once, then stores the XPK-style fixed normalization gain.
 * If loudness and peak measurements are already available, a new target LUFS only
 * recalculates gain and does not decode the file again unless force remeasurement
 * was explicitly requested by the normalization dialog.
 */
class SceneMusicAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container = (appContext.applicationContext as NgheTruyenApplication).container

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID).orEmpty()
        if (trackId.isBlank()) return Result.failure()
        val track = container.libraryRepository.getSceneMusicTrack(trackId) ?: return Result.failure()
        val kind = AudioAssetClassifier.classify(track)
        val maxTarget = if (kind == AudioAssetKind.MUSIC) {
            PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS
        } else {
            PcmLoudnessEstimator.MAX_TARGET_LUFS
        }
        val requestedTarget = inputData.getFloat(KEY_TARGET_LUFS, Float.NaN)
        val defaultTarget = when (kind) {
            AudioAssetKind.MUSIC -> container.settingsRepository.snapshot().sceneMusicTargetLufs
            AudioAssetKind.AMBIENCE -> AudioDirectionPreferences.shared(applicationContext)
                .snapshot().ambienceNormalizationTargetLufs
            AudioAssetKind.SFX -> AudioDirectionPreferences.shared(applicationContext)
                .snapshot().soundEffectsNormalizationTargetLufs
        }
        val target = (requestedTarget.takeIf(Float::isFinite) ?: defaultTarget)
            .coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, maxTarget)
        val forceRemeasure = inputData.getBoolean(KEY_FORCE_REMEASURE, false)
        val fastFreesound = inputData.getBoolean(KEY_FAST_FREESOUND, false)

        if (forceRemeasure) {
            invalidateStoredNormalization(trackId, target)
        }

        if (!forceRemeasure &&
            track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
            track.normalizationError.isBlank() &&
            track.loudnessLufsEstimate.isFinite() &&
            track.peakDbfs.isFinite()
        ) {
            val normalization = PcmLoudnessEstimator.calculateNormalization(
                track.loudnessLufsEstimate,
                track.peakDbfs,
                target,
            )
            persistNormalization(
                trackId = trackId,
                loudnessLufs = track.loudnessLufsEstimate,
                peakDbfs = track.peakDbfs,
                normalization = normalization,
            )
            return Result.success(
                workDataOf(
                    KEY_LOUDNESS to track.loudnessLufsEstimate,
                    KEY_PEAK to track.peakDbfs,
                    KEY_GAIN_DB to normalization.gainDb,
                    KEY_REUSED_MEASUREMENT to true,
                ),
            )
        }

        val temp = File(applicationContext.cacheDir, "scene-analysis/$trackId.wav")
        return try {
            analysisMutex.withLock {
                temp.parentFile?.mkdirs()
                AndroidAudioTrackDecoder.decodeToWave(
                    context = applicationContext,
                    uri = Uri.parse(track.uri),
                    targetSampleRate = 44_100,
                    targetChannels = 2,
                    destination = temp,
                    maxDecodeDurationUs = if (fastFreesound) fastAnalysisDurationUs(kind) else null,
                )
                val analysis = PcmLoudnessEstimator.analyze(temp)
                val normalization = PcmLoudnessEstimator.calculateNormalization(
                    analysis.loudnessLufs,
                    analysis.peakDbfs,
                    target,
                )
                persistNormalization(
                    trackId = trackId,
                    loudnessLufs = analysis.loudnessLufs,
                    peakDbfs = analysis.peakDbfs,
                    normalization = normalization,
                )
                Result.success(
                    workDataOf(
                        KEY_LOUDNESS to analysis.loudnessLufs,
                        KEY_PEAK to analysis.peakDbfs,
                        KEY_GAIN_DB to normalization.gainDb,
                        KEY_REUSED_MEASUREMENT to false,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message ?: "Không phân tích được âm thanh."
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                container.libraryRepository.markSceneMusicNormalizationError(trackId, message)
                Result.failure(workDataOf(KEY_ERROR to message.take(300)))
            }
        } finally {
            temp.delete()
        }
    }

    /** Marks the previous result unusable before a forced full decode begins. */
    private suspend fun invalidateStoredNormalization(trackId: String, targetLufs: Float) {
        val current = container.libraryRepository.getSceneMusicTrack(trackId) ?: return
        container.database.sceneMusicTrackDao().upsert(
            current.copy(
                volume = 1f,
                normalizationTargetLufs = targetLufs.coerceIn(
                    PcmLoudnessEstimator.MIN_TARGET_LUFS,
                    PcmLoudnessEstimator.MAX_TARGET_LUFS,
                ),
                normalizationGainDb = 0f,
                normalizationPeakLimited = false,
                normalizationVersion = 0,
                normalizationError = "",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Persist exactly the range produced by [PcmLoudnessEstimator].
     *
     * The legacy repository helper clamps gain to +12 dB and target to -18 LUFS.
     * That silently truncates valid normalization results (especially very quiet
     * tracks and ambience/SFX targets above -18 LUFS). Read the latest row before
     * writing so a concurrent title/tag/enable edit is not overwritten.
     * A successful normalization also clears the legacy per-track volume multiplier;
     * from that point loudness is controlled by the measured normalization gain.
     */
    private suspend fun persistNormalization(
        trackId: String,
        loudnessLufs: Float,
        peakDbfs: Float,
        normalization: PcmLoudnessEstimator.Normalization,
    ) {
        val current = container.libraryRepository.getSceneMusicTrack(trackId) ?: return
        container.database.sceneMusicTrackDao().upsert(
            current.copy(
                volume = 1f,
                loudnessLufsEstimate = loudnessLufs.coerceIn(-120f, 12f),
                peakDbfs = peakDbfs.coerceIn(-120f, 12f),
                normalizationTargetLufs = normalization.targetLufs.coerceIn(
                    PcmLoudnessEstimator.MIN_TARGET_LUFS,
                    PcmLoudnessEstimator.MAX_TARGET_LUFS,
                ),
                normalizationGainDb = normalization.gainDb.coerceIn(
                    PcmLoudnessEstimator.MIN_GAIN_DB,
                    PcmLoudnessEstimator.MAX_GAIN_DB,
                ),
                normalizationPeakLimited = normalization.peakLimited,
                normalizationVersion = PcmLoudnessEstimator.VERSION,
                normalizationError = "",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        private const val KEY_TRACK_ID = "track_id"
        private const val KEY_TARGET_LUFS = "target_lufs"
        private const val KEY_FORCE_REMEASURE = "force_remeasure"
        private const val KEY_FAST_FREESOUND = "fast_freesound"
        private const val KEY_LOUDNESS = "loudness_lufs"
        private const val KEY_PEAK = "peak_dbfs"
        private const val KEY_GAIN_DB = "normalization_gain_db"
        private const val KEY_REUSED_MEASUREMENT = "reused_measurement"
        private const val KEY_ERROR = "error"
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RETRY_BACKOFF_SECONDS = 10L
        private val analysisMutex = Mutex()

        internal fun fastAnalysisDurationUs(kind: AudioAssetKind): Long = when (kind) {
            AudioAssetKind.MUSIC -> 45_000_000L
            AudioAssetKind.AMBIENCE -> 30_000_000L
            AudioAssetKind.SFX -> 15_000_000L
        }

        fun enqueue(
            context: Context,
            trackId: String,
            targetLufs: Float? = null,
            forceRemeasure: Boolean = false,
            fastFreesound: Boolean = false,
        ): UUID {
            val data = Data.Builder()
                .putString(KEY_TRACK_ID, trackId)
                .putBoolean(KEY_FORCE_REMEASURE, forceRemeasure)
                .putBoolean(KEY_FAST_FREESOUND, fastFreesound)
            targetLufs?.takeIf(Float::isFinite)?.let { data.putFloat(KEY_TARGET_LUFS, it) }
            val request = OneTimeWorkRequestBuilder<SceneMusicAnalysisWorker>()
                .setInputData(data.build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "scene-music-analysis-$trackId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
            return request.id
        }

        fun cancel(context: Context, workId: UUID) {
            WorkManager.getInstance(context.applicationContext).cancelWorkById(workId)
        }
    }
}
