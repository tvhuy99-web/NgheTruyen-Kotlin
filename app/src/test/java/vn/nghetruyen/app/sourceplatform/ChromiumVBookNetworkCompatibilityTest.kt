package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromiumVBookNetworkCompatibilityTest {
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
