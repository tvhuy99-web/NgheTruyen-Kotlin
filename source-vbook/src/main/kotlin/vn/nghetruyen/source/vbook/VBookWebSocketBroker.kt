package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceWebSocketBroker
import vn.nghetruyen.source.api.SourceWebSocketFrame
import vn.nghetruyen.source.api.SourceWebSocketRequest
import vn.nghetruyen.source.api.SourceWebSocketResponse
import java.util.Base64

/**
 * Bridges rich broker frames through the legacy string-only VBookJsRuntime WebSocket queue.
 * The marker never leaves the sandbox boundary and is decoded by [VBookWebSocketPrelude].
 */
class VBookWebSocketBroker(
    private val delegate: SourceWebSocketBroker,
) : SourceWebSocketBroker {
    override fun exchange(
        manifest: SourceManifest,
        request: SourceWebSocketRequest,
    ): SourcePlatformResult<SourceWebSocketResponse> = when (val result = delegate.exchange(manifest, request)) {
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
    }
}
