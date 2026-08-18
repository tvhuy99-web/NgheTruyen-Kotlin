package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary

class ChapterPaginationTest {
    @Test
    fun `catalog cursor survives checkpoint encoding`() {
        val encoded = ChapterPageCursorCodec.encode(
            url = "https://example.test/story/page/2/",
            startIndex = 50,
            nextChapterUrl = "https://example.test/story/chapter-51",
        )

        assertTrue(ChapterPageCursorCodec.isEncoded(encoded))
        assertEquals(
            PersistedChapterPageCursor(
                "https://example.test/story/page/2/",
                50,
                "https://example.test/story/chapter-51",
            ),
            ChapterPageCursorCodec.decode(encoded),
        )
        assertNull(ChapterPageCursorCodec.decode("https://example.test/chapter-51"))
        assertNull(ChapterPageCursorCodec.decode("nghetruyen:toc-page:v1:broken"))
    }

    @Test
    fun `page 2 is appended once and repeated cursor is stopped`() {
        val existing = (1..50).map(::chapter)
        val page = ChapterPage(
            chapters = listOf(chapter(50), chapter(51), chapter(52)),
            nextPageUrl = "https://example.test/story/page/2/",
        )

        val merged = ChapterCatalogMerger.merge(
            existing = existing,
            requestedPageUrl = "https://example.test/story/page/2",
            page = page,
        )

        assertEquals(52, merged.chapters.size)
        assertEquals(listOf(51, 52), merged.chapters.takeLast(2).map { it.index + 1 })
        assertEquals(2, merged.addedCount)
        assertTrue(merged.repeatedCursor)
        assertNull(merged.nextPageUrl)
    }

    @Test
    fun `conflicting duplicate chapter id is blocked before UI state changes`() {
        val existing = listOf(chapter(1), chapter(2))
        val conflicting = chapter(3).copy(id = chapter(2).id)
        val page = ChapterPage(
            chapters = listOf(conflicting, chapter(4)),
            nextPageUrl = "https://example.test/story/page/3",
        )

        val merged = ChapterCatalogMerger.merge(
            existing = existing,
            requestedPageUrl = "https://example.test/story/page/2",
            page = page,
        )

        assertEquals(existing, merged.chapters)
        assertEquals(0, merged.addedCount)
        assertTrue(merged.repeatedCursor)
        assertNull(merged.nextPageUrl)
    }

    @Test
    fun `page with no new chapters is stopped instead of chaining more loads`() {
        val existing = listOf(chapter(1), chapter(2))
        val page = ChapterPage(
            chapters = listOf(chapter(1), chapter(2)),
            nextPageUrl = "https://example.test/story/page/3",
        )

        val merged = ChapterCatalogMerger.merge(
            existing = existing,
            requestedPageUrl = "https://example.test/story/page/2",
            page = page,
        )

        assertEquals(existing, merged.chapters)
        assertEquals(0, merged.addedCount)
        assertTrue(merged.repeatedCursor)
        assertNull(merged.nextPageUrl)
    }

    @Test
    fun `every chapter on a loaded page receives automatic navigation`() {
        val cache = ChapterPageNavigationCache()
        cache.registerPage(
            storyId = STORY_ID,
            chapters = listOf(chapter(51), chapter(52)),
            previousChapterUrl = chapter(50).url,
            nextPageUrl = "https://example.test/story/page/3",
            nextPageStartIndex = 100,
        )

        val first = cache.enrich(content(51))
        val last = cache.enrich(content(52))

        assertEquals(chapter(50).url, first.previousChapterUrl)
        assertEquals(chapter(52).url, first.nextChapterUrl)
        assertNull(first.nextChapterPageUrl)
        assertEquals(chapter(51).url, last.previousChapterUrl)
        assertNull(last.nextChapterUrl)
        assertEquals("https://example.test/story/page/3", last.nextChapterPageUrl)
        assertEquals(100, last.nextChapterPageStartIndex)
    }

    @Test
    fun `playback snapshot keeps page continuation at chapter 50`() {
        val content = content(50).copy(
            nextChapterPageUrl = "https://example.test/story/page/2",
            nextChapterPageStartIndex = 50,
        )

        PlaybackQueueStore.loadContent(sourceId = "remote", content = content)

        val snapshot = PlaybackQueueStore.state.value
        assertEquals(content.nextChapterPageUrl, snapshot.nextChapterPageUrl)
        assertEquals(50, snapshot.nextChapterPageStartIndex)
        assertTrue(
            NextChapterAdvancePolicy.hasRemoteSuccessor(
                snapshot.sourceId,
                snapshot.nextChapterUrl,
                snapshot.nextChapterPageUrl,
            ),
        )
        assertFalse(
            NextChapterAdvancePolicy.hasRemoteSuccessor(
                "offline",
                null,
                snapshot.nextChapterPageUrl,
            ),
        )
    }

    private fun chapter(number: Int) = ChapterSummary(
        id = "chapter-$number",
        storyId = STORY_ID,
        index = number - 1,
        title = "Chương $number",
        url = "https://example.test/story/chapter-$number",
    )

    private fun content(number: Int) = ChapterContent(
        chapter = chapter(number),
        paragraphs = listOf("Nội dung chương $number"),
    )

    private companion object {
        const val STORY_ID = "story-1"
    }
}
