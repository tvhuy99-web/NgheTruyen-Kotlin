package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink

/**
 * Keeps known anti-bot selector waits from hammering a WebView for the full source timeout.
 *
 * TruyenFull can fall back from a native HTTP 403 into a Cloudflare browser challenge. The source
 * then waits for its story selector for tens of seconds. A stale story-open job can survive while
 * the user opens another story, generating hundreds of evaluateJavascript calls. Bound that one
 * high-risk wait without changing normal browser operations or other sources.
 */
internal class SourceBrowserSafetyGuard(
    private val delegate: SourceBrowserBroker,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : SourceBrowserBroker, SourceWebViewCookieReader {

    override fun execute(
        manifest: SourceManifest,
        request: SourceBrowserRequest,
    ): SourcePlatformResult<SourceBrowserResponse> {
        val maxWaitMs = when {
            request.action == SourceBrowserAction.WAIT_SELECTOR &&
                manifest.id.contains("truyenfull", ignoreCase = true) -> TRUYENFULL_SELECTOR_WAIT_MAX_MS
            else -> request.timeoutMs
        }
        val guardedRequest = if (maxWaitMs < request.timeoutMs) {
            request.copy(timeoutMs = maxWaitMs)
        } else {
            request
        }
        if (guardedRequest !== request) {
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = request.traceId,
                    sourceId = manifest.id,
                    sourceVersion = manifest.version.toString(),
                    category = DiagnosticCategory.BROWSER,
                    name = "BROWSER_WAIT_SELECTOR_TIMEOUT_CAPPED",
                    severity = DiagnosticSeverity.WARN,
                    attributes = mapOf(
                        "originalTimeoutMs" to request.timeoutMs.toString(),
                        "guardedTimeoutMs" to guardedRequest.timeoutMs.toString(),
                        "selector" to request.selector.orEmpty().take(1_000),
                        "reason" to "truyenfull-stale-cloudflare-wait-guard",
                    ),
                ),
            )
        }
        return delegate.execute(manifest, guardedRequest)
    }

    override fun readWebViewCookieHeader(sourceId: String, requestUrl: String): String? =
        (delegate as? SourceWebViewCookieReader)?.readWebViewCookieHeader(sourceId, requestUrl)

    private companion object {
        const val TRUYENFULL_SELECTOR_WAIT_MAX_MS = 8_000L
    }
}
