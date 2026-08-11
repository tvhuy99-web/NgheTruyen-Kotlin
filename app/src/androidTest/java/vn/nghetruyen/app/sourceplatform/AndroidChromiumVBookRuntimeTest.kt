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
import vn.nghetruyen.source.api.SourceCryptoBroker
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceHostCommand
import vn.nghetruyen.source.api.SourceHostKernelBroker
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookContractProfile
import vn.nghetruyen.source.vbook.VBookScriptRole
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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

            assertTrue(
                (result as? SourcePlatformResult.Failure)?.let { "${it.error.code}:${it.error.message}" } ?: "expected success",
                result is SourcePlatformResult.Success,
            )
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

    @Test
    fun applicationPrimaryChromiumSupportsDomNetworkStorageAndCrypto() {
        val storageValues = ConcurrentHashMap<String, ByteArray>()
        val storage = object : SourceStorageBroker {
            override fun get(manifest: SourceManifest, request: SourceStorageRequest) =
                SourcePlatformResult.Success(storageValues[request.key]?.copyOf())

            override fun put(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> {
                storageValues[request.key] = request.value?.copyOf() ?: ByteArray(0)
                return SourcePlatformResult.Success(Unit)
            }

            override fun delete(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> {
                storageValues.remove(request.key)
                return SourcePlatformResult.Success(Unit)
            }

            override fun keys(manifest: SourceManifest, sourceId: String, prefix: String, traceId: String) =
                SourcePlatformResult.Success(storageValues.keys.filter { it.startsWith(prefix) }.sorted())

            override fun clearPrefix(manifest: SourceManifest, sourceId: String, prefix: String, traceId: String): SourcePlatformResult<Unit> {
                storageValues.keys.filter { it.startsWith(prefix) }.forEach(storageValues::remove)
                return SourcePlatformResult.Success(Unit)
            }

            override fun clear(sourceId: String): SourcePlatformResult<Unit> {
                storageValues.clear()
                return SourcePlatformResult.Success(Unit)
            }
        }
        var networkCalls = 0
        val network = SourceNetworkBroker { _, request ->
            networkCalls += 1
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("Content-Type" to listOf("text/plain; charset=utf-8")),
                body = "broker-body".toByteArray(),
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(1L, 2L),
                traceId = request.traceId,
                statusText = "OK",
                requestUrl = request.url,
                requestHeaders = request.headers.mapValues { listOf(it.value) },
            ))
        }
        val crypto = SourceCryptoBroker { _, request ->
            val bytes = when (request.operation) {
                SourceCryptoOperation.MD5 -> MessageDigest.getInstance("MD5").digest(request.payload)
                SourceCryptoOperation.SHA1 -> MessageDigest.getInstance("SHA-1").digest(request.payload)
                SourceCryptoOperation.SHA256 -> MessageDigest.getInstance("SHA-256").digest(request.payload)
                SourceCryptoOperation.SHA512 -> MessageDigest.getInstance("SHA-512").digest(request.payload)
                else -> error("TEST_CRYPTO_OPERATION_UNEXPECTED:${request.operation}")
            }
            SourcePlatformResult.Success(bytes)
        }
        val brokers = SourceCapabilityBrokers(
            network = network,
            storage = storage,
            crypto = crypto,
        )
        val runtime = VBookCompatibilityRuntime(brokers)
        val resources = resources(
            CURRENT_PLUGIN,
            mapOf(
                "src/search.js" to """
                    function execute(query, page) {
                      localStorage.setItem('fixture-key', 'fixture-value');
                      var doc = Html.parse('<a class="item" href="/chapter/1"><b>Hello</b> World</a>', 'https://x.example/base/');
                      var item = doc.selectFirst('a.item');
                      var response = fetch('https://x.example/api');
                      return Response.success([{
                        engine:({name:'chromium'})?.name,
                        stored:localStorage.getItem('fixture-key'),
                        text:item.text(),
                        href:item.absUrl('href'),
                        body:response.text(),
                        hash:Crypto.sha256('abc')
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
            traceId = "chromium-core-parity",
        )

        assertTrue(
            (result as? SourcePlatformResult.Failure)?.let { "${it.error.code}:${it.error.message}" } ?: "expected success",
            result is SourcePlatformResult.Success,
        )
        val success = result as SourcePlatformResult.Success
        val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("chromium", row.string("engine"))
        assertEquals("fixture-value", row.string("stored"))
        assertEquals("Hello World", row.string("text"))
        assertEquals("https://x.example/chapter/1", row.string("href"))
        assertEquals("broker-body", row.string("body"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", row.string("hash"))
        assertEquals(1, networkCalls)
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
