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
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink

class VBookBrowserSessionNetworkBrokerTest {
    @Test
    fun qidianStyleBrowserSessionIsProjectedIntoNativeFetch() {
        val partition = MemoryCookiePartition()
        val events = mutableListOf<DiagnosticEvent>()
        var capturedRequest: SourceNetworkRequest? = null
        var mirroredUrl = ""
        var mirroredCookies = emptyList<String>()
        val successPayload = qidianSuccessPayload()
        val delegate = SourceNetworkBroker { _, request ->
            capturedRequest = request
            val wireHeaders = linkedMapOf<String, List<String>>()
            request.headers.forEach { (name, value) -> wireHeaders[name] = listOf(value) }
            partition.readCookieHeader(request.sourceId, request.url)?.let { wireHeaders["Cookie"] = listOf(it) }
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf(
                    "content-type" to listOf("application/json; charset=utf-8"),
                    "set-cookie" to listOf("qid_session=server-refresh; Path=/; Secure; HttpOnly"),
                ),
                body = successPayload.toByteArray(),
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
                requestUrl = request.url,
                requestHeaders = wireHeaders,
            ))
        }
        val broker = VBookBrowserSessionNetworkBroker(
            delegate = delegate,
            cookies = partition,
            browserCookieReader = { _, _ -> "_csrfToken=csrf-from-webview; fu=1257514207" },
            browserCookieWriter = { _, url, cookies ->
                mirroredUrl = url
                mirroredCookies = cookies
            },
            browserUserAgent = { "Mozilla/5.0 (Linux; Android 16; wv) Chrome/138.0 Mobile Safari/537.36" },
            diagnostics = DiagnosticSink { events += it },
            clockMs = { 10L },
        )
        val request = SourceNetworkRequest(
            sourceId = manifest().id,
            url = "https://m.qidian.com/majax/rank/yuepiaolist?gender=male&pageNum=1&_csrfToken=csrf-from-webview",
            traceId = "qidian-parity",
        )

        val result = broker.execute(manifest(), request)

        assertTrue(result is SourcePlatformResult.Success)
        val sent = requireNotNull(capturedRequest)
        assertEquals("Mozilla/5.0 (Linux; Android 16; wv) Chrome/138.0 Mobile Safari/537.36", sent.headers.value("User-Agent"))
        assertEquals("text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8", sent.headers.value("Accept"))
        assertEquals("vi-VN,vi;q=0.9,en-US;q=0.7,en;q=0.6", sent.headers.value("Accept-Language"))
        assertEquals("https://m.qidian.com/", sent.headers.value("Referer"))
        assertTrue(partition.readCookieHeader(manifest().id, request.url).orEmpty().contains("_csrfToken=csrf-from-webview"))
        assertEquals(request.url, mirroredUrl)
        assertEquals(listOf("qid_session=server-refresh; Path=/; Secure; HttpOnly"), mirroredCookies)

        val session = events.first { it.name == "VBOOK_HTTP_SESSION_POLICY" }
        assertEquals("browser_shared", session.attributes["cookieMode"])
        assertTrue(session.attributes["browserCookieBytes"].orEmpty().toInt() > 0)
        assertTrue(session.attributes["networkCookieBytes"].orEmpty().toInt() > 0)
        assertEquals("webview", session.attributes["effectiveUserAgentSource"])
        assertEquals("source-origin", session.attributes["effectiveRefererSource"])
        assertEquals("lua-vbook-default", session.attributes["effectiveAcceptSource"])
        assertEquals("lua-vbook-default", session.attributes["effectiveAcceptLanguageSource"])

        val wire = events.first { it.name == "VBOOK_HTTP_WIRE_IDENTITY" }
        assertEquals("true", wire.attributes["userAgentMatchesBrowser"])
        assertTrue(wire.attributes["wireCookieNames"].orEmpty().contains("_csrfToken"))

        val shape = events.first { it.name == "VBOOK_HTTP_RESPONSE_SHAPE" }
        assertEquals("false", shape.attributes["suspicious2xx"])
        assertEquals("0", shape.attributes["applicationCode"])
        assertTrue(shape.attributes["observedJsonKeys"].orEmpty().contains("pageNum"))
        assertTrue(successPayload.contains("\"pageNum\":1"))
        assertEquals(20, Regex("\\\"bookId\\\"").findAll(successPayload).count())
        assertEquals(2, qidianReferenceNextPage(successPayload))
        assertTrue(qidianLuaReferenceNextRoute().contains("%22page%22%3A%222%22"))
    }

    @Test
    fun extensionHeadersWinOverBrowserDefaults() {
        val projection = VBookHttpSessionCompatibility.projectHeaders(
            original = mapOf(
                "user-agent" to "Extension-UA",
                "REFERER" to "https://custom.example/path",
                "Accept" to "application/json",
                "Accept-Language" to "zh-CN",
            ),
            browserUserAgent = "Browser-UA",
            defaultReferer = "https://m.qidian.com/",
        )

        assertEquals("Extension-UA", projection.headers.value("User-Agent"))
        assertEquals("https://custom.example/path", projection.headers.value("Referer"))
        assertEquals("application/json", projection.headers.value("Accept"))
        assertEquals("zh-CN", projection.headers.value("Accept-Language"))
        assertEquals("extension", projection.userAgentSource)
        assertEquals("extension", projection.refererSource)
        assertEquals("extension", projection.acceptSource)
        assertEquals("extension", projection.acceptLanguageSource)
    }

    @Test
    fun suspiciousTwoHundredEnvelopeIsCorrelatedWithChromiumFailure() {
        val underlying = mutableListOf<DiagnosticEvent>()
        var now = 100L
        val correlated = VBookHttpParityDiagnosticSink(DiagnosticSink { underlying += it }) { now }
        val partition = MemoryCookiePartition()
        val delegate = SourceNetworkBroker { _, request ->
            val headers = request.headers.mapValues { listOf(it.value) }
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("content-type" to listOf("application/json")),
                body = "{\"code\":10001,\"msg\":\"session denied\"}".toByteArray(),
                timing = SourceNetworkTiming(100L, 101L),
                traceId = request.traceId,
                requestUrl = request.url,
                requestHeaders = headers,
            ))
        }
        val broker = VBookBrowserSessionNetworkBroker(
            delegate = delegate,
            cookies = partition,
            browserCookieReader = { _, _ -> "_csrfToken=secret-value" },
            browserCookieWriter = { _, _, _ -> },
            browserUserAgent = { "Browser-UA" },
            diagnostics = correlated,
            clockMs = { now },
        )

        val result = broker.execute(manifest(), SourceNetworkRequest(
            sourceId = manifest().id,
            url = "https://m.qidian.com/majax/rank/yuepiaolist?pageNum=1",
            traceId = "suspicious-http",
        ))
        assertTrue(result is SourcePlatformResult.Success)
        val shape = underlying.first { it.name == "VBOOK_HTTP_RESPONSE_SHAPE" }
        assertEquals(DiagnosticSeverity.WARN, shape.severity)
        assertEquals("true", shape.attributes["suspicious2xx"])
        assertEquals("10001", shape.attributes["applicationCode"])
        assertFalse(shape.attributes["sanitizedPreview"].orEmpty().contains("secret-value"))

        now = 150L
        correlated.emit(DiagnosticEvent(
            timestampEpochMs = now,
            traceId = "suspicious-http",
            sourceId = manifest().id,
            category = DiagnosticCategory.RUNTIME,
            name = "CHROMIUM_ACTION_FAILED",
            severity = DiagnosticSeverity.ERROR,
            attributes = mapOf("error" to "TypeError: Cannot read properties of undefined (reading 'pageNum')"),
        ))

        val failed = underlying.last { it.name == "CHROMIUM_ACTION_FAILED" }
        assertEquals("VBOOK_HTTP_SESSION_PAYLOAD_INVALID", failed.attributes["likelyRootCause"])
        assertEquals("200", failed.attributes["httpStatus"])
        assertEquals("10001", failed.attributes["httpApplicationCode"])
        assertEquals("application-code:10001", failed.attributes["httpSuspicionReason"])
    }

    @Test
    fun successfulSmallApplicationEnvelopeIsNotFlagged() {
        val response = SourceNetworkResponse(
            statusCode = 200,
            finalUrl = "https://m.qidian.com/api",
            headers = mapOf("content-type" to listOf("application/json")),
            body = "{\"code\":0,\"data\":{\"pageNum\":1}}".toByteArray(),
            timing = SourceNetworkTiming(1L, 2L),
            traceId = "ok",
        )

        val shape = VBookHttpSessionCompatibility.classify(response)

        assertFalse(shape.suspicious2xx)
        assertEquals("0", shape.applicationCode)
        assertTrue(shape.observedJsonKeys.contains("pageNum"))
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "vbook.extension.qidian-regression",
        name = "Qidian regression",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://m.qidian.com"),
        capabilities = SourceCapabilities(cookies = SourceCookieMode.BROWSER_SHARED),
        actions = emptyMap(),
    )

    private fun qidianSuccessPayload(): String {
        val items = (1..20).joinToString(",") { index ->
            "{\"bookId\":\"$index\",\"bookName\":\"Book $index\"}"
        }
        return "{\"code\":0,\"msg\":\"success\",\"data\":{\"pageNum\":1,\"pageSize\":20,\"hasNext\":true,\"list\":[$items]}}"
    }

    private fun qidianReferenceNextPage(payload: String): Int? {
        val current = Regex("\\\"pageNum\\\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toIntOrNull()
        val hasNext = Regex("\\\"hasNext\\\"\\s*:\\s*true").containsMatchIn(payload)
        return if (current != null && hasNext) current + 1 else null
    }

    /** Oracle copied from the successful Lua session6 executor output: page="2". */
    private fun qidianLuaReferenceNextRoute(): String =
        "http://14.225.254.182#__vbook_route=%7B%22kind%22%3A%22list%22%2C%22script%22%3A%22gen0.js%22%2C%22input%22%3A%22%2Fmajax%2Frank%2Fyuepiaolist%3Fgender%3Dmale%26pageNum%3D%7Bpage%7D%26%7B_csrfToken%7D%22%2C%22page%22%3A%222%22%7D"

    private class MemoryCookiePartition : SourceCookiePartition {
        private var header: String? = null

        override fun readCookieHeader(sourceId: String): String? = header
        override fun readCookieHeader(sourceId: String, requestUrl: String): String? = header

        override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) {
            header = setCookieHeaders.joinToString("; ") { it.substringBefore(';').trim() }
        }

        override fun mergeSetCookieHeaders(sourceId: String, responseUrl: String, setCookieHeaders: List<String>) {
            mergeSetCookieHeaders(sourceId, setCookieHeaders)
        }

        override fun clear(sourceId: String) {
            header = null
        }
    }
}

private fun Map<String, String>.value(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
