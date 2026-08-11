package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookScriptRole

@RunWith(AndroidJUnit4::class)
class AndroidChromiumVBookLifecycleTest {
    @Test
    fun queuedReaderEventReplaysIntoChromiumLifecycleListenerBeforeExecute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = "test.vbook.chromium.lifecycle"
        SourceHostEventBus.unregister(sourceId)
        SourceHostEventBus.drain(sourceId)
        SourceHostEventBus.emit(
            sourceId,
            SourceHostKernelContract.event(
                "reader.enter",
                JsonValue.Obj(linkedMapOf("chapterId" to JsonValue.Str("chapter-chromium-42"))),
            ),
            "event-before-chromium",
        )
        val host = SourceHostKernelDispatcher()
            .register("hooks", "poll") { requestedSourceId, payload, traceId ->
                val name = (payload.values["name"] as? JsonValue.Str)?.value
                val events = SourceHostEventBus.drain(requestedSourceId, name)
                SourcePlatformResult.Success(JsonValue.Obj(linkedMapOf(
                    "events" to JsonValue.Arr(events.map(SourceHostKernelContract::encode)),
                    "traceId" to JsonValue.Str(traceId),
                )))
            }
        val brokers = SourceCapabilityBrokers(hostKernel = host)
        AndroidChromiumVBookRuntime(context, brokers).use { chromium ->
            val runtime = VBookCompatibilityRuntime(chromium)
            val result = runtime.executeDeclared(
                sourceManifest = manifest(sourceId),
                resources = resources(),
                role = VBookScriptRole.SEARCH,
                input = "q",
                traceId = "chromium-lifecycle-e2e",
            )

            assertTrue(result is SourcePlatformResult.Success)
            val success = result as SourcePlatformResult.Success
            val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
            assertEquals("chromium", row.string("engine"))
            assertEquals("chapter-chromium-42", row.string("chapterId"))
            assertTrue(SourceHostEventBus.drain(sourceId).isEmpty())
        }
    }

    private fun manifest(sourceId: String) = SourceManifest(
        schemaVersion = 2,
        id = sourceId,
        name = "Chromium lifecycle fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources(): SourceResourceProvider {
        val values = mapOf(
            "plugin.json" to CURRENT_PLUGIN.toByteArray(),
            "src/search.js" to """
                var enteredChapter = '';
                var engineName = ({name:'chromium'})?.name;
                App.lifecycle.on('reader.enter', function(event) {
                  enteredChapter = String(event.chapterId || '');
                });
                function execute(query, page) {
                  return Response.success([{engine:engineName,chapterId:enteredChapter}], '');
                }
            """.trimIndent().toByteArray(),
            "src/explore.js" to "function execute(){return Response.success([]);}".toByteArray(),
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
