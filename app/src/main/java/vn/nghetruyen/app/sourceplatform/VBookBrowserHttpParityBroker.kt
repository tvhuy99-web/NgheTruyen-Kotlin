package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.vbook.VBookRawNetworkBroker
import java.util.Locale

/** Chromium/vBook browser-session parity around an already wrapped VBookRawNetworkBroker. */
internal class VBookBrowserHttpParityBroker(
    private val delegate: SourceNetworkBroker,
    private val cookies: SourceCookiePartition,
    private val browserCookieReader: (sourceId: String, requestUrl: String) -> String?,
    private val browserCookieWriter: (sourceId: String, responseUrl: String, setCookies: List<String>) -> Unit,
    private val browserUserAgent: () -> String,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SourceNetworkBroker {
    override fun execute(manifest: SourceManifest, request: SourceNetworkRequest): SourcePlatformResult<SourceNetworkResponse> {
        if (!request.headers.headerValue(VBookRawNetworkBroker.INTERNAL_OPERATION).isNullOrBlank()) {
            return delegate.execute(manifest, request)
        }

        val sharedCookies = manifest.capabilities.cookies == SourceCookieMode.BROWSER_SHARED
        val browserCookieHeader = if (sharedCookies && request.url.startsWith("https://", true)) {
            runCatching { browserCookieReader(manifest.id, request.url) }.getOrNull().orEmpty()
        } else ""
        if (browserCookieHeader.isNotBlank()) {
            val projected = browserCookieHeader.toSetCookieLines()
            if (projected.isNotEmpty()) cookies.mergeSetCookieHeaders(manifest.id, request.url, projected)
        }
        val networkCookieHeader = if (sharedCookies) {
            runCatching { cookies.readCookieHeader(manifest.id, request.url) }.getOrNull().orEmpty()
        } else ""
        val hadSessionBeforeRequest = browserCookieHeader.isNotBlank() || networkCookieHeader.isNotBlank()
        val browserUa = runCatching { browserUserAgent() }.getOrDefault("").trim()
        val projection = VBookHttpSessionCompatibility.projectHeaders(
            original = request.headers,
            browserUserAgent = browserUa,
            defaultReferer = VBookHttpSessionCompatibility.defaultReferer(manifest, request.url),
        )

        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = clockMs(), traceId = request.traceId, sourceId = manifest.id,
            sourceVersion = manifest.version.toString(), category = DiagnosticCategory.NETWORK,
            name = "VBOOK_HTTP_SESSION_POLICY", severity = DiagnosticSeverity.INFO,
            attributes = mapOf(
                "cookieMode" to manifest.capabilities.cookies.name.lowercase(Locale.ROOT),
                "browserCookieBytes" to browserCookieHeader.toByteArray().size.toString(),
                "browserCookieNames" to VBookHttpSessionCompatibility.cookieNames(browserCookieHeader).joinToString(","),
                "networkCookieBytes" to networkCookieHeader.toByteArray().size.toString(),
                "networkCookieNames" to VBookHttpSessionCompatibility.cookieNames(networkCookieHeader).joinToString(","),
                "browserJarByteCount" to browserCookieHeader.toByteArray().size.toString(),
                "networkJarByteCount" to networkCookieHeader.toByteArray().size.toString(),
                "hadSessionBeforeRequest" to hadSessionBeforeRequest.toString(),
                "effectiveUserAgentSource" to projection.userAgentSource,
                "effectiveRefererSource" to projection.refererSource,
                "effectiveAcceptSource" to projection.acceptSource,
                "effectiveAcceptLanguageSource" to projection.acceptLanguageSource,
                "browserUserAgent" to browserUa.take(700),
                "browserUserAgentSha256" to VBookHttpSessionCompatibility.sha256(browserUa),
                "referer" to projection.headers.headerValue("Referer").orEmpty().take(700),
                "requestOrigin" to VBookHttpSessionCompatibility.originOf(request.url),
                "projectedHeaderNames" to projection.headers.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(","),
            ),
        ))

        val result = delegate.execute(manifest, request.copy(headers = projection.headers))
        if (result !is SourcePlatformResult.Success) return result
        val wrappedResponse = result.value
        val inspection = VBookRawEnvelopeInspector.inspect(wrappedResponse)
        val upstreamResponse = inspection.response

        val wireCookie = wrappedResponse.requestHeaders.headerValues("Cookie").joinToString("; ")
        val wireUserAgent = wrappedResponse.requestHeaders.headerValues("User-Agent").firstOrNull().orEmpty()
        val wireReferer = wrappedResponse.requestHeaders.headerValues("Referer").firstOrNull().orEmpty()
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = clockMs(), traceId = request.traceId, sourceId = manifest.id,
            sourceVersion = manifest.version.toString(), category = DiagnosticCategory.NETWORK,
            name = "VBOOK_HTTP_WIRE_IDENTITY", severity = DiagnosticSeverity.INFO,
            attributes = mapOf(
                "wireCookieBytes" to wireCookie.toByteArray().size.toString(),
                "wireCookieNames" to VBookHttpSessionCompatibility.cookieNames(wireCookie).joinToString(","),
                "wireJarByteCount" to wireCookie.toByteArray().size.toString(),
                "wireUserAgent" to wireUserAgent.take(700),
                "wireUserAgentSha256" to VBookHttpSessionCompatibility.sha256(wireUserAgent),
                "wireReferer" to wireReferer.take(700),
                "wireHeaderNames" to wrappedResponse.requestHeaders.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(","),
                "userAgentMatchesBrowser" to (browserUa.isNotBlank() && wireUserAgent == browserUa).toString(),
                "requestOrigin" to VBookHttpSessionCompatibility.originOf(wrappedResponse.requestUrl ?: request.url),
            ),
        ))

        val setCookies = upstreamResponse.headers.entries
            .filter { (name, _) -> name.equals("set-cookie", true) }
            .flatMap { (_, values) -> values.flatMap(::splitCombinedSetCookieHeader) }
            .filter(String::isNotBlank)
        if (sharedCookies && setCookies.isNotEmpty() && upstreamResponse.finalUrl.startsWith("https://", true)) {
            runCatching { browserCookieWriter(manifest.id, upstreamResponse.finalUrl, setCookies) }
            diagnostics.emit(DiagnosticEvent(
                timestampEpochMs = clockMs(), traceId = request.traceId, sourceId = manifest.id,
                sourceVersion = manifest.version.toString(), category = DiagnosticCategory.NETWORK,
                name = "VBOOK_HTTP_COOKIE_MIRROR", severity = DiagnosticSeverity.INFO,
                attributes = mapOf(
                    "direction" to "http-to-webview", "setCookieCount" to setCookies.size.toString(),
                    "responseOrigin" to VBookHttpSessionCompatibility.originOf(upstreamResponse.finalUrl),
                    "inspectedLayer" to if (inspection.metadataEnvelope) "raw-upstream-envelope" else "raw-response",
                ),
            ))
        }

        val shape = VBookHttpSessionCompatibility.classify(upstreamResponse)
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = clockMs(), traceId = request.traceId, sourceId = manifest.id,
            sourceVersion = manifest.version.toString(), category = DiagnosticCategory.NETWORK,
            name = "VBOOK_HTTP_RESPONSE_SHAPE",
            severity = if (shape.suspicious2xx) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            attributes = mapOf(
                "status" to upstreamResponse.statusCode.toString(),
                "responseBytes" to inspection.rawSize.toString(),
                "contentType" to shape.contentType.take(300), "jsonKind" to shape.jsonKind,
                "observedJsonKeys" to shape.observedJsonKeys.joinToString(","),
                "applicationCode" to shape.applicationCode.take(120),
                "suspicious2xx" to shape.suspicious2xx.toString(), "suspicionReason" to shape.suspicionReason,
                "sanitizedPreview" to shape.sanitizedPreview.take(512),
                "bodySha256" to VBookHttpSessionCompatibility.sha256(upstreamResponse.body),
                "responseOrigin" to VBookHttpSessionCompatibility.originOf(upstreamResponse.finalUrl),
                "inspectedLayer" to if (inspection.metadataEnvelope) "raw-upstream-envelope" else "raw-response",
                "metadataEnvelopeBytes" to wrappedResponse.body.size.toString(),
            ),
        ))

        if (sharedCookies && hadSessionBeforeRequest && shape.suspicious2xx && inspection.rawSize in 1..128) {
            return SourcePlatformResult.Failure(SourcePlatformFailure(
                code = SourceErrorCode.NETWORK_HTTP_ERROR,
                message = buildString {
                    append("VBOOK_HTTP_SESSION_PAYLOAD_INVALID:status=").append(upstreamResponse.statusCode)
                    append(":bytes=").append(inspection.rawSize)
                    if (shape.applicationCode.isNotBlank()) append(":code=").append(shape.applicationCode)
                    append(":reason=").append(shape.suspicionReason)
                },
                traceId = request.traceId,
            ))
        }
        return result
    }
}

