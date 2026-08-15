package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult

/**
 * Promotes only high-confidence tiny 2xx application-error envelopes to an explicit network error.
 *
 * This deliberately leaves larger/business responses untouched so extensions may inspect them.  It
 * targets the anti-bot/session failure pattern seen after a Browser-derived CSRF token: HTTP 200,
 * a tiny JSON error object, and no usable payload.  Failing here preserves the real cause instead of
 * allowing a later script dereference such as data.pageNum to hide it.
 */
internal class VBookSuspiciousResponseFailFastBroker(
    private val delegate: SourceNetworkBroker,
    private val cookies: SourceCookiePartition,
) : SourceNetworkBroker {
    override fun execute(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ): SourcePlatformResult<SourceNetworkResponse> {
        val result = delegate.execute(manifest, request)
        if (result !is SourcePlatformResult.Success) return result
        if (manifest.capabilities.cookies != SourceCookieMode.BROWSER_SHARED) return result
        if (cookies.readCookieHeader(manifest.id, request.url).isNullOrBlank()) return result

        val response = result.value
        val shape = VBookHttpSessionCompatibility.classify(response)
        if (!shape.suspicious2xx || response.body.size !in 1..128) return result

        return SourcePlatformResult.Failure(SourcePlatformFailure(
            code = SourceErrorCode.NETWORK_HTTP_ERROR,
            message = buildString {
                append("VBOOK_HTTP_SESSION_PAYLOAD_INVALID")
                append(":status=").append(response.statusCode)
                append(":bytes=").append(response.body.size)
                if (shape.applicationCode.isNotBlank()) append(":code=").append(shape.applicationCode)
                append(":reason=").append(shape.suspicionReason)
            },
            traceId = request.traceId,
        ))
    }
}
