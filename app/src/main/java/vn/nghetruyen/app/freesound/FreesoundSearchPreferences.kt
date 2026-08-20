package vn.nghetruyen.app.freesound

import android.content.Context
import org.json.JSONArray

data class FreesoundSavedSearch(
    val query: String = "",
    val duration: FreesoundDuration = FreesoundDuration.RECOMMENDED,
    val sort: FreesoundSort = FreesoundSort.RELEVANCE,
    val recentQueries: List<String> = emptyList(),
)

class FreesoundSearchPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(category: FreesoundCategory): FreesoundSavedSearch {
        val key = category.name.lowercase()
        return FreesoundSavedSearch(
            query = prefs.getString("query_$key", "").orEmpty().take(FreesoundSearchRequest.MAX_QUERY_LENGTH),
            duration = enumValueOrDefault(
                prefs.getString("duration_$key", null),
                FreesoundDuration.RECOMMENDED,
            ),
            sort = enumValueOrDefault(
                prefs.getString("sort_$key", null),
                FreesoundSort.RELEVANCE,
            ),
            recentQueries = decodeRecent(prefs.getString("recent_$key", null)),
        )
    }

    fun rememberSearch(
        category: FreesoundCategory,
        query: String,
        duration: FreesoundDuration,
        sort: FreesoundSort,
    ) {
        val cleanQuery = query.trim().take(FreesoundSearchRequest.MAX_QUERY_LENGTH)
        if (cleanQuery.isBlank()) return
        val key = category.name.lowercase()
        val previous = decodeRecent(prefs.getString("recent_$key", null))
        val recent = mergeRecentQueries(cleanQuery, previous)
        prefs.edit()
            .putString("query_$key", cleanQuery)
            .putString("duration_$key", duration.name)
            .putString("sort_$key", sort.name)
            .putString("recent_$key", JSONArray(recent).toString())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "freesound_search_preferences_v1"
        const val MAX_RECENT_QUERIES = 8

        fun presets(category: FreesoundCategory): List<String> = when (category) {
            FreesoundCategory.MUSIC -> listOf(
                "fantasy music",
                "epic orchestral",
                "dark ambient",
                "battle music",
                "sad piano",
                "mystical music",
                "tension music",
                "peaceful background",
            )
            FreesoundCategory.AMBIENCE -> listOf(
                "rain ambience",
                "forest ambience",
                "thunderstorm",
                "tavern ambience",
                "cave ambience",
                "night ambience",
                "strong wind",
                "river ambience",
            )
            FreesoundCategory.SFX -> listOf(
                "sword clash",
                "magic spell",
                "footsteps",
                "thunder",
                "explosion",
                "fire",
                "door",
                "horse",
            )
            FreesoundCategory.ALL -> emptyList()
        }

        internal fun mergeRecentQueries(query: String, previous: List<String>): List<String> {
            val clean = query.trim().take(FreesoundSearchRequest.MAX_QUERY_LENGTH)
            if (clean.isBlank()) return previous.take(MAX_RECENT_QUERIES)
            return buildList<String> {
                add(clean)
                previous.forEach { candidate ->
                    val normalized = candidate.trim().take(FreesoundSearchRequest.MAX_QUERY_LENGTH)
                    if (normalized.isNotBlank() && none { it.equals(normalized, ignoreCase = true) }) {
                        add(normalized)
                    }
                }
            }.take(MAX_RECENT_QUERIES)
        }

        internal fun decodeRecent(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList<String> {
                    for (index in 0 until array.length()) {
                        val value = array.optString(index).trim().take(FreesoundSearchRequest.MAX_QUERY_LENGTH)
                        if (value.isNotBlank() && none { it.equals(value, ignoreCase = true) }) {
                            add(value)
                        }
                        if (size >= MAX_RECENT_QUERIES) break
                    }
                }
            }.getOrDefault(emptyList())
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
            runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
    }
}
