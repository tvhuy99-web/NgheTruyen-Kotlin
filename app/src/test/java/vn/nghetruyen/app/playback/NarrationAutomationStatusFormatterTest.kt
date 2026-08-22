package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationAutomationStatusFormatterTest {
    @Test
    fun automaticAndManualShareSameBodyAndAlwaysReportErrorCount() {
        assertEquals(
            "Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm • 0 lỗi. Đang bắt đầu phát.",
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
            "Đã tải xong chương tiếp theo: Chương 2. Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm • 0 lỗi.",
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

    @Test
    fun reportsAllDistinctSpecificErrorsInline() {
        assertEquals(
            "Đã phân vai xong 20 mục • 3 tải mới • 5 bộ nhớ tạm • 2 lỗi: 1) Freesound ‘light rain drops’: Preview quá thời gian; 2) Âm thanh quan trọng ‘night wind’ chưa tìm thấy kết quả. Đang bắt đầu phát.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 20,
                resultPresent = true,
                downloadedAssets = 3,
                reusedAssets = 5,
                retryRequired = false,
                audioLayersEnabled = true,
                beginPlayback = true,
                issues = listOf(
                    "Freesound ‘light rain drops’: Preview quá thời gian.",
                    "Freesound ‘light rain drops’: Preview quá thời gian.",
                    "Một số âm thanh Freesound còn thiếu sau 3 lần; ứng dụng sẽ phát phần đã tải hợp lệ thay vì làm câm cả chương.",
                    "Âm thanh quan trọng ‘night wind’ chưa tìm thấy kết quả.",
                ),
            ),
        )
    }

    @Test
    fun reportsErrorsEvenWhenAudioLayersAreDisabled() {
        assertEquals(
            "Đã phân vai xong 8 mục • 1 lỗi: 1) Không áp dụng được giọng nhân vật.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 8,
                resultPresent = true,
                downloadedAssets = 0,
                reusedAssets = 0,
                retryRequired = false,
                audioLayersEnabled = false,
                issues = listOf("Không áp dụng được giọng nhân vật."),
            ),
        )
    }
}
