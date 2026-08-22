package vn.nghetruyen.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionedSourceCookieJarTest {
    @Test fun `cookies are partitioned and matched by host path secure and expiry`() {
        var now = 1_000L
        val jar = PartitionedSourceCookieJar(clockMs = { now })
        jar.mergeSetCookieHeaders(
            "vn.nghetruyen.sources.test",
            "https://www.example.org/account/login",
            listOf(
                "sid=abc; Path=/; Secure; HttpOnly",
                "pref=dark; Domain=example.org; Path=/account; Max-Age=10",
                "bad=x; Domain=evil.org",
            ),
        )
        assertEquals("pref=dark; sid=abc", jar.readCookieHeader("vn.nghetruyen.sources.test", "https://www.example.org/account/me"))
        assertTrue(jar.readCookieHeader("vn.nghetruyen.sources.test", "https://api.example.org/").isNullOrBlank())
        assertTrue(jar.readCookieHeader("vn.nghetruyen.sources.other", "https://www.example.org/").isNullOrBlank())
        now += 11_000
        assertEquals("sid=abc", jar.readCookieHeader("vn.nghetruyen.sources.test", "https://www.example.org/account/me"))
    }

    @Test fun `max age zero deletes cookie`() {
        val jar = PartitionedSourceCookieJar()
        val id = "vn.nghetruyen.sources.test"
        jar.mergeSetCookieHeaders(id, "https://example.org/", listOf("sid=abc; Path=/"))
        assertFalse(jar.readCookieHeader(id, "https://example.org/").isNullOrBlank())
        jar.mergeSetCookieHeaders(id, "https://example.org/", listOf("sid=gone; Max-Age=0; Path=/"))
        assertTrue(jar.readCookieHeader(id, "https://example.org/").isNullOrBlank())
    }

    @Test fun `cleartext urls keep ordinary cookies but never expose secure cookies`() {
        val jar = PartitionedSourceCookieJar()
        val id = "vn.nghetruyen.sources.test"
        jar.mergeSetCookieHeaders(
            id,
            "http://example.org/login",
            listOf(
                "sid=clear; Path=/",
                "secure=secret; Path=/; Secure",
            ),
        )

        assertEquals("sid=clear", jar.readCookieHeader(id, "http://example.org/account"))
        val exported = jar.exportSetCookieHeaders(id, "http://example.org/account")
        assertEquals(1, exported.size)
        assertTrue(exported.single().startsWith("sid=clear"))
        assertEquals(
            setOf("sid=clear", "secure=secret"),
            jar.readCookieHeader(id, "https://example.org/account").orEmpty().split("; ").toSet(),
        )
    }

    @Test fun `cookie partition still rejects non http urls`() {
        val jar = PartitionedSourceCookieJar()
        val id = "vn.nghetruyen.sources.test"
        listOf("ftp://example.org/file", "about:blank", "/relative/path").forEach { url ->
            val failure = runCatching { jar.readCookieHeader(id, url) }.exceptionOrNull()
            assertTrue("Expected SOURCE_COOKIE_URL_INVALID for $url", failure is IllegalArgumentException)
            assertEquals("SOURCE_COOKIE_URL_INVALID", failure?.message)
        }
    }
}
