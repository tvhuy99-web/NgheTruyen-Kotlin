package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationAutomationStatusFormatterTest {
    @Test
    fun automaticAndManualShareSameBody() {
        assertEquals(
            "Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm. Đang bắt đầu phát.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 12,
                resultPresent = true,
                downloadedAssets = 5,
                reusedAssets = 3,
                retryRequired = false,
                audioLayersEnabled = true,
                beginPlayback = true,
            ),
        )
    }

    @Test
    fun prefetchOnlyAddsNextChapterPrefix() {
        assertEquals(
            "Đã tải xong chương tiếp theo: Chương 2. Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 12,
                resultPresent = true,
                downloadedAssets = 5,
                reusedAssets = 3,
                retryRequired = false,
                audioLayersEnabled = true,
                prefix = "Đã tải xong chương tiếp theo: Chương 2",
            ),
        )
    }
}
