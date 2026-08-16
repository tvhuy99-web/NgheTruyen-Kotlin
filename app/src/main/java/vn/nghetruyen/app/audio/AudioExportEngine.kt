package vn.nghetruyen.app.audio

import vn.nghetruyen.app.core.model.AudioExportFormat

/** Scope selected before Android's file or directory picker is opened. */
enum class AudioExportScope {
    CURRENT_CHAPTER,
    CACHED_STORY,
    CHAPTER_RANGE,
}

/** A single audiobook file, or one independent file for every selected chapter. */
enum class AudioExportPackaging {
    SINGLE_FILE,
    ONE_FILE_PER_CHAPTER,
}

/** Immutable request carried across the system document picker. Chapter numbers are one-based. */
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
