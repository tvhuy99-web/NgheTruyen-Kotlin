package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
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
import vn.nghetruyen.source.api.SourceTranslationBroker
import vn.nghetruyen.source.api.SourceTranslationResponse
import vn.nghetruyen.source.api.SourceWebSocketBroker
import vn.nghetruyen.source.api.SourceWebSocketFrame
import vn.nghetruyen.source.api.SourceWebSocketResponse
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookCurrentHostWiringTest {
    @Test
    fun qtExtrasReachDedicatedQuickBroker() {
        var capturedOptions = emptyMap<String, String>()
        var capturedTarget = ""
        val quick = SourceTranslationBroker { _, request ->
            capturedOptions = request.options
            capturedTarget = request.targetLanguage
            SourcePlatformResult.Success(SourceTranslationResponse(
                translatedText = "translated",
                provider = "fixture-qt",
                traceId = request.traceId,
            ))
        }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(quickTranslation = quick))
        val result = success(runtime.executeDeclared(
            sourceManifest = manifest(),
            resources = resources("""
                function execute(query, page) {
                  var result = Qt.translate('张三', 'vp', {person_name:true, first_capitalize:false, ner:2});
                  return Response.success([{text:result.translateText}], '');
                }
            """.trimIndent()),
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "qt-wiring",
        ))

        assertEquals("vp", capturedTarget)
        assertEquals("true", capturedOptions["person_name"])
        assertEquals("false", capturedOptions["first_capitalize"])
        assertEquals("2", capturedOptions["ner"])
        val row = (result.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("translated", row.string("text"))
    }

    @Test
    fun websocketHeadersAndFrameShapeCrossCompatibilityFacade() {
        var capturedHeaders = emptyMap<String, String>()
        var capturedMessages = emptyList<String>()
        val websocket = SourceWebSocketBroker { _, request ->
            capturedHeaders = request.headers
            capturedMessages = request.messages
            SourcePlatformResult.Success(SourceWebSocketResponse(
                messages = emptyList(),
                closeCode = 1000,
                closeReason = "fixture",
                traceId = request.traceId,
                frames = listOf(SourceWebSocketFrame("text", "pong")),
            ))
        }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(websocket = websocket))
        val result = success(runtime.executeDeclared(
            sourceManifest = manifest(),
            resources = resources("""
                function execute(query, page) {
                  var ws = new WebSocket('wss://socket.example/ws', {'X-Test':'yes'});
                  ws.connect();
                  ws.send('ping');
                  var frame = ws.message();
                  ws.close();
                  return Response.success([{type:String(frame.type), data:String(frame.data)}], '');
                }
            """.trimIndent()),
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "ws-wiring",
        ))

        assertEquals("yes", capturedHeaders["X-Test"])
        assertEquals(listOf("ping"), capturedMessages)
        val row = (result.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("text", row.string("type"))
        assertEquals("pong", row.string("data"))
    }

    @Test
    fun fetchQueriesAreInsertedBeforeFragment() {
        var capturedUrl = ""
        val network = SourceNetworkBroker { _, request ->
            capturedUrl = request.url
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = emptyMap(),
                body = "ok".toByteArray(),
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(1, 1),
                traceId = request.traceId,
                requestUrl = request.url,
            ))
        }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(network = network))
        success(runtime.executeDeclared(
            sourceManifest = manifest(network = true),
            resources = resources("""
                function execute(query, page) {
                  fetch('https://x.example/path#chapter', {queries:{q:'a b'}}).text();
                  return Response.success([], '');
                }
            """.trimIndent()),
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "query-fragment",
        ))

        assertEquals("https://x.example/path?q=a%20b#chapter", capturedUrl)
    }

    @Test
    fun scannerTracksUtilityHostApis() {
        val audit = VBookCorpusAnalyzer.audit(
            "utilities",
            CURRENT_PLUGIN,
            mapOf("src/search.js" to """
                function execute(q,p){
                  console.log(UserAgent.chrome());
                  sleep(1);
                  return Response.success([], '');
                }
            """.trimIndent(), "src/explore.js" to "function execute(){return Response.success([]);}"),
        )
        assertTrue(VBookFeature.USER_AGENT in audit.features)
        assertTrue(VBookFeature.SLEEP in audit.features)
        assertTrue(VBookFeature.LOGGING in audit.features)
    }

    private fun success(result: SourcePlatformResult<VBookCompatibilityRuntime.ExecutionResult>): SourcePlatformResult.Success<VBookCompatibilityRuntime.ExecutionResult> {
        if (result is SourcePlatformResult.Success) return result
        val failure = result as SourcePlatformResult.Failure
        fail("VBOOK_HOST_WIRING_FAILURE code=${failure.error.code} message=${failure.error.message} cause=${failure.error.cause?.javaClass?.name}:${failure.error.cause?.message}")
        error("unreachable")
    }

    private fun manifest(network: Boolean = false): SourceManifest = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.current.host",
        name = "VBook current host fixture",
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
        ),
        actions = emptyMap(),
    )

    private fun resources(searchScript: String): SourceResourceProvider {
        val values = mapOf(
            "plugin.json" to CURRENT_PLUGIN.toByteArray(),
            "src/search.js" to searchScript.toByteArray(),
            "src/explore.js" to "function execute(){return Response.success([]);}".toByteArray(),
        )
        return object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? = values[path]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
    }

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
