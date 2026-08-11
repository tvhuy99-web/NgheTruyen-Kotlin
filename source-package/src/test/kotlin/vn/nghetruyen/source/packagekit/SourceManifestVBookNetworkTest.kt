package vn.nghetruyen.source.packagekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoCapability
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
    fun nativePublicInternetFlagsRoundTripWithoutRuntimeDowngrade() {
        val manifest = base(SourceRuntimeMode.DECLARATIVE).copy(
            origins = setOf("http://legacy.example"),
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(
                    methods = setOf("GET", "POST", "PUT"),
                    publicInternet = true,
                    allowCleartext = true,
                ),
            ),
        )
        manifest.validate()
        val parsed = SourceManifestParser.parse(SourceManifestWriter.write(manifest))
        assertTrue(parsed.capabilities.network!!.publicInternet)
        assertTrue(parsed.capabilities.network!!.allowCleartext)
        assertTrue("PUT" in parsed.capabilities.network!!.methods)
    }

    @Test
    fun restrictiveSignedManifestIsUpgradedAfterParsing() {
        val declared = base(SourceRuntimeMode.DECLARATIVE).copy(
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(methods = setOf("GET")),
                storageBytes = 0,
            ),
        )
        declared.validate()
        val parsed = SourceManifestParser.parse(SourceManifestWriter.write(declared))

        assertTrue(parsed.capabilities.network!!.publicInternet)
        assertTrue(parsed.capabilities.network!!.allowCleartext)
        assertEquals(setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"), parsed.capabilities.network!!.methods)
        assertEquals(SourceCookieMode.BROWSER_SHARED, parsed.capabilities.cookies)
        assertTrue(parsed.capabilities.browser.navigate)
        assertTrue(parsed.capabilities.browser.pageJavaScript)
        assertEquals(SourceCryptoCapability.entries.toSet(), parsed.capabilities.crypto)
        assertTrue(parsed.capabilities.websocket.enabled)
        assertEquals(16 * 1024 * 1024, parsed.capabilities.storageBytes)
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
