package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.vbook.VBookRawNetworkBroker

class VBookBrowserHttpParityBrokerTest {
    @Test
    fun tinyQidianErrorInsideMetadataEnvelopeFailsAtTheRealBodyLayer() {
        val cookies = MemoryCookies()
        val events = mutableListOf<DiagnosticEvent>()
        val actualBody = "{\"code\":10001,\"msg\":\"session denied\"}"
        val raw = VBookRawNetworkBroker(upstream(cookies, actualBody))
        val broker = broker(raw, cookies, events)

        val result = broker.execute(manifest(), request("qidian-error"))

        assertTrue(result is SourcePlatformResult.Failure)
        val failure = result as SourcePlatformResult.Failure
        assertTrue(failure.error.message.startsWith("VBOOK_HTTP_SESSION_PAYLOAD_INVALID"))
        assertTrue(failure.error.message.contains("bytes=${actualBody.toByteArray().size}"))
        val shape = events.last { it.name == "VBOOK_HTTP_RESPONSE_SHAPE" }
        assertEquals(actualBody.toByteArray().size.toString(), shape.attributes["responseBytes"])
        assertEquals("true", shape.attributes["suspicious2xx"])
        assertEquals("10001", shape.attributes["applicationCode"])
        assertEquals("raw-upstream-envelope", shape.attributes["inspectedLayer"])
        assertFalse(shape.attributes["observedJsonKeys"].orEmpty().contains("__ngheVBookFetch"))
    }

