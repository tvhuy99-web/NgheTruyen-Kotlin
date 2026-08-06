package vn.nghetruyen.app.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.DownloadSelectionMode

class DownloadRequestTest {
    @Test fun createsStableRangeIdentity() {
        val first = DownloadRequest.create("source", "story", DownloadSelectionMode.RANGE, 4, 9)
        val second = DownloadRequest.create("source", "story", DownloadSelectionMode.RANGE, 4, 9)
        assertEquals(first.jobId, second.jobId)
        assertTrue(first.jobId.contains("RANGE:4-9"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReverseRange() {
        DownloadRequest("id", "source", "story", DownloadSelectionMode.RANGE, 9, 4)
    }
}