internal data class VBookRawResponseInspection(
    val response: SourceNetworkResponse,
    val rawSize: Int,
    val metadataEnvelope: Boolean,
)

internal object VBookRawEnvelopeInspector {
    fun inspect(response: SourceNetworkResponse): VBookRawResponseInspection {
        val root = runCatching {
            JsonCodec.parse(response.bodyText(), maxDepth = 32, maxNodes = 20_000) as? JsonValue.Obj
        }.getOrNull()
        if (root?.int("__ngheVBookFetch") != 1) return VBookRawResponseInspection(response, response.body.size, false)
        val body = root.string("body") ?: return VBookRawResponseInspection(response, response.body.size, false)
        val headers = linkedMapOf<String, List<String>>()
        root.obj("headers")?.values?.forEach { (name, value) ->
            val text = (value as? JsonValue.Str)?.value ?: return@forEach
            headers[name] = listOf(text)
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        return VBookRawResponseInspection(
            response.copy(body = bytes, charsetName = Charsets.UTF_8.name(), headers = headers),
            root.int("rawSize")?.coerceAtLeast(0) ?: bytes.size,
            true,
        )
    }
}

private fun String.toSetCookieLines(): List<String> = split(';').map(String::trim)
    .filter { it.isNotBlank() && it.contains('=') }.take(128)
    .map { "$it; Path=/; Secure; SameSite=Lax" }

private fun splitCombinedSetCookieHeader(value: String): List<String> = value
    .split(Regex(",\\s*(?=[A-Za-z0-9!#%&'*+.^_`|~-]+=)"))
    .map(String::trim).filter(String::isNotBlank)

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, true) }?.value
private fun Map<String, List<String>>.headerValues(name: String): List<String> =
    entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
