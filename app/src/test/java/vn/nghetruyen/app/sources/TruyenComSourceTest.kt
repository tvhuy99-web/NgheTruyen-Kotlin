package vn.nghetruyen.app.sources

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult

class TruyenComSourceTest {
    @Test
    fun parsesListAndRejectsCategoryLinks() {
        val stories = TruyenComParser.parseStoryList(
            fixture("list.html", "https://truyencom.com/truyen-moi-cap-nhat/"),
        )
        assertEquals(2, stories.size)
        assertEquals("Truyền Kiếm", stories.first().title)
        assertEquals("Văn Mặc", stories.first().author)
        assertEquals("truyencom", stories.first().sourceId)
        assertEquals("https://truyencom.com/truyen-kiem.6795/", stories.first().url)
    }

    @Test
    fun storyReturnsFirstChapterPageAndContinuation() = runBlocking {
        val url = "https://truyencom.com/truyen-kiem.6795/"
        val source = TruyenComSource(FixtureTruyenComClient(mapOf(url to fixture("detail.html", url))))
        val result = source.story(url)
        assertTrue(result is AppResult.Success)
        val detail = (result as AppResult.Success).value
        assertEquals("Truyền Kiếm", detail.story.title)
        assertEquals("Văn Mặc", detail.story.author)
        assertEquals(listOf("Kiếm Hiệp"), detail.genres)
        assertEquals(2, detail.chapters.size)
        assertEquals("https://truyencom.com/truyen-kiem.6795/trang-2/", detail.nextChapterPageUrl)
    }

    @Test
    fun latestChapterLoadsLastPaginationPage() = runBlocking {
        val detailUrl = "https://truyencom.com/truyen-kiem.6795/"
        val pageTwo = "https://truyencom.com/truyen-kiem.6795/trang-2/"
        val client = FixtureTruyenComClient(
            mapOf(
                detailUrl to fixture("detail.html", detailUrl),
                pageTwo to fixture("detail-page-2.html", pageTwo),
            ),
        )
        val result = TruyenComSource(client).latestChapter(detailUrl)
        assertTrue(result is AppResult.Success)
        assertEquals("Chương 4: Tiểu Hắc", (result as AppResult.Success).value?.title)
        assertEquals(listOf(detailUrl, pageTwo), client.requestedUrls)
    }

    @Test
    fun chapterPageContinuesIndexAndStopsWithoutNextPage() = runBlocking {
        val url = "https://truyencom.com/truyen-kiem.6795/trang-2/"
        val source = TruyenComSource(FixtureTruyenComClient(mapOf(url to fixture("detail-page-2.html", url))))
        val result = source.chapterPage("story:test", url, 2)
        assertTrue(result is AppResult.Success)
        val page = (result as AppResult.Success).value
        assertEquals(listOf(2, 3), page.chapters.map { it.index })
        assertNull(page.nextPageUrl)
    }

    @Test
    fun chapterContentRemovesAdsAndBoilerplate() = runBlocking {
        val url = "https://truyencom.com/truyen-kiem.6795/chuong-2-phi-tien/"
        val source = TruyenComSource(FixtureTruyenComClient(mapOf(url to fixture("chapter.html", url))))
        val result = source.chapter(url)
        assertTrue(result is AppResult.Success)
        val content = (result as AppResult.Success).value
        assertEquals("Chương 2: Phi tiên", content.chapter.title)
        assertEquals(1, content.chapter.index)
        assertFalse(content.paragraphs.any { it.contains("Quảng cáo") || it.contains("TruyenCom") })
        assertEquals("https://truyencom.com/truyen-kiem.6795/chuong-1-tan-mach-bam-sinh/", content.previousChapterUrl)
        assertEquals("https://truyencom.com/truyen-kiem.6795/chuong-3-kiem-dang-khoc/", content.nextChapterUrl)
    }

    @Test
    fun buildsCurrentSearchAndPagingUrls() {
        assertEquals("minh-hon", TruyenComSource.searchSlug("Minh Hôn"))
        assertEquals(
            "https://truyencom.com/truyen-moi-cap-nhat/trang-3/",
            TruyenComSource.pagedUrl("https://truyencom.com/truyen-moi-cap-nhat/", 3),
        )
    }

    private fun fixture(name: String, baseUrl: String): Document {
        val resource = checkNotNull(javaClass.getResource("/truyencom/$name"))
        return Jsoup.parse(resource.readText(), baseUrl)
    }
}

private class FixtureTruyenComClient(
    private val documents: Map<String, Document>,
) : HtmlDocumentClient {
    val requestedUrls = mutableListOf<String>()

    override suspend fun getDocument(url: String, allowedHosts: Set<String>): Document {
        requestedUrls += url
        val host = url.toHttpUrl().host
        check(allowedHosts.any { host == it || host.endsWith(".$it") })
        return documents[url]?.clone() ?: error("Thiếu fixture Truyện Com cho $url")
    }
}
