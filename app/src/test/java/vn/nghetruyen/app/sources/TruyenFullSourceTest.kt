package vn.nghetruyen.app.sources

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult

class TruyenFullSourceTest {
    @Test
    fun parsesListAndStableMetadata() {
        val document = fixture("list.html", "https://truyenfull.live/danh-sach/truyen-moi/")

        val stories = TruyenFullParser.parseStoryList(document)

        assertEquals(2, stories.size)
        assertEquals("Thần Đạo Đan Tôn", stories[0].title)
        assertEquals("Cô Đơn Địa Phi", stories[0].author)
        assertEquals("truyenfull", stories[0].sourceId)
        assertTrue(stories[0].id.startsWith("story:"))
        assertEquals("https://truyenfull.live/than-dao-dan-ton/", stories[0].url)
    }

    @Test
    fun searchAcceptsAnAllowedStoryUrlWithoutUsingKeywordSearch() = runBlocking {
        val detailUrl = "https://truyenfull.live/than-dao-dan-ton/"
        val client = FixtureClient(
            mapOf(detailUrl to fixture("detail-page-1.html", detailUrl)),
        )
        val source = TruyenFullSource(client)

        val result = source.search(detailUrl)

        assertTrue(result is AppResult.Success)
        val stories = (result as AppResult.Success).value
        assertEquals(1, stories.size)
        assertEquals("Thần Đạo Đan Tôn", stories.single().title)
        assertEquals(listOf(detailUrl), client.requestedUrls)
    }

    @Test
    fun storyLoadsOnlyFirstChapterPageAndExposesContinuation() = runBlocking {
        val detailUrl = "https://truyenfull.live/than-dao-dan-ton/"
        val client = FixtureClient(
            mapOf(detailUrl to fixture("detail-page-1.html", detailUrl)),
        )
        val source = TruyenFullSource(client)

        val result = source.story(detailUrl)

        assertTrue(result is AppResult.Success)
        val detail = (result as AppResult.Success).value
        assertEquals("Thần Đạo Đan Tôn", detail.story.title)
        assertEquals(listOf("Tiên Hiệp", "Huyền Huyễn"), detail.genres)
        assertEquals(2, detail.chapters.size)
        assertEquals("https://truyenfull.live/than-dao-dan-ton/trang-2/", detail.nextChapterPageUrl)
        assertEquals(listOf(detailUrl), client.requestedUrls)
    }


    @Test
    fun latestChapterLoadsHighestPaginationPage() = runBlocking {
        val detailUrl = "https://truyenfull.live/than-dao-dan-ton/"
        val pageTwo = "https://truyenfull.live/than-dao-dan-ton/trang-2/"
        val client = FixtureClient(
            mapOf(
                detailUrl to fixture("detail-page-1.html", detailUrl),
                pageTwo to fixture("detail-page-2.html", pageTwo),
            ),
        )
        val source = TruyenFullSource(client)

        val result = source.latestChapter(detailUrl)

        assertTrue(result is AppResult.Success)
        val chapter = (result as AppResult.Success).value
        assertEquals("Chương 4: Thử thách", chapter?.title)
        assertEquals(listOf(detailUrl, pageTwo), client.requestedUrls)
    }

    @Test
    fun chapterPageContinuesIndexesWithoutDuplicates() = runBlocking {
        val pageUrl = "https://truyenfull.live/than-dao-dan-ton/trang-2/"
        val client = FixtureClient(
            mapOf(pageUrl to fixture("detail-page-2.html", pageUrl)),
        )
        val source = TruyenFullSource(client)

        val result = source.chapterPage("story:test", pageUrl, startIndex = 2)

        assertTrue(result is AppResult.Success)
        val page = (result as AppResult.Success).value
        assertEquals(listOf(2, 3), page.chapters.map { it.index })
        assertEquals("Chương 3: Gặp gỡ", page.chapters.first().title)
        assertNull(page.nextPageUrl)
    }

    @Test
    fun chapterContentRemovesActiveContentAndBoilerplate() = runBlocking {
        val chapterUrl = "https://truyenfull.live/than-dao-dan-ton/chuong-2/"
        val client = FixtureClient(
            mapOf(chapterUrl to fixture("chapter.html", chapterUrl)),
        )
        val source = TruyenFullSource(client)

        val result = source.chapter(chapterUrl)

        assertTrue(result is AppResult.Success)
        val content = (result as AppResult.Success).value
        assertEquals("Chương 2: Lên đường", content.chapter.title)
        assertEquals(1, content.chapter.index)
        assertEquals(
            listOf("Đoạn văn thứ nhất.", "Đoạn văn thứ hai", "vẫn thuộc nội dung."),
            content.paragraphs,
        )
        assertFalse(content.paragraphs.any { it.contains("Quảng cáo") })
        assertEquals("https://truyenfull.live/than-dao-dan-ton/chuong-1/", content.previousChapterUrl)
        assertEquals("https://truyenfull.live/than-dao-dan-ton/chuong-3/", content.nextChapterUrl)
    }

    @Test
    fun rejectsDocumentsWithoutExpectedLayout() {
        val document = Jsoup.parse("<html><body><h1>Cloudflare</h1></body></html>", "https://truyenfull.live/")
        val error = runCatching { TruyenFullParser.parseStoryList(document) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is SourceParseException)
    }

    private fun fixture(name: String, baseUrl: String): Document {
        val resource = checkNotNull(javaClass.getResource("/truyenfull/$name"))
        return Jsoup.parse(resource.readText(), baseUrl)
    }
}

private class FixtureClient(
    private val documents: Map<String, Document>,
) : HtmlDocumentClient {
    val requestedUrls = mutableListOf<String>()

    override suspend fun getDocument(url: String, allowedHosts: Set<String>): Document {
        requestedUrls += url
        val host = url.toHttpUrl().host
        check(allowedHosts.any { host == it || host.endsWith(".$it") })
        return documents[url]?.clone() ?: error("Thiếu fixture cho $url")
    }
}

