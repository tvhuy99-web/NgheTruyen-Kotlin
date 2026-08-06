package vn.nghetruyen.app.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterSummary

class DownloadBatchPlannerTest {
    private val chapters = (0 until 105).map { index ->
        ChapterSummary(
            id = "chapter-$index",
            storyId = "story",
            index = index,
            title = "Chương ${index + 1}",
            url = "https://example.test/chapter-$index",
        )
    }

    @Test
    fun limitsEachWorkerRunAndPreservesProgress() {
        val downloaded = (0 until 20).mapTo(hashSetOf()) { "chapter-$it" }
        val plan = DownloadBatchPlanner.create(chapters, downloaded, maxBatchSize = 40)

        assertEquals(20, plan.completedBeforeBatch)
        assertEquals(40, plan.batch.size)
        assertEquals("chapter-20", plan.batch.first().id)
        assertEquals(45, plan.remainingAfterBatch)
        assertTrue(plan.hasMore)
    }

    @Test
    fun returnsEmptyBatchWhenStoryIsAlreadyDownloaded() {
        val plan = DownloadBatchPlanner.create(
            chapters,
            chapters.mapTo(hashSetOf()) { it.id },
            maxBatchSize = 40,
        )

        assertEquals(105, plan.completedBeforeBatch)
        assertTrue(plan.batch.isEmpty())
        assertFalse(plan.hasMore)
    }
}
