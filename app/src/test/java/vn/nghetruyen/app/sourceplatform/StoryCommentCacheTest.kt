package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage

class StoryCommentCacheTest {
    @Test fun expiresEntriesAndKeepsNextPage() {
        var now = 1_000L
        val cache = StoryCommentCache(ttlMillis = 100L, clock = { now })
        val key = StoryCommentCache.Key("source", "story")
        cache.put(key, StoryCommentPage(listOf(StoryComment(text = "Một")), "next"))
        assertEquals("next", cache.get(key)?.nextPageUrl)
        now += 101
        assertNull(cache.get(key))
    }

    @Test fun mergeDeduplicatesAndPreservesOrder() {
        val a = StoryComment("A", "now", "one")
        val b = StoryComment("B", "later", "two")
        assertEquals(listOf(a, b), StoryCommentCache.merge(listOf(a), listOf(a, b)))
    }
}
