package vn.nghetruyen.source.api

import java.nio.charset.Charset
import java.util.UUID

/** Network response representation requested by a declarative SourcePack action. */
enum class SourceNetworkResponseMode { TEXT, JSON, BASE64, BYTES }

data class SourceNetworkRequest(
    val sourceId: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val responseMode: SourceNetworkResponseMode = SourceNetworkResponseMode.TEXT,
    val allowHttpError: Boolean = false,
    val timeoutMs: Long = 30_000,
    val traceId: String = UUID.randomUUID().toString(),
)

data class SourceRedirectHop(
    val statusCode: Int,
    val fromUrl: String,
    val toUrl: String,
)

data class SourceNetworkTiming(
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val dnsDurationMs: Long? = null,
    val connectDurationMs: Long? = null,
    val tlsDurationMs: Long? = null,
    val firstByteDurationMs: Long? = null,
) {
    val totalDurationMs: Long get() = (completedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
}

data class SourceNetworkResponse(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val charsetName: String? = null,
    val redirectChain: List<SourceRedirectHop> = emptyList(),
    val resolvedAddresses: List<String> = emptyList(),
    val tlsVersion: String? = null,
    val cipherSuite: String? = null,
    val timing: SourceNetworkTiming,
    val traceId: String,
    /** HTTP reason phrase when the transport exposes one. HTTP/2+ may legitimately return an empty string. */
    val statusText: String = "",
    val fromReplay: Boolean = false,
) {
    fun bodyText(defaultCharset: Charset = Charsets.UTF_8): String =
        body.toString(charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: defaultCharset)
}

/** Capability boundary used by the runtime. Implementations must enforce the manifest, not trust the action program. */
fun interface SourceNetworkBroker {
    fun execute(manifest: SourceManifest, request: SourceNetworkRequest): SourcePlatformResult<SourceNetworkResponse>

    companion object {
        val DENY_ALL = SourceNetworkBroker { _, request ->
            SourcePlatformResult.Failure(
                SourcePlatformFailure(
                    code = SourceErrorCode.NETWORK_UNAVAILABLE,
                    message = "SOURCE_NETWORK_BROKER_UNAVAILABLE",
                    traceId = request.traceId,
                ),
            )
        }
    }
}

/** Cookie storage is partitioned strictly by SourcePack ID. */
interface SourceCookiePartition {
    /** Legacy source-wide header. New implementations should override the URL-aware methods below. */
    fun readCookieHeader(sourceId: String): String?
    fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>)

    /** RFC 6265-aware lookup for one request URL. */
    fun readCookieHeader(sourceId: String, requestUrl: String): String? = readCookieHeader(sourceId)

    /** RFC 6265-aware merge using the response URL as the default domain/path context. */
    fun mergeSetCookieHeaders(sourceId: String, responseUrl: String, setCookieHeaders: List<String>) =
        mergeSetCookieHeaders(sourceId, setCookieHeaders)

    /** Export Set-Cookie lines suitable for importing into a browser profile. */
    fun exportSetCookieHeaders(sourceId: String, requestUrl: String): List<String> =
        readCookieHeader(sourceId, requestUrl)?.split("; ")?.filter(String::isNotBlank).orEmpty()

    fun clear(sourceId: String)

    companion object {
        val NONE = object : SourceCookiePartition {
            override fun readCookieHeader(sourceId: String): String? = null
            override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) = Unit
            override fun clear(sourceId: String) = Unit
        }
    }
}
