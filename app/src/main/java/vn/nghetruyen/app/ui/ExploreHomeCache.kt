package vn.nghetruyen.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.core.model.StorySummary

internal class ExploreHomeCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(sourceId: String, nowMs: Long = System.currentTimeMillis()): List<StorySummary> {
        val key = sourceId.trim().takeIf(String::isNotBlank) ?: return emptyList()
        val savedAt = prefs.getLong(savedAtKey(key), 0L)
        if (savedAt <= 0L || nowMs - savedAt > MAX_AGE_MILLIS) return emptyList()
        val raw = prefs.getString(itemsKey(key), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(minOf(array.length(), MAX_ITEMS)) {
                repeat(minOf(array.length(), MAX_ITEMS)) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val id = item.optString("id").trim()
                    val itemSourceId = item.optString("sourceId").trim().ifBlank { key }
                    val title = item.optString("title").trim()
                    if (id.isBlank() || title.isBlank()) return@repeat
                    add(
                        StorySummary(
                            id = id,
                            sourceId = itemSourceId,
                            title = title,
                            author = item.optString("author"),
                            coverUrl = item.optString("coverUrl").takeIf(String::isNotBlank),
                            description = item.optString("description"),
                            url = item.optString("url"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(sourceId: String, stories: List<StorySummary>, nowMs: Long = System.currentTimeMillis()) {
        val key = sourceId.trim().takeIf(String::isNotBlank) ?: return
        if (stories.isEmpty()) return
        val array = JSONArray()
        stories.take(MAX_ITEMS).forEach { story ->
            array.put(JSONObject().apply {
                put("id", story.id)
                put("sourceId", story.sourceId)
                put("title", story.title)
                put("author", story.author)
                put("coverUrl", story.coverUrl ?: "")
                put("description", story.description)
                put("url", story.url)
            })
        }
        prefs.edit()
            .putString(itemsKey(key), array.toString())
            .putLong(savedAtKey(key), nowMs)
            .apply()
    }

    fun clear(sourceId: String) {
        val key = sourceId.trim().takeIf(String::isNotBlank) ?: return
        prefs.edit().remove(itemsKey(key)).remove(savedAtKey(key)).apply()
    }

    private fun itemsKey(sourceId: String) = "home.$sourceId.items"
    private fun savedAtKey(sourceId: String) = "home.$sourceId.saved_at"

    private companion object {
        const val PREFS = "explore_home_cache_v1"
        const val MAX_ITEMS = 80
        const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}
