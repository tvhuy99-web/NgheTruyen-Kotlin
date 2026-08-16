package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookRuntimeCachesTest {
    @Test
    fun boundedLruEvictsLeastRecentlyUsedEntry() {
        val cache = BoundedLruCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"])
        cache["c"] = 3

        assertTrue(cache.containsKey("a"))
        assertFalse(cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun artifactCacheReusesImmutableValueAndPrunesInactiveKeys() {
        val cache = ArtifactValueCache<Any>(4)
        var loads = 0
        val first = cache.getOrLoad("artifact-a") { loads += 1; Any() }
        val second = cache.getOrLoad("artifact-a") { loads += 1; Any() }

        assertSame(first, second)
        assertEquals(1, loads)

        cache.retainKeys(setOf("artifact-b"))
        cache.getOrLoad("artifact-a") { loads += 1; Any() }
        assertEquals(2, loads)
    }

    @Test
    fun artifactCacheCanAvoidStickyNullForRecoverableBlobMiss() {
        val cache = ArtifactValueCache<String>(4)
        var loads = 0
        assertEquals(null, cache.getOrLoad("artifact-a", cacheNull = false) { loads += 1; null })
        assertEquals("ready", cache.getOrLoad("artifact-a", cacheNull = false) { loads += 1; "ready" })
        assertEquals(2, loads)
        assertEquals(1, cache.size())
    }
}
