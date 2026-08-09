package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceWebSocketBroker
import vn.nghetruyen.source.api.SourceWebSocketFrame
import vn.nghetruyen.source.api.SourceWebSocketRequest
import vn.nghetruyen.source.api.SourceWebSocketResponse
import java.util.Base64

/**
 * Bridges rich broker frames through the legacy string-only VBookJsRuntime WebSocket queue.
 * Internal markers never leave the sandbox boundary and are decoded by [VBookWebSocketPrelude].
 */
class VBookWebSocketBroker(
    private val delegate: SourceWebSocketBroker,
) : SourceWebSocketBroker {
    override fun exchange(
        manifest: SourceManifest,
        request: SourceWebSocketRequest,
    ): SourcePlatformResult<SourceWebSocketResponse> {
        val (headers, messages) = decodeHeaderCarrier(request)
        return when (val result = delegate.exchange(manifest, request.copy(headers = headers, messages = messages))) {
            is SourcePlatformResult.Failure -> result
            is SourcePlatformResult.Success -> {
                val source = result.value
                val frames = if (source.frames.isNotEmpty()) source.frames else source.messages.map { SourceWebSocketFrame("text", it) }
                SourcePlatformResult.Success(source.copy(
                    messages = frames.map(::encodeFrame),
                    frames = frames,
                ))
            }
        }
    }

    private fun decodeHeaderCarrier(request: SourceWebSocketRequest): Pair<Map<String, String>, List<String>> {
        val first = request.messages.firstOrNull() ?: return request.headers to request.messages
        if (!first.startsWith(HEADER_PREFIX)) return request.headers to request.messages
        val encoded = first.removePrefix(HEADER_PREFIX)
        require(encoded.length <= MAX_HEADER_CARRIER_CHARS) { "VBOOK_WEBSOCKET_HEADERS_TOO_LARGE" }
        val decoded = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        val root = JsonCodec.parse(decoded, maxDepth = 8, maxNodes = 512) as? JsonValue.Obj
            ?: error("VBOOK_WEBSOCKET_HEADERS_OBJECT_REQUIRED")
        require(root.values.size <= MAX_HEADER_COUNT) { "VBOOK_WEBSOCKET_HEADERS_TOO_MANY" }
        val carrierHeaders = root.values.mapValues { (name, value) ->
            require(name.length in 1..256) { "VBOOK_WEBSOCKET_HEADER_NAME_INVALID" }
            val text = when (value) {
                is JsonValue.Str -> value.value
                is JsonValue.Num -> value.raw
                is JsonValue.Bool -> value.value.toString()
                JsonValue.Null -> ""
                else -> error("VBOOK_WEBSOCKET_HEADER_VALUE_INVALID:$name")
            }
            require(text.length <= 8 * 1024) { "VBOOK_WEBSOCKET_HEADER_VALUE_TOO_LARGE:$name" }
            text
        }
        return (request.headers + carrierHeaders) to request.messages.drop(1)
    }

    private fun encodeFrame(frame: SourceWebSocketFrame): String {
        val type = if (frame.type == "binary") "b" else "t"
        val data = if (frame.type == "binary") {
            // Normalize binary payload to canonical base64 and reject malformed broker output.
            Base64.getEncoder().encodeToString(Base64.getDecoder().decode(frame.data))
        } else frame.data
        return FRAME_PREFIX + type + ":" + Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FRAME_PREFIX = "__NGHE_VBOOK_WS_FRAME_V1__:"
        const val HEADER_PREFIX = "__NGHE_VBOOK_WS_HEADERS_V1__:"
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_HEADER_CARRIER_CHARS = 128 * 1024
    }
}
