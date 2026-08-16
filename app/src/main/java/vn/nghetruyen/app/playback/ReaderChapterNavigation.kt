package vn.nghetruyen.app.playback

import vn.nghetruyen.app.core.model.ChapterSummary





object ReaderChapterNavigation {
    fun sequenceNumber(chapter: ChapterSummary): Long? = chapterNumber(chapter)

    fun readingOrder(chapters: List<ChapterSummary>): List<ChapterSummary> =
        if (chapters.count { chapterNumber(it) != null } >= 2) {
            chapters.sortedWith(compareBy<ChapterSummary> { chapterNumber(it) ?: Long.MAX_VALUE })
        } else {
            chapters
        }

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
        val currentNumber = chapterNumber(current)
        if (currentNumber != null) {
            val numberedCandidates = chapters.mapNotNull { candidate ->
                if (sameChapter(candidate, current)) null
                else chapterNumber(candidate)?.let { it to candidate }
            }
            return if (delta > 0) {
                numberedCandidates
                    .filter { (number) -> number > currentNumber }
                    .minByOrNull { (number) -> number }
                    ?.second
            } else {
                numberedCandidates
                    .filter { (number) -> number < currentNumber }
                    .maxByOrNull { (number) -> number }
                    ?.second
            }
        }
        val position = chapters.indexOfFirst { candidate ->
            sameChapter(candidate, current) ||
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

    private fun sameChapter(left: ChapterSummary, right: ChapterSummary): Boolean =
        left.id == right.id || (
            left.url.isNotBlank() && right.url.isNotBlank() &&
                left.url.trim().trimEnd('/') == right.url.trim().trimEnd('/')
            )

    private fun chapterNumber(chapter: ChapterSummary): Long? {
        val fromTitle = CHAPTER_TITLE_NUMBER.find(chapter.title)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (fromTitle != null) return fromTitle
        return CHAPTER_URL_NUMBER.find(chapter.url.lowercase())
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private val CHAPTER_TITLE_NUMBER = Regex("(?i)(?:chương|chuong)\\s*[:#-]?\\s*(\\d+)")
    private val CHAPTER_URL_NUMBER = Regex("(?:chuong|chapter)[-_/%\\s]?(\\d+)")
}
