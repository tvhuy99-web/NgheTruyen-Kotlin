package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import java.util.LinkedHashMap

/**
 * Small process-local cache for comments. It deliberately stores public page
 * data only, never cookies or credentials. Entries expire and the map is LRU
 * bounded so a noisy source cannot grow memory without limit.
 */
class StoryCommentCache(
    private val ttlMillis: Long = 5 * 60_000L,
    private val maxEntries: Int = 32,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Key(val sourceId: String, val storyUrl: String)
    data class CacheEntry(val page: StoryCommentPage, val storedAtMillis: Long)

    private val entries = object : LinkedHashMap<Key, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CacheEntry>?): Boolean = size > maxEntries.coerceAtLeast(1)
    }

    @Synchronized
    fun get(key: Key): StoryCommentPage? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAtMillis > ttlMillis.coerceAtLeast(0L)) {
            entries.remove(key)
            return null
        }
        return entry.page
    }

    @Synchronized
    fun put(key: Key, page: StoryCommentPage) {
        entries[key] = CacheEntry(page.copy(comments = deduplicate(page.comments).take(MAX_CACHED_COMMENTS)), clock())
    }

    @Synchronized
    fun invalidate(key: Key) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        private const val MAX_CACHED_COMMENTS = 500

        fun merge(existing: List<StoryComment>, incoming: List<StoryComment>): List<StoryComment> =
            deduplicate(existing + incoming).take(MAX_CACHED_COMMENTS)

        private fun deduplicate(items: List<StoryComment>): List<StoryComment> {
            val seen = HashSet<String>()
            return items.filter { comment ->
                val key = buildString {
                    append(comment.user.trim().lowercase())
                    append('\u0000')
                    append(comment.time.trim().lowercase())
                    append('\u0000')
                    append(comment.text.trim())
                }
                seen.add(key)
            }
        }
    }
}
