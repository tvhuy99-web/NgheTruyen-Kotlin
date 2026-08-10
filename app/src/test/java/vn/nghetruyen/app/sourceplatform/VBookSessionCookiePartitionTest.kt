package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.nghetruyen.app.sources.InMemorySourceSessionStore
import vn.nghetruyen.source.network.PartitionedSourceCookieJar

class VBookSessionCookiePartitionTest {
    @Test
    fun `manual login cookies reach vBook and network cookies mirror back`() {
        val sessions = InMemorySourceSessionStore().apply {
            replaceCookieHeader(SOURCE_ID, "manual=one")
        }
        val cookies = VBookSessionCookiePartition(PartitionedSourceCookieJar(), sessions)

        assertEquals("manual=one", cookies.readCookieHeader(SOURCE_ID, URL))
        cookies.mergeSetCookieHeaders(SOURCE_ID, URL, listOf("network=two; Path=/; Secure"))
        assertEquals("manual=one; network=two", sessions.cookieHeader(SOURCE_ID))

        sessions.clear(SOURCE_ID)
        assertNull(cookies.readCookieHeader(SOURCE_ID, URL))
    }

    companion object {
        private const val SOURCE_ID = "vn.nghetruyen.sources.vbooklogin"
        private const val URL = "https://example.org/login"
    }
}
