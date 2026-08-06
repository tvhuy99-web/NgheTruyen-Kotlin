package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterSummary

class ReaderChapterNavigationTest {
    private val chapters = listOf(
        ChapterSummary("a", "story", 10, "Chương 11", "https://example/11"),
        ChapterSummary("b", "story", 20, "Chương 21", "https://example/21"),
        ChapterSummary("c", "story", 30, "Chương 31", "https://example/31"),
    )

    @Test
    fun navigationUsesCatalogPositionWhenSourceIndexesHaveGaps() {
        assertEquals("c", ReaderChapterNavigation.next(chapters[1], chapters, null)?.id)
        assertEquals("a", ReaderChapterNavigation.previous(chapters[1], chapters, null)?.id)
    }

    @Test
    fun navigationCanResolveCurrentChapterByUrl() {
        val reconstructed = chapters[1].copy(id = "different-id", index = 999)
        assertEquals("c", ReaderChapterNavigation.next(reconstructed, chapters, null)?.id)
    }

    @Test
    fun fallbackUrlIsUsedOnlyWhenCatalogHasNoAdjacentChapter() {
        val next = ReaderChapterNavigation.next(chapters.last(), chapters, "https://example/32")
        assertEquals("https://example/32", next?.url)
        assertEquals(31, next?.index)
        assertNull(ReaderChapterNavigation.next(chapters.last(), chapters, chapters.last().url))
    }
}
