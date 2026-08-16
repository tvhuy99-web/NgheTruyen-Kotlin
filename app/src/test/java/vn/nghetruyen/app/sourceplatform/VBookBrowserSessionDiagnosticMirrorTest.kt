package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink

class VBookBrowserSessionDiagnosticMirrorTest {
    @Test
    fun wireIdentityCreatesSafeBrowserFlowAnchor() {
        val events = mutableListOf<DiagnosticEvent>()
        val mirror = VBookBrowserSessionDiagnosticMirror(DiagnosticSink { events += it })
        mirror.emit(event(
            name = "VBOOK_HTTP_SESSION_POLICY",
            category = DiagnosticCategory.NETWORK,
            attributes = mapOf(
                "effectiveUserAgentSource" to "webview",
                "effectiveRefererSource" to "source-origin",
                "browserUserAgentSha256" to "browser-ua-hash",
                "browserCookieBytes" to "269",
                "networkCookieBytes" to "269",
                "browserCookieNames" to "_csrfToken,fu",
                "networkCookieNames" to "_csrfToken,fu",
            ),
        ))
        mirror.emit(event(
            name = "VBOOK_HTTP_WIRE_IDENTITY",
            category = DiagnosticCategory.NETWORK,
            attributes = mapOf(
                "requestOrigin" to "https://m.qidian.com",
                "wireUserAgentSha256" to "browser-ua-hash",
                "userAgentMatchesBrowser" to "true",
                "wireCookieBytes" to "269",
                "wireCookieNames" to "_csrfToken,fu",
                "wireReferer" to "https://m.qidian.com/",
                "wireHeaderNames" to "Accept,Accept-Language,Cookie,Referer,User-Agent",
            ),
        ))

        val linked = events.single { it.name == "BROWSER_VBOOK_HTTP_SESSION_LINKED" }
        assertEquals(DiagnosticCategory.BROWSER, linked.category)
        assertEquals("browser", linked.attributes["flow"])
        assertEquals("http_session_link", linked.attributes["stage"])
        assertEquals("https://m.qidian.com", linked.attributes["url"])
        assertEquals("true", linked.attributes["userAgentMatchesBrowser"])
        assertEquals("269", linked.attributes["wireCookieBytes"])
        assertEquals("_csrfToken,fu", linked.attributes["wireCookieNames"])
        assertFalse(linked.attributes.values.any { it.contains("csrf-secret-value") })
    }

    @Test
    fun userAgentMismatchIsVisibleAsWarning() {
        val events = mutableListOf<DiagnosticEvent>()
        val mirror = VBookBrowserSessionDiagnosticMirror(DiagnosticSink { events += it })
        mirror.emit(event(
            name = "VBOOK_HTTP_SESSION_POLICY",
            category = DiagnosticCategory.NETWORK,
            attributes = mapOf("effectiveUserAgentSource" to "webview"),
        ))
        mirror.emit(event(
            name = "VBOOK_HTTP_WIRE_IDENTITY",
            category = DiagnosticCategory.NETWORK,
            attributes = mapOf("userAgentMatchesBrowser" to "false"),
        ))

        val linked = events.single { it.name == "BROWSER_VBOOK_HTTP_SESSION_LINKED" }
        assertEquals(DiagnosticSeverity.WARN, linked.severity)
        assertTrue(linked.attributes["detail"].orEmpty().contains("uaMatch=false"))
    }

    private fun event(
        name: String,
        category: DiagnosticCategory,
        attributes: Map<String, String>,
    ) = DiagnosticEvent(
        timestampEpochMs = 1L,
        traceId = "qidian-trace",
        sourceId = "vbook.extension.qidian",
        category = category,
        name = name,
        attributes = attributes,
    )
}
