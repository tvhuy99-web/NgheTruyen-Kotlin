package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import java.nio.charset.Charset
import java.util.Base64
import java.util.LinkedHashMap

/**
 * vBook-only network decorator used to bridge the mature text-oriented JS host to the raw-byte
 * contract without weakening or modifying the generic network broker.
 *
 * Internal control headers are removed before any upstream request. Requests that provide an
 * internal response key opt into the raw-response cache contract: the initial response body is a
 * small JSON metadata envelope consumed by [VBookFetchSafePrelude], and response representations are
 * then served lazily from the captured raw bytes without replaying the upstream request. Callers that
 * do not provide a response key keep the ordinary SourceNetworkBroker response contract unchanged.
 */
class VBookRawNetworkBroker(
    private val delegate: SourceNetworkBroker,
    private val maxCachedResponses: Int = 32,
    private val maxCachedBytes: Long = 32L * 1024L * 1024L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : SourceNetworkBroker {
    private data class CacheKey(val sourceId: String, val key: String)
    private data class Cached(val response: SourceNetworkResponse, val bytes: Int)

    private val cache = object : LinkedHashMap<CacheKey, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Cached>?): Boolean = size > maxCachedResponses
    }
    private var cachedBytes: Long = 0
    private val delayLock = Any()
    private val lastUpstreamStartMs = mutableMapOf<String, Long>()

    init {
        require(maxCachedResponses in 1..256)
        require(maxCachedBytes in 1024L..256L * 1024L * 1024L)
    }

    override fun execute(manifest: SourceManifest, request: SourceNetworkRequest): SourcePlatformResult<SourceNetworkResponse> {
        VBookSafeRhinoBoundary.installCurrentContext()
        val requestKey = request.headers.controlHeader(INTERNAL_REQUEST_KEY)
        val operation = request.headers.controlHeader(INTERNAL_OPERATION)?.lowercase()
        val decodeCharset = request.headers.controlHeader(INTERNAL_DECODE_CHARSET)
        val requestedTimeout = request.headers.controlHeader(INTERNAL_TIMEOUT_MS)
            ?.toLongOrNull()
            ?.coerceIn(100L, 120_000L)
        val requestedDelay = request.headers.controlHeader(INTERNAL_DELAY_MS)
            ?.toLongOrNull()
            ?.coerceIn(0L, 120_000L)
            ?: 0L
        val sanitizedHeaders = request.headers.filterKeys { key ->
            !key.startsWith(INTERNAL_PREFIX, ignoreCase = true)
        }

        if (!operation.isNullOrBlank()) {
            val key = requestKey?.takeIf(String::isNotBlank)
                ?: return failure(request, "VBOOK_RAW_CACHE_KEY_REQUIRED")
            val cached = synchronized(this) { cache[CacheKey(request.sourceId, key)] }
                ?: return failure(request, "VBOOK_RAW_RESPONSE_CACHE_MISS")
            val bytes = when (operation) {
                OP_TEXT -> {
                    val charset = decodeCharset?.takeIf(String::isNotBlank)?.let { charsetName ->
                        runCatching { Charset.forName(charsetName) }.getOrElse {
                            return failure(request, "VBOOK_FETCH_CHARSET_INVALID:$charsetName")
                        }
                    } ?: cached.response.charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
                        ?: Charsets.UTF_8
                    cached.response.body.toString(charset).toByteArray(Charsets.UTF_8)
                }
                OP_BASE64 -> Base64.getEncoder().encode(cached.response.body)
                OP_REQUEST -> requestMetadataJson(cached.response).toByteArray(Charsets.UTF_8)
                else -> return failure(request, "VBOOK_RAW_OPERATION_INVALID:$operation")
            }
            return SourcePlatformResult.Success(cached.response.copy(
                body = bytes,
                charsetName = Charsets.UTF_8.name(),
                traceId = request.traceId,
                fromReplay = true,
                headers = emptyMap(),
            ))
        }

        val timeoutBudgetMs = minOf(requestedTimeout ?: request.timeoutMs, request.timeoutMs).coerceAtLeast(100L)
        val delayElapsedMs = when (val delay = enforceInterRequestDelay(request.sourceId, requestedDelay, timeoutBudgetMs)) {
            is DelayResult.Allowed -> delay.elapsedMs
            DelayResult.ExceedsBudget -> return failure(request, "VBOOK_FETCH_DELAY_EXCEEDS_TIMEOUT")
        }
        val delegatedRequest = request.copy(
            headers = sanitizedHeaders,
            timeoutMs = (timeoutBudgetMs - delayElapsedMs).coerceAtLeast(100L),
        )
        val result = delegate.execute(manifest, delegatedRequest)
        if (result !is SourcePlatformResult.Success) return result
        val response = result.value
        val key = requestKey?.takeIf(String::isNotBlank) ?: return result
        put(CacheKey(request.sourceId, key), response)
        val envelope = responseEnvelopeJson(response, key)
        return SourcePlatformResult.Success(response.copy(
            body = envelope.toByteArray(Charsets.UTF_8),
            charsetName = Charsets.UTF_8.name(),
            headers = emptyMap(),
        ))
    }

    private sealed interface DelayResult {
        data class Allowed(val elapsedMs: Long) : DelayResult
        data object ExceedsBudget : DelayResult
    }

    private fun enforceInterRequestDelay(sourceId: String, delayMs: Long, timeoutBudgetMs: Long): DelayResult {
        if (delayMs <= 0L) return DelayResult.Allowed(0L)
        val started = clockMs()
        synchronized(delayLock) {
            val now = clockMs()
            val last = lastUpstreamStartMs[sourceId]
            if (last != null) {
                val waitMs = (last + delayMs - now).coerceAtLeast(0L)
                if (waitMs >= timeoutBudgetMs - 100L) return DelayResult.ExceedsBudget
                if (waitMs > 0L) sleeper(waitMs)
            }
            lastUpstreamStartMs[sourceId] = clockMs()
        }
        return DelayResult.Allowed((clockMs() - started).coerceAtLeast(0L))
    }

    @Synchronized
    private fun put(key: CacheKey, response: SourceNetworkResponse) {
        cache.remove(key)?.let { cachedBytes -= it.bytes.toLong() }
        if (response.body.size.toLong() > maxCachedBytes) return
        cache[key] = Cached(response, response.body.size)
        cachedBytes += response.body.size
        while (cache.isNotEmpty() && (cache.size > maxCachedResponses || cachedBytes > maxCachedBytes)) {
            val first = cache.entries.iterator().next()
            cachedBytes -= first.value.bytes.toLong()
            cache.remove(first.key)
        }
    }

    private fun responseEnvelopeJson(response: SourceNetworkResponse, key: String): String {
        val headers = linkedMapOf<String, JsonValue>()
        response.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, values) ->
            headers[name] = JsonValue.Str(values.joinToString(", "))
        }
        val requestHeaders = linkedMapOf<String, JsonValue>()
        response.requestHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, values) ->
            requestHeaders[name] = JsonValue.Str(values.joinToString(", "))
        }
        val defaultCharset = response.charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "__ngheVBookFetch" to JsonValue.Num(1.0, "1"),
            "responseKey" to JsonValue.Str(key),
            "body" to JsonValue.Str(response.body.toString(defaultCharset)),
            "rawSize" to JsonValue.Num(response.body.size.toDouble(), response.body.size.toString()),
            "statusText" to JsonValue.Str(response.statusText),
            "headers" to JsonValue.Obj(headers),
            "request" to JsonValue.Obj(linkedMapOf(
                "url" to JsonValue.Str(response.requestUrl ?: response.finalUrl),
                "headers" to JsonValue.Obj(requestHeaders),
            )),
        )))
    }

    private fun requestMetadataJson(response: SourceNetworkResponse): String {
        val headers = linkedMapOf<String, JsonValue>()
        response.requestHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, values) ->
            headers[name] = JsonValue.Str(values.joinToString(", "))
        }
        return JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "url" to JsonValue.Str(response.requestUrl ?: response.finalUrl),
            "headers" to JsonValue.Obj(headers),
        )))
    }

    private fun Map<String, String>.controlHeader(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    private fun failure(request: SourceNetworkRequest, message: String) = SourcePlatformResult.Failure(
        SourcePlatformFailure(
            code = vn.nghetruyen.source.api.SourceErrorCode.NETWORK_IO_ERROR,
            message = message,
            traceId = request.traceId,
        ),
    )

    companion object {
        const val INTERNAL_PREFIX = "X-Nghe-VBook-"
        const val INTERNAL_REQUEST_KEY = "X-Nghe-VBook-Request-Key"
        const val INTERNAL_OPERATION = "X-Nghe-VBook-Operation"
        const val INTERNAL_DECODE_CHARSET = "X-Nghe-VBook-Decode-Charset"
        const val INTERNAL_TIMEOUT_MS = "X-Nghe-VBook-Timeout-Ms"
        const val INTERNAL_DELAY_MS = "X-Nghe-VBook-Delay-Ms"
        const val INTERNAL_RAW_SIZE = "X-Nghe-VBook-Raw-Size"
        const val INTERNAL_STATUS_TEXT = "X-Nghe-VBook-Status-Text"
        const val INTERNAL_RESPONSE_KEY = "X-Nghe-VBook-Response-Key"
        const val OP_TEXT = "text"
        const val OP_BASE64 = "base64"
        const val OP_REQUEST = "request"
    }
}
