package vn.nghetruyen.app.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterSummary

class ChapterCatalogSafetyTest {
    @Test
    fun initialCatalogRemovesPaginationControlsAndRecoversPageTwo() {
        val chapters = (1..50).map { number ->
            chapter(
                id = "chapter-$number",
                index = number - 1,
                title = "Chương $number",
                url = "https://truyenfull.vision/linh-vu-thien-ha/chuong-$number/",
            )
        } + listOf(
            chapter("page-2", 50, "2", "https://truyenfull.vision/linh-vu-thien-ha/trang-2/#list-chapter"),
            chapter("page-3", 51, "3", "https://truyenfull.vision/linh-vu-thien-ha/trang-3/#list-chapter"),
            chapter("page-4", 52, "4", "https://truyenfull.vision/linh-vu-thien-ha/trang-4/#list-chapter"),
            // The real crash capture contained two labels sharing this exact page-2 URL/id.
            chapter("page-2", 53, "Trang tiếp", "https://truyenfull.vision/linh-vu-thien-ha/trang-2/#list-chapter"),
            chapter("page-11", 54, "Cuối »", "https://truyenfull.vision/linh-vu-thien-ha/trang-11/#list-chapter"),
        )

        val safe = sanitizeChapterCatalog(
            currentPageUrl = "https://truyenfull.vision/linh-vu-thien-ha/",
            chapters = chapters,
            nextPageUrl = null,
        )

        assertEquals(50, safe.chapters.size)
        assertEquals("Chương 50", safe.chapters.last().title)
        assertEquals(
            "https://truyenfull.vision/linh-vu-thien-ha/trang-2/#list-chapter",
            safe.nextPageUrl,
        )
        assertEquals(50, safe.chapters.map { it.id }.distinct().size)
    }

    @Test
    fun laterCatalogRecoversNextPageRelativeToCurrentPage() {
        val safe = sanitizeChapterCatalog(
            currentPageUrl = "https://truyenfull.vision/linh-vu-thien-ha/trang-2/#list-chapter",
            chapters = listOf(
                chapter("chapter-51", 50, "Chương 51", "https://truyenfull.vision/linh-vu-thien-ha/chuong-51/"),
                chapter("page-1", 51, "1", "https://truyenfull.vision/linh-vu-thien-ha/#list-chapter"),
                chapter("page-3", 52, "3", "https://truyenfull.vision/linh-vu-thien-ha/trang-3/#list-chapter"),
                chapter("page-11", 53, "Cuối »", "https://truyenfull.vision/linh-vu-thien-ha/trang-11/#list-chapter"),
            ),
            nextPageUrl = null,
        )

        assertEquals(listOf("Chương 51"), safe.chapters.map { it.title })
        assertEquals(
            "https://truyenfull.vision/linh-vu-thien-ha/trang-3/#list-chapter",
            safe.nextPageUrl,
        )
    }

    @Test
    fun duplicateRealChapterIdsAreCollapsedBeforeCompose() {
        val safe = sanitizeChapterCatalog(
            currentPageUrl = "https://truyenfull.vision/story/",
            chapters = listOf(
                chapter("same", 0, "Chương A", "https://truyenfull.vision/story/a/"),
                chapter("same", 1, "Chương B", "https://truyenfull.vision/story/b/"),
            ),
            nextPageUrl = null,
        )

        assertEquals(1, safe.chapters.size)
        assertEquals("Chương A", safe.chapters.single().title)
        assertTrue(safe.chapters.single().id.isNotBlank())
    }

    private fun chapter(id: String, index: Int, title: String, url: String) = ChapterSummary(
        id = id,
        storyId = "story",
        index = index,
        title = title,
        url = url,
    )
}
