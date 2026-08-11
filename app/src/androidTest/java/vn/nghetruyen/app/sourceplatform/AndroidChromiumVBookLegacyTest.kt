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
class AndroidChromiumVBookLegacyTest {
    @Test
    fun legacyVBookUsesChromiumAndSameAppHostCommandSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var capturedCommand: SourceHostCommand? = null
        val host = SourceHostKernelBroker { _, command, traceId ->
            capturedCommand = command
            SourcePlatformResult.Success(JsonValue.Obj(linkedMapOf(
                "accepted" to JsonValue.Bool(true),
                "traceId" to JsonValue.Str(traceId),
            )))
        }
        val brokers = SourceCapabilityBrokers(hostKernel = host)
        AndroidChromiumVBookRuntime(context, brokers).use { chromium ->
            val runtime = VBookCompatibilityRuntime(chromium)
            val result = runtime.executeDeclared(
                sourceManifest = manifest(),
                resources = resources(),
                role = VBookScriptRole.SEARCH,
                input = "legacy-q",
                traceId = "chromium-legacy-host",
            )

            assertTrue(result is SourcePlatformResult.Success)
            val success = result as SourcePlatformResult.Success
            val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
            assertEquals(VBookContractProfile.LEGACY_JS, success.value.profile)
            assertEquals("chromium", row.string("engine"))
            assertEquals("true", row.string("accepted"))
            assertEquals("chromium-legacy-host", row.string("traceId"))
            assertEquals("tts", capturedCommand?.domain)
            assertEquals("play", capturedCommand?.action)
        }
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.chromium.legacy",
        name = "Legacy Chromium fixture",
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
            "plugin.json" to LEGACY_PLUGIN.toByteArray(),
            "src/search.js" to """
                function execute(query, page) {
                  var engine = ({name:'chromium'})?.name;
                  var hostResult = App.tts.play();
                  return Response.success([{
                    engine:String(engine),
                    accepted:String(hostResult.accepted),
                    traceId:String(hostResult.traceId)
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
        private val LEGACY_PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi_VN","regexp":"x","type":"novel","language":"javascript","encrypt":false},
              "script":{"search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{"thread_num":1,"delay":0}
            }
        """.trimIndent()
    }
}
