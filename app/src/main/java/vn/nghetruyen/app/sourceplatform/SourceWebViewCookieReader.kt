package vn.nghetruyen.app.sourceplatform

/** Source-scoped access to cookies that are still authoritative in Android's WebView profile. */
fun interface SourceWebViewCookieReader {
    fun readWebViewCookieHeader(sourceId: String, requestUrl: String): String?
}
