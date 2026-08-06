package vn.nghetruyen.source.runtime

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRedirectHop
import java.util.Base64
import java.util.Locale

/** Deterministic network replay used by mandatory SourcePack fixtures. No live network is touched. */
class SnapshotReplayNetworkBroker private constructor(
    private val responses: MutableList<ReplayResponse>,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SourceNetworkBroker {
    override fun execute(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ): SourcePlatformResult<SourceNetworkResponse> {
        val index = responses.indexOfFirst { response ->
            response.method.equals(request.method, ignoreCase = true) && response.url == request.url
        }
        if (index < 0) return SourcePlatformResult.Failure(
            SourcePlatformFailure(
                SourceErrorCode.NETWORK_UNAVAILABLE,
                "SOURCE_REPLAY_REQUEST_NOT_FOUND:${request.method}:${request.url}",
                request.traceId,
            ),
        )
        val replay = responses.removeAt(index)
        val now = clockMs()
        return SourcePlatformResult.Success(
            SourceNetworkResponse(
                statusCode = replay.status,
                finalUrl = replay.finalUrl ?: replay.url,
                headers = replay.headers,
                body = replay.body,
                charsetName = replay.charsetName,
                redirectChain = replay.redirects,
                resolvedAddresses = listOf("replay"),
                timing = SourceNetworkTiming(now, now),
                traceId = request.traceId,
                fromReplay = true,
            ),
        )
    }

    companion object {
        fun fromResource(
            resources: SourceResourceProvider,
            path: String,
            maxBytes: Int = 8 * 1024 * 1024,
        ): SnapshotReplayNetworkBroker {
            SourceManifest.requireSafeRelativePath(path)
            val bytes = resources.read(path, maxBytes) ?: error("SOURCE_REPLAY_RESOURCE_MISSING:$path")
            val root = JsonCodec.parse(bytes.toString(Charsets.UTF_8)) as? JsonValue.Obj
                ?: error("SOURCE_REPLAY_ROOT_INVALID")
            require(root.int("version") == 1) { "SOURCE_REPLAY_VERSION_UNSUPPORTED" }
            val entries = root.array("responses")?.values ?: error("SOURCE_REPLAY_RESPONSES_MISSING")
            require(entries.size <= 256) { "SOURCE_REPLAY_TOO_MANY_RESPONSES" }
            return SnapshotReplayNetworkBroker(entries.map { parseResponse(it, resources, maxBytes) }.toMutableList())
        }

        private fun parseResponse(
            raw: JsonValue,
            resources: SourceResourceProvider,
            maxBytes: Int,
        ): ReplayResponse {
            val obj = raw as? JsonValue.Obj ?: error("SOURCE_REPLAY_RESPONSE_INVALID")
            val url = obj.string("url")?.takeIf(String::isNotBlank) ?: error("SOURCE_REPLAY_URL_REQUIRED")
            val method = obj.string("method")?.uppercase(Locale.ROOT) ?: "GET"
            val body = when {
                obj.string("bodyText") != null -> obj.string("bodyText").orEmpty().toByteArray(Charsets.UTF_8)
                obj.string("bodyBase64") != null -> Base64.getDecoder().decode(obj.string("bodyBase64"))
                obj.string("bodyResource") != null -> {
                    val path = obj.string("bodyResource").orEmpty()
                    SourceManifest.requireSafeRelativePath(path)
                    resources.read(path, maxBytes) ?: error("SOURCE_REPLAY_BODY_RESOURCE_MISSING:$path")
                }
                else -> ByteArray(0)
            }
            require(body.size <= maxBytes) { "SOURCE_REPLAY_BODY_TOO_LARGE" }
            val headers = linkedMapOf<String, List<String>>()
            obj.obj("headers")?.values.orEmpty().forEach { (name, value) ->
                headers[name.lowercase(Locale.ROOT)] = when (value) {
                    is JsonValue.Str -> listOf(value.value)
                    is JsonValue.Arr -> value.values.map { (it as? JsonValue.Str)?.value ?: error("SOURCE_REPLAY_HEADER_INVALID") }
                    else -> error("SOURCE_REPLAY_HEADER_INVALID")
                }
            }
            val redirects = obj.array("redirects")?.values.orEmpty().map { item ->
                val hop = item as? JsonValue.Obj ?: error("SOURCE_REPLAY_REDIRECT_INVALID")
                SourceRedirectHop(
                    statusCode = hop.int("status") ?: error("SOURCE_REPLAY_REDIRECT_STATUS_REQUIRED"),
                    fromUrl = hop.string("from") ?: error("SOURCE_REPLAY_REDIRECT_FROM_REQUIRED"),
                    toUrl = hop.string("to") ?: error("SOURCE_REPLAY_REDIRECT_TO_REQUIRED"),
                )
            }
            return ReplayResponse(
                method = method,
                url = url,
                status = obj.int("status") ?: 200,
                finalUrl = obj.string("finalUrl"),
                headers = headers,
                body = body,
                charsetName = obj.string("charset") ?: "UTF-8",
                redirects = redirects,
            )
        }
    }

    private data class ReplayResponse(
        val method: String,
        val url: String,
        val status: Int,
        val finalUrl: String?,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val charsetName: String?,
        val redirects: List<SourceRedirectHop>,
    )
}
