package vn.nghetruyen.app.sourceplatform

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookScriptRole
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AndroidChromiumVBookBrowserReplayTest {
    @Test
    fun productionBrowserNavigateDefersCrossHostRedirectDnsOffMainThread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val publicFixtureAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val resolvedHosts = Collections.synchronizedList(mutableListOf<String>())
        LocalRedirectServer().use { server ->
            val sourceManifest = navigationManifest(server.startUrl)
            val browser = AndroidSourceBrowserBroker(
                context = context,
                cookiePartition = SourceCookiePartition.NONE,
                resolver = { host ->
                    assertTrue("DNS resolver must never run on Android main", Looper.myLooper() != Looper.getMainLooper())
                    resolvedHosts += host
                    listOf(publicFixtureAddress)
                },
            )

            val result = browser.execute(sourceManifest, SourceBrowserRequest(
                sourceId = sourceManifest.id,
                action = SourceBrowserAction.NAVIGATE,
                url = server.startUrl,
                timeoutMs = 15_000,
                traceId = "webview-cross-host-redirect",
            ))

            val success = when (result) {
                is SourcePlatformResult.Success -> result
                is SourcePlatformResult.Failure -> throw AssertionError(result.error.message, result.error.cause)
            }
            assertEquals(server.finalUrl, success.value.finalUrl)
            assertEquals(listOf("localhost", "127.0.0.1"), resolvedHosts.distinct())
            browser.execute(sourceManifest, SourceBrowserRequest(
                sourceId = sourceManifest.id,
                action = SourceBrowserAction.CLEAR_SESSION,
                traceId = "webview-cross-host-cleanup",
            ))
        }
    }

    @Test
    fun productionBrowserWebViewRunsOutsideChromiumPromptAndReplaysIntoScript() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val publicFixtureAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val resolverCalls = AtomicInteger()
        val productionBrowser = AndroidSourceBrowserBroker(
            context = context,
            cookiePartition = SourceCookiePartition.NONE,
            resolver = { host ->
                assertEquals("x.example", host)
                assertEquals(1, resolverCalls.incrementAndGet())
                listOf(publicFixtureAddress)
            },
        )
        val browser = SourceBrowserBroker { manifest, request ->
            if (request.action == SourceBrowserAction.LOAD_HTML) {
                assertEquals("https://x.example/base/", request.url)
                assertTrue(request.value.orEmpty().contains("Ready"))
                assertTrue("https://x.example" in manifest.origins)
                assertTrue(manifest.capabilities.network?.publicInternet == true)
            }
            productionBrowser.execute(manifest, request)
        }
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
            assertEquals("https://x.example/base/#csrf", row.string("url"))
            assertEquals(1, resolverCalls.get())
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
                click = true,
                requestMetadata = true,
                pageJavaScript = true,
            ),
        ),
        actions = emptyMap(),
    )

    private fun navigationManifest(url: String): SourceManifest {
        val uri = java.net.URI(url)
        return SourceManifest(
            schemaVersion = 2,
            id = "test.vbook.chromium.navigate",
            name = "Chromium real navigation fixture",
            version = SemanticVersion(1, 0, 0),
            apiVersion = 2,
            contentType = SourceContentType.NOVEL,
            runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT, actionTimeoutMs = 15_000),
            origins = setOf("${uri.scheme}://${uri.host}:${uri.port}"),
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(publicInternet = true, allowCleartext = true),
                browser = SourceBrowserCapability(navigate = true, requestMetadata = true),
            ),
            actions = emptyMap(),
        )
    }

    private fun resources(): SourceResourceProvider {
        val values = mapOf(
            "plugin.json" to CURRENT_PLUGIN.toByteArray(),
            "src/search.js" to """
                function execute(query, page) {
                  var engine = ({name:'chromium'})?.name;
                  var browser = Engine.newBrowser();
                  browser.loadHtml(
                    '<div class="row"><a href="next">Ready</a><a id="csrf-route" href="#csrf">CSRF</a></div>',
                    'https://x.example/base/'
                  );
                  browser.tapSelector('#csrf-route');
                  var pageUrl = browser.evaluate('location.href');
                  var doc = browser.html();
                  var link = doc.selectFirst('div.row a');
                  return Response.success([{
                    engine:String(engine),
                    text:link.text(),
                    href:link.absUrl('href'),
                    url:String(pageUrl)
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

    private class LocalRedirectServer : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val startUrl = "http://localhost:${socket.localPort}/start"
        val finalUrl = "http://127.0.0.1:${socket.localPort}/final#csrf"
        private val worker = Thread({ serve() }, "browser-test-http").apply {
            isDaemon = true
            start()
        }

        private fun serve() {
            while (running.get()) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        val response = if (path == "/start") {
                            "HTTP/1.1 302 Found\r\nLocation: $finalUrl\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            val body = "<html><body><div id=ready>Ready</div></body></html>"
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        }
                        client.getOutputStream().write(response.toByteArray())
                        client.getOutputStream().flush()
                    }
                } catch (_: SocketException) {
                    if (running.get()) throw AssertionError("Local redirect server stopped unexpectedly")
                }
            }
        }

        override fun close() {
            running.set(false)
            socket.close()
            worker.join(2_000)
        }
    }

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
