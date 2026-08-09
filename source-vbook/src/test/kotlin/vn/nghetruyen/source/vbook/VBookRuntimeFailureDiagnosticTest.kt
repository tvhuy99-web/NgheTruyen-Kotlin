package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookRuntimeFailureDiagnosticTest {
    @Test
    fun scriptThatDoesNotUseStorageDoesNotRequireStorageBroker() {
        val result = execute("function execute(q,p){return Response.success([{name:q+'@'+DOMAIN}],p+'/next');}")
        val success = result as? SourcePlatformResult.Success ?: failWithCause(result)
        val item = ((success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj)
        assertEquals("q@configured.example", item.string("name"))
        assertEquals("cursor/next", success.value.continuation.token)
    }

    @Test
    fun deniedStorageStillRejectsActualMutation() {
        val result = execute("function execute(q,p){localStorage.setItem('k','v');return Response.success([],'');}")
        val failure = result as? SourcePlatformResult.Failure ?: fail("Expected denied storage mutation to fail")
        assertTrue(failure.error.message.contains("SOURCE_STORAGE_BROKER_UNAVAILABLE"))
    }

    private fun execute(script: String): SourcePlatformResult<VBookCompatibilityRuntime.ExecutionResult> {
        val plugin = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"explore":"explore.js","search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{"DOMAIN":{"title":"Domain","default":"default.example","mode":"input","format":"text"}}
            }
        """.trimIndent()
        val files = mapOf(
            "plugin.json" to plugin.toByteArray(),
            "src/search.js" to script.toByteArray(),
            "src/explore.js" to "function execute(){return Response.success([]);}".toByteArray(),
        )
        val resources = object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? = files[path]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
        val manifest = SourceManifest(
            schemaVersion = 2,
            id = "test.vbook.storage-optional",
            name = "VBook storage optional",
            version = SemanticVersion(1, 0, 0),
            apiVersion = 2,
            contentType = SourceContentType.NOVEL,
            runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
            origins = setOf("https://x.example"),
            capabilities = SourceCapabilities(),
            actions = emptyMap(),
        )
        return VBookCompatibilityRuntime().executeDeclared(
            sourceManifest = manifest,
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            continuation = VBookContinuation("cursor"),
            runtimeConfig = mapOf("DOMAIN" to "configured.example"),
            traceId = "storage-optional",
        )
    }

    private fun failWithCause(result: SourcePlatformResult<*>): Nothing {
        val failure = result as? SourcePlatformResult.Failure
        fail(
            "VBOOK_RUNTIME_FAILURE code=${failure?.error?.code} message=${failure?.error?.message} " +
                "cause=${failure?.error?.cause?.javaClass?.name}:${failure?.error?.cause?.message}",
        )
        error("unreachable")
    }
}
