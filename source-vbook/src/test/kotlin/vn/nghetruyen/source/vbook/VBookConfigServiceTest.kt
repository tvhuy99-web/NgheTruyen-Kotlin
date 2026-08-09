package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookConfigServiceTest {
    @Test
    fun stableExtensionKeyKeepsValuesWhenManifestVersionChanges() {
        val store = InMemoryVBookConfigStore()
        val service = VBookConfigService(store)
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
        val service = VBookConfigService(store)
        store.write("x", mapOf("OLD_KEY" to "secret", "timeout" to "5000"))
        val restored = service.load("x", manifest(2)).values
        assertEquals(null, restored["OLD_KEY"])
        assertEquals("5000", restored["timeout"])
        assertTrue(restored.values.keys.contains("DOMAIN"))
    }

    @Test
    fun connectionValuesAreValidatedAndClampedAtPersistenceBoundary() {
        val service = VBookConfigService(InMemoryVBookConfigStore())
        val saved = service.save("x", manifest(1), mapOf(
            "thread_num" to "999",
            "timeout" to "1",
            "delay" to "999999",
        )).values.connectionSettings()
        assertEquals(8, saved.threadNum)
        assertEquals(100L, saved.timeoutMs)
        assertEquals(120_000L, saved.delayMs)
    }

    private fun manifest(version: Int): VBookExtensionManifest = VBookManifestParser.parse("""
        {
          "metadata":{"name":"x","author":"a","version":$version,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","nsfw":false},
          "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
          "config":{"DOMAIN":{"title":"Domain","default":"default.example","mode":"input","format":"text"}}
        }
    """.trimIndent())
}
