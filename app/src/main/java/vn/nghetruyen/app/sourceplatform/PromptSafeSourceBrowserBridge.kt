package vn.nghetruyen.app.sourceplatform

import android.webkit.CookieManager
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Chromium-facing browser bridge that keeps synchronous prompt cookie reads off Browser locks/main.
 *
 * AndroidSourceBrowserBroker deliberately serializes real WebView operations. Chromium's prompt
 * bridge, however, already blocks Android's main thread while the host answers. Calling the broker's
 * cookie reader from that prompt would try to acquire the browser lock and then post back to the
 * blocked main thread. This bridge keeps only a source authority snapshot and reads CookieManager
 * directly when no browser action is in flight.
 */
internal class PromptSafeSourceBrowserBridge(
    private val delegate: SourceBrowserBroker,
    private val cookieHeaderReader: (String) -> String? = { url ->
        CookieManager.getInstance().getCookie(url)
    },
) : SourceBrowserBroker, SourceWebViewCookieReader {
    private val activeSourceId = AtomicReference<String?>(null)
    private val generation = AtomicLong(0L)
    private val inFlightBrowserCalls = AtomicInteger(0)

    override fun execute(
        manifest: SourceManifest,
        request: SourceBrowserRequest,
    ): SourcePlatformResult<SourceBrowserResponse> {
        inFlightBrowserCalls.incrementAndGet()
        generation.incrementAndGet()
        return try {
            delegate.execute(manifest, request).also { result ->
                when (result) {
                    is SourcePlatformResult.Success -> {
                        if (request.action == SourceBrowserAction.CLOSE_SESSION ||
                            request.action == SourceBrowserAction.CLEAR_SESSION
                        ) {
                            activeSourceId.set(null)
                        } else {
                            activeSourceId.set(manifest.id)
                        }
                    }
                    is SourcePlatformResult.Failure -> activeSourceId.set(null)
                }
            }
        } finally {
            generation.incrementAndGet()
            inFlightBrowserCalls.decrementAndGet()
        }
    }

    override fun readWebViewCookieHeader(sourceId: String, requestUrl: String): String? =
        SourceWebViewCookieReadPolicy.read(
            sourceId = sourceId,
            requestUrl = requestUrl,
            snapshot = {
                activeSourceId.get()?.let { activeSource ->
                    SourceWebViewCookieSnapshot(
                        sourceId = activeSource,
                        generation = generation.get(),
                        inFlightBrowserCalls = inFlightBrowserCalls.get(),
                    )
                }
            },
            readCookieHeader = cookieHeaderReader,
        )
}
