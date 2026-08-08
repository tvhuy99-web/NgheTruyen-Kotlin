package vn.nghetruyen.app.sources

import java.text.Normalizer
import kotlin.math.max
import vn.nghetruyen.app.core.model.SearchSortMode
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StorySummary

object StorySearch {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace('đ', 'd')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun merge(
        results: List<StorySummary>,
        healthBySource: Map<String, SourceHealth>,
        query: String = "",
        sortMode: SearchSortMode = SearchSortMode.RELEVANCE,
        groupDuplicates: Boolean = true,
    ): List<StorySummary> {
        val normalizedQuery = normalize(query)
        val selected = if (groupDuplicates) {
            results
                .groupBy(::dedupeKey)
                .values
                .map { duplicates ->
                    duplicates.maxWithOrNull(
                        compareBy<StorySummary> { score(it, normalizedQuery, healthBySource[it.sourceId]) }
                            .thenByDescending { it.description.length }
                            .thenByDescending { it.url.length }
                            .thenBy { it.sourceId },
                    ) ?: duplicates.first()
                }
        } else {
            results.distinctBy { story -> story.url.ifBlank { "${story.sourceId}:${story.id}" } }
        }

        return when (sortMode) {
            SearchSortMode.RELEVANCE -> selected.sortedWith(
                compareByDescending<StorySummary> { score(it, normalizedQuery, healthBySource[it.sourceId]) }
                    .thenBy { healthRank(healthBySource[it.sourceId]) }
                    .thenBy { normalize(it.title) }
                    .thenBy { normalize(it.author) },
            )
            SearchSortMode.TITLE -> selected.sortedWith(
                compareBy<StorySummary> { normalize(it.title) }
                    .thenBy { normalize(it.author) }
                    .thenBy { healthRank(healthBySource[it.sourceId]) },
            )
            SearchSortMode.AUTHOR -> selected.sortedWith(
                compareBy<StorySummary> { normalize(it.author) }
                    .thenBy { normalize(it.title) }
                    .thenBy { healthRank(healthBySource[it.sourceId]) },
            )
            SearchSortMode.SOURCE -> selected.sortedWith(
                compareBy<StorySummary> { healthRank(healthBySource[it.sourceId]) }
                    .thenBy { it.sourceId }
                    .thenBy { normalize(it.title) },
            )
        }
    }

    fun score(story: StorySummary, query: String, health: SourceHealth?): Int {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return healthBonus(health)
        val title = normalize(story.title)
        val author = normalize(story.author)
        val description = normalize(story.description)
        val url = normalize(story.url)
        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank)
        val titleTokens = title.split(' ').filter(String::isNotBlank)
        val authorTokens = author.split(' ').filter(String::isNotBlank)

        var result = healthBonus(health)
        result += when {
            title == normalizedQuery -> 1_200
            title.startsWith(normalizedQuery) -> 1_000
            title.contains(normalizedQuery) -> 850
            else -> 0
        }
        result += when {
            author == normalizedQuery -> 760
            author.startsWith(normalizedQuery) -> 650
            author.contains(normalizedQuery) -> 560
            else -> 0
        }
        if (description.contains(normalizedQuery)) result += 120
        if (url.contains(normalizedQuery)) result += 80

        result += tokenCoverage(queryTokens, titleTokens) * 45
        result += tokenCoverage(queryTokens, authorTokens) * 28
        result += fuzzyTokenScore(queryTokens, titleTokens) * 18
        result += fuzzyTokenScore(queryTokens, authorTokens) * 10
        return result
    }

    private fun dedupeKey(story: StorySummary): String {
        val title = normalize(story.title)
        val author = normalize(story.author)
        return if (title.isBlank()) {
            "${story.sourceId}:${story.url.ifBlank { story.id }}"
        } else {
            "$title|$author"
        }
    }

    private fun tokenCoverage(query: List<String>, candidate: List<String>): Int {
        if (query.isEmpty() || candidate.isEmpty()) return 0
        return query.count { token -> candidate.any { it == token || it.startsWith(token) } }
    }

    private fun fuzzyTokenScore(query: List<String>, candidate: List<String>): Int {
        if (query.isEmpty() || candidate.isEmpty()) return 0
        return query.sumOf { wanted ->
            candidate.maxOfOrNull { actual -> similarityBucket(wanted, actual) } ?: 0
        }
    }

    private fun similarityBucket(left: String, right: String): Int {
        if (left == right) return 4
        if (left.length < 3 || right.length < 3) return 0
        val limit = when (max(left.length, right.length)) {
            in 0..5 -> 1
            in 6..9 -> 2
            else -> 3
        }
        val distance = damerauLevenshtein(left, right, limit)
        return when {
            distance == 1 -> 3
            distance == 2 && limit >= 2 -> 2
            distance == 3 && limit >= 3 -> 1
            else -> 0
        }
    }

    private fun damerauLevenshtein(left: String, right: String, limit: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > limit) return limit + 1
        var previousPrevious = IntArray(right.length + 1)
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                var value = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    value = minOf(value, previousPrevious[j - 2] + 1)
                }
                current[j] = value
                rowMin = minOf(rowMin, value)
            }
            if (rowMin > limit) return limit + 1
            val swap = previousPrevious
            previousPrevious = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun healthBonus(health: SourceHealth?): Int = when (health) {
        SourceHealth.READY -> 60
        SourceHealth.DEGRADED -> 35
        SourceHealth.NEEDS_LOGIN -> 20
        SourceHealth.DISABLED -> 5
        SourceHealth.NOT_PORTED, null -> 0
    }

    private fun healthRank(health: SourceHealth?): Int = when (health) {
        SourceHealth.READY -> 0
        SourceHealth.DEGRADED -> 1
        SourceHealth.NEEDS_LOGIN -> 2
        SourceHealth.DISABLED -> 3
        SourceHealth.NOT_PORTED, null -> 4
    }
}