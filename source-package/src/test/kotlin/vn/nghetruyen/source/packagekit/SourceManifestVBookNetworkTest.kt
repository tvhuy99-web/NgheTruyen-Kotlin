package vn.nghetruyen.source.packagekit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy

class SourceManifestVBookNetworkTest {
    @Test
    fun vbookPublicInternetFlagsRoundTrip() {
        val manifest = base(SourceRuntimeMode.VBOOK_JS_COMPAT).copy(
            origins = setOf("http://legacy.example"),
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(
                    methods = setOf("GET", "POST"),
                    publicInternet = true,
                    allowCleartext = true,
                ),
            ),
        )
        manifest.validate()
        val parsed = SourceManifestParser.parse(SourceManifestWriter.write(manifest))
        assertTrue(parsed.capabilities.network!!.publicInternet)
        assertTrue(parsed.capabilities.network!!.allowCleartext)
    }

    @Test
    fun nativeManifestCannotSmugglePublicInternetFlagsThroughParser() {
        val manifest = base(SourceRuntimeMode.DECLARATIVE).copy(
            capabilities = SourceCapabilities(network = SourceNetworkCapability(publicInternet = false)),
        )
        val json = SourceManifestWriter.write(manifest).toString(Charsets.UTF_8)
            .replace("\"publicInternet\":false", "\"publicInternet\":true")
        val failure = runCatching { SourceManifestParser.parse(json.toByteArray()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("PUBLIC_INTERNET_MODE_DENIED"))
    }

    private fun base(mode: SourceRuntimeMode) = SourceManifest(
        schemaVersion = 2,
        id = "test.source.manifest",
        name = "fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        runtime = SourceRuntimePolicy(mode),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(network = SourceNetworkCapability()),
        actions = mapOf(
            SourceActionName.DETAIL to SourceActionSpec("src/detail.js"),
            SourceActionName.TOC to SourceActionSpec("src/toc.js"),
            SourceActionName.CHAPTER to SourceActionSpec("src/chap.js"),
        ),
    )
}
