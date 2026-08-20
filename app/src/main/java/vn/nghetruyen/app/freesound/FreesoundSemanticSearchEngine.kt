package vn.nghetruyen.app.freesound

import vn.nghetruyen.app.audio.AudioAssetKind

data class FreesoundSemanticSearchHit(
    val sound: FreesoundSound,
    val score: Double,
    val matchedQueries: Int,
)

sealed interface FreesoundSemanticSearchResult {
    data class Success(
        val hits: List<FreesoundSemanticSearchHit>,
        val queries: List<String>,
        val failedQueries: Int,
    ) : FreesoundSemanticSearchResult

    data class Failure(val message: String) : FreesoundSemanticSearchResult
}

/**
 * Runs several short Freesound queries, merges by remote sound id and re-ranks with
 * reciprocal-rank fusion. FreesoundClient provides the cache/rate limiter, so semantic
 * expansion does not burst the API even when AI returns multiple alternatives.
 */
class FreesoundSemanticSearchEngine(
    private val client: FreesoundClient,
) {
    suspend fun search(
        queries: Collection<String>,
        category: FreesoundCategory,
        duration: FreesoundDuration = FreesoundDuration.RECOMMENDED,
        maxResults: Int = 40,
    ): FreesoundSemanticSearchResult {
        val normalizedQueries = queries
            .map { it.trim().replace(Regex("\\s+"), " ").take(FreesoundSearchRequest.MAX_QUERY_LENGTH) }
            .filter { it.length >= 2 }
            .distinctBy(String::lowercase)
            .take(MAX_QUERIES)
        if (normalizedQueries.isEmpty()) {
            return FreesoundSemanticSearchResult.Failure("Không có truy vấn ngữ nghĩa hợp lệ.")
        }

        data class MutableHit(
            val sound: FreesoundSound,
            var score: Double,
            val queryIndexes: MutableSet<Int>,
        )

        val merged = linkedMapOf<Int, MutableHit>()
        var failed = 0
        normalizedQueries.forEachIndexed { queryIndex, query ->
            when (
                val result = client.search(
                    FreesoundSearchRequest(
                        query = query,
                        category = category,
                        duration = duration,
                        sort = FreesoundSort.RELEVANCE,
                        page = 1,
                        pageSize = RESULTS_PER_QUERY,
                    ),
                )
            ) {
                is FreesoundSearchResult.Failure -> failed += 1
                is FreesoundSearchResult.Success -> {
                    result.page.results.forEachIndexed { rank, sound ->
                        val contribution = 1.0 / (RRF_K + rank + 1.0)
                        val hit = merged[sound.id]
                        if (hit == null) {
                            merged[sound.id] = MutableHit(
                                sound = sound,
                                score = contribution,
                                queryIndexes = linkedSetOf(queryIndex),
                            )
                        } else {
                            hit.score += contribution
                            hit.queryIndexes += queryIndex
                        }
                    }
                }
            }
        }

        if (merged.isEmpty()) {
            return FreesoundSemanticSearchResult.Failure(
                if (failed == normalizedQueries.size) {
                    "Không thực hiện được các truy vấn Freesound đã tạo. Hãy kiểm tra mạng/API rồi thử lại."
                } else {
                    "Không tìm thấy âm thanh phù hợp với mô tả."
                },
            )
        }

        val hits = merged.values
            .map { hit ->
                FreesoundSemanticSearchHit(
                    sound = hit.sound,
                    score = hit.score + (hit.queryIndexes.size - 1).coerceAtLeast(0) * MULTI_QUERY_BONUS,
                    matchedQueries = hit.queryIndexes.size,
                )
            }
            .sortedWith(
                compareByDescending<FreesoundSemanticSearchHit> { it.matchedQueries }
                    .thenByDescending { it.score }
                    .thenBy { it.sound.name.lowercase() },
            )
            .take(maxResults.coerceIn(1, 100))

        return FreesoundSemanticSearchResult.Success(hits, normalizedQueries, failed)
    }

    companion object {
        private const val MAX_QUERIES = 5
        private const val RESULTS_PER_QUERY = 20
        private const val RRF_K = 30.0
        private const val MULTI_QUERY_BONUS = 0.025

        /** Local fallback when online AI is disabled/unavailable. */
        fun fallbackQueries(input: String, kind: AudioAssetKind): List<String> {
            val tokens = FreesoundLibraryAnalyzer.tokens(input)
                .filter { it.length >= 2 }
                .take(8)
            if (tokens.isEmpty()) return emptyList()
            val core = tokens.joinToString(" ")
            val suffix = when (kind) {
                AudioAssetKind.MUSIC -> "music"
                AudioAssetKind.AMBIENCE -> "ambience"
                AudioAssetKind.SFX -> "sound effect"
            }
            return buildList {
                add("$core $suffix")
                if (tokens.size >= 3) add(tokens.take(4).joinToString(" ") + " $suffix")
                if (tokens.size >= 2) add(tokens.take(2).joinToString(" ") + " $suffix")
                add(core)
            }
                .map { it.trim().replace(Regex("\\s+"), " ").take(FreesoundSearchRequest.MAX_QUERY_LENGTH) }
                .filter { it.length >= 2 }
                .distinctBy(String::lowercase)
                .take(MAX_QUERIES)
        }
    }
}
