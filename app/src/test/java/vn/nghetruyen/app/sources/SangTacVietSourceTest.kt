package vn.nghetruyen.app.sources

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SangTacVietSourceTest {
    private val route = SangTacVietRoute.parseStory("https://sangtacviet.vip/truyen/qidian/1/12345/")

    @Test fun parsesStaticStoryListWithoutChapterLinks() {
        val items = SangTacVietParser.parseStoryList(Jsoup.parse(fixture("list.html"), SangTacVietSource.BASE_URL))
        assertEquals(2, items.size)
        assertEquals("Đại Đạo Tranh Phong", items.first().title)
        assertEquals("https://sangtacviet.vip/truyen/qidian/1/12345/", items.first().url)
    }

    @Test fun parsesStoryMetadata() {
        val detail = SangTacVietParser.parseStoryDetail(Jsoup.parse(fixture("detail.html"), route.storyUrl), route.storyUrl)
        assertEquals("Đại Đạo Tranh Phong", detail.story.title)
        assertEquals("Ngộ Đạo Giả", detail.story.author)
        assertEquals(listOf("Tiên hiệp", "Huyền huyễn"), detail.genres)
        assertTrue(detail.story.description.contains("Tranh phong"))
    }

    @Test fun parsesChapterApiRecords() {
        val chapters = SangTacVietParser.parseChapterApi(fixture("toc.json"), route)
        assertEquals(3, chapters.size)
        assertEquals("Chương 2: Gặp gỡ", chapters[1].title)
        assertTrue(chapters[1].url.endsWith("/1002/"))
    }

    @Test fun parsesChapterResponseAndNavigation() {
        val chapterRoute = SangTacVietRoute.parseChapter("https://sangtacviet.vip/truyen/qidian/1/12345/1002/")
        val content = SangTacVietParser.parseChapterApiResponse(fixture("chapter.json"), chapterRoute)
        assertEquals(2, content.paragraphs.size)
        assertTrue(content.previousChapterUrl!!.endsWith("/1001/"))
        assertTrue(content.nextChapterUrl!!.endsWith("/1003/"))
    }

    @Test(expected = SourceLoginRequiredException::class)
    fun maps4005ToLoginRequired() {
        val chapterRoute = SangTacVietRoute.parseChapter("https://sangtacviet.vip/truyen/qidian/1/12345/1002/")
        SangTacVietParser.parseChapterApiResponse(fixture("login-required.json"), chapterRoute)
    }

    @Test fun routeValidationRejectsForeignOrListUrls() {
        assertTrue(SangTacVietSource.isStoryTarget("https://sangtacviet.vip/truyen/qidian/1/12345/"))
        assertTrue(!SangTacVietSource.isStoryTarget("https://sangtacviet.vip/search/?find=test"))
        assertTrue(!SangTacVietSource.isStoryTarget("https://example.com/truyen/qidian/1/12345/"))
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/sangtacviet/$name")).readText()
}
