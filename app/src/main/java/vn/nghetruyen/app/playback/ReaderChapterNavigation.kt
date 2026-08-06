package vn.nghetruyen.app.playback

import vn.nghetruyen.app.core.model.ChapterSummary

/**
 * Resolves chapter movement by catalog position rather than assuming that a
 * source's chapter index is identical to the current in-memory list offset.
 */
object ReaderChapterNavigation {
    fun next(
        current: ChapterSummary,
        chapters: List<ChapterSummary>,
        fallbackUrl: String?,
    ): ChapterSummary? = adjacent(current, chapters, 1)
        ?: fallback(current, fallbackUrl, 1, "Chương tiếp theo")

    fun previous(
        current: ChapterSummary,
        chapters: List<ChapterSummary>,
        fallbackUrl: String?,
    ): ChapterSummary? = adjacent(current, chapters, -1)
        ?: fallback(current, fallbackUrl, -1, "Chương trước")

    private fun adjacent(
        current: ChapterSummary,
        chapters: List<ChapterSummary>,
        delta: Int,
    ): ChapterSummary? {
        val position = chapters.indexOfFirst { candidate ->
            candidate.id == current.id ||
                (current.url.isNotBlank() && candidate.url == current.url) ||
                candidate.index == current.index
        }
        if (position < 0) return null
        return chapters.getOrNull(position + delta)
    }

    private fun fallback(
        current: ChapterSummary,
        url: String?,
        delta: Int,
        title: String,
    ): ChapterSummary? {
        val targetUrl = url?.trim()?.takeIf { it.isNotBlank() && it != current.url } ?: return null
        return ChapterSummary(
            id = targetUrl,
            storyId = current.storyId,
            index = (current.index + delta).coerceAtLeast(0),
            title = title,
            url = targetUrl,
        )
    }
}
