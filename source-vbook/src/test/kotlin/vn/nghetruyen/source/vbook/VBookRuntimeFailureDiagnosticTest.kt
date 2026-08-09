package vn.nghetruyen.source.vbook

import org.junit.Assert.fail
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

/** Keeps the focused CI useful: if the facade regresses, print its own failure rather than a cast. */
class VBookRuntimeFailureDiagnosticTest {
    @Test
    fun currentSearchFailureReportsRuntimeCause() {
        val plugin = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"explore":"explore.js","search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{"DOMAIN":{"title":"Domain","default":"default.example","mode":"input","format":"text"}}
            }
        """.trimIndent()
        val files = mapOf(
            "plugin.json" to plugin.toByteArray(),
            "src/search.js" to "function execute(q,p){return Response.success([{name:q+'@'+DOMAIN}],p+'/next');}".toByteArray(),
            "src/explore.js" to "function execute(){return Response.success([]);}".toByteArray(),
        )
        val resources = object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? = files[path]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
        val manifest = SourceManifest(
            schemaVersion = 2,
            id = "test.vbook.diagnostic",
            name = "VBook diagnostic",
            version = SemanticVersion(1, 0, 0),
            apiVersion = 2,
            contentType = SourceContentType.NOVEL,
            runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
            origins = setOf("https://x.example"),
            capabilities = SourceCapabilities(),
            actions = emptyMap(),
        )
        when (val result = VBookCompatibilityRuntime().executeDeclared(
            sourceManifest = manifest,
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "q",
            continuation = VBookContinuation("cursor"),
            runtimeConfig = mapOf("DOMAIN" to "configured.example"),
            traceId = "diagnostic",
        )) {
            is SourcePlatformResult.Success -> Unit
            is SourcePlatformResult.Failure -> fail(
                "VBOOK_DIAGNOSTIC_FAILURE code=${result.error.code} message=${result.error.message} cause=${result.error.cause?.javaClass?.name}:${result.error.cause?.message}",
            )
        }
    }
}
