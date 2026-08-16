package vn.nghetruyen.app.sourceplatform

import java.util.LinkedHashMap

internal class BoundedLruCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "CACHE_MAX_ENTRIES_MUST_BE_POSITIVE" }
    }

    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = value
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun containsKey(key: K): Boolean = entries.containsKey(key)
}

internal class ArtifactValueCache<V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "CACHE_MAX_ENTRIES_MUST_BE_POSITIVE" }
    }

    private data class CachedValue<V>(val value: V?)

    private val entries = object : LinkedHashMap<String, CachedValue<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedValue<V>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun getOrLoad(key: String, cacheNull: Boolean = true, loader: () -> V?): V? {
        entries[key]?.let { return it.value }
        val loaded = loader()
        if (loaded != null || cacheNull) entries[key] = CachedValue(loaded)
        return loaded
    }

    @Synchronized
    fun retainKeys(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }

    @Synchronized
    fun size(): Int = entries.size
}
