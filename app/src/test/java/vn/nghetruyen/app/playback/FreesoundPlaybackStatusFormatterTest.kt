package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundPlaybackStatusFormatterTest {
    @Test
    fun reportsNewAndReusedFilesSeparately() {
        assertEquals(
            " • Freesound: tải mới 3 tệp, dùng lại 5 tệp đã có",
            FreesoundPlaybackStatusFormatter.format(true, 3, 5, false, true),
        )
    }

    @Test
    fun cacheOnlyRunNeverClaimsNothingResolved() {
        val message = FreesoundPlaybackStatusFormatter.format(true, 0, 6, false, true)
        assertEquals(" • Freesound: tải mới 0 tệp, dùng lại 6 tệp đã có", message)
        assertTrue(!message.contains("chưa resolve được tệp nào"))
    }

    @Test
    fun incompleteRunKeepsCountsAndRetryState() {
        assertEquals(
            " • Freesound: tải mới 2 tệp, dùng lại 4 tệp đã có; đang thử lại phần còn thiếu",
            FreesoundPlaybackStatusFormatter.format(true, 2, 4, true, true),
        )
    }

    @Test
    fun disabledLayersStayExplicit() {
        assertEquals(
            " • các lớp âm thanh Mode 3 đang tắt",
            FreesoundPlaybackStatusFormatter.format(true, 9, 9, false, false),
        )
    }
}
