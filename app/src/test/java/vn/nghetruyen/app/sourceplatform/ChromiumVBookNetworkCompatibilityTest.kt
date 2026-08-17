package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromiumVBookNetworkCompatibilityTest {
    @Test
    fun defaultsVBookFetchToBrowserUserAgent() {
        val headers = ChromiumVBookNetworkCompatibility.withDefaultUserAgent(
            headers = linkedMapOf("Accept" to "application/json"),
            userAgent = "Mozilla/5.0 Android WebView",
        )

        assertEquals("Mozilla/5.0 Android WebView", headers["User-Agent"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun explicitUserAgentWinsCaseInsensitively() {
        val original = linkedMapOf("user-agent" to "Extension-UA", "Accept" to "*/*")

        val headers = ChromiumVBookNetworkCompatibility.withDefaultUserAgent(
            headers = original,
            userAgent = "Mozilla/5.0 Android WebView",
        )

        assertEquals(original, headers)
        assertEquals("Extension-UA", headers["user-agent"])
    }

    @Test
    fun collapsesOnlyDuplicateSlashesAtTheOriginBoundary() {
        assertEquals(
            "https://m.qidian.com/majax/rank/yuepiaolist?gender=male&pageNum=1",
            ChromiumVBookNetworkCompatibility.normalizeUrl(
                "https://m.qidian.com//majax/rank/yuepiaolist?gender=male&pageNum=1",
            ),
        )
        assertEquals(
            "https://m.qidian.com/a//b",
            ChromiumVBookNetworkCompatibility.normalizeUrl("https://m.qidian.com/a//b"),
        )
    }

    @Test
    fun leavesNonHttpAndOriginOnlyUrlsUntouched() {
        assertEquals("wss://x.example//socket", ChromiumVBookNetworkCompatibility.normalizeUrl("wss://x.example//socket"))
        assertEquals("https://x.example", ChromiumVBookNetworkCompatibility.normalizeUrl("https://x.example"))
        assertEquals("https://x.example?next=//a", ChromiumVBookNetworkCompatibility.normalizeUrl("https://x.example?next=//a"))
    }
}
