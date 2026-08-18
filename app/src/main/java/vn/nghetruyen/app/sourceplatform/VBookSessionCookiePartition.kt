package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.app.sources.SourceSessionStore
import vn.nghetruyen.source.api.SourceCookiePartition





class VBookSessionCookiePartition(
    private val delegate: SourceCookiePartition,
    private val sessions: SourceSessionStore,
) : SourceCookiePartition {
    private val initialized = mutableSetOf<String>()
    private val mirrored = mutableMapOf<String, String?>()

    @Synchronized
    override fun readCookieHeader(sourceId: String): String? = sessions.cookieHeader(sourceId)

    @Synchronized
    override fun readCookieHeader(sourceId: String, requestUrl: String): String? {
        syncFromManualLogin(sourceId, requestUrl)
        return delegate.readCookieHeader(sourceId, requestUrl)
    }

    @Synchronized
    override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) {
        sessions.mergeSetCookieHeaders(sourceId, setCookieHeaders)
        initialized.remove(sourceId)
        mirrored.remove(sourceId)
    }

    @Synchronized
    override fun mergeSetCookieHeaders(
        sourceId: String,
        responseUrl: String,
        setCookieHeaders: List<String>,
    ) {
        syncFromManualLogin(sourceId, responseUrl)
        delegate.mergeSetCookieHeaders(sourceId, responseUrl, setCookieHeaders)
        mirrorDelegate(sourceId)
    }

    @Synchronized
    override fun exportSetCookieHeaders(sourceId: String, requestUrl: String): List<String> {
        syncFromManualLogin(sourceId, requestUrl)
        return delegate.exportSetCookieHeaders(sourceId, requestUrl)
    }

    @Synchronized
    override fun clear(sourceId: String) {
        delegate.clear(sourceId)
        sessions.clear(sourceId)
        initialized += sourceId
        mirrored[sourceId] = null
    }

    private fun syncFromManualLogin(sourceId: String, requestUrl: String) {
        val manual = sessions.cookieHeader(sourceId)
        if (sourceId in initialized && manual == mirrored[sourceId]) return
        delegate.clear(sourceId)
        if (!manual.isNullOrBlank()) {
            delegate.mergeSetCookieHeaders(
                sourceId,
                requestUrl,
                manual.split(';').map(String::trim).filter(String::isNotBlank).map { cookie ->
                    "$cookie; Path=/; Secure; SameSite=Lax"
                },
            )
        }
        initialized += sourceId
        mirrored[sourceId] = manual
    }

    private fun mirrorDelegate(sourceId: String) {
        val header = delegate.readCookieHeader(sourceId)
        if (header.isNullOrBlank()) sessions.clear(sourceId) else sessions.replaceCookieHeader(sourceId, header)
        initialized += sourceId
        mirrored[sourceId] = sessions.cookieHeader(sourceId)
    }
}
