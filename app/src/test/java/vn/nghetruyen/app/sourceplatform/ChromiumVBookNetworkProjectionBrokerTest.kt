package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.vbook.VBookRawNetworkBroker

class ChromiumVBookNetworkProjectionBrokerTest {
    @Test
    fun projectsInitialRawVBookEnvelopeIntoCompatibilityResponse() {
        val envelope = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "__ngheVBookFetch" to JsonValue.Num(1.0, "1"),
            "responseKey" to JsonValue.Str("cache-1"),
            "body" to JsonValue.Str("xin chao"),
            "rawSize" to JsonValue.Num(8.0, "8"),
            "statusText" to JsonValue.Str("OK"),
            "headers" to JsonValue.Obj(linkedMapOf(
                "Content-Type" to JsonValue.Str("text/plain; charset=utf-8"),
            )),
        )))
        val delegate = SourceNetworkBroker { _, request ->
            SourcePlatformResult.Success(response(request, envelope))
        }

        val result = ChromiumVBookNetworkProjectionBroker(delegate).execute(manifest(), request())

        result as SourcePlatformResult.Success
        val projected = result.value
        assertEquals("xin chao", projected.bodyText())
        assertEquals(listOf("text/plain; charset=utf-8"), projected.headers["Content-Type"])
        assertEquals(listOf("cache-1"), projected.headers[VBookRawNetworkBroker.INTERNAL_RESPONSE_KEY])
        assertEquals(listOf("8"), projected.headers[VBookRawNetworkBroker.INTERNAL_RAW_SIZE])
        assertEquals(listOf("OK"), projected.headers[VBookRawNetworkBroker.INTERNAL_STATUS_TEXT])
    }

    @Test
    fun leavesCacheRepresentationWithoutEnvelopeMarkerUntouched() {
        val original = response(request(), "cached-text")
        val delegate = SourceNetworkBroker { _, _ -> SourcePlatformResult.Success(original) }

        val result = ChromiumVBookNetworkProjectionBroker(delegate).execute(manifest(), request())

        result as SourcePlatformResult.Success
        assertSame(original, result.value)
    }

    private fun request() = SourceNetworkRequest(
        sourceId = "test.vbook.chromium",
        url = "https://x.example/api",
        traceId = "projection-test",
    )

    private fun response(request: SourceNetworkRequest, body: String) = SourceNetworkResponse(
        statusCode = 200,
        finalUrl = request.url,
        headers = emptyMap(),
        body = body.toByteArray(),
        charsetName = "UTF-8",
        timing = SourceNetworkTiming(1L, 2L),
        traceId = request.traceId,
        statusText = "OK",
        requestUrl = request.url,
    )

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.chromium",
        name = "Chromium projection fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )
}
