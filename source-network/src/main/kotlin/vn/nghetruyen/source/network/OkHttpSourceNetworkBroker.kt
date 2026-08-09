package vn.nghetruyen.source.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRedirectHop
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

class OkHttpSourceNetworkBroker(
    private val cookiePartition: SourceCookiePartition = SourceCookiePartition.NONE,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    client: OkHttpClient? = null,
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SourceNetworkBroker {
    private val limiter = SourceNetworkLimiter(clockMs)
    private val dns = Dns { hostname -> PublicAddressPolicy.requirePublic(resolver(hostname)) }
    private val client = client ?: OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(35))
        .callTimeout(Duration.ofSeconds(60))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ): SourcePlatformResult<SourceNetworkResponse> {
        val started = clockMs()
        return runCatching {
            require(request.sourceId == manifest.id) { "SOURCE_NETWORK_SOURCE_ID_MISMATCH" }
            val capability = manifest.capabilities.network ?: error("SOURCE_NETWORK_CAPABILITY_REQUIRED")
            val method = request.method.uppercase(Locale.ROOT)
            require(method in capability.methods) { "SOURCE_NETWORK_METHOD_DENIED:$method" }
            require(request.body.size <= capability.maxRequestBytes) { "SOURCE_NETWORK_REQUEST_TOO_LARGE" }
            SourceHeaderPolicy.validate(request.headers)
            SourceOriginPolicy.requireInitialUrl(manifest, request.url)
            diagnostics.emit(networkEvent(manifest, request, "REQUEST_STARTED", attributes = mapOf(
                "method" to method,
                "origin" to SourceOriginPolicy.originOf(SourceOriginPolicy.requireInitialUrl(manifest, request.url)),
                "requestBytes" to request.body.size.toString(),
                "headerNames" to request.headers.keys.sorted().joinToString(","),
            )))
            require(request.timeoutMs in 100L..120_000L) { "SOURCE_NETWORK_TIMEOUT_INVALID" }
            val deadlineMs = started + request.timeoutMs
            limiter.run(manifest.id, capability, request.timeoutMs) {
                val remainingMs = deadlineMs - clockMs()
                require(remainingMs > 0L) { "SOURCE_NETWORK_TIMEOUT" }
                executeRedirectLoop(
                    manifest = manifest,
                    initial = request.copy(method = method, timeoutMs = remainingMs),
                    maxResponseBytes = capability.maxResponseBytes,
                    started = started,
                    deadlineMs = deadlineMs,
                )
            }
        }.fold(
            onSuccess = { response ->
                diagnostics.emit(networkEvent(manifest, request, "REQUEST_COMPLETED", durationMs = response.timing.totalDurationMs, attributes = mapOf(
                    "status" to response.statusCode.toString(),
                    "redirects" to response.redirectChain.size.toString(),
                    "responseBytes" to response.body.size.toString(),
                    "finalOrigin" to runCatching { SourceOriginPolicy.originOf(java.net.URI(response.finalUrl)) }.getOrDefault("invalid"),
                    "resolvedAddresses" to response.resolvedAddresses.joinToString(","),
                    "tlsVersion" to response.tlsVersion.orEmpty(),
                    "cipherSuite" to response.cipherSuite.orEmpty(),
                    "responseHeaderNames" to response.headers.keys.sorted().joinToString(","),
                )))
                SourcePlatformResult.Success(response)
            },
            onFailure = { error ->
                val code = mapError(error.message.orEmpty())
                diagnostics.emit(networkEvent(manifest, request, "REQUEST_FAILED", DiagnosticSeverity.ERROR, clockMs() - started, mapOf(
                    "code" to code.name,
                    "error" to (error.message ?: error.javaClass.simpleName),
                )))
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_NETWORK_FAILED", request.traceId, error))
            },
        )
    }

    private fun executeRedirectLoop(
        manifest: SourceManifest,
        initial: SourceNetworkRequest,
        maxResponseBytes: Int,
        started: Long,
        deadlineMs: Long,
    ): SourceNetworkResponse {
        var url = initial.url
        var method = initial.method
        var body = initial.body
        var redirects = 0
        val hops = mutableListOf<SourceRedirectHop>()
        val resolved = linkedSetOf<String>()
        while (true) {
            val uri = if (redirects == 0) SourceOriginPolicy.requireInitialUrl(manifest, url)
                else SourceOriginPolicy.requireRedirectUrl(manifest, url)
            PublicAddressPolicy.requirePublic(resolver(uri.host)).forEach { resolved += it.hostAddress }
            val builder = Request.Builder().url(url.toHttpUrl())
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.6")
                .header("Accept", "text/html,application/json,text/plain,*/*")
            initial.headers.forEach(builder::header)
            readCookies(manifest, url)?.let { builder.header("Cookie", it) }
            when (method) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                else -> builder.method(method, body.toRequestBody(initial.contentType?.toMediaTypeOrNull()))
            }
            val call = client.newCall(builder.build())
            val remainingMs = deadlineMs - clockMs()
            require(remainingMs > 0L) { "SOURCE_NETWORK_TIMEOUT" }
            call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS)
            val redirected = call.execute().use { response ->
                writeCookies(manifest, response.request.url.toString(), response)
                if (response.code in REDIRECT_CODES) {
                    require(redirects < MAX_REDIRECTS) { "SOURCE_NETWORK_TOO_MANY_REDIRECTS" }
                    val location = response.header("Location") ?: error("SOURCE_NETWORK_REDIRECT_LOCATION_MISSING")
                    val next = response.request.url.resolve(location)?.toString() ?: error("SOURCE_NETWORK_REDIRECT_URL_INVALID")
                    SourceOriginPolicy.requireRedirectUrl(manifest, next)
                    hops += SourceRedirectHop(response.code, url, next)
                    if (response.code == 303 || ((response.code == 301 || response.code == 302) && method !in setOf("GET", "HEAD"))) {
                        method = "GET"
                        body = ByteArray(0)
                    }
                    url = next
                    redirects += 1
                    true
                } else {
                    if (!initial.allowHttpError) require(response.code in 200..299) { "SOURCE_NETWORK_HTTP_ERROR:${response.code}" }
                    val declared = response.body.contentLength()
                    require(declared < 0 || declared <= maxResponseBytes) { "SOURCE_NETWORK_RESPONSE_TOO_LARGE" }
                    val bytes = response.body.byteStream().readBounded(maxResponseBytes)
                    val contentType = response.body.contentType()
                    val completed = clockMs()
                    return SourceNetworkResponse(
                        statusCode = response.code,
                        finalUrl = response.request.url.toString(),
                        headers = response.headers.toMultimap().mapKeys { it.key.lowercase(Locale.ROOT) },
                        body = bytes,
                        charsetName = contentType?.charset(Charsets.UTF_8)?.name(),
                        redirectChain = hops.toList(),
                        resolvedAddresses = resolved.toList(),
                        tlsVersion = response.handshake?.tlsVersion?.javaName,
                        cipherSuite = response.handshake?.cipherSuite?.javaName,
                        timing = SourceNetworkTiming(started, completed),
                        traceId = initial.traceId,
                        statusText = response.message,
                    )
                }
            }
            if (redirected) continue
        }
    }

    private fun readCookies(manifest: SourceManifest, requestUrl: String): String? = when (manifest.capabilities.cookies) {
        SourceCookieMode.READ, SourceCookieMode.READ_WRITE, SourceCookieMode.BROWSER_SHARED ->
            cookiePartition.readCookieHeader(manifest.id, requestUrl)?.takeIf(String::isNotBlank)
        else -> null
    }

    private fun writeCookies(manifest: SourceManifest, responseUrl: String, response: Response) {
        when (manifest.capabilities.cookies) {
            SourceCookieMode.WRITE, SourceCookieMode.READ_WRITE, SourceCookieMode.BROWSER_SHARED ->
                cookiePartition.mergeSetCookieHeaders(manifest.id, responseUrl, response.headers("Set-Cookie"))
            else -> Unit
        }
    }

    private fun networkEvent(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        timestampEpochMs = clockMs(),
        traceId = request.traceId,
        sourceId = manifest.id,
        sourceVersion = manifest.version.toString(),
        category = DiagnosticCategory.NETWORK,
        name = name,
        severity = severity,
        durationMs = durationMs,
        attributes = attributes,
    )

    private fun mapError(message: String): SourceErrorCode = when {
        "PRIVATE_ADDRESS" in message || "DNS" in message -> SourceErrorCode.NETWORK_DNS_BLOCKED
        "METHOD_DENIED" in message -> SourceErrorCode.NETWORK_METHOD_DENIED
        "RATE_LIMIT" in message -> SourceErrorCode.NETWORK_RATE_LIMITED
        "CONCURRENCY_LIMIT" in message -> SourceErrorCode.NETWORK_CONCURRENCY_LIMITED
        "REQUEST_TOO_LARGE" in message -> SourceErrorCode.NETWORK_REQUEST_TOO_LARGE
        "RESPONSE_TOO_LARGE" in message -> SourceErrorCode.NETWORK_RESPONSE_TOO_LARGE
        "REDIRECT" in message -> SourceErrorCode.NETWORK_REDIRECT_DENIED
        "HTTP_ERROR" in message -> SourceErrorCode.NETWORK_HTTP_ERROR
        "TIMEOUT" in message || "timed out" in message.lowercase(Locale.ROOT) -> SourceErrorCode.NETWORK_TIMEOUT
        "URL" in message || "ORIGIN" in message || "HTTPS" in message -> SourceErrorCode.NETWORK_URL_INVALID
        "CAPABILITY" in message -> SourceErrorCode.PERMISSION_DENIED
        else -> SourceErrorCode.NETWORK_IO_ERROR
    }

    companion object {
        const val DEFAULT_USER_AGENT = "NgheTruyen-SourcePlatform/2 Android"
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("SOURCE_NETWORK_RESPONSE_TOO_LARGE")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
