package vn.nghetruyen.app.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.SearchSortMode
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StorySummary

class StorySearchTest {
    @Test fun normalizesVietnameseText() {
        assertEquals("dau la dai luc", StorySearch.normalize("Đấu La Đại Lục"))
    }

    @Test fun mergesDuplicateStoriesAndPrefersReadySource() {
        val merged = StorySearch.merge(
            listOf(
                StorySummary("a", "degraded", "Đấu La Đại Lục", "Đường Gia Tam Thiếu"),
                StorySummary("b", "ready", "Dau La Dai Luc", "Duong Gia Tam Thieu"),
            ),
            mapOf("ready" to SourceHealth.READY, "degraded" to SourceHealth.DEGRADED),
            query = "dau la",
        )
        assertEquals(1, merged.size)
        assertEquals("ready", merged.single().sourceId)
    }

    @Test fun toleratesOneCharacterTypo() {
        val exact = StorySummary("1", "ready", "Đấu La Đại Lục", "")
        val unrelated = StorySummary("2", "ready", "Tiên Nghịch", "")
        val results = StorySearch.merge(
            listOf(unrelated, exact),
            mapOf("ready" to SourceHealth.READY),
            query = "dau la dai lucj",
        )
        assertEquals("1", results.first().id)
        assertTrue(
            StorySearch.score(exact, "dau la dai lucj", SourceHealth.READY) >
                StorySearch.score(unrelated, "dau la dai lucj", SourceHealth.READY),
        )
    }

    @Test fun canSortByAuthor() {
        val results = StorySearch.merge(
            listOf(
                StorySummary("1", "s", "B", "Zed"),
                StorySummary("2", "s", "A", "An"),
            ),
            mapOf("s" to SourceHealth.READY),
            sortMode = SearchSortMode.AUTHOR,
        )
        assertEquals(listOf("2", "1"), results.map { it.id })
    }
}
