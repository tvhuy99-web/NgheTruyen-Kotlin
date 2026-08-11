package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceHostEventBus
import vn.nghetruyen.source.api.SourceHostKernelContract
import vn.nghetruyen.source.api.SourceHostKernelDispatcher
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookHostLifecycleEventIntegrationTest {
    @Test
    fun queuedReaderEventReplaysIntoTopLevelLifecycleListenerBeforeExecute() {
        val sourceId = "test.vbook.lifecycle"
        SourceHostEventBus.unregister(sourceId)
        SourceHostEventBus.drain(sourceId)
        SourceHostEventBus.emit(
            sourceId,
            SourceHostKernelContract.event(
                "reader.enter",
                JsonValue.Obj(linkedMapOf("chapterId" to JsonValue.Str("chapter-42"))),
            ),
            "event-before-runtime",
        )

        val host = SourceHostKernelDispatcher()
            .register("hooks", "poll") { requestedSourceId, payload, traceId ->
                val name = (payload.values["name"] as? JsonValue.Str)?.value
                val events = SourceHostEventBus.drain(requestedSourceId, name)
                SourcePlatformResult.Success(
                    JsonValue.Obj(linkedMapOf(
                        "events" to JsonValue.Arr(events.map(SourceHostKernelContract::encode)),
                        "traceId" to JsonValue.Str(traceId),
                    )),
                )
            }
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(hostKernel = host))
        val resources = resources(
            mapOf(
                "src/search.js" to """
                    var enteredChapter = '';
                    App.lifecycle.on('reader.enter', function(event) {
                      enteredChapter = String(event.chapterId || '');
                    });
                    function execute(query, page) {
                      return Response.success([{chapterId:enteredChapter}], '');
                    }
                """.trimIndent(),
                "src/explore.js" to "function execute(){return Response.success([]);}",
            ),
        )

        val result = runtime.executeDeclared(
            sourceManifest = manifest(sourceId),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "lifecycle-e2e",
        )

        assertTrue(result is SourcePlatformResult.Success)
        val success = result as SourcePlatformResult.Success
        val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("chapter-42", row.string("chapterId"))
        assertTrue(SourceHostEventBus.drain(sourceId).isEmpty())
    }

    private fun manifest(sourceId: String): SourceManifest = SourceManifest(
        schemaVersion = 2,
        id = sourceId,
        name = "VBook lifecycle fixture",
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
