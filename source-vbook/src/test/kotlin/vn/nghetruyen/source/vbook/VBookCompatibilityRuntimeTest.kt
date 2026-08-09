package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceBrowserRequestMetadata
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookCompatibilityRuntimeTest {
    @Test
    fun currentSearchGetsOpaqueCursorAndInjectedConfig() {
        val resources = resources(
            CURRENT_PLUGIN,
            mapOf("src/search.js" to """
                function execute(query, page) {
                  return Response.success([{name:query + '@' + DOMAIN}], page + '/next?x=1');
                }
            """.trimIndent(), "src/explore.js" to "function execute(){return Response.success([]);}"),
        )
        val result = VBookCompatibilityRuntime().executeDeclared(
            sourceManifest = manifest(),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "kiem-tien",
            continuation = VBookContinuation("opaque://a/b?cursor=7"),
            runtimeConfig = mapOf("DOMAIN" to "configured.example"),
            traceId = "current",
        )
        val success = result as SourcePlatformResult.Success
        assertEquals("opaque://a/b?cursor=7/next?x=1", success.value.continuation.token)
        val first = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("kiem-tien@configured.example", first.string("name"))
    }

    @Test
    fun legacyResponseUses200ContractInsideDispatcher() {
        val resources = resources(
            LEGACY_PLUGIN,
            mapOf("src/search.js" to "function execute(query,page){return Response.success([{name:query}], page + '-legacy');}"),
        )
        val result = VBookCompatibilityRuntime().executeDeclared(
            sourceManifest = manifest(),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            continuation = VBookContinuation("cursor"),
            traceId = "legacy",
        )
        assertTrue(result is SourcePlatformResult.Success)
        val success = result as SourcePlatformResult.Success
        assertEquals(VBookContractProfile.LEGACY_JS, success.value.profile)
        assertEquals("cursor-legacy", success.value.continuation.token)
    }

    @Test
    fun fetchExposesTransportStatusAndFinalRequestMetadataWithoutReplay() {
        var upstreamCalls = 0
        var upstreamHeaders = emptyMap<String, String>()
        val broker = SourceNetworkBroker { _, request ->
            upstreamCalls++
            upstreamHeaders = request.headers
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 202,
                finalUrl = "https://cdn.example/final",
                headers = mapOf("content-type" to listOf("text/plain; charset=utf-8")),
                body = "payload".toByteArray(),
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(1, 2),
                traceId = request.traceId,
                statusText = "Accepted",
                requestUrl = "https://cdn.example/final",
                requestHeaders = mapOf(
                    "User-Agent" to listOf("Reference-UA"),
                    "Cookie" to listOf("sid=abc"),
                    "X-Client" to listOf("one"),
                ),
            ))
        }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(network = broker))
        val resources = resources(
            CURRENT_PLUGIN,
            mapOf(
                "src/search.js" to """
                    function execute(query, page) {
                      var response = fetch('https://x.example/start', {headers:{'X-Client':'one'}});
                      var blob = response.blob();
                      return Response.success([{
                        status:String(response.status),
                        statusText:String(response.statusText),
                        requestUrl:String(response.request.url),
                        requestUa:String(response.request.headers['User-Agent']),
                        requestCookie:String(response.request.headers['Cookie']),
                        body:response.text('UTF-8'),
                        base64:response.base64(),
                        blobSize:String(blob.size),
                        blobBase64:blob.base64(),
                        hiddenInternal:String(response.header('X-Nghe-VBook-Status-Text'))
                      }], '');
                    }
                """.trimIndent(),
                "src/explore.js" to "function execute(){return Response.success([]);}",
            ),
        )

        val result = runtime.executeDeclared(
            sourceManifest = manifest(network = true),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "fetch-metadata",
        ) as SourcePlatformResult.Success

        assertEquals(1, upstreamCalls)
        assertFalse(upstreamHeaders.keys.any { it.startsWith(VBookRawNetworkBroker.INTERNAL_PREFIX, ignoreCase = true) })
        val first = (result.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("202", first.string("status"))
        assertEquals("Accepted", first.string("statusText"))
        assertEquals("https://cdn.example/final", first.string("requestUrl"))
        assertEquals("Reference-UA", first.string("requestUa"))
        assertEquals("sid=abc", first.string("requestCookie"))
        assertEquals("payload", first.string("body"))
        assertEquals("cGF5bG9hZA==", first.string("base64"))
        assertEquals("7", first.string("blobSize"))
        assertEquals("cGF5bG9hZA==", first.string("blobBase64"))
        assertEquals("undefined", first.string("hiddenInternal"))
    }

    @Test
    fun domCollectionsAndMutationFollowCurrentVBookShape() {
        val resources = resources(
            CURRENT_PLUGIN,
            mapOf(
                "src/search.js" to """
                    function execute(query, page) {
                      var doc = Html.parse("<div id='content'><script>bad()</script><p>A</p><p data-x='1'>B</p></div>");
                      doc.select('script').forEach(function(el){ el.remove(); });
                      var paragraphs = doc.select('p');
                      var nested = doc.select('#content').select('p');
                      return Response.success([{
                        html:doc.select('#content').html(),
                        count:String(paragraphs.size()),
                        empty:String(paragraphs.isEmpty()),
                        mapped:paragraphs.map(function(el){return el.text();}).join('|'),
                        attr:String(paragraphs.get(1).attributes()['data-x']),
                        nested:nested.text()
                      }], '');
                    }
                """.trimIndent(),
                "src/explore.js" to "function execute(){return Response.success([]);}",
            ),
        )

        val result = VBookCompatibilityRuntime().executeDeclared(
            sourceManifest = manifest(),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "dom-current",
        ) as SourcePlatformResult.Success

        val row = (result.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertFalse(row.string("html").orEmpty().contains("script", ignoreCase = true))
        assertEquals("2", row.string("count"))
        assertEquals("false", row.string("empty"))
        assertEquals("A|B", row.string("mapped"))
        assertEquals("1", row.string("attr"))
        assertEquals("A B", row.string("nested"))
    }

    @Test
    fun browserLoadHtmlUsesHtmlThenBaseUrlAndWaitUrlObservesNetworkRequests() {
        var loadHtmlUrl: String? = null
        var loadHtmlValue: String? = null
        var requestMetadataCalls = 0
        val browser = SourceBrowserBroker { _, request ->
            when (request.action) {
                SourceBrowserAction.LOAD_HTML -> {
                    loadHtmlUrl = request.url
                    loadHtmlValue = request.value
                    SourcePlatformResult.Success(SourceBrowserResponse(
                        finalUrl = request.url,
                        value = "ok",
                        traceId = request.traceId,
                    ))
                }
                SourceBrowserAction.REQUEST_METADATA -> {
                    requestMetadataCalls++
                    SourcePlatformResult.Success(SourceBrowserResponse(
                        finalUrl = "https://page.example/current",
                        requestMetadata = listOf(SourceBrowserRequestMetadata(
                            url = "https://api.example/data?id=7",
                            method = "GET",
                            mainFrame = false,
                            resourceType = "fetch",
                            timestampEpochMs = 10,
                        )),
                        traceId = request.traceId,
                    ))
                }
                SourceBrowserAction.CLOSE_SESSION -> SourcePlatformResult.Success(SourceBrowserResponse(
                    finalUrl = "https://page.example/current",
                    traceId = request.traceId,
                ))
                else -> SourcePlatformResult.Success(SourceBrowserResponse(
                    finalUrl = "https://page.example/current",
                    traceId = request.traceId,
                ))
            }
        }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(browser = browser))
        val resources = resources(
            CURRENT_PLUGIN,
            mapOf(
                "src/search.js" to """
                    function execute(query, page) {
                      var browser = Engine.newBrowser();
                      try {
                        browser.loadHtml('<html><body>fixture</body></html>', 'https://base.example/path/');
                        var matched = browser.waitUrl(['*api.example/data*'], 500);
                        return Response.success([{
                          matched:String(matched),
                          current:String(browser.currentUrl())
                        }], '');
                      } finally {
                        browser.close();
                      }
                    }
                """.trimIndent(),
                "src/explore.js" to "function execute(){return Response.success([]);}",
            ),
        )

        val result = runtime.executeDeclared(
            sourceManifest = manifest(browser = true),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "browser-current",
        ) as SourcePlatformResult.Success

        assertEquals("https://base.example/path/", loadHtmlUrl)
        assertEquals("<html><body>fixture</body></html>", loadHtmlValue)
        assertTrue(requestMetadataCalls >= 1)
        val row = (result.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("https://api.example/data?id=7", row.string("matched"))
        assertEquals("https://page.example/current", row.string("current"))
    }

    private fun manifest(
        network: Boolean = false,
        browser: Boolean = false,
    ): SourceManifest = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.source",
        name = "VBook fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(
            network = if (network) SourceNetworkCapability(
                methods = setOf("GET", "POST"),
                maxResponseBytes = 4 * 1024 * 1024,
                maxRequestBytes = 1024 * 1024,
                publicInternet = true,
            ) else null,
            browser = if (browser) SourceBrowserCapability(
                navigate = true,
                domSnapshot = true,
                click = true,
                input = true,
                requestMetadata = true,
                pageJavaScript = true,
            ) else SourceBrowserCapability(),
        ),
        actions = emptyMap(),
    )

    private fun resources(plugin: String, scripts: Map<String, String>): SourceResourceProvider {
        val values = buildMap<String, ByteArray> {
            put("plugin.json", plugin.toByteArray())
            scripts.forEach { (path, source) -> put(path, source.toByteArray()) }
        }
        return object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? = values[path]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
    }

    companion object {
        private val CURRENT_PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"explore":"explore.js","search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{"DOMAIN":{"title":"Domain","default":"default.example","mode":"input","format":"text"}}
            }
        """.trimIndent()
        private val LEGACY_PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi_VN","regexp":"x","type":"novel","language":"javascript","encrypt":false},
              "script":{"search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{"thread_num":1,"delay":4000}
            }
        """.trimIndent()
    }
}
