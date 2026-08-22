package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookBrowserChallengeDetectorTest {
    @Test
    fun detectsCloudflareVerificationPage() {
        assertTrue(
            VBookBrowserChallengeDetector.isChallenge(
                "<html><title>Chờ một chút...</title><body>Cloudflare - Thực hiện xác minh bảo mật</body></html>",
            ),
        )
    }

    @Test
    fun ignoresNormalStoryPage() {
        assertFalse(
            VBookBrowserChallengeDetector.isChallenge(
                "<html><body><h3 class='title'>Tên truyện</h3><div>Nội dung</div></body></html>",
            ),
        )
    }
}
