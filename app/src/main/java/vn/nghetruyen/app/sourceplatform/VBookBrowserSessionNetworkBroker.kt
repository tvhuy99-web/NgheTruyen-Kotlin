package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Chromium/vBook-only network compatibility decorator.
 *
 * vBook scripts commonly obtain a CSRF token or anti-bot session in Browser/WebView and then call a
 * JSON endpoint through fetch(). The request must therefore keep the same browser identity. The
 * generic SourceNetworkBroker intentionally stays neutral; this decorator projects the WebView
 * session into vBook HTTP without changing semantics for native SourcePacks.
 */
internal class VBookBrowserSessionNetworkBroker(
    private val delegate: SourceNetworkBroker,
    private val cookies: SourceCookiePartition,
    private val browserCookieReader: (sourceId: String, requestUrl: String) -> String?,
    private val browserCookieWriter: (sourceId: String, responseUrl: String, setCookies: List<String>) -> Unit,
    private val browserUserAgent: () -> String,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SourceNetworkBroker {
    override fun execute(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ): SourcePlatformResult<SourceNetworkResponse> {
        val sharedCookies = manifest.capabilities.cookies == SourceCookieMode.BROWSER_SHARED
        val browserCookieHeader = if (sharedCookies && request.url.startsWith("https://", ignoreCase = true)) {
            runCatching { browserCookieReader(manifest.id, request.url) }.getOrNull().orEmpty()
        } else {
            ""
        }
        if (browserCookieHeader.isNotBlank()) {
            val projectedCookies = browserCookieHeader.toSetCookieLines()
            if (projectedCookies.isNotEmpty()) {
                cookies.mergeSetCookieHeaders(manifest.id, request.url, projectedCookies)
            }
        }

        val networkCookieHeader = if (sharedCookies) {
            cookies.readCookieHeader(manifest.id, request.url).orEmpty()
        } else {
            ""
        }
        val browserUa = runCatching { browserUserAgent() }.getOrDefault("").trim()
        val projection = VBookHttpSessionCompatibility.projectHeaders(
            original = request.headers,
            browserUserAgent = browserUa,
            defaultReferer = VBookHttpSessionCompatibility.defaultReferer(manifest, request.url),
        )

        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = clockMs(),
            traceId = request.traceId,
            sourceId = manifest.id,
            sourceVersion = manifest.version.toString(),
            category = DiagnosticCategory.NETWORK,
            name = "VBOOK_HTTP_SESSION_POLICY",
            severity = DiagnosticSeverity.INFO,
            attributes = mapOf(
                "cookieMode" to manifest.capabilities.cookies.name.lowercase(Locale.ROOT),
                "browserCookieBytes" to browserCookieHeader.toByteArray(Charsets.UTF_8).size.toString(),
                "browserCookieNames" to VBookHttpSessionCompatibility.cookieNames(browserCookieHeader).joinToString(","),
                "networkCookieBytes" to networkCookieHeader.toByteArray(Charsets.UTF_8).size.toString(),
                "networkCookieNames" to VBookHttpSessionCompatibility.cookieNames(networkCookieHeader).joinToString(","),
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

        val response = result.value
        val setCookies = response.headers.entries
            .filter { (name, _) -> name.equals("set-cookie", ignoreCase = true) }
            .flatMap { it.value }
            .filter(String::isNotBlank)
        if (sharedCookies && setCookies.isNotEmpty() && response.finalUrl.startsWith("https://", ignoreCase = true)) {
            runCatching { browserCookieWriter(manifest.id, response.finalUrl, setCookies) }
            diagnostics.emit(DiagnosticEvent(
                timestampEpochMs = clockMs(),
                traceId = request.traceId,
                sourceId = manifest.id,
                sourceVersion = manifest.version.toString(),
                category = DiagnosticCategory.NETWORK,
                name = "VBOOK_HTTP_COOKIE_MIRROR",
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf(
                    "direction" to "http-to-webview",
                    "setCookieCount" to setCookies.size.toString(),
                    "responseOrigin" to VBookHttpSessionCompatibility.originOf(response.finalUrl),
                ),
            ))
        }

        val wireCookie = response.requestHeaders.headerValues("Cookie").joinToString("; ")
        val wireUserAgent = response.requestHeaders.headerValues("User-Agent").firstOrNull().orEmpty()
        val wireReferer = response.requestHeaders.headerValues("Referer").firstOrNull().orEmpty()
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = clockMs(),
            traceId = request.traceId,
            sourceId = manifest.id,
            sourceVersion = manifest.version.toString(),
            category = DiagnosticCategory.NETWORK,
            name = "VBOOK_HTTP_WIRE_IDENTITY",
            severity = DiagnosticSeverity.INFO,
            attributes = mapOf(
                "wireCookieBytes" to wireCookie.toByteArray(Charsets.UTF_8).size.toString(),
                "wireCookieNames" to VBookHttpSessionCompatibility.cookieNames(wireCookie).joinToString(","),
                "wireUserAgent" to wireUserAgent.take(700),
                "wireUserAgentSha256" to VBookHttpSessionCompatibility.sha256(wireUserAgent),
                "wireReferer" to wireReferer.take(700),
                "wireHeaderNames" to response.requestHeaders.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(","),
                "userAgentMatchesBrowser" to (browserUa.isNotBlank() && wireUserAgent == browserUa).toString(),
                "requestOrigin" to VBookHttpSessionCompatibility.originOf(response.requestUrl ?: request.url),
            ),
        ))

        val shape = VBookHttpSessionCompatibility.classify(response)
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = clockMs(),
            traceId = request.traceId,
            sourceId = manifest.id,
            sourceVersion = manifest.version.toString(),
            category = DiagnosticCategory.NETWORK,
            name = "VBOOK_HTTP_RESPONSE_SHAPE",
            severity = if (shape.suspicious2xx) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            attributes = mapOf(
                "status" to response.statusCode.toString(),
                "responseBytes" to response.body.size.toString(),
                "contentType" to shape.contentType.take(300),
                "jsonKind" to shape.jsonKind,
                "observedJsonKeys" to shape.observedJsonKeys.joinToString(","),
                "applicationCode" to shape.applicationCode.take(120),
                "suspicious2xx" to shape.suspicious2xx.toString(),
                "suspicionReason" to shape.suspicionReason,
                "sanitizedPreview" to shape.sanitizedPreview.take(512),
                "bodySha256" to VBookHttpSessionCompatibility.sha256(response.body),
                "responseOrigin" to VBookHttpSessionCompatibility.originOf(response.finalUrl),
            ),
        ))
        return result
    }
}

