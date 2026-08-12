package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime

@RunWith(AndroidJUnit4::class)
class ChromiumBrowserReplayVBookRuntimeTest {
    @Test
    fun dynamicHttpThenBrowserReplaysWithoutRepeatingNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var networkCalls = 0
        val network = SourceNetworkBroker { _, request ->
            networkCalls += 1
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("Content-Type" to listOf("text/plain; charset=utf-8")),
                body = "qidian-ranking-body".toByteArray(),
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
                statusText = "OK",
                requestUrl = request.url,
                requestHeaders = request.headers.mapValues { listOf(it.value) },
            ))
        }

        var currentUrl = "https://x.example/start"
        val browserActions = mutableListOf<SourceBrowserAction>()
        val browser = SourceBrowserBroker { _, request ->
            browserActions += request.action
            when (request.action) {
                SourceBrowserAction.NAVIGATE -> {
                    currentUrl = request.url.orEmpty()
                    SourcePlatformResult.Success(SourceBrowserResponse(
                        finalUrl = currentUrl,
                        value = "",
                        traceId = request.traceId,
                    ))
                }
                SourceBrowserAction.DOM_SNAPSHOT -> SourcePlatformResult.Success(SourceBrowserResponse(
                    finalUrl = currentUrl,
                    value = "<html><body><h1 class=\"result\">Browser OK</h1></body></html>",
                    traceId = request.traceId,
                ))
                SourceBrowserAction.REQUEST_METADATA -> SourcePlatformResult.Success(SourceBrowserResponse(
                    finalUrl = currentUrl,
                    value = "",
                    traceId = request.traceId,
                ))
                else -> error("BROWSER_ACTION_UNEXPECTED:${request.action}")
            }
        }

        ChromiumBrowserReplayVBookRuntime(
            context = context,
            brokers = SourceCapabilityBrokers(network = network, browser = browser),
        ).use { chromium ->
            val runtime = VBookCompatibilityRuntime(chromium)
            val result = runtime.executeDynamic(
                sourceManifest = manifest(),
                resources = resources(),
                scriptPath = "src/gen0.js",
                args = listOf("/majax/rank/yuepiaolist"),
                traceId = "chromium-browser-replay-dynamic",
            )

            assertTrue(
                (result as? SourcePlatformResult.Failure)?.let { "${it.error.code}:${it.error.message}" }
                    ?: "expected success",
                result is SourcePlatformResult.Success,
            )
            val success = result as SourcePlatformResult.Success
            val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
            assertEquals("qidian-ranking-body", row.string("body"))
            assertEquals("Browser OK", row.string("heading"))
        }

        assertEquals("HTTP before Browser must be memoized across Chromium replay rounds", 1, networkCalls)
        assertEquals(1, browserActions.count { it == SourceBrowserAction.NAVIGATE })
        assertTrue(browserActions.any { it == SourceBrowserAction.DOM_SNAPSHOT })
        assertTrue(browserActions.any { it == SourceBrowserAction.REQUEST_METADATA })
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.chromium.replay",
        name = "Chromium Browser replay fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT, actionTimeoutMs = 30_000L),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources(): SourceResourceProvider {
        val values = mapOf(
            "plugin.json" to CURRENT_PLUGIN.toByteArray(),
            "src/search.js" to "function execute(){return Response.success([]);}".toByteArray(),
            "src/explore.js" to "function execute(){return Response.success([]);}".toByteArray(),
            "src/gen0.js" to """
                function execute(path) {
                  var response = Http.get('https://x.example/ranking');
                  var browser = Engine.newBrowser();
                  var doc = browser.launch('https://x.example/page');
                  var heading = doc.selectFirst('h1.result');
                  return Response.success([{
                    body: response.text(),
                    heading: heading.text()
                  }], '');
                }
            """.trimIndent().toByteArray(),
        )
        return object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? =
                values[path]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
    }

    private fun JsonValue.Obj.string(name: String): String? = (values[name] as? JsonValue.Str)?.value

    companion object {
        private val CURRENT_PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"explore":"explore.js","search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{}
            }
        """.trimIndent()
    }
}
