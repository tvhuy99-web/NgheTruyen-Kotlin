package vn.nghetruyen.app.sourceplatform

/** Source-scoped access to cookies that are still authoritative in Android's WebView profile. */
fun interface SourceWebViewCookieReader {
    fun readWebViewCookieHeader(sourceId: String, requestUrl: String): String?
}

internal data class SourceWebViewCookieSnapshot(
    val sourceId: String,
    val generation: Long,
    val inFlightBrowserCalls: Int,
)

/**
 * Lock-free guard for synchronous cookie reads made from Chromium's prompt bridge.
 *
 * The cookie read must never wait for AndroidSourceBrowserBroker.operationLock or rendezvous with
 * the main thread: the caller may itself be servicing a synchronous WebView prompt while a Browser
 * replay operation is waiting for main-thread callbacks. A browser action increments generation at
 * both boundaries. The read is accepted only when no browser action is in flight and the exact
 * source/generation snapshot is unchanged after CookieManager returns.
 */
internal object SourceWebViewCookieReadPolicy {
    fun read(
        sourceId: String,
        requestUrl: String,
        snapshot: () -> SourceWebViewCookieSnapshot?,
        readCookieHeader: (String) -> String?,
    ): String? {
        if (!requestUrl.startsWith("https://", ignoreCase = true)) return null
        val before = snapshot() ?: return null
        if (before.sourceId != sourceId || before.inFlightBrowserCalls != 0) return null
        val header = runCatching { readCookieHeader(requestUrl) }.getOrNull()
        val after = snapshot() ?: return null
        if (after != before || after.inFlightBrowserCalls != 0 || after.sourceId != sourceId) return null
        return header
    }
}
