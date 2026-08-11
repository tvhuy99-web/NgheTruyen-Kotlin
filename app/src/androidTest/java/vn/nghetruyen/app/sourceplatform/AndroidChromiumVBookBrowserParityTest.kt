package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookScriptRole

@RunWith(AndroidJUnit4::class)
class AndroidChromiumVBookBrowserParityTest {
    @Test
    fun productionChromiumBrowserLaunchAndHtmlReturnDocuments() {
        var currentUrl = "https://x.example/start"
        var currentHtml = "<a class=\"item\" href=\"/one\">First</a>"
        val browser = SourceBrowserBroker { _, request ->
            when (request.action) {
                SourceBrowserAction.NAVIGATE -> {
                    currentUrl = request.url.orEmpty()
                    SourcePlatformResult.Success(SourceBrowserResponse(currentUrl, value = "", traceId = request.traceId))
                }
                SourceBrowserAction.LOAD_HTML -> {
                    currentUrl = request.url.orEmpty()
                    currentHtml = request.value.orEmpty()
                    SourcePlatformResult.Success(SourceBrowserResponse(currentUrl, value = "true", traceId = request.traceId))
                }
                SourceBrowserAction.DOM_SNAPSHOT ->
                    SourcePlatformResult.Success(SourceBrowserResponse(currentUrl, value = currentHtml, traceId = request.traceId))
                SourceBrowserAction.REQUEST_METADATA ->
                    SourcePlatformResult.Success(SourceBrowserResponse(currentUrl, value = "", traceId = request.traceId))
                else -> error("BROWSER_ACTION_UNEXPECTED:${request.action}")
            }
        }
        val storage = isolatedStorage()
        val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(browser = browser, storage = storage))
        val result = runtime.executeDeclared(
            sourceManifest = manifest(),
            resources = resources(),
            role = VBookScriptRole.SEARCH,
            input = "q",
            traceId = "chromium-browser-parity",
        )

        assertTrue(result is SourcePlatformResult.Success)
        val success = result as SourcePlatformResult.Success
        val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
        assertEquals("chromium", row.string("engine"))
        assertEquals("First", row.string("first"))
        assertEquals("https://x.example/one", row.string("firstHref"))
        assertEquals("Second", row.string("second"))
        assertEquals("https://x.example/base/two", row.string("secondHref"))
    }

    private fun isolatedStorage(): SourceStorageBroker = object : SourceStorageBroker {
        override fun get(manifest: SourceManifest, request: SourceStorageRequest) = SourcePlatformResult.Success<ByteArray?>(null)
        override fun put(manifest: SourceManifest, request: SourceStorageRequest) = SourcePlatformResult.Success(Unit)
        override fun delete(manifest: SourceManifest, request: SourceStorageRequest) = SourcePlatformResult.Success(Unit)
        override fun clear(sourceId: String) = SourcePlatformResult.Success(Unit)
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.chromium.browser",
        name = "Chromium browser parity fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources(): SourceResourceProvider {
        val values = mapOf(
            "plugin.json" to CURRENT_PLUGIN.toByteArray(),
            "src/search.js" to """
                function execute(query, page) {
                  var engine = ({name:'chromium'})?.name;
                  var browser = Engine.newBrowser();
                  var firstDoc = browser.launch('https://x.example/first');
                  var first = firstDoc.selectFirst('a.item');
                  browser.loadHtml('<div class="next"><a href="two">Second</a></div>', 'https://x.example/base/');
                  var secondDoc = browser.html();
                  var second = secondDoc.selectFirst('div.next a');
                  return Response.success([{
                    engine:engine,
                    first:first.text(),
                    firstHref:first.absUrl('href'),
                    second:second.text(),
                    secondHref:second.absUrl('href')
                  }], '');
                }
            """.trimIndent().toByteArray(),
            "src/explore.js" to "function execute(){return Response.success([]);}".toByteArray(),
        )
        return object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? =
                values[path]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
    }

    private fun JsonValue.Obj.string(name: String): String? = (values[name] as? JsonValue.Str)?.value

    companion object {
        private val CURRENT_PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"explore":"explore.js","search":"search.js","detail":"search.js","toc":"search.js","chap":"search.js"},
              "config":{}
            }
        """.trimIndent()
    }
}
