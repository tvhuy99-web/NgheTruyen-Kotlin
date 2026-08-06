package vn.nghetruyen.app.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.DownloadSelectionMode
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.data.repository.LibraryRepository
import java.time.Duration

class ChapterDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private var notificationTitle: String = "Tải truyện ngoại tuyến"

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure(error("Thiếu sourceId"))
        val storyId = inputData.getString(KEY_STORY_ID) ?: return Result.failure(error("Thiếu storyId"))
        val startIndex = inputData.getInt(KEY_START_INDEX, 0).coerceAtLeast(0)
        val endIndex = inputData.getInt(KEY_END_INDEX, Int.MAX_VALUE).coerceAtLeast(startIndex)
        val selectionMode = runCatching {
            DownloadSelectionMode.valueOf(inputData.getString(KEY_SELECTION_MODE) ?: DownloadSelectionMode.ALL.name)
        }.getOrDefault(DownloadSelectionMode.ALL)
        val wifiOnly = inputData.getBoolean(KEY_WIFI_ONLY, false)
        val chargingOnly = inputData.getBoolean(KEY_CHARGING_ONLY, false)
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: DownloadRequest.create(sourceId, storyId, selectionMode, startIndex, endIndex, wifiOnly, chargingOnly).jobId
        val application = applicationContext as? NgheTruyenApplication
            ?: return Result.failure(error("Application chưa khởi tạo AppContainer."))
        val repository = application.container.libraryRepository
        val source = application.container.sourceRegistry.get(sourceId)
            ?: return failJob(repository, jobId, storyId, sourceId, "Nguồn truyện không tồn tại.")
        val storedStory = repository.getStory(storyId)
            ?: return failJob(repository, jobId, storyId, sourceId, "Chưa có thông tin truyện để tải.")
        notificationTitle = storedStory.title.ifBlank { "Tải truyện ngoại tuyến" }
        createNotificationChannel()
        setForeground(createForegroundInfo(0, "Đang chuẩn bị"))

        var completedForStatus = 0
        var totalForStatus = 0
        return try {
            repository.updateDownloadJob(
                id = jobId,
                storyId = storyId,
                sourceId = sourceId,
                state = DownloadState.RUNNING,
                completedChapters = 0,
                totalChapters = 0,
                selectionMode = selectionMode,
                startChapterIndex = startIndex,
                endChapterIndex = endIndex,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
                retryCount = runAttemptCount,
            )
            publishProgress(0, "Đang tải mục lục")

            val detail = when (val result = source.story(storedStory.remoteUrl)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return handleSourceFailure(
                    repository = repository,
                    jobId = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    failure = result,
                    completed = 0,
                    total = 0,
                )
            }
            val chapters = when (
                val plan = StoryDownloadPlanner().collectChapters(source, detail) {
                    if (isStopped) throw CancellationException("Worker đã dừng.")
                }
            ) {
                is AppResult.Success -> plan.value
                is AppResult.Failure -> return handleSourceFailure(
                    repository = repository,
                    jobId = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    failure = plan,
                    completed = 0,
                    total = 0,
                )
            }
            if (chapters.isEmpty()) {
                return failJob(repository, jobId, storyId, sourceId, "Truyện không có chương để tải.")
            }
            val selectedChapters = ChapterRangeSelector.select(chapters, startIndex, endIndex)
            if (selectedChapters.isEmpty()) {
                return failJob(repository, jobId, storyId, sourceId, "Khoảng chương đã chọn không tồn tại.")
            }

            totalForStatus = selectedChapters.size
            val downloadedIds = repository.listDownloadedChapterIds(storyId)
            val batchPlan = DownloadBatchPlanner.create(
                chapters = selectedChapters,
                downloadedChapterIds = downloadedIds,
                maxBatchSize = MAX_CHAPTERS_PER_RUN,
            )
            if (batchPlan.batch.isNotEmpty()) {
                val storageEstimate = DownloadStorageGuard.estimate(
                    availableBytes = DownloadStorageGuard.availableBytes(applicationContext),
                    chapterCount = batchPlan.batch.size,
                )
                if (!storageEstimate.hasEnoughSpace) {
                    return failJob(
                        repository,
                        jobId,
                        storyId,
                        sourceId,
                        "Không đủ dung lượng an toàn để tiếp tục tải. Cần thêm khoảng ${formatBytes(storageEstimate.shortfallBytes)}.",
                    )
                }
            }
            val completedBeforeBatch = batchPlan.completedBeforeBatch
            completedForStatus = completedBeforeBatch

            repository.updateDownloadJob(
                id = jobId,
                storyId = storyId,
                sourceId = sourceId,
                state = DownloadState.RUNNING,
                completedChapters = completedBeforeBatch,
                totalChapters = selectedChapters.size,
                selectionMode = selectionMode,
                startChapterIndex = startIndex,
                endChapterIndex = endIndex,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
                retryCount = runAttemptCount,
            )

            if (batchPlan.batch.isEmpty()) {
                repository.clearDownloadFailures(jobId)
                repository.markStoryDownloaded(detail.story)
                repository.updateDownloadJob(
                    id = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    state = DownloadState.COMPLETED,
                    completedChapters = selectedChapters.size,
                    totalChapters = selectedChapters.size,
                    currentChapterIndex = -1,
                    currentChapterTitle = "",
                )
                publishProgress(100, "Hoàn tất")
                return Result.success(
                    Data.Builder()
                        .putString(KEY_STORY_ID, storyId)
                        .putInt(KEY_PROGRESS, 100)
                        .build(),
                )
            }

            var completed = completedBeforeBatch
            batchPlan.batch.forEach { chapter ->
                ensureWorkerActive(repository, jobId, storyId, sourceId, completed, selectedChapters.size)
                repository.updateDownloadJob(
                    id = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    state = DownloadState.RUNNING,
                    completedChapters = completed,
                    totalChapters = selectedChapters.size,
                    currentChapterIndex = chapter.index,
                    currentChapterTitle = chapter.title,
                    retryCount = runAttemptCount,
                )
                when (val content = source.chapter(chapter.url.ifBlank { chapter.id })) {
                    is AppResult.Success -> {
                        repository.saveDownloadedChapter(content.value.copy(chapter = chapter))
                        repository.clearDownloadFailure(jobId, chapter.index)
                    }
                    is AppResult.Failure -> {
                        repository.recordDownloadFailure(
                            jobId = jobId,
                            storyId = storyId,
                            sourceId = sourceId,
                            chapterIndex = chapter.index,
                            chapterTitle = chapter.title,
                            errorMessage = content.message,
                            retryCount = runAttemptCount,
                        )
                        return handleSourceFailure(
                            repository = repository,
                            jobId = jobId,
                            storyId = storyId,
                            sourceId = sourceId,
                            failure = content,
                            completed = completed,
                            total = selectedChapters.size,
                        )
                    }
                }
                completed += 1
                completedForStatus = completed
                val percent = completed * 100 / selectedChapters.size
                publishProgress(percent, "Đã tải $completed/${selectedChapters.size} chương")
                repository.updateDownloadJob(
                    id = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    state = DownloadState.RUNNING,
                    completedChapters = completed,
                    totalChapters = selectedChapters.size,
                    currentChapterIndex = chapter.index,
                    currentChapterTitle = chapter.title,
                    retryCount = runAttemptCount,
                )
            }

            if (batchPlan.hasMore) {
                val percent = completed * 100 / selectedChapters.size
                repository.updateDownloadJob(
                    id = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    state = DownloadState.QUEUED,
                    completedChapters = completed,
                    totalChapters = selectedChapters.size,
                )
                publishProgress(percent, "Đã tải $completed/${selectedChapters.size}, chuyển sang lượt kế tiếp")
                enqueueContinuation(sourceId, storyId, jobId, selectionMode, startIndex, endIndex, wifiOnly, chargingOnly)
                return Result.success(
                    Data.Builder()
                        .putString(KEY_STORY_ID, storyId)
                        .putInt(KEY_PROGRESS, percent)
                        .putString(KEY_STAGE, "Tiếp tục tải ở lượt kế tiếp")
                        .build(),
                )
            }

            repository.clearDownloadFailures(jobId)
            repository.markStoryDownloaded(detail.story)
            repository.updateDownloadJob(
                id = jobId,
                storyId = storyId,
                sourceId = sourceId,
                state = DownloadState.COMPLETED,
                completedChapters = completed,
                totalChapters = selectedChapters.size,
                currentChapterIndex = -1,
                currentChapterTitle = "",
            )
            publishProgress(100, "Hoàn tất")
            Result.success(
                Data.Builder()
                    .putString(KEY_STORY_ID, storyId)
                    .putInt(KEY_PROGRESS, 100)
                    .build(),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val existing = repository.getDownloadJob(jobId)
                val paused = existing?.state == DownloadState.PAUSED.name
                repository.updateDownloadJob(
                    id = jobId,
                    storyId = storyId,
                    sourceId = sourceId,
                    state = if (paused) DownloadState.PAUSED else DownloadState.CANCELLED,
                    completedChapters = completedForStatus,
                    totalChapters = totalForStatus,
                    errorMessage = if (paused) "Đã tạm dừng." else "Đã hủy tải truyện.",
                    retryCount = runAttemptCount,
                )
            }
            throw cancelled
        } catch (error: Exception) {
            repository.updateDownloadJob(
                id = jobId,
                storyId = storyId,
                sourceId = sourceId,
                state = DownloadState.FAILED,
                completedChapters = completedForStatus,
                totalChapters = totalForStatus,
                errorMessage = error.message ?: "Lỗi tải truyện không xác định.",
                retryCount = runAttemptCount,
            )
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry()
            else Result.failure(error(error.message ?: "Lỗi tải truyện."))
        }
    }

    private fun enqueueContinuation(
        sourceId: String,
        storyId: String,
        uniqueWorkName: String,
        selectionMode: DownloadSelectionMode,
        startIndex: Int,
        endIndex: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        when {
                            sourceId == "offline" -> NetworkType.NOT_REQUIRED
                            wifiOnly -> NetworkType.UNMETERED
                            else -> NetworkType.CONNECTED
                        },
                    )
                    .setRequiresCharging(chargingOnly)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .setInputData(
                workDataOf(
                    KEY_JOB_ID to uniqueWorkName,
                    KEY_SOURCE_ID to sourceId,
                    KEY_STORY_ID to storyId,
                    KEY_SELECTION_MODE to selectionMode.name,
                    KEY_START_INDEX to startIndex,
                    KEY_END_INDEX to endIndex,
                    KEY_WIFI_ONLY to wifiOnly,
                    KEY_CHARGING_ONLY to chargingOnly,
                ),
            )
            .addTag("story-download")
            .addTag("story-download:$storyId")
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.APPEND,
            request,
        )
    }

    private suspend fun ensureWorkerActive(
        repository: LibraryRepository,
        jobId: String,
        storyId: String,
        sourceId: String,
        completed: Int,
        total: Int,
    ) {
        if (!isStopped) return
        // State is resolved in the NonCancellable catch block so a user pause
        // cannot be overwritten by an eager CANCELLED write here.
        throw CancellationException("Worker đã dừng: $jobId/$storyId/$sourceId/$completed/$total")
    }

    private suspend fun handleSourceFailure(
        repository: LibraryRepository,
        jobId: String,
        storyId: String,
        sourceId: String,
        failure: AppResult.Failure,
        completed: Int,
        total: Int,
    ): Result {
        repository.updateDownloadJob(
            id = jobId,
            storyId = storyId,
            sourceId = sourceId,
            state = DownloadState.FAILED,
            completedChapters = completed,
            totalChapters = total,
            errorMessage = failure.message,
            retryCount = runAttemptCount,
        )
        return if (failure.isRetryable() && runAttemptCount < MAX_ATTEMPTS - 1) {
            Result.retry()
        } else {
            Result.failure(error(failure.message))
        }
    }

    private suspend fun failJob(
        repository: LibraryRepository,
        jobId: String,
        storyId: String,
        sourceId: String,
        message: String,
    ): Result {
        repository.updateDownloadJob(
            id = jobId,
            storyId = storyId,
            sourceId = sourceId,
            state = DownloadState.FAILED,
            completedChapters = 0,
            totalChapters = 0,
            errorMessage = message,
        )
        return Result.failure(error(message))
    }

    private suspend fun publishProgress(percent: Int, stage: String) {
        val safePercent = percent.coerceIn(0, 100)
        setProgress(
            Data.Builder()
                .putInt(KEY_PROGRESS, safePercent)
                .putString(KEY_STAGE, stage)
                .build(),
        )
        setForeground(createForegroundInfo(safePercent, stage))
    }

    private fun createForegroundInfo(percent: Int, stage: String): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notification.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(vn.nghetruyen.app.R.drawable.ic_stat_reader)
            .setContentTitle(notificationTitle)
            .setContentText(stage)
            .setOnlyAlertOnce(true)
            .setOngoing(percent < 100)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(
                Notification.Action.Builder(
                    vn.nghetruyen.app.R.drawable.ic_stat_reader,
                    "Hủy",
                    cancelIntent,
                ).build(),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DOWNLOAD_NOTIFICATION_ID_BASE + (id.hashCode() and 0x0fffffff),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                DOWNLOAD_NOTIFICATION_ID_BASE + (id.hashCode() and 0x0fffffff),
                notification,
            )
        }
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Tải truyện ngoại tuyến",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Tiến độ lưu truyện để đọc khi không có mạng"
                setSound(null, null)
            },
        )
    }

    private fun AppResult.Failure.isRetryable(): Boolean =
        code == "SOURCE_HTTP_429" ||
            code.startsWith("SOURCE_HTTP_5") ||
            code.endsWith("_FAILED")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun error(message: String): Data = Data.Builder().putString(KEY_ERROR, message).build()

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_STORY_ID = "story_id"
        const val KEY_SELECTION_MODE = "selection_mode"
        const val KEY_START_INDEX = "start_index"
        const val KEY_END_INDEX = "end_index"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_CHARGING_ONLY = "charging_only"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"

        private const val MAX_ATTEMPTS = 3
        private const val MAX_CHAPTERS_PER_RUN = 40
        private const val DOWNLOAD_CHANNEL_ID = "story_downloads"
        private const val DOWNLOAD_NOTIFICATION_ID_BASE = 4_000
    }
}
