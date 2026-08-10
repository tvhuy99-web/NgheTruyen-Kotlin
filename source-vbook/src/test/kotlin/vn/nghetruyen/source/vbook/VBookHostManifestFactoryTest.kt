package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookHostManifestFactoryTest {
    @Test
    fun legacyHttpExtensionGetsScopedCleartextWithoutChangingNativeContract() {
        val plugin = VBookManifestParser.parse(PLUGIN_HTTP)
        val manifest = VBookHostManifestFactory.create("repo\nlegacy.zip", plugin, resources(SCRIPTS_HTTP))
        assertEquals(SourceRuntimeMode.VBOOK_JS_COMPAT, manifest.runtime.mode)
        assertTrue(manifest.capabilities.network!!.publicInternet)
        assertTrue(manifest.capabilities.network!!.allowCleartext)
        assertTrue(manifest.origins.single().startsWith("http://"))
        manifest.validate()
    }

    @Test
    fun httpsExtensionDoesNotReceiveCleartextCapability() {
        val plugin = VBookManifestParser.parse(PLUGIN_HTTPS)
        val manifest = VBookHostManifestFactory.create("repo\nhttps.zip", plugin, resources(SCRIPTS_HTTPS))
        assertTrue(manifest.capabilities.network!!.publicInternet)
        assertFalse(manifest.capabilities.network!!.allowCleartext)
        assertEquals("https://x.example", manifest.origins.single())
    }

    @Test
    fun scriptLiteralCanRequestLegacyCleartextEvenWhenMetadataIsHttps() {
        val plugin = VBookManifestParser.parse(PLUGIN_HTTPS)
        val manifest = VBookHostManifestFactory.create("repo\nscript-http.zip", plugin, resources(SCRIPTS_HTTP))
        assertTrue(manifest.capabilities.network!!.allowCleartext)
    }

    private fun resources(scripts: Map<String, String>): SourceResourceProvider = object : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? = scripts[path]?.toByteArray()?.takeIf { it.size <= maxBytes }
    }

    companion object {
        private val commonScriptJson = """
            "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
            "config":{"DOMAIN":{"title":"Domain","default":"https://x.example","mode":"input","format":"text"}}
        """.trimIndent()
        private val PLUGIN_HTTP = """
            {"metadata":{"name":"x","author":"a","version":1,"source":"http://legacy.example","description":"","locale":"vi_VN","regexp":"x","type":"novel","language":"javascript","encrypt":false},$commonScriptJson}
        """.trimIndent()
        private val PLUGIN_HTTPS = """
            {"metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi_VN","regexp":"x","type":"novel","language":"javascript","encrypt":false},$commonScriptJson}
        """.trimIndent()
        private val SCRIPTS_HTTPS = mapOf(
            "src/search.js" to "function execute(q,p){return Response.success([],p);}",
            "src/detail.js" to "function execute(u){return Response.success({url:u});}",
            "src/toc.js" to "function execute(u){return Response.success([]);}",
            "src/chap.js" to "function execute(u){return Response.success('x');}",
        )
        private val SCRIPTS_HTTP = SCRIPTS_HTTPS + ("src/search.js" to "function execute(q,p){fetch('http://legacy.example/api');return Response.success([],p);}")
    }
}
