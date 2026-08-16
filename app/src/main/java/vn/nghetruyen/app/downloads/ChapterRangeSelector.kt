package vn.nghetruyen.app.downloads

import vn.nghetruyen.app.core.model.ChapterSummary






object ChapterRangeSelector {
    fun select(
        chapters: List<ChapterSummary>,
        startIndex: Int,
        endIndexInclusive: Int,
    ): List<ChapterSummary> {
        if (chapters.isEmpty()) return emptyList()
        val start = startIndex.coerceAtLeast(0)
        if (start >= chapters.size) return emptyList()
        val end = endIndexInclusive.coerceAtLeast(start).coerceAtMost(chapters.lastIndex)
        return chapters.subList(start, end + 1)
    }
}
