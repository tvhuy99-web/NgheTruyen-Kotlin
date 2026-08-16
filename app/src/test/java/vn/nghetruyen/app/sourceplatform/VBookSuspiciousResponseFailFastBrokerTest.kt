package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy

class VBookSuspiciousResponseFailFastBrokerTest {
    @Test
    fun browserSharedTinyApplicationErrorBecomesExplicitNetworkFailure() {
        val cookies = FixedCookies("_csrfToken=ready; fu=1")
        val delegate = SourceNetworkBroker { _, request ->
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("content-type" to listOf("application/json")),
                body = "{\"code\":10001,\"msg\":\"session denied\"}".toByteArray(),
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
            ))
        }
        val broker = VBookSuspiciousResponseFailFastBroker(delegate, cookies)

        val result = broker.execute(manifest(), request())

        assertTrue(result is SourcePlatformResult.Failure)
        val failure = result as SourcePlatformResult.Failure
        assertEquals(SourceErrorCode.NETWORK_HTTP_ERROR, failure.error.code)
        assertTrue(failure.error.message.startsWith("VBOOK_HTTP_SESSION_PAYLOAD_INVALID"))
        assertTrue(failure.error.message.contains("code=10001"))
        assertTrue(failure.error.message.contains("bytes="))
    }

    @Test
    fun browserCookieReaderCanEstablishThePreRequestSession() {
        val cookies = FixedCookies(null)
        val delegate = tinyApplicationErrorDelegate()
        val broker = VBookSuspiciousResponseFailFastBroker(
            delegate = delegate,
            cookies = cookies,
            browserCookieReader = { _, _ -> "_csrfToken=webview-only; fu=1" },
        )

        val result = broker.execute(manifest(), request())

        assertTrue(result is SourcePlatformResult.Failure)
        assertTrue((result as SourcePlatformResult.Failure).error.message.startsWith("VBOOK_HTTP_SESSION_PAYLOAD_INVALID"))
    }

    @Test
    fun successfulQidianEnvelopeRemainsAvailableToTheScript() {
        val cookies = FixedCookies("_csrfToken=ready; fu=1")
        val body = "{\"code\":0,\"msg\":\"success\",\"data\":{\"pageNum\":1,\"pageSize\":20,\"list\":[]}}"
        val delegate = SourceNetworkBroker { _, request ->
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("content-type" to listOf("application/json")),
                body = body.toByteArray(),
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
            ))
        }
        val broker = VBookSuspiciousResponseFailFastBroker(delegate, cookies)

        val result = broker.execute(manifest(), request())

        assertTrue(result is SourcePlatformResult.Success)
        assertEquals(body, (result as SourcePlatformResult.Success).value.bodyText())
    }

    @Test
    fun noBrowserCookieLeavesApplicationEnvelopeUntouched() {
        val cookies = FixedCookies(null)
        val broker = VBookSuspiciousResponseFailFastBroker(tinyApplicationErrorDelegate(), cookies)

        assertTrue(broker.execute(manifest(), request()) is SourcePlatformResult.Success)
    }

    @Test
    fun cookieCreatedByTheErrorResponseCannotTriggerFailFastRetroactively() {
        val cookies = FixedCookies(null)
        val delegate = SourceNetworkBroker { _, request ->
            cookies.mergeSetCookieHeaders(request.sourceId, request.url, listOf("server_session=created-by-response; Path=/; Secure"))
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf(
                    "content-type" to listOf("application/json"),
                    "set-cookie" to listOf("server_session=created-by-response; Path=/; Secure"),
                ),
                body = "{\"code\":10001,\"msg\":\"bootstrap session\"}".toByteArray(),
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
            ))
        }
        val broker = VBookSuspiciousResponseFailFastBroker(delegate, cookies)

        val result = broker.execute(manifest(), request())

        assertTrue(result is SourcePlatformResult.Success)
        assertTrue(cookies.readCookieHeader(manifest().id, request().url).orEmpty().contains("server_session"))
    }

    private fun tinyApplicationErrorDelegate() = SourceNetworkBroker { _, request ->
        SourcePlatformResult.Success(SourceNetworkResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("content-type" to listOf("application/json")),
            body = "{\"code\":10001,\"msg\":\"expected business error\"}".toByteArray(),
            timing = SourceNetworkTiming(1L, 2L),
            traceId = request.traceId,
        ))
    }

    private fun request() = SourceNetworkRequest(
        sourceId = manifest().id,
        url = "https://m.qidian.com/majax/rank/yuepiaolist?pageNum=1",
        traceId = "fail-fast-regression",
    )

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "vbook.extension.qidian-fail-fast",
        name = "Qidian fail fast",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://m.qidian.com"),
        capabilities = SourceCapabilities(cookies = SourceCookieMode.BROWSER_SHARED),
        actions = emptyMap(),
    )

    private class FixedCookies(private var header: String?) : SourceCookiePartition {
        override fun readCookieHeader(sourceId: String): String? = header
        override fun readCookieHeader(sourceId: String, requestUrl: String): String? = header
        override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) {
            header = setCookieHeaders.joinToString("; ") { it.substringBefore(';') }
        }
        override fun mergeSetCookieHeaders(sourceId: String, responseUrl: String, setCookieHeaders: List<String>) {
            mergeSetCookieHeaders(sourceId, setCookieHeaders)
        }
        override fun clear(sourceId: String) {
            header = null
        }
    }
}
