package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec

class VBookStoryNormalizerTest {
    @Test
    fun listAndDetailResolveRelativeLinksWithoutWebsiteSpecificRules() {
        val list = JsonCodec.parse("""[
          {"name":"Truyện A","link":"/book/a","cover":"/a.jpg","description":"Tác giả X","host":"https://x.example"}
        ]""")
        val story = VBookStoryNormalizer.stories(list).single()
        assertEquals("Truyện A", story.title)
        assertEquals("https://x.example/book/a", story.url)
        assertEquals("https://x.example/a.jpg", story.coverUrl)

        val detail = VBookStoryNormalizer.detail(
            JsonCodec.parse("""{
              "name":"Truyện A","author":"Tác giả X","cover":"/a.jpg","description":"Mô tả",
              "host":"https://x.example","ongoing":true,
              "genres":[{"title":"Tiên Hiệp","input":"/genre/tien","script":"genre.js"}],
              "suggests":{"title":"Liên quan","input":"/book/a","script":"suggest.js"}
            }"""),
            inputUrl = "https://x.example/book/a",
        )!!
        assertEquals("Tác giả X", detail.story.author)
        assertEquals(listOf("Tiên Hiệp"), detail.genres)
        assertEquals("Đang ra", detail.status)
        assertTrue(detail.dynamicActions.any { it.scriptPath == "src/genre.js" })
        assertTrue(detail.dynamicActions.any { it.scriptPath == "src/suggest.js" })
    }

    @Test
    fun tocKeepsStableIndexesAcrossPages() {
        val chapters = VBookStoryNormalizer.chapters(
            JsonCodec.parse("""[
              {"name":"C 11","url":"/c11","host":"https://x.example"},
              {"name":"C 12","url":"/c12","host":"https://x.example"}
            ]"""),
            storyUrl = "https://x.example/book/a",
            startIndex = 10,
        )
        assertEquals(listOf(10, 11), chapters.map { it.index })
        assertEquals("https://x.example/c11", chapters[0].url)
        assertEquals(chapters[0].storyId, chapters[1].storyId)
    }

    @Test
    fun novelChapterRemovesExecutableMarkupButKeepsReadingParagraphs() {
        val body = VBookStoryNormalizer.chapterBody(
            JsonCodec.parse(""""<script>bad()</script><p>Đoạn một</p><p>Đoạn hai</p>""""),
        )
        assertEquals(listOf("Đoạn một", "Đoạn hai"), body.paragraphs)
        assertFalse(body.paragraphs.any { "bad" in it })
    }
}
