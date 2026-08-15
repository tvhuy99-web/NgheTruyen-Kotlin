package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.util.concurrent.ConcurrentHashMap

/**
 * Adds one safe browser-flow summary for every Chromium/vBook HTTP request.
 *
 * The detailed HTTP identity stays in the network flow, while this mirror gives the diagnostic ZIP
 * a browser-session anchor so `browser_sessions.json` no longer loses the Browser -> HTTP hand-off.
 * Cookie values and token values are never copied; only names, byte counts and header identity are.
 */
internal class VBookBrowserSessionDiagnosticMirror(
    private val delegate: DiagnosticSink,
) : DiagnosticSink {
    private val policyByTrace = ConcurrentHashMap<String, Map<String, String>>()

    override fun emit(event: DiagnosticEvent) {
        when (event.name) {
            "VBOOK_HTTP_SESSION_POLICY" -> policyByTrace[event.traceId] = event.attributes
            "CHROMIUM_ACTION_COMPLETED", "CHROMIUM_ACTION_FAILED" -> policyByTrace.remove(event.traceId)
        }
        delegate.emit(event)

        if (event.name != "VBOOK_HTTP_WIRE_IDENTITY") return
        val policy = policyByTrace[event.traceId].orEmpty()
        val uaMatches = event.attributes["userAgentMatchesBrowser"].equals("true", ignoreCase = true)
        val browserUaExpected = policy["effectiveUserAgentSource"] == "webview"
        val severity = if (browserUaExpected && !uaMatches) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO
        val requestOrigin = event.attributes["requestOrigin"].orEmpty()
        val wireCookieBytes = event.attributes["wireCookieBytes"].orEmpty()
        val browserCookieBytes = policy["browserCookieBytes"].orEmpty()
        val networkCookieBytes = policy["networkCookieBytes"].orEmpty()
        delegate.emit(DiagnosticEvent(
            timestampEpochMs = event.timestampEpochMs,
            traceId = event.traceId,
            sourceId = event.sourceId,
            sourceVersion = event.sourceVersion,
            category = DiagnosticCategory.BROWSER,
            name = "BROWSER_VBOOK_HTTP_SESSION_LINKED",
            severity = severity,
            attributes = mapOf(
                "flow" to "browser",
                "stage" to "http_session_link",
                "sessionId" to "vbook-http:${event.traceId.take(48)}",
                "url" to requestOrigin,
                "requestOrigin" to requestOrigin,
                "effectiveUserAgentSource" to policy["effectiveUserAgentSource"].orEmpty(),
                "effectiveRefererSource" to policy["effectiveRefererSource"].orEmpty(),
                "browserUserAgentSha256" to policy["browserUserAgentSha256"].orEmpty(),
                "wireUserAgentSha256" to event.attributes["wireUserAgentSha256"].orEmpty(),
                "userAgentMatchesBrowser" to uaMatches.toString(),
                "browserCookieBytes" to browserCookieBytes,
                "networkCookieBytes" to networkCookieBytes,
                "wireCookieBytes" to wireCookieBytes,
                "browserCookieNames" to policy["browserCookieNames"].orEmpty(),
                "networkCookieNames" to policy["networkCookieNames"].orEmpty(),
                "wireCookieNames" to event.attributes["wireCookieNames"].orEmpty(),
                "wireReferer" to event.attributes["wireReferer"].orEmpty().take(700),
                "wireHeaderNames" to event.attributes["wireHeaderNames"].orEmpty(),
                "detail" to "Browser->HTTP session parity: uaMatch=$uaMatches browserCookieBytes=$browserCookieBytes networkCookieBytes=$networkCookieBytes wireCookieBytes=$wireCookieBytes",
            ),
        ))
    }
}
