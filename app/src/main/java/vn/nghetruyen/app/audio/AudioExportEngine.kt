package vn.nghetruyen.app.audio

import vn.nghetruyen.app.core.model.AudioExportFormat


enum class AudioExportScope {
    CURRENT_CHAPTER,
    CACHED_STORY,
    CHAPTER_RANGE,
}


enum class AudioExportPackaging {
    SINGLE_FILE,
    ONE_FILE_PER_CHAPTER,
}


data class AudioExportRequest(
    val scope: AudioExportScope,
    val format: AudioExportFormat,
    val startChapterNumber: Int = 1,
    val endChapterNumber: Int = Int.MAX_VALUE,
    val includeSceneMusic: Boolean = false,
    val packaging: AudioExportPackaging = AudioExportPackaging.SINGLE_FILE,
    val chapterMarkers: Boolean = true,
) {
    fun normalized(): AudioExportRequest {
        val start = startChapterNumber.coerceAtLeast(1)
        val end = endChapterNumber.coerceAtLeast(start)
        return copy(startChapterNumber = start, endChapterNumber = end)
    }
}

data class AudioExportProgress(
    val completedSegments: Int,
    val totalSegments: Int,
    val stage: String,
)
