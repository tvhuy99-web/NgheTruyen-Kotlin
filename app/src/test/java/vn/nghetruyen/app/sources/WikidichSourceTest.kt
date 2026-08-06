package vn.nghetruyen.app.sources

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikidichSourceTest {
    @Test fun parsesStoryList() {
        val items = WikidichParser.parseStoryList(document("list.html", "https://wikidichvn.com/danh-sach/truyen-full"))
        assertEquals(2, items.size)
        assertEquals("Quỷ Bí Chi Chủ", items.first().title)
        assertEquals("Mực Thích Lặn Nước", items.first().author)
    }

    @Test fun parsesDetailAndChapterPage() {
        val doc = document("detail.html", "https://wikidichvn.com/quy-bi-chi-chu")
        val detail = WikidichParser.parseStoryDetail(doc, "https://wikidichvn.com/quy-bi-chi-chu")
        val page = WikidichParser.parseChapterPage(doc, detail.story.id, 0, detail.story.url)
        assertEquals("Quỷ Bí Chi Chủ", detail.story.title)
        assertEquals(listOf("Huyền Huyễn"), detail.genres)
        assertEquals(3, page.chapters.size)
        assertEquals("https://wikidichvn.com/quy-bi-chi-chu?page=2", page.nextPageUrl)
    }

    @Test fun computesLastTocPage() {
        val doc = document("detail.html", "https://wikidichvn.com/quy-bi-chi-chu")
        assertEquals("https://wikidichvn.com/quy-bi-chi-chu?page=3", WikidichParser.lastChapterPage(doc, "https://wikidichvn.com/quy-bi-chi-chu"))
        val last = WikidichParser.parseChapterPage(document("chapter-page-3.html", "https://wikidichvn.com/quy-bi-chi-chu?page=3"), "story", 200, "https://wikidichvn.com/quy-bi-chi-chu?page=3")
        assertNull(last.nextPageUrl)
        assertEquals("Chương 205: Lời kết", last.chapters.last().title)
    }

    @Test fun parsesChapterContentAndNavigation() {
        val content = WikidichParser.parseChapterContent(document("chapter.html", "https://wikidichvn.com/quy-bi-chi-chu/chuong-2-tinh-trang"), "https://wikidichvn.com/quy-bi-chi-chu/chuong-2-tinh-trang")
        assertEquals(2, content.paragraphs.size)
        assertTrue(content.previousChapterUrl!!.endsWith("chuong-1-do-ruc"))
        assertTrue(content.nextChapterUrl!!.endsWith("chuong-3-melissa"))
    }

    @Test fun validatesDirectStoryUrl() {
        assertTrue(WikidichSource.isStoryTarget("https://wikidichvn.com/quy-bi-chi-chu"))
        assertTrue(!WikidichSource.isStoryTarget("https://wikidichvn.com/the-loai/tien-hiep"))
        assertTrue(!WikidichSource.isStoryTarget("http://wikidichvn.com/quy-bi-chi-chu"))
    }

    private fun document(name: String, base: String) = Jsoup.parse(fixture(name), base)
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/wikidich/$name")).readText()
}
