package vn.nghetruyen.app.downloads

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sources.SourceDescriptor
import vn.nghetruyen.app.sources.StorySource

class StoryDownloadPlannerTest {
    @Test
    fun collectsPagedChaptersDeduplicatesAndReindexes() = runBlocking {
        val story = story()
        val source = PagedFakeSource(
            pages = mapOf(
                "page-2" to ChapterPage(
                    chapters = listOf(
                        chapter(story.id, 99, "Chương 2", "chapter-2"),
                        chapter(story.id, 99, "Chương 3", "chapter-3"),
                    ),
                    nextPageUrl = "page-3",
                ),
                "page-3" to ChapterPage(
                    chapters = listOf(chapter(story.id, 99, "Chương 4", "chapter-4")),
                ),
            ),
        )
        val detail = StoryDetail(
            story = story,
            chapters = listOf(
                chapter(story.id, 0, "Chương 1", "chapter-1"),
                chapter(story.id, 1, "Chương 2", "chapter-2"),
            ),
            nextChapterPageUrl = "page-2",
        )

        val result = StoryDownloadPlanner().collectChapters(source, detail)

        assertTrue(result is AppResult.Success)
        val chapters = (result as AppResult.Success).value
        assertEquals(listOf("chapter-1", "chapter-2", "chapter-3", "chapter-4"), chapters.map { it.url })
        assertEquals(listOf(0, 1, 2, 3), chapters.map { it.index })
    }

    @Test
    fun detectsPaginationCycle() = runBlocking {
        val story = story()
        val source = PagedFakeSource(
            pages = mapOf(
                "page-2" to ChapterPage(emptyList(), nextPageUrl = "page-2"),
            ),
        )
        val detail = StoryDetail(story = story, nextChapterPageUrl = "page-2")

        val result = StoryDownloadPlanner().collectChapters(source, detail)

        assertTrue(result is AppResult.Failure)
        assertEquals("DOWNLOAD_CHAPTER_PAGE_CYCLE", (result as AppResult.Failure).code)
    }

    @Test
    fun forwardsSourcePagingFailure() = runBlocking {
        val story = story()
        val source = PagedFakeSource(
            pages = emptyMap(),
            failure = AppResult.Failure("SOURCE_LAYOUT_CHANGED", "Selector hỏng"),
        )

        val result = StoryDownloadPlanner().collectChapters(
            source,
            StoryDetail(story = story, nextChapterPageUrl = "missing"),
        )

        assertTrue(result is AppResult.Failure)
        assertEquals("SOURCE_LAYOUT_CHANGED", (result as AppResult.Failure).code)
    }

    private fun story() = StorySummary(
        id = "story-1",
        sourceId = "fake",
        title = "Truyện thử",
        url = "https://example.test/story",
    )

    private fun chapter(storyId: String, index: Int, title: String, url: String) = ChapterSummary(
        id = url,
        storyId = storyId,
        index = index,
        title = title,
        url = url,
    )
}

private class PagedFakeSource(
    private val pages: Map<String, ChapterPage>,
    private val failure: AppResult.Failure? = null,
) : StorySource {
    override val descriptor = SourceDescriptor("fake", "Fake", "https://example.test", SourceHealth.READY)

    override suspend fun search(query: String, page: Int) = AppResult.Success(emptyList<StorySummary>())
    override suspend fun category(category: String, page: Int) = AppResult.Success(emptyList<StorySummary>())
    override suspend fun story(url: String) = AppResult.Failure("UNUSED", "unused")
    override suspend fun chapter(url: String) = AppResult.Failure("UNUSED", "unused")

    override suspend fun chapterPage(storyId: String, url: String, startIndex: Int): AppResult<ChapterPage> {
        failure?.let { return it }
        return AppResult.Success(pages[url] ?: ChapterPage(emptyList()))
    }
}
