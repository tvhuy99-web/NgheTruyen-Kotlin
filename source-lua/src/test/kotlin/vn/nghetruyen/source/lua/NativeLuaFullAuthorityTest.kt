package vn.nghetruyen.source.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceFullAuthorityPolicy

class NativeLuaFullAuthorityTest {
    @Test
    fun permissionlessNativeSourceGetsFullInAppAuthority() {
        val source = """
            return {
              api_version = 2,
              metadata = {
                id = "full-authority-test",
                name = "Full Authority Test",
                version = 1,
                website = "https://example.com"
              },
              source = {
                base_url = "https://example.com",
                actions = {
                  search = { steps = {}, result = { type = "items", fields = {} } },
                  chapters = { steps = {}, result = { type = "items", fields = {} } },
                  content = { steps = {}, result = { type = "content", fields = {} } }
                },
                pipelines = {
                  power = {
                    steps = {
                      { browser = { op = "open" } },
                      { browser = { op = "capture", patterns = { "/api" }, fetch = { response = "json" } } },
                      { storage = { op = "set", key = "token", value = "ok" } }
                    }
                  }
                }
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val result = NativeLuaSourceImporter.import(source)
        val capabilities = result.manifest.capabilities
        val network = requireNotNull(capabilities.network)

        assertEquals(SourceFullAuthorityPolicy.AUTHORITY_ID, "FULL_IN_APP")
        assertTrue(network.publicInternet)
        assertTrue(network.allowCleartext)
        assertEquals(setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"), network.methods)
        assertEquals(SourceCookieMode.BROWSER_SHARED, capabilities.cookies)
        assertTrue(capabilities.browser.navigate)
        assertTrue(capabilities.browser.domSnapshot)
        assertTrue(capabilities.browser.click)
        assertTrue(capabilities.browser.input)
        assertTrue(capabilities.browser.requestMetadata)
        assertTrue(capabilities.browser.serviceWorkerCapture)
        assertTrue(capabilities.browser.pageJavaScript)
        assertTrue(capabilities.storageBytes >= 16 * 1024 * 1024)
        assertEquals(SourceCryptoCapability.entries.toSet(), capabilities.crypto)
        assertTrue(capabilities.websocket.enabled)

        val info = JsonCodec.parse(requireNotNull(result.entries["data/native-source-info.json"]).toString(Charsets.UTF_8)) as JsonValue.Obj
        assertEquals("FULL_IN_APP", (info["authority"] as JsonValue.Str).value)
        assertTrue((info["fullInternalAuthority"] as JsonValue.Bool).value)
        assertTrue((info["browser"] as JsonValue.Bool).value)
        assertTrue((info["networkCapture"] as JsonValue.Bool).value)
        assertTrue((info["storage"] as JsonValue.Bool).value)

        val core = requireNotNull(result.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)
        assertTrue(core.contains("\"browser\":true"))
        assertTrue(core.contains("\"storage\":true"))
        assertTrue(core.contains("\"network_capture\":true"))
    }

    @Test
    fun fullAuthorityDoesNotOpenOutsideSandbox() {
        val sandbox = LuaSandbox(
            modules = emptyMap(),
            instructionBudget = 50_000,
            timeoutMs = 5_000,
            memoryBudgetBytes = 8 * 1024 * 1024,
        )
        val value = sandbox.evaluate(
            """
                return {
                  luajava = luajava ~= nil,
                  io = io ~= nil,
                  os = os ~= nil,
                  debug = debug ~= nil,
                  package = package ~= nil,
                  load = load ~= nil,
                  loadfile = loadfile ~= nil,
                  dofile = dofile ~= nil
                }
            """.trimIndent(),
            "@full-authority-boundary-test",
        )
        val json = sandbox.luaToJson(value) as JsonValue.Obj
        listOf("luajava", "io", "os", "debug", "package", "load", "loadfile", "dofile").forEach { key ->
            assertFalse("$key must stay outside the Native Source sandbox", (json[key] as JsonValue.Bool).value)
        }
    }
}
