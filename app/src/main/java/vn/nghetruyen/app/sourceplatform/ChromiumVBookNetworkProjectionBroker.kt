package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.vbook.VBookRawNetworkBroker

/**
 * Adapts the raw-byte vBook broker's initial metadata envelope to the host response shape expected
 * by the shared compatibility dispatcher.
 *
 * Rhino performs this projection while creating its JavaScript Response object. Chromium keeps the
 * same VBookRawNetworkBroker and cache protocol, but needs the projection before the JSON response
 * crosses the prompt bridge. Cache operations (text/base64/request) do not contain the marker and
 * therefore pass through untouched.
 */
class ChromiumVBookNetworkProjectionBroker(
    private val delegate: SourceNetworkBroker,
) : SourceNetworkBroker {
    override fun execute(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ) = when (val result = delegate.execute(manifest, request)) {
        is SourcePlatformResult.Failure -> result
        is SourcePlatformResult.Success -> SourcePlatformResult.Success(project(result.value))
    }

    private fun project(response: vn.nghetruyen.source.api.SourceNetworkResponse): vn.nghetruyen.source.api.SourceNetworkResponse {
        val envelope = runCatching {
            JsonCodec.parse(response.bodyText(), maxDepth = 32, maxNodes = 10_000) as? JsonValue.Obj
        }.getOrNull() ?: return response
        if (envelope.int("__ngheVBookFetch") != 1) return response

        val projectedHeaders = linkedMapOf<String, List<String>>()
        envelope.obj("headers")?.values.orEmpty().forEach { (name, value) ->
            val text = (value as? JsonValue.Str)?.value ?: return@forEach
            projectedHeaders[name] = listOf(text)
        }
        projectedHeaders[VBookRawNetworkBroker.INTERNAL_RESPONSE_KEY] =
            listOf(envelope.string("responseKey").orEmpty())
        projectedHeaders[VBookRawNetworkBroker.INTERNAL_RAW_SIZE] =
            listOf(envelope.numberText("rawSize") ?: "0")
        projectedHeaders[VBookRawNetworkBroker.INTERNAL_STATUS_TEXT] =
            listOf(envelope.string("statusText").orEmpty())

        return response.copy(
            body = envelope.string("body").orEmpty().toByteArray(Charsets.UTF_8),
            charsetName = Charsets.UTF_8.name(),
            headers = projectedHeaders,
        )
    }
}

private fun JsonValue.Obj.obj(name: String): JsonValue.Obj? = values[name] as? JsonValue.Obj
private fun JsonValue.Obj.string(name: String): String? = (values[name] as? JsonValue.Str)?.value
private fun JsonValue.Obj.int(name: String): Int? = (values[name] as? JsonValue.Num)?.raw?.toIntOrNull()
private fun JsonValue.Obj.numberText(name: String): String? = (values[name] as? JsonValue.Num)?.raw
