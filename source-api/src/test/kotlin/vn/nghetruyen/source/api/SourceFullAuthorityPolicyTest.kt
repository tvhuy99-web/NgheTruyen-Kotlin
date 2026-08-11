package vn.nghetruyen.source.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFullAuthorityPolicyTest {
    @Test
    fun nativeLuaReceivesSameFullInAppCapabilities() {
        val result = SourceFullAuthorityPolicy.apply(manifest(SourceRuntimeMode.NATIVE_LUA_COMPAT))
        assertTrue(result.capabilities.network!!.publicInternet)
        assertTrue(result.capabilities.network!!.allowCleartext)
        assertEquals(setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"), result.capabilities.network!!.methods)
        assertTrue(result.capabilities.browser.navigate)
        assertTrue(result.capabilities.browser.pageJavaScript)
        assertTrue(result.capabilities.browser.requestMetadata)
        assertEquals(SourceCookieMode.BROWSER_SHARED, result.capabilities.cookies)
        assertEquals(SourceCryptoCapability.entries.toSet(), result.capabilities.crypto)
        assertTrue(result.capabilities.websocket.enabled)
        assertEquals(16 * 1024 * 1024, result.capabilities.storageBytes)
    }

    @Test
    fun declarativeRuntimeCanAlsoCarryFullAuthorityEnvelope() {
        val result = SourceFullAuthorityPolicy.apply(manifest(SourceRuntimeMode.DECLARATIVE))
        assertTrue(result.capabilities.network!!.publicInternet)
        assertTrue(result.capabilities.network!!.allowCleartext)
        result.validate()
    }

    private fun manifest(mode: SourceRuntimeMode) = SourceManifest(
        schemaVersion = SOURCE_PACK_SCHEMA_VERSION,
        id = "vn.nghetruyen.test.source",
        name = "Test source",
        version = SemanticVersion(1, 0, 0),
        apiVersion = SOURCE_API_VERSION,
        runtime = SourceRuntimePolicy(mode = mode, entry = if (mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) "native/source.lua" else null),
        origins = setOf("https://example.org"),
        capabilities = SourceCapabilities(
            network = SourceNetworkCapability(methods = setOf("GET")),
        ),
        actions = mapOf(
            SourceActionName.DETAIL to SourceActionSpec("src/detail.js"),
            SourceActionName.TOC to SourceActionSpec("src/toc.js"),
            SourceActionName.CHAPTER to SourceActionSpec("src/chapter.js"),
        ),
    )
}
