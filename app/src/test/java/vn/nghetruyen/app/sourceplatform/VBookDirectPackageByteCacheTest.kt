package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VBookDirectPackageByteCacheTest {
    @Test
    fun exactBytesAreCopiedAndConsumedOnce() {
        var now = 1_000L
        val cache = VBookDirectPackageByteCache(clock = { now })
        val original = byteArrayOf(1, 2, 3)
        cache.put("repo:item", "https://example.com/plugin.zip", "abc", original)
        original[0] = 9

        val hit = cache.take("repo:item", "https://example.com/plugin.zip")
        requireNotNull(hit)
        assertEquals("abc", hit.sha256)
        assertArrayEquals(byteArrayOf(1, 2, 3), hit.bytes)
        hit.bytes[1] = 8
        assertNull(cache.take("repo:item", "https://example.com/plugin.zip"))
    }

    @Test
    fun wrongUrlDoesNotReuseClassifiedPackage() {
        val cache = VBookDirectPackageByteCache()
        cache.put("repo:item", "https://example.com/a.zip", "abc", byteArrayOf(1))

        assertNull(cache.take("repo:item", "https://example.com/b.zip"))
        assertNull(cache.take("repo:item", "https://example.com/a.zip"))
    }

    @Test
    fun expiredAndOldestEntriesAreDiscarded() {
        var now = 0L
        val cache = VBookDirectPackageByteCache(maxEntries = 2, ttlMillis = 100, clock = { now })
        cache.put("a", "https://example.com/a.zip", "a", byteArrayOf(1))
        now = 10
        cache.put("b", "https://example.com/b.zip", "b", byteArrayOf(2))
        now = 20
        cache.put("c", "https://example.com/c.zip", "c", byteArrayOf(3))

        assertNull(cache.take("a", "https://example.com/a.zip"))
        assertArrayEquals(byteArrayOf(2), requireNotNull(cache.take("b", "https://example.com/b.zip")).bytes)

        now = 200
        assertNull(cache.take("c", "https://example.com/c.zip"))
    }

    @Test
    fun removeUrlInvalidatesOnlyMatchingPackage() {
        val cache = VBookDirectPackageByteCache()
        cache.put("a", "https://example.com/a.zip", "a", byteArrayOf(1))
        cache.put("b", "https://example.com/b.zip", "b", byteArrayOf(2))

        cache.removeUrl("https://example.com/a.zip")

        assertNull(cache.take("a", "https://example.com/a.zip"))
        assertArrayEquals(byteArrayOf(2), requireNotNull(cache.take("b", "https://example.com/b.zip")).bytes)
    }
}
