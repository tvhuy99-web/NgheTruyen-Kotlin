package vn.nghetruyen.app.following

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

class FollowingUpdateScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<FollowingUpdateWorker>(Duration.ofHours(12))
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun checkNow() {
        val request = OneTimeWorkRequestBuilder<FollowingUpdateWorker>()
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object {
        private const val PERIODIC_WORK_NAME = "following-updates"
        private const val MANUAL_WORK_NAME = "following-updates-manual"
    }
}
