package vn.nghetruyen.app.sources

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult

class TruyenYySourceTest {
    @Test fun parsesMarkdownList() {
        val items = TruyenYyParser.parseStoryList(fixture("list.md"))
        assertEquals(2, items.size)
        assertEquals("Kiếm Đạo Độc Tôn", items.first().title)
        assertEquals("https://truyenyy.co/truyen/kiem-dao-doc-ton", items.first().url)
    }

    @Test fun parsesDetailAndDescriptionBoundary() {
        val detail = TruyenYyParser.parseStoryDetail(
            fixture("detail.md"),
            "https://truyenyy.co/truyen/kiem-dao-doc-ton",
        )
        assertEquals("Kiếm Đạo Độc Tôn", detail.story.title)
        assertEquals("Kiếm Du Thái Hư", detail.story.author)
        assertEquals(listOf("Tiên hiệp", "Kiếm hiệp"), detail.genres)
        assertTrue(detail.story.description.contains("thiếu niên"))
        assertTrue(!detail.story.description.contains("Thế Giới Truyện"))
    }

    @Test fun chapterPagingUsesTotalCount() {
        val page = TruyenYyParser.parseChapterList(
            fixture("toc-1.md"),
            storyId = "story:test",
            startIndex = 0,
            currentUrl = "https://truyenyy.co/truyen/kiem-dao-doc-ton/danh-sach-chuong",
        )
        assertEquals(103, page.totalChapters)
        assertEquals(listOf(0, 1, 2), page.page.chapters.map { it.index })
        assertEquals(
            "https://truyenyy.co/truyen/kiem-dao-doc-ton/danh-sach-chuong?p=2",
            page.page.nextPageUrl,
        )
    }

    @Test fun parsesChapterContentAndNavigation() {
        val content = TruyenYyParser.parseChapter(
            fixture("chapter.md"),
            "https://truyenyy.co/truyen/kiem-dao-doc-ton/chuong-2-luyen-kiem",
        )
        assertEquals("Chương 2: Luyện kiếm", content.chapter.title)
        assertEquals(1, content.chapter.index)
        assertEquals(2, content.paragraphs.size)
        assertEquals("https://truyenyy.co/truyen/kiem-dao-doc-ton/chuong-1-khoi-dau", content.previousChapterUrl)
        assertEquals("https://truyenyy.co/truyen/kiem-dao-doc-ton/chuong-3-gap-dich", content.nextChapterUrl)
    }


    @Test fun directUrlValidationAcceptsStoryOrChapterButRejectsCategoryAndHttp() {
        assertTrue(TruyenYySource.isStoryTarget("https://truyenyy.co/truyen/kiem-dao-doc-ton"))
        assertTrue(TruyenYySource.isStoryTarget("https://truyenyy.co/truyen/kiem-dao-doc-ton/chuong-2-luyen-kiem"))
        assertTrue(!TruyenYySource.isStoryTarget("https://truyenyy.co/truyen-moi-cap-nhat"))
        assertTrue(!TruyenYySource.isStoryTarget("http://truyenyy.co/truyen/kiem-dao-doc-ton"))
    }

    @Test fun sourceLoadsLastTocPageForLatestChapter() = runBlocking {
        val story = "https://truyenyy.co/truyen/kiem-dao-doc-ton"
        val detailProxy = TruyenYySource.jinaUrl(story)
        val tocOne = TruyenYySource.tocUrl(story, 1)
        val tocTwo = TruyenYySource.tocUrl(story, 2)
        val client = FixtureTruyenYyClient(
            mapOf(
                detailProxy to fixture("detail.md"),
                TruyenYySource.jinaUrl(tocOne) to fixture("toc-1.md"),
                TruyenYySource.jinaUrl(tocTwo) to fixture("toc-2.md"),
            ),
        )
        val result = TruyenYySource(client).latestChapter(story)
        assertTrue(result is AppResult.Success)
        assertEquals("Chương 103 Kiếm đạo độc tôn", (result as AppResult.Success).value?.title)
        assertEquals(listOf(TruyenYySource.jinaUrl(tocOne), TruyenYySource.jinaUrl(tocTwo)), client.requested)
    }

    @Test fun lastPageHasNoContinuation() {
        val page = TruyenYyParser.parseChapterList(
            fixture("toc-2.md"),
            "story:test",
            100,
            "https://truyenyy.co/truyen/kiem-dao-doc-ton/danh-sach-chuong?p=2",
        )
        assertNull(page.page.nextPageUrl)
        assertEquals(listOf(100, 101, 102), page.page.chapters.map { it.index })
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/truyenyy/$name")).readText()
}

private class FixtureTruyenYyClient(
    private val values: Map<String, String>,
) : TextDocumentClient {
    val requested = mutableListOf<String>()

    override suspend fun getText(url: String, allowedHosts: Set<String>, headers: Map<String, String>): String {
        requested += url
        val host = url.toHttpUrl().host
        check(allowedHosts.any { host == it || host.endsWith(".$it") })
        return values[url] ?: error("Thiếu fixture TruyenYY cho $url")
    }
}
