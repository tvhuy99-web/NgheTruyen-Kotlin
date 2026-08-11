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
import vn.nghetruyen.source.api.SourceHostCommand
import vn.nghetruyen.source.api.SourceHostKernelBroker
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookContractProfile
import vn.nghetruyen.source.vbook.VBookScriptRole

@RunWith(AndroidJUnit4::class)
class AndroidChromiumVBookRuntimeTest {
    @Test
    fun chromiumExecutesCurrentVBookAppCommandEndToEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var capturedSourceId = ""
        var capturedTraceId = ""
        var capturedCommand: SourceHostCommand? = null
        val host = SourceHostKernelBroker { sourceId, command, traceId ->
            capturedSourceId = sourceId
            capturedTraceId = traceId
            capturedCommand = command
            SourcePlatformResult.Success(JsonValue.Obj(linkedMapOf(
                "accepted" to JsonValue.Bool(true),
                "traceId" to JsonValue.Str(traceId),
            )))
        }
        val brokers = SourceCapabilityBrokers(hostKernel = host)
        AndroidChromiumVBookRuntime(context, brokers).use { chromium ->
            val runtime = VBookCompatibilityRuntime(chromium)
            val resources = resources(
                CURRENT_PLUGIN,
                mapOf(
                    "src/search.js" to """
                        function execute(query, page) {
                          var modern = ({engine:'chromium'})?.engine;
                          var hostResult = App.reader.nextChapter();
                          return Response.success([{
                            engine:String(modern),
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
                traceId = "chromium-host-bridge-e2e",
            )

            assertTrue(result is SourcePlatformResult.Success)
            val success = result as SourcePlatformResult.Success
            val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
            assertEquals(VBookContractProfile.CURRENT_JS, success.value.profile)
            assertEquals("chromium", row.string("engine"))
            assertEquals("true", row.string("accepted"))
            assertEquals("chromium-host-bridge-e2e", row.string("traceId"))
            assertEquals("test.vbook.chromium", capturedSourceId)
            assertEquals("chromium-host-bridge-e2e", capturedTraceId)
            assertEquals("reader", capturedCommand?.domain)
            assertEquals("nextChapter", capturedCommand?.action)
        }
    }

    private fun manifest(): SourceManifest = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.chromium",
        name = "Chromium vBook fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources(plugin: String, scripts: Map<String, String>): SourceResourceProvider {
        val values = buildMap<String, ByteArray> {
            put("plugin.json", plugin.toByteArray())
            scripts.forEach { (path, source) -> put(path, source.toByteArray()) }
        }
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
