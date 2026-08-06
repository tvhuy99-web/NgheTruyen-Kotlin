package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.app.sources.SourceSessionStore
import vn.nghetruyen.source.api.SourceCookiePartition

class SourceSessionCookiePartition(
    private val store: SourceSessionStore,
) : SourceCookiePartition {
    override fun readCookieHeader(sourceId: String): String? = store.cookieHeader(sourceId)
    override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) =
        store.mergeSetCookieHeaders(sourceId, setCookieHeaders)
    override fun clear(sourceId: String) = store.clear(sourceId)
}
