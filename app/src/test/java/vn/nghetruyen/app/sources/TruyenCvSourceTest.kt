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
import vn.nghetruyen.app.core.model.SourceHealth

class TruyenCvSourceTest {
    @Test
    fun parsesStoryListWithoutDuplicates() {
        val stories = TruyenCvParser.parseStoryList(
            fixture("list.html", "https://truyencv.io/moi-cap-nhat/"),
        )

        assertEquals(2, stories.size)
        assertEquals("Uyên Thiên Tôn", stories.first().title)
        assertEquals("Nga Căn", stories.first().author)
        assertEquals("https://truyencv.io/truyen/uyen-thien-ton/", stories.first().url)
        assertEquals("truyencv", stories.first().sourceId)
    }

    @Test
    fun sourceIsDegradedUntilLiveDeviceVerification() {
        assertEquals(SourceHealth.DEGRADED, TruyenCvSource(FakeTruyenCvClient(emptyMap())).descriptor.health)
    }

    @Test
    fun storyStartsAtOldestChapterPageAndMovesForward() = runBlocking {
        val storyUrl = "https://truyencv.io/truyen/uyen-thien-ton/"
        val page3 = "https://truyencv.io/truyen/uyen-thien-ton/chuong/page/3/"
        val client = FakeTruyenCvClient(
            mapOf(
                storyUrl to fixture("detail.html", storyUrl),
                page3 to fixture("chapter-page-3.html", page3),
            ),
        )

        val result = TruyenCvSource(client).story(storyUrl)

        assertTrue(result is AppResult.Success)
        val detail = (result as AppResult.Success).value
        assertEquals("Uyên Thiên Tôn", detail.story.title)
        assertEquals("Nga Căn", detail.story.author)
        assertEquals(listOf("Tiên Hiệp", "Huyền Huyễn"), detail.genres)
        assertEquals(listOf(0, 1), detail.chapters.map { it.index })
        assertTrue(detail.chapters.first().title.contains("Chương 1"))
        assertEquals("https://truyencv.io/truyen/uyen-thien-ton/chuong/page/2/", detail.nextChapterPageUrl)
        assertEquals(listOf(storyUrl, page3), client.requestedUrls)
    }

    @Test
    fun latestChapterUsesNewestUnpagedChapterList() = runBlocking {
        val storyUrl = "https://truyencv.io/truyen/uyen-thien-ton/"
        val client = FakeTruyenCvClient(mapOf(storyUrl to fixture("detail.html", storyUrl)))

        val result = TruyenCvSource(client).latestChapter(storyUrl)

        assertTrue(result is AppResult.Success)
        val chapter = (result as AppResult.Success).value
        assertTrue(chapter?.title.orEmpty().contains("Chương"))
        assertEquals(listOf(storyUrl), client.requestedUrls)
    }

    @Test
    fun chapterPagingKeepsAscendingIndexes() = runBlocking {
        val page2 = "https://truyencv.io/truyen/uyen-thien-ton/chuong/page/2/"
        val client = FakeTruyenCvClient(mapOf(page2 to fixture("chapter-page-2.html", page2)))

        val result = TruyenCvSource(client).chapterPage("story:test", page2, startIndex = 2)

        assertTrue(result is AppResult.Success)
        val page = (result as AppResult.Success).value
        assertEquals(listOf(2, 3), page.chapters.map { it.index })
        assertTrue(page.chapters.first().title.contains("Chương 3"))
        assertEquals("https://truyencv.io/truyen/uyen-thien-ton/chuong/page/1/", page.nextPageUrl)
    }

    @Test
    fun chapterContentIsCleanAndHasNavigation() = runBlocking {
        val url = "https://truyencv.io/truyen/uyen-thien-ton/chuong-2/"
        val source = TruyenCvSource(FakeTruyenCvClient(mapOf(url to fixture("chapter.html", url))))

        val result = source.chapter(url)

        assertTrue(result is AppResult.Success)
        val content = (result as AppResult.Success).value
        assertEquals(1, content.chapter.index)
        assertEquals(
            listOf("Đoạn thứ nhất.", "Đoạn thứ hai.", "Vẫn là nội dung."),
            content.paragraphs,
        )
        assertFalse(content.paragraphs.any { it.contains("Quảng cáo") || it.contains("truyencv.io") })
        assertEquals("https://truyencv.io/truyen/uyen-thien-ton/chuong-1/", content.previousChapterUrl)
        assertEquals("https://truyencv.io/truyen/uyen-thien-ton/chuong-3/", content.nextChapterUrl)
    }

    @Test
    fun pageOneHasNoEarlierChapterPage() {
        val page = TruyenCvParser.parseChapterPage(
            fixture("chapter-page-3.html", "https://truyencv.io/truyen/uyen-thien-ton/chuong/page/1/"),
            storyId = "story:test",
            startIndex = 0,
            currentUrl = "https://truyencv.io/truyen/uyen-thien-ton/chuong/page/1/",
        )
        assertNull(page.nextPageUrl)
    }

    private fun fixture(name: String, baseUrl: String): Document {
        val resource = checkNotNull(javaClass.getResource("/truyencv/$name"))
        return Jsoup.parse(resource.readText(), baseUrl)
    }
}

private class FakeTruyenCvClient(
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
