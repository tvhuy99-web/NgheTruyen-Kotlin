package vn.nghetruyen.app.downloads

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterSummary

class ChapterRangeSelectorTest {
    private val chapters = (0..9).map { index ->
        ChapterSummary("c$index", "s", index * 10 + 7, "Chương ${index + 1}")
    }

    @Test fun selectsInclusiveRangeByCataloguePosition() {
        assertEquals(listOf("c2", "c3", "c4"), ChapterRangeSelector.select(chapters, 2, 4).map { it.id })
    }

    @Test fun clampsNegativeStartAndEndPastCatalogue() {
        assertEquals(listOf("c0", "c1"), ChapterRangeSelector.select(chapters, -5, 1).map { it.id })
        assertEquals(listOf("c8", "c9"), ChapterRangeSelector.select(chapters, 8, 200).map { it.id })
    }

    @Test fun rejectsRangeStartingAfterCatalogue() {
        assertEquals(emptyList<ChapterSummary>(), ChapterRangeSelector.select(chapters, 20, 30))
    }
}
