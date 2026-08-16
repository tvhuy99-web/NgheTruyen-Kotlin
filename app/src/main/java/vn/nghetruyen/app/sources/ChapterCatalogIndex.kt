package vn.nghetruyen.app.sources

import vn.nghetruyen.app.core.model.ChapterSummary





class ChapterCatalogIndex(chapters: List<ChapterSummary>) {
    private data class Entry(val chapter: ChapterSummary, val normalizedTitle: String)

    private val ordered = chapters.toList()
    private val entries = ordered.map { Entry(it, StorySearch.normalize(it.title)) }
    private val byHumanNumber = ordered.associateBy { it.index + 1 }

    val size: Int get() = ordered.size

    fun search(rawQuery: String): List<ChapterSummary> {
        val query = StorySearch.normalize(rawQuery).trim()
        if (query.isBlank()) return ordered
        query.toIntOrNull()?.let { number ->
            byHumanNumber[number]?.let { return listOf(it) }
        }
        return entries.asSequence()
            .filter { entry ->
                entry.normalizedTitle.contains(query) ||
                    (entry.chapter.index + 1).toString().contains(query)
            }
            .map(Entry::chapter)
            .toList()
    }
}
