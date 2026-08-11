package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceHostCommand
import vn.nghetruyen.source.api.SourceHostKernelBroker
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookHostCommandBridgeIntegrationTest {
    @Test
    fun currentVBookAppReaderCommandExecutesThroughHostBroker() {
        var capturedSourceId = ""
        var capturedTraceId = ""
        var capturedCommand: SourceHostCommand? = null
        val host = SourceHostKernelBroker { sourceId, command, traceId ->
            capturedSourceId = sourceId
            capturedTraceId = traceId
            capturedCommand = command
            SourcePlatformResult.Success(
                JsonValue.Obj(linkedMapOf(
                    "accepted" to JsonValue.Bool(true),
                    "traceId" to JsonValue.Str(traceId),
                )),
            )
        }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(hostKernel = host))
        val resources = resources(
            mapOf(
                "src/search.js" to """
                    function execute(query, page) {
                      var hostResult = App.reader.nextChapter();
                      return Response.success([{
                        accepted:String(hostResult.accepted),
                        traceId:String(hostResult.traceId)
                      }], '');
                    }
                """.trimIndent(),
                "src/explore.js" to "function execute(){return Response.success([]);}",
            ),
        )

        val result = runtime.executeDeclared(
            sourceManifest = manifest(),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "host-bridge-e2e",
        )

        assertTrue(result is SourcePlatformResult.Success)
        val success = result as SourcePlatformResult.Success
        val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("true", row.string("accepted"))
        assertEquals("host-bridge-e2e", row.string("traceId"))
        assertEquals("test.vbook.host", capturedSourceId)
        assertEquals("host-bridge-e2e", capturedTraceId)
        assertEquals("reader", capturedCommand?.domain)
        assertEquals("nextChapter", capturedCommand?.action)
        assertTrue(capturedCommand?.payload?.values?.isEmpty() == true)
    }

    private fun manifest(): SourceManifest = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.host",
        name = "VBook host bridge fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources(scripts: Map<String, String>): SourceResourceProvider {
        val values = buildMap<String, ByteArray> {
            put("plugin.json", CURRENT_PLUGIN.toByteArray())
            scripts.forEach { (path, source) -> put(path, source.toByteArray()) }
        }
        return object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? =
                values[path]?.takeIf { it.size <= maxBytes }?.copyOf()
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
