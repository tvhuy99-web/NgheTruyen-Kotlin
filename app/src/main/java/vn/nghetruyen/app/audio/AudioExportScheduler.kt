package vn.nghetruyen.app.audio

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

class AudioExportScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(jobId: String): UUID {
        val request = OneTimeWorkRequestBuilder<AudioExportWorker>()
            .setInputData(workDataOf(AudioExportWorker.KEY_JOB_ID to jobId))
            .addTag(tagForJob(jobId))
            .build()
        workManager.enqueueUniqueWork(
            uniqueName(jobId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    fun cancel(jobId: String) {
        workManager.cancelUniqueWork(uniqueName(jobId))
    }

    companion object {
        fun uniqueName(jobId: String) = "audio-export:$jobId"
        fun tagForJob(jobId: String) = "audio-export-tag:$jobId"
    }
}
