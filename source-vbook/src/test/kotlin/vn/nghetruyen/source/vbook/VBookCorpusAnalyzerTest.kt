package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookCorpusAnalyzerTest {
    @Test
    fun scansHostApisAndDynamicScripts() {
        val audit = VBookCorpusAnalyzer.audit(
            id = "sample",
            pluginJson = PLUGIN,
            scripts = mapOf(
                "src/search.js" to """
                    load('crypto.js');
                    function execute(query, page) {
                      var response = fetch(DOMAIN + '/search', {queries:{q:query}, timeout:3000});
                      var token = response.request.url;
                      var body = response.text('gbk');
                      var blob = response.blob();
                      localStorage.setItem('token', token);
                      return Response.success([], 'cursor-2');
                    }
                """.trimIndent(),
                "src/home.js" to "function execute(){ return Response.success([{title:'All',input:'',script:'listing.js'}]); }",
                "src/listing.js" to "function execute(input,page){ return Response.success([], ''); }",
                "src/detail.js" to "function execute(url){ return Response.success({name:'X',url:url}); }",
                "src/toc.js" to "function execute(url){ return Response.success([]); }",
                "src/chap.js" to "function execute(url){ return Response.success('<p>x</p>'); }",
                "src/explore.js" to "function execute(){ return Response.success([]); }",
            ),
        )

        assertEquals(VBookContractProfile.CURRENT_JS, audit.detection.profile)
        assertTrue(VBookFeature.FETCH_QUERIES in audit.features)
        assertTrue(VBookFeature.FETCH_CHARSET in audit.features)
        assertTrue(VBookFeature.FETCH_BLOB in audit.features)
        assertTrue(VBookFeature.FETCH_REQUEST_INFO in audit.features)
        assertTrue(VBookFeature.LOAD_CRYPTO_BUILTIN in audit.features)
        assertTrue("src/listing.js" in audit.referencedDynamicScripts)
        assertTrue(audit.missingReferencedScripts.isEmpty())
        assertTrue(audit.missingRequiredScripts.isEmpty())
    }

    @Test
    fun reportsMissingDynamicFileWithoutTreatingWebsiteAsEngineFailure() {
        val audit = VBookCorpusAnalyzer.audit(
            id = "broken-package",
            pluginJson = PLUGIN,
            scripts = mapOf(
                "src/home.js" to "function execute(){return Response.success([{title:'x',input:'',script:'missing.js'}]);}",
                "src/search.js" to "function execute(a,b){return Response.success([], '');}",
                "src/detail.js" to "function execute(a){return Response.success({name:'x',url:a});}",
                "src/toc.js" to "function execute(a){return Response.success([]);}",
                "src/chap.js" to "function execute(a){return Response.success('x');}",
                "src/explore.js" to "function execute(){return Response.success([]);}",
            ),
        )
        assertEquals(setOf("src/missing.js"), audit.missingReferencedScripts)
        assertFalse(audit.missingReferencedScripts.isEmpty())
    }

    @Test
    fun repositoryIndexAcceptsAllCatalogEntriesWithoutHardcodedOwners() {
        val rows = VBookRepositoryIndexParser.parse(
            """[
              {"link":"https://example.invalid/a.json","author":"A","description":"one"},
              {"link":"https://example.invalid/b.json","author":"B","description":"two"}
            ]""",
        )
        assertEquals(2, rows.size)
        assertEquals("B", rows[1].author)
    }

    companion object {
        private val PLUGIN = """
            {
              "metadata": {
                "name":"X", "author":"A", "version":1, "source":"https://x.example",
                "description":"", "locale":"vi", "regexp":"x", "type":"novel", "encrypt":true
              },
              "script": {
                "home":"home.js", "explore":"explore.js", "search":"search.js",
                "detail":"detail.js", "toc":"toc.js", "chap":"chap.js"
              },
              "config": {
                "DOMAIN":{"title":"Domain","default":"https://x.example","mode":"input","format":"text"}
              }
            }
        """.trimIndent()
    }
}
