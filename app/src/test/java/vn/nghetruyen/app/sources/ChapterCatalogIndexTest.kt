package vn.nghetruyen.app.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterSummary

class ChapterCatalogIndexTest {
    private val chapters = (0 until 10_000).map { index ->
        ChapterSummary(
            id = "chapter-$index",
            storyId = "story",
            index = index,
            title = "Chương ${index + 1}: Hành trình số ${index + 1}",
            url = "https://example.invalid/$index",
        )
    }

    @Test
    fun exactHumanNumberUsesDirectLookup() {
        val index = ChapterCatalogIndex(chapters)
        val result = index.search("9999")
        assertEquals(1, result.size)
        assertEquals(9_998, result.single().index)
    }

    @Test
    fun accentInsensitiveTitleSearchWorksAcrossTenThousandChapters() {
        val started = System.nanoTime()
        val index = ChapterCatalogIndex(chapters)
        val result = index.search("hanh trinh so 8765")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(8_764, result.first().index)
        assertTrue("Index and search should remain practical for 10k chapters", elapsedMs < 5_000)
    }
}
