package vn.nghetruyen.source.vbook

import com.nghetruyen.source.platform.SourceCompatibilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookCleartextCookieFeatureTest {
    @Test
    fun cleartextConfigPlusLocalCookieProducesExplicitBlockingFeature() {
        val scripts = coreScripts(chap = "function execute(url){localCookie.setCookie('http://legacy.example','a=1');return Response.success('ok');}")
        val audit = VBookCorpusAnalyzer.audit("legacy-cookie", plugin("http://legacy.example"), scripts)
        assertTrue(VBookFeature.LEGACY_HTTP_SOURCE in audit.features)
        assertTrue(VBookFeature.LOCAL_COOKIE in audit.features)
        assertTrue(VBookFeature.LOCAL_COOKIE_CLEARTEXT in audit.features)

        val validation = VBookCandidateValidator().validate(VBookCandidate("legacy-cookie", plugin("http://legacy.example"), scripts))
        assertEquals(SourceCompatibilityState.UNSUPPORTED, validation.state)
        assertEquals(
            VBookFeatureImplementationLevel.EXPLICITLY_UNSUPPORTED,
            VBookEngineFeatureMatrix.support(VBookFeature.LOCAL_COOKIE_CLEARTEXT).implementation,
        )
        assertTrue(VBookFeature.LOCAL_COOKIE_CLEARTEXT in validation.blockingFeatures)
        assertFalse(validation.activatable)
    }

    @Test
    fun cleartextWithoutLocalCookieDoesNotInventCookieBlocker() {
        val scripts = coreScripts(chap = "function execute(url){return Response.success(fetch('http://legacy.example/ch').text());}")
        val audit = VBookCorpusAnalyzer.audit("legacy-http", plugin("http://legacy.example"), scripts)
        assertTrue(VBookFeature.LEGACY_HTTP_SOURCE in audit.features)
        assertFalse(VBookFeature.LOCAL_COOKIE_CLEARTEXT in audit.features)
    }

    private fun plugin(domain: String): String = """
        {
          "metadata":{"name":"Legacy HTTP","author":"test","version":1,"source":"https://catalog.example","description":"","locale":"vi","regexp":"legacy","type":"novel","nsfw":false},
          "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
          "config":{"DOMAIN":{"title":"Domain","default":"$domain","mode":"input","format":"text"}}
        }
    """.trimIndent()

    private fun coreScripts(chap: String): Map<String, String> = mapOf(
        "src/search.js" to "function execute(q,p){return Response.success([], '');}",
        "src/detail.js" to "function execute(url){return Response.success({name:'x',url:url});}",
        "src/toc.js" to "function execute(url){return Response.success([]);}",
        "src/chap.js" to chap,
    )
}
