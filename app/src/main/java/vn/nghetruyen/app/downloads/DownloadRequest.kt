package vn.nghetruyen.app.downloads

import vn.nghetruyen.app.core.model.DownloadSelectionMode
import vn.nghetruyen.app.data.local.DownloadJobEntity

data class DownloadRequest(
    val jobId: String,
    val sourceId: String,
    val storyId: String,
    val selectionMode: DownloadSelectionMode,
    val startChapterIndex: Int = 0,
    val endChapterIndex: Int = Int.MAX_VALUE,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
) {
    init {
        require(jobId.isNotBlank()) { "Thiếu mã tác vụ tải." }
        require(sourceId.isNotBlank()) { "Thiếu mã nguồn tải." }
        require(storyId.isNotBlank()) { "Thiếu mã truyện tải." }
        require(startChapterIndex >= 0) { "Chỉ số chương bắt đầu không hợp lệ." }
        require(endChapterIndex >= startChapterIndex) { "Chỉ số chương kết thúc không hợp lệ." }
    }

    companion object {
        fun create(
            sourceId: String,
            storyId: String,
            selectionMode: DownloadSelectionMode,
            startChapterIndex: Int = 0,
            endChapterIndex: Int = Int.MAX_VALUE,
            wifiOnly: Boolean = false,
            chargingOnly: Boolean = false,
        ): DownloadRequest {
            val start = startChapterIndex.coerceAtLeast(0)
            val end = endChapterIndex.coerceAtLeast(start)
            val id = "story-download:$sourceId:$storyId:${selectionMode.name}:$start-$end"
            return DownloadRequest(id, sourceId, storyId, selectionMode, start, end, wifiOnly, chargingOnly)
        }

        fun from(job: DownloadJobEntity): DownloadRequest = DownloadRequest(
            jobId = job.id,
            sourceId = job.sourceId,
            storyId = job.storyId,
            selectionMode = runCatching { DownloadSelectionMode.valueOf(job.selectionMode) }
                .getOrDefault(DownloadSelectionMode.ALL),
            startChapterIndex = job.startChapterIndex,
            endChapterIndex = job.endChapterIndex,
            wifiOnly = job.wifiOnly,
            chargingOnly = job.chargingOnly,
        )
    }
}
