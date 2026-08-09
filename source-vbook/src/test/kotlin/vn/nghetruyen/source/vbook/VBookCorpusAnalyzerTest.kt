package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookCorpusAnalyzerTest {
    @Test
    fun hostApisInsideCommentsAndGeneratedPageScriptsAreNotCorpusRequirements() {
        val audit = VBookCorpusAnalyzer.audit(
            "generated-page-script",
            PLUGIN,
            mapOf("src/search.js" to """
                function execute(query, page) {
                  // new WebSocket('wss://comment.invalid'); Qt.translate('x', 'vp', {});
                  var html = "<script>var ws = new WebSocket('wss://page.example'); ws.onmessage=function(e){};<" + "/script>";
                  var browser = Engine.newBrowser();
                  browser.loadHtml(html, 'https://page.example/');
                  return Response.success([], '');
                }
            """.trimIndent(),
            "src/detail.js" to response("{}"),
            "src/toc.js" to response("[]"),
            "src/chap.js" to response("'text'")),
        )

        assertFalse(VBookFeature.WEBSOCKET in audit.features)
        assertFalse(VBookFeature.QUICK_TRANSLATOR_OPTIONS in audit.features)
        assertTrue(VBookFeature.BROWSER in audit.features)
        assertTrue(VBookFeature.BROWSER_LOAD_HTML in audit.features)
    }

    @Test
    fun executableHostApisRemainVisibleAfterLexicalMasking() {
        val audit = VBookCorpusAnalyzer.audit(
            "real-host-api",
            PLUGIN,
            mapOf("src/search.js" to """
                function execute(query, page) {
                  var ws = new WebSocket('wss://socket.example', {'X-Test':'yes'});
                  var translated = Qt.translate('x', 'vp', {person_name:true});
                  return Response.success([], '');
                }
            """.trimIndent(),
            "src/detail.js" to response("{}"),
            "src/toc.js" to response("[]"),
            "src/chap.js" to response("'text'")),
        )

        assertTrue(VBookFeature.WEBSOCKET in audit.features)
        assertTrue(VBookFeature.WEBSOCKET_HEADERS in audit.features)
        assertTrue(VBookFeature.QUICK_TRANSLATOR_OPTIONS in audit.features)
    }

    @Test
    fun charsetLiteralIsDetectedOnlyInExecutableCode() {
        val audit = VBookCorpusAnalyzer.audit(
            "charset-mask",
            PLUGIN,
            mapOf(
                "src/search.js" to """
                    function execute(query, page) {
                      var generated = "<script>response.text('shift_jis');<" + "/script>";
                      var plain = fetch('https://example.org/plain').text();
                      var decoded = fetch('https://example.org/gbk').text('gbk');
                      return Response.success([], '');
                    }
                """.trimIndent(),
                "src/detail.js" to response("{}"),
                "src/toc.js" to response("[]"),
                "src/chap.js" to response("'text'"),
            ),
        )

        assertTrue(VBookFeature.FETCH_CHARSET in audit.features)
        assertTrue(audit.evidence.any { it.feature == VBookFeature.FETCH_CHARSET && "gbk" in it.evidence })
        assertFalse(audit.evidence.any { it.feature == VBookFeature.FETCH_CHARSET && "shift_jis" in it.evidence })
    }

    @Test
    fun scansHostApisDynamicScriptsAndSubFeatures() {
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
                      var doc = response.html();
                      doc.select('script').forEach(function(el){el.remove();});
                      var attrs = doc.select('a').first().attributes();
                      var browser = Engine.newBrowser();
                      browser.loadHtml('<html></html>', DOMAIN);
                      browser.waitUrl(['*api*'], 1000);
                      localStorage.setItem('token', token);
                      var translated = Qt.translate('你好', 'vp', {person_name:true});
                      var segments = translated.segments;
                      var ws = new WebSocket('wss://ws.example', {'X-Test':'one'});
                      var frame = ws.message();
                      return Response.success([], 'cursor-2');
                    }
                """.trimIndent(),
                "src/home.js" to "function execute(){ return Response.success([{title:'All',input:'',data:'server-a',script:'listing.js'}]); }",
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
        assertTrue(VBookFeature.DYNAMIC_DATA_ARGUMENT in audit.features)
        assertTrue(VBookFeature.HTML_COLLECTION_CALLBACKS in audit.features)
        assertTrue(VBookFeature.HTML_MUTATION in audit.features)
        assertTrue(VBookFeature.HTML_ATTRIBUTES in audit.features)
        assertTrue(VBookFeature.BROWSER_LOAD_HTML in audit.features)
        assertTrue(VBookFeature.BROWSER_WAIT_URL in audit.features)
        assertTrue(VBookFeature.WEBSOCKET_HEADERS in audit.features)
        assertTrue(VBookFeature.WEBSOCKET_FRAMES in audit.features)
        assertTrue(VBookFeature.QUICK_TRANSLATOR_OPTIONS in audit.features)
        assertTrue(VBookFeature.QUICK_TRANSLATOR_SEGMENTS in audit.features)
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

    private fun response(data: String): String = "function execute(){return Response.success($data, '');}"

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
                "thread_num":4,
                "timeout":15000,
                "delay":250,
                "DOMAIN":{"title":"Domain","default":"https://x.example","mode":"input","format":"text"}
              }
            }
        """.trimIndent()
    }
}
