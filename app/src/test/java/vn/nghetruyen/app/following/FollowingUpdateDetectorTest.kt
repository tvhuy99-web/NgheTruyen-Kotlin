package vn.nghetruyen.app.following

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingUpdateDetectorTest {
    @Test fun detectsChangedTitle() {
        assertTrue(FollowingUpdateDetector.hasNewChapter("Chương 10", "Chương 11"))
        assertFalse(FollowingUpdateDetector.hasNewChapter("Chương 10", " chương 10 "))
    }

    @Test fun computesCountFromIndexOrTitle() {
        assertEquals(4, FollowingUpdateDetector.newChapterCount("Chương 10", 9, "Chương 14", 13))
        assertEquals(3, FollowingUpdateDetector.newChapterCount("Chương 20", -1, "Chương 23", -1))
        assertEquals(1, FollowingUpdateDetector.newChapterCount("Ngoại truyện A", -1, "Ngoại truyện B", -1))
    }
}