    @Test
    fun wrappedSuccessKeepsEnvelopeAndMirrorsSetCookieBackToWebView() {
        val cookies = MemoryCookies()
        val events = mutableListOf<DiagnosticEvent>()
        val mirrored = mutableListOf<String>()
        val body = "{\"code\":0,\"msg\":\"success\",\"data\":{\"pageNum\":1,\"list\":[]}}"
        val upstream = SourceNetworkBroker { _, request ->
            val requestHeaders = request.headers.mapValues { listOf(it.value) }.toMutableMap()
            cookies.readCookieHeader(request.sourceId, request.url)?.let { requestHeaders["Cookie"] = listOf(it) }
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf(
                    "content-type" to listOf("application/json; charset=utf-8"),
                    "set-cookie" to listOf(
                        "qid_session=refresh; Path=/; Secure; HttpOnly",
                        "qid_pref=1; Path=/; Secure",
                    ),
                ),
                body = body.toByteArray(),
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
                statusText = "OK",
                requestUrl = request.url,
                requestHeaders = requestHeaders,
            ))
        }
        val broker = VBookBrowserHttpParityBroker(
            delegate = VBookRawNetworkBroker(upstream),
            cookies = cookies,
            browserCookieReader = { _, _ -> "csrf_cookie=webview; fu=1" },
            browserCookieWriter = { _, _, values -> mirrored += values },
            browserUserAgent = { "Browser-UA" },
            diagnostics = DiagnosticSink { events += it },
            clockMs = { 10L },
        )

        val result = broker.execute(manifest(), request("qidian-success"))

        assertTrue(result is SourcePlatformResult.Success)
        assertTrue((result as SourcePlatformResult.Success).value.bodyText().contains("\"__ngheVBookFetch\":1"))
        assertEquals(2, mirrored.size)
        assertTrue(mirrored.any { it.startsWith("qid_session=") })
        assertTrue(mirrored.any { it.startsWith("qid_pref=") })
        val shape = events.last { it.name == "VBOOK_HTTP_RESPONSE_SHAPE" }
        assertEquals(body.toByteArray().size.toString(), shape.attributes["responseBytes"])
        assertEquals("false", shape.attributes["suspicious2xx"])
        assertTrue(events.any { it.name == "VBOOK_HTTP_COOKIE_MIRROR" })
        val wire = events.last { it.name == "VBOOK_HTTP_WIRE_IDENTITY" }
        assertEquals("true", wire.attributes["userAgentMatchesBrowser"])
        assertTrue(wire.attributes["wireCookieNames"].orEmpty().contains("csrf_cookie"))
    }

    @Test
    fun rawCacheOperationsDoNotReimportBrowserState() {
        val cookies = MemoryCookies()
        val events = mutableListOf<DiagnosticEvent>()
        var browserReads = 0
        val broker = VBookBrowserHttpParityBroker(
            delegate = VBookRawNetworkBroker(upstream(cookies, "plain-body")),
            cookies = cookies,
            browserCookieReader = { _, _ -> browserReads += 1; "csrf_cookie=webview" },
            browserCookieWriter = { _, _, _ -> },
            browserUserAgent = { "Browser-UA" },
            diagnostics = DiagnosticSink { events += it },
            clockMs = { 10L },
        )
        val initial = request("cache-initial")

        assertTrue(broker.execute(manifest(), initial) is SourcePlatformResult.Success)
        val cached = initial.copy(headers = mapOf(
            VBookRawNetworkBroker.INTERNAL_REQUEST_KEY to "fixture-key",
            VBookRawNetworkBroker.INTERNAL_OPERATION to VBookRawNetworkBroker.OP_TEXT,
        ))
        assertTrue(broker.execute(manifest(), cached) is SourcePlatformResult.Success)
        assertEquals(1, browserReads)
        assertEquals(1, events.count { it.name == "VBOOK_HTTP_SESSION_POLICY" })
    }

    private fun broker(delegate: SourceNetworkBroker, cookies: MemoryCookies, events: MutableList<DiagnosticEvent>) =
        VBookBrowserHttpParityBroker(
            delegate = delegate,
            cookies = cookies,
            browserCookieReader = { _, _ -> "csrf_cookie=webview; fu=1257514207" },
            browserCookieWriter = { _, _, _ -> },
            browserUserAgent = { "Mozilla/5.0 Android wv Chrome/138.0" },
            diagnostics = DiagnosticSink { events += it },
            clockMs = { 10L },
        )

    private fun upstream(cookies: MemoryCookies, body: String) = SourceNetworkBroker { _, request ->
        val requestHeaders = request.headers.mapValues { listOf(it.value) }.toMutableMap()
        cookies.readCookieHeader(request.sourceId, request.url)?.let { requestHeaders["Cookie"] = listOf(it) }
        SourcePlatformResult.Success(SourceNetworkResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("content-type" to listOf("application/json; charset=utf-8")),
            body = body.toByteArray(),
            charsetName = "UTF-8",
            timing = SourceNetworkTiming(1L, 2L),
            traceId = request.traceId,
            statusText = "OK",
            requestUrl = request.url,
            requestHeaders = requestHeaders,
        ))
    }

    private fun request(traceId: String) = SourceNetworkRequest(
        sourceId = manifest().id,
        url = "https://m.qidian.com/majax/rank/yuepiaolist?pageNum=1",
        headers = mapOf(VBookRawNetworkBroker.INTERNAL_REQUEST_KEY to "fixture-key"),
        traceId = traceId,
    )

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "vbook.extension.qidian-parity-v2",
        name = "Qidian parity v2",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("http://14.225.254.182", "https://m.qidian.com"),
        capabilities = SourceCapabilities(cookies = SourceCookieMode.BROWSER_SHARED),
        actions = emptyMap(),
    )

    private class MemoryCookies : SourceCookiePartition {
        private var header: String? = null
        override fun readCookieHeader(sourceId: String): String? = header
        override fun readCookieHeader(sourceId: String, requestUrl: String): String? = header
        override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) {
            header = setCookieHeaders.joinToString("; ") { it.substringBefore(';').trim() }
        }
        override fun mergeSetCookieHeaders(sourceId: String, responseUrl: String, setCookieHeaders: List<String>) {
            mergeSetCookieHeaders(sourceId, setCookieHeaders)
        }
        override fun clear(sourceId: String) { header = null }
    }
}
