package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookScriptRole
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class AndroidChromiumVBookBrowserReplayTest {
    @Test
    fun productionBrowserWebViewRunsOutsideChromiumPromptAndReplaysIntoScript() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val publicFixtureAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val browser = AndroidSourceBrowserBroker(
            context = context,
            cookiePartition = SourceCookiePartition.NONE,
            resolver = { listOf(publicFixtureAddress) },
        )
        val replay = ChromiumVBookReplayCoordinator(
            browserDelegate = browser,
            networkDelegate = SourceNetworkBroker.DENY_ALL,
        )
        val chromium = AndroidChromiumVBookRuntime(
            context = context,
            brokers = SourceCapabilityBrokers(
                browser = replay.browserBroker,
                network = replay.networkBroker,
            ),
        )
        chromium.use {
            val runtime = VBookCompatibilityRuntime(
                ChromiumVBookDispatcherParityRuntime(
                    ChromiumVBookBrowserReplayRuntime(
                        delegate = chromium,
                        replay = replay,
                    ),
                ),
            )
            val result = runtime.executeDeclared(
                sourceManifest = manifest(),
                resources = resources(),
                role = VBookScriptRole.SEARCH,
                input = "q",
                traceId = "chromium-production-browser-replay",
            )

            val success = when (result) {
                is SourcePlatformResult.Success -> result
                is SourcePlatformResult.Failure -> throw AssertionError(
                    "${result.error.code}:${result.error.message}",
                    result.error.cause,
                )
            }
            val row = (success.value.data as JsonValue.Arr).values.first() as JsonValue.Obj
            assertEquals("chromium", row.string("engine"))
            assertEquals("Ready", row.string("text"))
            assertEquals("https://x.example/base/next", row.string("href"))
            assertTrue(row.string("url").orEmpty().startsWith("https://x.example/base/"))
        }
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.chromium.replay",
        name = "Chromium production browser replay fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(
            mode = SourceRuntimeMode.VBOOK_JS_COMPAT,
            actionTimeoutMs = 30_000,
        ),
        origins = setOf("https://x.example"),
        capabilities = SourceCapabilities(
            browser = SourceBrowserCapability(
                navigate = true,
                domSnapshot = true,
                requestMetadata = true,
            ),
        ),
        actions = emptyMap(),
    )

    private fun resources(): SourceResourceProvider {
        val values = mapOf(
            "plugin.json" to CURRENT_PLUGIN.toByteArray(),
            "src/search.js" to """
                function execute(query, page) {
                  var engine = ({name:'chromium'})?.name;
                  var browser = Engine.newBrowser();
                  browser.loadHtml(
                    '<div class="row"><a href="next">Ready</a></div>',
                    'https://x.example/base/'
                  );
                  var doc = browser.html();
                  var link = doc.selectFirst('div.row a');
                  return Response.success([{
                    engine:String(engine),
                    text:link.text(),
                    href:link.absUrl('href'),
                    url:browser.currentUrl()
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