internal data class VBookHttpHeaderProjection(
    val headers: Map<String, String>,
    val userAgentSource: String,
    val refererSource: String,
    val acceptSource: String,
    val acceptLanguageSource: String,
)

internal data class VBookHttpResponseShape(
    val contentType: String,
    val jsonKind: String,
    val observedJsonKeys: List<String>,
    val applicationCode: String,
    val suspicious2xx: Boolean,
    val suspicionReason: String,
    val sanitizedPreview: String,
)

internal object VBookHttpSessionCompatibility {
    private val jsonKeyRegex = Regex("\\\"([A-Za-z0-9_.-]{1,80})\\\"\\s*:")
    private val codeRegex = Regex("\\\"(?:code|status|errno|errorCode)\\\"\\s*:\\s*(\\\"[^\\\"]{0,80}\\\"|-?[0-9]{1,12}|true|false|null)", RegexOption.IGNORE_CASE)
    private val successRegex = Regex("\\\"success\\\"\\s*:\\s*(false|0|\\\"false\\\")", RegexOption.IGNORE_CASE)
    private val sensitiveValueRegex = Regex("(?i)(\\\"(?:token|csrf|cookie|authorization|session|secret|sign|signature)[^\\\"]*\\\"\\s*:\\s*)\\\"[^\\\"]*\\\"")
    private val payloadKeyRegex = Regex("\\\"(?:data|result|list|items|records|pageNum|pageData)\\\"\\s*:", RegexOption.IGNORE_CASE)
    private val errorKeyRegex = Regex("\\\"(?:error|errors|errMsg|errorMessage)\\\"\\s*:", RegexOption.IGNORE_CASE)
    private val successfulCodes = setOf("0", "200", "ok", "success", "true", "null")

    fun projectHeaders(
        original: Map<String, String>,
        browserUserAgent: String,
        defaultReferer: String?,
    ): VBookHttpHeaderProjection {
        val out = LinkedHashMap<String, String>()
        original.forEach { (name, value) -> out[name] = value }

        val userAgentSource = if (out.hasHeader("User-Agent")) {
            "extension"
        } else {
            browserUserAgent.takeIf(String::isNotBlank)?.let { out["User-Agent"] = it }
            if (browserUserAgent.isNotBlank()) "webview" else "transport-fallback"
        }
        val acceptSource = if (out.hasHeader("Accept")) {
            "extension"
        } else {
            out["Accept"] = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
            "lua-vbook-default"
        }
        val acceptLanguageSource = if (out.hasHeader("Accept-Language")) {
            "extension"
        } else {
            out["Accept-Language"] = "vi-VN,vi;q=0.9,en-US;q=0.7,en;q=0.6"
            "lua-vbook-default"
        }
        val refererSource = if (out.hasHeader("Referer")) {
            "extension"
        } else if (!defaultReferer.isNullOrBlank()) {
            out["Referer"] = defaultReferer
            "source-origin"
        } else {
            "none"
        }
        return VBookHttpHeaderProjection(
            headers = out,
            userAgentSource = userAgentSource,
            refererSource = refererSource,
            acceptSource = acceptSource,
            acceptLanguageSource = acceptLanguageSource,
        )
    }

