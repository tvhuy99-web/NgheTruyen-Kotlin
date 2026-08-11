package vn.nghetruyen.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import java.net.InetAddress

class SourceNetworkPolicyVBookTest {
    @Test
    fun nativeRuntimeMayUseFullPublicInternetAuthority() {
        val manifest = manifest(
            mode = SourceRuntimeMode.DECLARATIVE,
            network = SourceNetworkCapability(publicInternet = true, allowCleartext = true),
        )
        manifest.validate()
        assertEquals("cdn.example", SourceOriginPolicy.requireInitialUrl(manifest, "https://cdn.example/x").host)
        assertEquals("http", SourceOriginPolicy.requireInitialUrl(manifest, "http://legacy.example/x").scheme)
    }

    @Test
    fun nativeRuntimeWithoutFullAuthorityStillRequiresDeclaredOrigin() {
        val manifest = manifest(SourceRuntimeMode.DECLARATIVE, SourceNetworkCapability())
        assertEquals("a.example", SourceOriginPolicy.requireInitialUrl(manifest, "https://a.example/x").host)
        val error = runCatching { SourceOriginPolicy.requireInitialUrl(manifest, "https://cdn.example/x") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("ORIGIN_DENIED"))
    }

    @Test
    fun vbookPublicInternetAllowsOtherPublicHttpsHosts() {
        val manifest = manifest(
            SourceRuntimeMode.VBOOK_JS_COMPAT,
            SourceNetworkCapability(publicInternet = true),
        )
        assertEquals("cdn.example", SourceOriginPolicy.requireInitialUrl(manifest, "https://cdn.example/a.js").host)
    }

    @Test
    fun cleartextRequiresExplicitPublicInternetCapability() {
        val httpsOnly = manifest(
            SourceRuntimeMode.VBOOK_JS_COMPAT,
            SourceNetworkCapability(publicInternet = true, allowCleartext = false),
        )
        assertTrue(runCatching { SourceOriginPolicy.requireInitialUrl(httpsOnly, "http://legacy.example/a") }.isFailure)

        val fullAuthority = manifest(
            SourceRuntimeMode.VBOOK_JS_COMPAT,
            SourceNetworkCapability(publicInternet = true, allowCleartext = true),
        )
        assertEquals("http", SourceOriginPolicy.requireInitialUrl(fullAuthority, "http://legacy.example/a").scheme)
    }

    @Test
    fun publicInternetStillDoesNotMakePrivateAddressesPublic() {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("127.0.0.1")))
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("192.168.1.1")))
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
    }

    private fun manifest(mode: SourceRuntimeMode, network: SourceNetworkCapability): SourceManifest = SourceManifest(
        schemaVersion = 2,
        id = "test.source.policy",
        name = "Policy fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode),
        origins = setOf("https://a.example"),
        capabilities = SourceCapabilities(network = network),
        actions = mapOf(
            SourceActionName.DETAIL to SourceActionSpec("src/detail.js"),
            SourceActionName.TOC to SourceActionSpec("src/toc.js"),
            SourceActionName.CHAPTER to SourceActionSpec("src/chap.js"),
        ),
    )
}
