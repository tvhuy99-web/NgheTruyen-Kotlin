package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class FreesoundPlaybackStatusFormatterTest {
    @Test
    fun compactCountsOnly() {
        assertEquals(
            " • 5 tải mới • 3 bộ nhớ tạm",
            FreesoundPlaybackStatusFormatter.format(true, 5, 3, false, true),
        )
    }

    @Test
    fun normalZeroCountsStaySilent() {
        assertEquals("", FreesoundPlaybackStatusFormatter.format(true, 0, 0, false, true))
    }

    @Test
    fun abnormalStateRemainsVisible() {
        assertEquals(
            " • 1 tải mới • còn thiếu",
            FreesoundPlaybackStatusFormatter.format(true, 1, 0, true, true),
        )
    }

    @Test
    fun disabledLayersStaySilent() {
        assertEquals("", FreesoundPlaybackStatusFormatter.format(false, 2, 4, true, false))
    }
}
