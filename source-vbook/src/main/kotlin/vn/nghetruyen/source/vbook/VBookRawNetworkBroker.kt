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
 * Internal control headers are removed before any upstream request. Charset/base64/request-info
 * operations are served lazily from the already captured response, so requests are never replayed
 * merely to inspect a response representation.
 */
class VBookRawNetworkBroker(
    private val delegate: SourceNetworkBroker,
    private val maxCachedResponses: Int = 32,
    private val maxCachedBytes: Long = 32L * 1024L * 1024L,
) : SourceNetworkBroker {
    private data class CacheKey(val sourceId: String, val key: String)
    private data class Cached(val response: SourceNetworkResponse, val bytes: Int)

    private val cache = object : LinkedHashMap<CacheKey, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Cached>?): Boolean = size > maxCachedResponses
    }
    private var cachedBytes: Long = 0

    init {
        require(maxCachedResponses in 1..256)
        require(maxCachedBytes in 1024L..256L * 1024L * 1024L)
    }

    override fun execute(manifest: SourceManifest, request: SourceNetworkRequest): SourcePlatformResult<SourceNetworkResponse> {
        val requestKey = request.headers[INTERNAL_REQUEST_KEY]
        val operation = request.headers[INTERNAL_OPERATION]?.lowercase()
        val decodeCharset = request.headers[INTERNAL_DECODE_CHARSET]
        val requestedTimeout = request.headers[INTERNAL_TIMEOUT_MS]?.toLongOrNull()?.coerceIn(100L, 120_000L)
        val sanitizedHeaders = request.headers.filterKeys { key -> key !in INTERNAL_CONTROL_HEADERS }

        if (!operation.isNullOrBlank()) {
            val key = requestKey?.takeIf(String::isNotBlank)
                ?: return failure(request, "VBOOK_RAW_CACHE_KEY_REQUIRED")
            val cached = synchronized(this) { cache[CacheKey(request.sourceId, key)] }
                ?: return failure(request, "VBOOK_RAW_RESPONSE_CACHE_MISS")
            val bytes = when (operation) {
                OP_TEXT -> {
                    val charsetName = decodeCharset?.takeIf(String::isNotBlank)
                        ?: return failure(request, "VBOOK_FETCH_CHARSET_REQUIRED")
                    val charset = runCatching { Charset.forName(charsetName) }.getOrElse {
                        return failure(request, "VBOOK_FETCH_CHARSET_INVALID:$charsetName")
                    }
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
                headers = enrichHeaders(cached.response.headers, cached.response, key),
            ))
        }

        val delegatedRequest = request.copy(
            headers = sanitizedHeaders,
            timeoutMs = requestedTimeout ?: request.timeoutMs,
        )
        val result = delegate.execute(manifest, delegatedRequest)
        if (result !is SourcePlatformResult.Success) return result
        val response = result.value
        val key = requestKey?.takeIf(String::isNotBlank)
        if (key != null) put(CacheKey(request.sourceId, key), response)
        return SourcePlatformResult.Success(response.copy(
            headers = enrichHeaders(response.headers, response, key),
        ))
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

    private fun enrichHeaders(
        headers: Map<String, List<String>>,
        response: SourceNetworkResponse,
        key: String?,
    ): Map<String, List<String>> = LinkedHashMap(headers).apply {
        put(INTERNAL_RAW_SIZE.lowercase(), listOf(response.body.size.toString()))
        put(INTERNAL_STATUS_TEXT.lowercase(), listOf(response.statusText))
        if (key != null) put(INTERNAL_RESPONSE_KEY.lowercase(), listOf(key))
    }

    private fun failure(request: SourceNetworkRequest, message: String) = SourcePlatformResult.Failure(
        SourcePlatformFailure(
            code = vn.nghetruyen.source.api.SourceErrorCode.NETWORK_IO_ERROR,
            message = message,
            traceId = request.traceId,
        ),
    )

    companion object {
        const val INTERNAL_REQUEST_KEY = "X-Nghe-VBook-Request-Key"
        const val INTERNAL_OPERATION = "X-Nghe-VBook-Operation"
        const val INTERNAL_DECODE_CHARSET = "X-Nghe-VBook-Decode-Charset"
        const val INTERNAL_TIMEOUT_MS = "X-Nghe-VBook-Timeout-Ms"
        const val INTERNAL_RAW_SIZE = "X-Nghe-VBook-Raw-Size"
        const val INTERNAL_STATUS_TEXT = "X-Nghe-VBook-Status-Text"
        const val INTERNAL_RESPONSE_KEY = "X-Nghe-VBook-Response-Key"
        const val OP_TEXT = "text"
        const val OP_BASE64 = "base64"
        const val OP_REQUEST = "request"

        private val INTERNAL_CONTROL_HEADERS = setOf(
            INTERNAL_REQUEST_KEY,
            INTERNAL_OPERATION,
            INTERNAL_DECODE_CHARSET,
            INTERNAL_TIMEOUT_MS,
        )
    }
}
