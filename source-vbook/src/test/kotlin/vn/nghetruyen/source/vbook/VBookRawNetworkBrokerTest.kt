package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import java.nio.charset.Charset
import java.util.Base64

class VBookRawNetworkBrokerTest {
    @Test
    fun cachedFormatsUseCapturedBytesWithoutSecondUpstreamRequest() {
        var upstreamCalls = 0
        val gbk = Charset.forName("GBK")
        val raw = "中文测试".toByteArray(gbk)
        val delegate = SourceNetworkBroker { _, request ->
            upstreamCalls++
            SourcePlatformResult.Success(response(request, raw))
        }
        val broker = VBookRawNetworkBroker(delegate)
        val key = "request-1"
        val original = broker.execute(
            manifest(),
            SourceNetworkRequest(
                sourceId = "fixture",
                url = "https://x.example/raw",
                headers = mapOf(VBookRawNetworkBroker.INTERNAL_REQUEST_KEY to key),
                allowHttpError = true,
            ),
        ) as SourcePlatformResult.Success

        assertEquals(1, upstreamCalls)
        assertEquals(raw.size.toString(), original.value.headers.getValue("x-nghe-vbook-raw-size").single())

        val decoded = broker.execute(
            manifest(),
            SourceNetworkRequest(
                sourceId = "fixture",
                url = "https://x.example/raw",
                headers = mapOf(
                    VBookRawNetworkBroker.INTERNAL_REQUEST_KEY to key,
                    VBookRawNetworkBroker.INTERNAL_OPERATION to VBookRawNetworkBroker.OP_TEXT,
                    VBookRawNetworkBroker.INTERNAL_DECODE_CHARSET to "GBK",
                ),
                allowHttpError = true,
            ),
        ) as SourcePlatformResult.Success
        val base64 = broker.execute(
            manifest(),
            SourceNetworkRequest(
                sourceId = "fixture",
                url = "https://x.example/raw",
                headers = mapOf(
                    VBookRawNetworkBroker.INTERNAL_REQUEST_KEY to key,
                    VBookRawNetworkBroker.INTERNAL_OPERATION to VBookRawNetworkBroker.OP_BASE64,
                ),
                allowHttpError = true,
            ),
        ) as SourcePlatformResult.Success

        assertEquals(1, upstreamCalls)
        assertEquals("中文测试", decoded.value.body.toString(Charsets.UTF_8))
        assertEquals(Base64.getEncoder().encodeToString(raw), base64.value.body.toString(Charsets.UTF_8))
        assertTrue(decoded.value.fromReplay)
        assertTrue(base64.value.fromReplay)
    }

    @Test
    fun internalControlHeadersNeverReachUpstreamAndTimeoutIsApplied() {
        var seenHeaders = emptyMap<String, String>()
        var timeoutMs = 0L
        val delegate = SourceNetworkBroker { _, request ->
            seenHeaders = request.headers
            timeoutMs = request.timeoutMs
            SourcePlatformResult.Success(response(request, "ok".toByteArray()))
        }
        val broker = VBookRawNetworkBroker(delegate)
        broker.execute(
            manifest(),
            SourceNetworkRequest(
                sourceId = "fixture",
                url = "https://x.example/raw",
                headers = mapOf(
                    "X-Public" to "yes",
                    VBookRawNetworkBroker.INTERNAL_REQUEST_KEY to "secret-control",
                    VBookRawNetworkBroker.INTERNAL_TIMEOUT_MS to "4321",
                ),
                allowHttpError = true,
            ),
        )
        assertEquals(mapOf("X-Public" to "yes"), seenHeaders)
        assertEquals(4321L, timeoutMs)
    }

    private fun response(request: SourceNetworkRequest, body: ByteArray) = SourceNetworkResponse(
        statusCode = 200,
        finalUrl = request.url,
        headers = mapOf("content-type" to listOf("application/octet-stream")),
        body = body,
        timing = SourceNetworkTiming(1, 2),
        traceId = request.traceId,
    )

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "fixture",
        name = "fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )
}
