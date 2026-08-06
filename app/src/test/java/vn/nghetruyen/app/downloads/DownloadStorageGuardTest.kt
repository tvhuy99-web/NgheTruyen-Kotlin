package vn.nghetruyen.app.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageGuardTest {
    @Test fun rejectsWhenReserveWouldBeConsumed() {
        val estimate = DownloadStorageGuard.estimate(
            availableBytes = 80L * 1024 * 1024,
            chapterCount = 100,
        )
        assertFalse(estimate.hasEnoughSpace)
        assertTrue(estimate.shortfallBytes > 0)
    }

    @Test fun usesObservedAverageWithinSafetyBounds() {
        val estimate = DownloadStorageGuard.estimate(
            availableBytes = 2L * 1024 * 1024 * 1024,
            chapterCount = 10,
            knownDownloadedBytes = 10L * 1024 * 1024,
            knownDownloadedChapters = 10,
        )
        assertTrue(estimate.hasEnoughSpace)
        assertTrue(estimate.estimatedChapterBytes >= 1024L * 1024L)
    }
}
