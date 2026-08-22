package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceWebViewCookieReadPolicyTest {
    @Test
    fun stableMatchingSourceReadsCookieInline() {
        val snapshot = SourceWebViewCookieSnapshot("source-a", generation = 4L, inFlightBrowserCalls = 0)
        var called = false

        val result = SourceWebViewCookieReadPolicy.read(
            sourceId = "source-a",
            requestUrl = "https://example.com/story",
            snapshot = { snapshot },
            readCookieHeader = {
                called = true
                "sid=abc"
            },
        )

        assertTrue(called)
        assertEquals("sid=abc", result)
    }

    @Test
    fun browserCallInFlightReturnsImmediatelyWithoutTouchingCookieManager() {
        var called = false

        val result = SourceWebViewCookieReadPolicy.read(
            sourceId = "source-a",
            requestUrl = "https://example.com/story",
            snapshot = { SourceWebViewCookieSnapshot("source-a", 7L, inFlightBrowserCalls = 1) },
            readCookieHeader = {
                called = true
                "sid=should-not-be-read"
            },
        )

        assertNull(result)
        assertFalse(called)
    }

    @Test
    fun sourceMismatchAndCleartextNeverReadCookies() {
        var calls = 0
        val reader: (String) -> String? = {
            calls += 1
            "sid=unexpected"
        }

        assertNull(SourceWebViewCookieReadPolicy.read(
            sourceId = "source-b",
            requestUrl = "https://example.com/",
            snapshot = { SourceWebViewCookieSnapshot("source-a", 2L, 0) },
            readCookieHeader = reader,
        ))
        assertNull(SourceWebViewCookieReadPolicy.read(
            sourceId = "source-a",
            requestUrl = "http://example.com/",
            snapshot = { SourceWebViewCookieSnapshot("source-a", 2L, 0) },
            readCookieHeader = reader,
        ))
        assertEquals(0, calls)
    }

    @Test
    fun browserGenerationChangeDuringCookieReadDiscardsResult() {
        var generation = 10L

        val result = SourceWebViewCookieReadPolicy.read(
            sourceId = "source-a",
            requestUrl = "https://example.com/",
            snapshot = { SourceWebViewCookieSnapshot("source-a", generation, 0) },
            readCookieHeader = {
                generation += 1
                "sid=raced"
            },
        )

        assertNull(result)
    }

    @Test
    fun readerFailureIsContained() {
        val snapshot = SourceWebViewCookieSnapshot("source-a", 12L, 0)

        val result = SourceWebViewCookieReadPolicy.read(
            sourceId = "source-a",
            requestUrl = "https://example.com/",
            snapshot = { snapshot },
            readCookieHeader = { error("cookie backend failed") },
        )

        assertNull(result)
    }
}
