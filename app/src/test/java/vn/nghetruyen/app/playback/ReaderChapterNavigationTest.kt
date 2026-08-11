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
    fun navigationMatchesXpkChapterNumbersEvenWhenCatalogPagesAreReversed() {
        val reversed = listOf(
            ChapterSummary("c53", "story", 50, "Chương 53", "https://example/chuong-53"),
            ChapterSummary("c52", "story", 51, "Chương 52", "https://example/chuong-52"),
            ChapterSummary("c51", "story", 52, "Chương 51", "https://example/chuong-51"),
        )
        val current = ChapterSummary("c50", "story", 49, "Chương 50", "https://example/chuong-50")

        assertEquals("c51", ReaderChapterNavigation.next(current, reversed, null)?.id)
    }

    @Test
    fun anOlderCatalogPageIsNotMistakenForTheSuccessorOfAStoredLaterChapter() {
        val current = ChapterSummary("c100", "story", 99, "Chương 100", "https://example/chuong-100")
        val olderPage = (51..99).map { number ->
            ChapterSummary(
                "c$number",
                "story",
                number - 1,
                "Chương $number",
                "https://example/chuong-$number",
            )
        }

        assertNull(ReaderChapterNavigation.next(current, olderPage, null))
    }

    @Test
    fun fallbackUrlIsUsedOnlyWhenCatalogHasNoAdjacentChapter() {
        val next = ReaderChapterNavigation.next(chapters.last(), chapters, "https://example/32")
        assertEquals("https://example/32", next?.url)
        assertEquals(31, next?.index)
        assertNull(ReaderChapterNavigation.next(chapters.last(), chapters, chapters.last().url))
    }
}
