package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookConfigServiceTest {
    @Test
    fun stableExtensionKeyKeepsValuesWhenManifestVersionChanges() {
        val store = InMemoryVBookConfigStore()
        val service = VBookConfigService(store, InMemoryVBookConfigStore())
        val key = "repo-id:remote-id"
        val v1 = manifest(version = 1)
        service.save(key, v1, mapOf(
            "DOMAIN" to "custom.example",
            "thread_num" to "5",
            "timeout" to "9000",
            "delay" to "250",
        ))

        val v2 = manifest(version = 2)
        val restored = service.load(key, v2).values
        assertEquals("custom.example", restored["DOMAIN"])
        assertEquals(5, restored.connectionSettings().threadNum)
        assertEquals(9_000L, restored.connectionSettings().timeoutMs)
        assertEquals(250L, restored.connectionSettings().delayMs)
    }

    @Test
    fun removedConfigKeysAreDiscardedInsteadOfLeakingIntoNewScriptScope() {
        val store = InMemoryVBookConfigStore()
        val service = VBookConfigService(store, InMemoryVBookConfigStore())
        store.write("x", mapOf("OLD_KEY" to "secret", "timeout" to "5000"))
        val restored = service.load("x", manifest(2)).values
        assertEquals(null, restored["OLD_KEY"])
        assertEquals("5000", restored["timeout"])
        assertTrue(restored.values.keys.contains("DOMAIN"))
    }

    @Test
    fun connectionValuesAreValidatedAndClampedAtPersistenceBoundary() {
        val service = VBookConfigService(InMemoryVBookConfigStore(), InMemoryVBookConfigStore())
        val saved = service.save("x", manifest(1), mapOf(
            "thread_num" to "999",
            "timeout" to "1",
            "delay" to "999999",
        )).values.connectionSettings()
        assertEquals(8, saved.threadNum)
        assertEquals(100L, saved.timeoutMs)
        assertEquals(120_000L, saved.delayMs)
    }

    @Test
    fun sensitiveFieldsArePersistedSeparatelyFromPortableConfig() {
        val config = InMemoryVBookConfigStore()
        val secrets = InMemoryVBookConfigStore()
        val service = VBookConfigService(config, secrets)
        val manifest = VBookManifestParser.parse("""
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","nsfw":false},
              "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
              "config":{
                "DOMAIN":{"title":"Domain","default":"default.example","mode":"input","format":"text"},
                "API_TOKEN":{"title":"Access token","default":"","mode":"input","format":"text"},
                "API_KEY":{"title":"API key","default":"","mode":"input","format":"text"},
                "PIN":{"title":"PIN","default":"","mode":"input","format":"text","secret":true}
              }
            }
        """.trimIndent())

        service.save("x", manifest, mapOf("DOMAIN" to "site.example", "API_TOKEN" to "abc", "API_KEY" to "xyz", "PIN" to "1234"))

        assertEquals(mapOf("DOMAIN" to "site.example"), config.read("x"))
        assertEquals(mapOf("API_TOKEN" to "abc", "API_KEY" to "xyz", "PIN" to "1234"), secrets.read("x"))
        assertEquals("abc", service.load("x", manifest).values["API_TOKEN"])
    }

    private fun manifest(version: Int): VBookExtensionManifest = VBookManifestParser.parse("""
        {
          "metadata":{"name":"x","author":"a","version":$version,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","nsfw":false},
          "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
          "config":{"DOMAIN":{"title":"Domain","default":"default.example","mode":"input","format":"text"}}
        }
    """.trimIndent())
}