    fun defaultReferer(manifest: SourceManifest, requestUrl: String): String? {
        val origin = manifest.origins.firstOrNull { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: originOf(requestUrl).takeIf(String::isNotBlank)
        return origin?.trimEnd('/')?.plus('/')
    }

    fun classify(response: SourceNetworkResponse): VBookHttpResponseShape {
        val contentType = response.headers.entries
            .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
        val raw = response.bodyText().trim()
        val looksJson = contentType.contains("json", ignoreCase = true) || raw.startsWith('{') || raw.startsWith('[')
        val jsonKind = when {
            !looksJson -> "non-json"
            raw.startsWith('{') -> "object"
            raw.startsWith('[') -> "array"
            else -> "unknown-json"
        }
        val keys = if (looksJson) {
            jsonKeyRegex.findAll(raw.take(4096)).map { it.groupValues[1] }.distinct().take(32).toList()
        } else {
            emptyList()
        }
        val applicationCode = codeRegex.find(raw.take(2048))?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"').orEmpty()
        val codeSignalsFailure = applicationCode.isNotBlank() && applicationCode.lowercase(Locale.ROOT) !in successfulCodes
        val successSignalsFailure = successRegex.containsMatchIn(raw.take(2048))
        val hasErrorWithoutPayload = errorKeyRegex.containsMatchIn(raw.take(2048)) && !payloadKeyRegex.containsMatchIn(raw.take(4096))
        val smallApplicationEnvelope = response.body.size in 1..512 && jsonKind == "object"
        val suspicious = response.statusCode in 200..299 && smallApplicationEnvelope &&
            (codeSignalsFailure || successSignalsFailure || hasErrorWithoutPayload)
        val reason = when {
            !suspicious -> "none"
            codeSignalsFailure -> "application-code:$applicationCode"
            successSignalsFailure -> "success=false"
            else -> "error-envelope-without-payload"
        }
        return VBookHttpResponseShape(
            contentType = contentType,
            jsonKind = jsonKind,
            observedJsonKeys = keys,
            applicationCode = applicationCode,
            suspicious2xx = suspicious,
            suspicionReason = reason,
            sanitizedPreview = if (suspicious) sanitizePreview(raw) else "",
        )
    }

    fun cookieNames(header: String): List<String> = header.split(';')
        .map(String::trim)
        .mapNotNull { token -> token.substringBefore('=', "").trim().takeIf(String::isNotBlank) }
        .distinct()
        .take(128)

    fun originOf(rawUrl: String): String = runCatching {
        val uri = URI(rawUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching ""
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching ""
        val defaultPort = if (scheme == "https") 443 else 80
        buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1 && uri.port != defaultPort) append(':').append(uri.port)
        }
    }.getOrDefault("")

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun sanitizePreview(raw: String): String = sensitiveValueRegex
        .replace(raw.take(512)) { match -> match.groupValues[1] + "\"<redacted>\"" }
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Adds the most recent suspicious 2xx HTTP envelope to a later Chromium script failure. */
internal class VBookHttpParityDiagnosticSink(
    private val delegate: DiagnosticSink,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : DiagnosticSink {
    private data class Suspicion(
        val timestampEpochMs: Long,
        val attributes: Map<String, String>,
    )

    private val suspiciousByTrace = ConcurrentHashMap<String, Suspicion>()

    override fun emit(event: DiagnosticEvent) {
        when {
            event.name == "VBOOK_HTTP_RESPONSE_SHAPE" && event.attributes["suspicious2xx"] == "true" -> {
                suspiciousByTrace[event.traceId] = Suspicion(
                    timestampEpochMs = event.timestampEpochMs,
                    attributes = mapOf(
                        "likelyRootCause" to "VBOOK_HTTP_SESSION_PAYLOAD_INVALID",
                        "httpStatus" to event.attributes["status"].orEmpty(),
                        "httpResponseBytes" to event.attributes["responseBytes"].orEmpty(),
                        "httpApplicationCode" to event.attributes["applicationCode"].orEmpty(),
                        "httpSuspicionReason" to event.attributes["suspicionReason"].orEmpty(),
                        "httpSanitizedPreview" to event.attributes["sanitizedPreview"].orEmpty(),
                        "httpBodySha256" to event.attributes["bodySha256"].orEmpty(),
                    ),
                )
                delegate.emit(event)
            }
            event.name == "CHROMIUM_ACTION_FAILED" -> {
                val suspicion = suspiciousByTrace.remove(event.traceId)
                    ?.takeIf { clockMs() - it.timestampEpochMs <= 120_000L }
                delegate.emit(if (suspicion == null) event else event.copy(
                    attributes = event.attributes + suspicion.attributes,
                ))
            }
            event.name == "CHROMIUM_ACTION_COMPLETED" -> {
                suspiciousByTrace.remove(event.traceId)
                delegate.emit(event)
            }
            else -> delegate.emit(event)
        }
    }
}

private fun String.toSetCookieLines(): List<String> = split(';')
    .map(String::trim)
    .filter { it.isNotBlank() && it.contains('=') }
    .take(128)
    .map { "$it; Path=/; Secure; SameSite=Lax" }

private fun Map<String, String>.hasHeader(name: String): Boolean = keys.any { it.equals(name, ignoreCase = true) }
private fun Map<String, String>.headerValue(name: String): String? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
private fun Map<String, List<String>>.headerValues(name: String): List<String> = entries
    .firstOrNull { it.key.equals(name, ignoreCase = true) }
    ?.value.orEmpty()
