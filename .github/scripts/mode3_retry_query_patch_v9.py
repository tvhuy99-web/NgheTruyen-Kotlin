from pathlib import Path

resolver_path = Path('app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt')
test_path = Path('app/src/test/java/vn/nghetruyen/app/freesound/FreesoundRetryQueryTest.kt')
text = resolver_path.read_text(encoding='utf-8')

old = '''            clientSearches += 1
            val searchStartedNanos = System.nanoTime()
            diagnostics += "CLIENT_SEARCH_START index=${index + 1} kind=${need.kind.name} query=${need.query.take(160)}"
            liveDiagnostic(traceId, "FREESOUND_CLIENT_SEARCH_START", attributes = baseAttributes + mapOf(
                "index" to (index + 1).toString(), "total" to needs.size.toString(),
                "kind" to need.kind.name, "query" to need.query.take(180),
            ))
            val search = searchBest(need)
'''
new = '''            clientSearches += 1
            val searchStartedNanos = System.nanoTime()
            val effectiveQuery = searchQueryForRetry(need.query, retryAttempt)
            val searchStrategy = when {
                retryAttempt <= 1 -> "EXACT"
                retryAttempt == 2 -> "RELAXED_3_TERMS"
                else -> "RELAXED_2_TERMS"
            }
            diagnostics += "CLIENT_SEARCH_START index=${index + 1} kind=${need.kind.name} query=${need.query.take(160)} effectiveQuery=${effectiveQuery.take(160)} strategy=$searchStrategy"
            liveDiagnostic(traceId, "FREESOUND_CLIENT_SEARCH_START", attributes = baseAttributes + mapOf(
                "index" to (index + 1).toString(), "total" to needs.size.toString(),
                "kind" to need.kind.name, "query" to need.query.take(180),
                "effectiveQuery" to effectiveQuery.take(180), "strategy" to searchStrategy,
            ))
            val search = searchBest(need, effectiveQuery)
'''
if old not in text:
    raise SystemExit('search start matcher not found')
text = text.replace(old, new, 1)

old = '''                    "httpCode" to (search.httpCode ?: 0).toString(), "selectedSoundId" to (remote?.id ?: 0).toString(),
                    "failure" to search.failureMessage.orEmpty().take(220),
'''
new = '''                    "httpCode" to (search.httpCode ?: 0).toString(), "selectedSoundId" to (remote?.id ?: 0).toString(),
                    "effectiveQuery" to effectiveQuery.take(180), "strategy" to searchStrategy,
                    "failure" to search.failureMessage.orEmpty().take(220),
'''
if old not in text:
    raise SystemExit('search done matcher not found')
text = text.replace(old, new, 1)

old = '''        liveDiagnostic(
            traceId,
            "FREESOUND_RESOLVE_DONE",
            if (retryRecommended) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            baseAttributes + mapOf(
'''
new = '''        liveDiagnostic(
            traceId,
            "FREESOUND_RESOLVE_DONE",
            if (retryRecommended) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            baseAttributes + mapOf(
'''
if old not in text:
    raise SystemExit('resolve done matcher not found')
# keep block itself, insert exhausted marker after the block below

anchor = '''                "elapsedMs" to totalElapsedMs.toString(),
            ),
        )
        return FreesoundAutoResolveResult(
'''
insert = '''                "elapsedMs" to totalElapsedMs.toString(),
            ),
        )
        if (retryRecommended && retryAttempt >= retryMax) {
            liveDiagnostic(
                traceId,
                "FREESOUND_RETRY_EXHAUSTED",
                DiagnosticSeverity.ERROR,
                baseAttributes + mapOf(
                    "resolved" to resolutions.count { !it.trackId.isNullOrBlank() }.toString(),
                    "unresolved" to resolutions.count { it.trackId.isNullOrBlank() }.toString(),
                    "unresolvedRequired" to unresolvedRequired.toString(),
                    "requirements" to requirements.size.toString(),
                ),
            )
        }
        return FreesoundAutoResolveResult(
'''
if anchor not in text:
    raise SystemExit('resolve exhausted insertion anchor not found')
text = text.replace(anchor, insert, 1)

old = '''    private suspend fun searchBest(need: FreesoundAutoSearchNeed): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = need.query,
'''
new = '''    private suspend fun searchBest(
        need: FreesoundAutoSearchNeed,
        effectiveQuery: String,
    ): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = effectiveQuery,
'''
if old not in text:
    raise SystemExit('searchBest signature matcher not found')
text = text.replace(old, new, 1)

anchor = '''    companion object {
        private const val SEARCH_PAGE_SIZE = 15
        private const val REMOTE_MIN_SCORE = 0.22

        internal fun scoreCandidate(query: String, sound: FreesoundSound, rankIndex: Int): Double {
'''
replacement = '''    companion object {
        private const val SEARCH_PAGE_SIZE = 15
        private const val REMOTE_MIN_SCORE = 0.22
        private val RETRY_QUERY_STOPWORDS = setOf(
            "a", "an", "the", "on", "in", "at", "with", "and", "or", "of", "to", "from", "for", "by",
            "into", "onto", "single", "one", "sound", "effect", "audio",
        )

        internal fun searchQueryForRetry(query: String, retryAttempt: Int): String {
            val original = query.trim()
            if (retryAttempt <= 1 || original.isBlank()) return original
            val tokens = FreesoundAutoRequirementAggregator.normalizeQuery(original)
                .split(' ')
                .map(String::trim)
                .filter { it.length >= 2 && it !in RETRY_QUERY_STOPWORDS }
            if (tokens.isEmpty()) return original
            val keep = if (retryAttempt == 2) 3 else 2
            return tokens.takeLast(keep.coerceAtMost(tokens.size)).joinToString(" ").ifBlank { original }
        }

        internal fun scoreCandidate(query: String, sound: FreesoundSound, rankIndex: Int): Double {
'''
if anchor not in text:
    raise SystemExit('companion matcher not found')
text = text.replace(anchor, replacement, 1)

resolver_path.write_text(text, encoding='utf-8')

test_path.write_text('''package vn.nghetruyen.app.freesound\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass FreesoundRetryQueryTest {\n    @Test\n    fun firstAttemptKeepsAiQueryExactly() {\n        assertEquals(\n            "wall breaking debris crash",\n            FreesoundAutoAudioResolver.searchQueryForRetry("wall breaking debris crash", 1),\n        )\n    }\n\n    @Test\n    fun secondAttemptKeepsThreeCoreTerms() {\n        assertEquals(\n            "chinese flute music",\n            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 2),\n        )\n        assertEquals(\n            "landing thud wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 2),\n        )\n        assertEquals(\n            "coin drop wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("single gold coin drop on wood", 2),\n        )\n    }\n\n    @Test\n    fun thirdAttemptKeepsTwoBroadCoreTerms() {\n        assertEquals(\n            "flute music",\n            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 3),\n        )\n        assertEquals(\n            "debris crash",\n            FreesoundAutoAudioResolver.searchQueryForRetry("wall breaking debris crash", 3),\n        )\n        assertEquals(\n            "thud wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 3),\n        )\n    }\n}\n''', encoding='utf-8')
print('Mode 3 V9 retry query patch applied successfully.')
