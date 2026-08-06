package vn.nghetruyen.app.downloads

import vn.nghetruyen.app.core.model.ChapterSummary

data class DownloadBatchPlan(
    val batch: List<ChapterSummary>,
    val completedBeforeBatch: Int,
    val totalChapters: Int,
    val remainingAfterBatch: Int,
) {
    val hasMore: Boolean get() = remainingAfterBatch > 0
}

object DownloadBatchPlanner {
    fun create(
        chapters: List<ChapterSummary>,
        downloadedChapterIds: Set<String>,
        maxBatchSize: Int,
    ): DownloadBatchPlan {
        require(maxBatchSize > 0) { "maxBatchSize phải lớn hơn 0." }
        val pending = chapters.filterNot { it.id in downloadedChapterIds }
        val batch = pending.take(maxBatchSize)
        return DownloadBatchPlan(
            batch = batch,
            completedBeforeBatch = chapters.size - pending.size,
            totalChapters = chapters.size,
            remainingAfterBatch = (pending.size - batch.size).coerceAtLeast(0),
        )
    }
}
