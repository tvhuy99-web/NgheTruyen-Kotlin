package vn.nghetruyen.app.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import vn.nghetruyen.app.core.model.DownloadSelectionMode

class DownloadScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueueStory(
        sourceId: String,
        storyId: String,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ): DownloadRequest = enqueue(
        DownloadRequest.create(
            sourceId = sourceId,
            storyId = storyId,
            selectionMode = DownloadSelectionMode.ALL,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        ),
    )

    fun enqueueUnread(
        sourceId: String,
        storyId: String,
        firstUnreadIndex: Int,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ): DownloadRequest = enqueue(
        DownloadRequest.create(
            sourceId = sourceId,
            storyId = storyId,
            selectionMode = DownloadSelectionMode.UNREAD,
            startChapterIndex = firstUnreadIndex.coerceAtLeast(0),
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        ),
    )

    fun enqueueChapter(
        sourceId: String,
        storyId: String,
        chapterIndex: Int,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ): DownloadRequest = enqueue(
        DownloadRequest.create(
            sourceId = sourceId,
            storyId = storyId,
            selectionMode = DownloadSelectionMode.SINGLE,
            startChapterIndex = chapterIndex,
            endChapterIndex = chapterIndex,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        ),
    )

    fun enqueueRange(
        sourceId: String,
        storyId: String,
        startIndex: Int,
        endIndexInclusive: Int,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ): DownloadRequest = enqueue(
        DownloadRequest.create(
            sourceId = sourceId,
            storyId = storyId,
            selectionMode = DownloadSelectionMode.RANGE,
            startChapterIndex = startIndex,
            endChapterIndex = endIndexInclusive,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        ),
    )

    fun resume(request: DownloadRequest): DownloadRequest = enqueue(request, ExistingWorkPolicy.REPLACE)

    fun cancel(request: DownloadRequest) {
        workManager.cancelUniqueWork(request.jobId)
    }

    fun cancelStory(storyId: String) {
        workManager.cancelAllWorkByTag("story-download:$storyId")
    }

    private fun enqueue(
        request: DownloadRequest,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ): DownloadRequest {
        val networkType = when {
            request.sourceId == "offline" -> NetworkType.NOT_REQUIRED
            request.wifiOnly -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        val work = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .setRequiresCharging(request.chargingOnly)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .setInputData(
                workDataOf(
                    ChapterDownloadWorker.KEY_JOB_ID to request.jobId,
                    ChapterDownloadWorker.KEY_SOURCE_ID to request.sourceId,
                    ChapterDownloadWorker.KEY_STORY_ID to request.storyId,
                    ChapterDownloadWorker.KEY_SELECTION_MODE to request.selectionMode.name,
                    ChapterDownloadWorker.KEY_START_INDEX to request.startChapterIndex,
                    ChapterDownloadWorker.KEY_END_INDEX to request.endChapterIndex,
                    ChapterDownloadWorker.KEY_WIFI_ONLY to request.wifiOnly,
                    ChapterDownloadWorker.KEY_CHARGING_ONLY to request.chargingOnly,
                ),
            )
            .addTag("story-download")
            .addTag("story-download:${request.storyId}")
            .addTag("download-job:${request.jobId}")
            .build()
        workManager.enqueueUniqueWork(request.jobId, policy, work)
        return request
    }
}
