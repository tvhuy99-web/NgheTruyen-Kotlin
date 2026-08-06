package vn.nghetruyen.app.following

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import vn.nghetruyen.app.MainActivity
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.R
import vn.nghetruyen.app.core.common.AppResult

class FollowingUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NgheTruyenApplication).container
        val items = container.libraryRepository.listFollowingForUpdate(MAX_STORIES_PER_RUN)
        var failures = 0
        items.forEachIndexed { index, item ->
            setProgress(androidx.work.workDataOf("checked" to index, "total" to items.size))
            val source = container.sourceRegistry.get(item.sourceId)
            if (source == null) {
                failures += 1
                container.libraryRepository.updateFollowCheck(item, "")
                return@forEachIndexed
            }
            when (val result = source.latestChapter(item.remoteUrl)) {
                is AppResult.Success -> {
                    val latestSummary = result.value
                    val latest = latestSummary?.title.orEmpty()
                    val additional = FollowingUpdateDetector.newChapterCount(
                        previousTitle = item.latestKnownChapter,
                        previousIndex = item.latestKnownChapterIndex,
                        latestTitle = latest,
                        latestIndex = latestSummary?.index ?: -1,
                    )
                    if (additional > 0) {
                        notifyNewChapter(item.storyId, item.title, latest, additional)
                    }
                    container.libraryRepository.updateFollowCheck(
                        item = item,
                        latestChapter = latest,
                        latestChapterIndex = latestSummary?.index ?: -1,
                        additionalNewChapters = additional,
                    )
                }
                is AppResult.Failure -> {
                    failures += 1
                    container.libraryRepository.updateFollowCheck(item, "")
                }
            }
        }
        return if (failures > 0 && runAttemptCount < 2) Result.retry() else Result.success()
    }

    private fun notifyNewChapter(storyId: String, title: String, chapter: String, count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Truyện theo dõi", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            applicationContext,
            storyId.hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply {
                putExtra(EXTRA_STORY_ID, storyId)
                putExtra(EXTRA_STORY_TITLE, title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_BASE + (storyId.hashCode() and 0x0fffffff),
            Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_reader)
                .setContentTitle(if (count > 1) "$title có $count chương mới" else "$title có chương mới")
                .setContentText(chapter.ifBlank { "Mở ứng dụng để xem chương mới." })
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val MAX_STORIES_PER_RUN = 30
        private const val CHANNEL_ID = "following_updates"
        private const val NOTIFICATION_BASE = 8_000
        const val EXTRA_STORY_ID = "following_story_id"
        const val EXTRA_STORY_TITLE = "following_story_title"
    }
}
