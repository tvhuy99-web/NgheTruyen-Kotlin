from pathlib import Path
import re


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'{label}: anchor not found in {path}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


def regex_once(path: Path, pattern: str, repl: str, label: str, flags=re.S):
    text = path.read_text(encoding='utf-8')
    new, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count} in {path}')
    path.write_text(new, encoding='utf-8')

resolver = Path('app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt')
text = resolver.read_text(encoding='utf-8')

# Search outcome now records why a candidate was chosen, so future diagnostics can audit quality.
old = '''internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
    val queryUsed: String = "",
    val requestCount: Int = 1,
)'''
new = '''internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
    val queryUsed: String = "",
    val requestCount: Int = 1,
    val categoryUsed: String = "",
    val selectedName: String = "",
    val selectedDurationSec: Double = 0.0,
    val selectedScore: Double = 0.0,
    val selectedLexicalCoverage: Double = 0.0,
    val excludedSoundIds: Set<Int> = emptySet(),
)'''
if old not in text: raise SystemExit('search outcome anchor missing')
text = text.replace(old, new, 1)

# Bump durable query cache namespace so previously poor choices are re-evaluated once under the new policy.
text = text.replace('private const val PREFERENCES = "freesound_auto_query_cache_v1"', 'private const val PREFERENCES = "freesound_auto_query_cache_v2"', 1)

old = '''    private val importer = FreesoundImporter(
        context = appContext,
        repository = repository,
        existingTracksProvider = existingTracksProvider,
    )
'''
new = old + '''    // Per-resolution-cycle blacklist. A retry must never select the exact remote sound whose
    // preview just timed out/failed; attempt 1 clears the prior cycle, attempts 2/3 keep exclusions.
    private val failedSoundIdsByNeed = linkedMapOf<String, MutableSet<Int>>()

    private fun failedSoundKey(need: FreesoundAutoSearchNeed): String =
        "${need.kind.name}:${FreesoundAutoRequirementAggregator.normalizeQuery(need.query)}"

    private fun failedSoundIds(need: FreesoundAutoSearchNeed): Set<Int> =
        failedSoundIdsByNeed[failedSoundKey(need)]?.toSet().orEmpty()

    private fun rememberFailedSound(need: FreesoundAutoSearchNeed, soundId: Int) {
        if (soundId > 0) failedSoundIdsByNeed.getOrPut(failedSoundKey(need)) { linkedSetOf() }.add(soundId)
    }
'''
if old not in text: raise SystemExit('importer anchor missing')
text = text.replace(old, new, 1)

old = '''        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        val baseAttributes = mapOf(
'''
new = '''        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        if (retryAttempt <= 1) {
            needs.forEach { need -> failedSoundIdsByNeed.remove(failedSoundKey(need)) }
        }
        val baseAttributes = mapOf(
'''
if old not in text: raise SystemExit('resolve needs anchor missing')
text = text.replace(old, new, 1)

# Search must know whether this is a retry, so category fallback is only used after the strict pass.
text = text.replace('            val search = searchBest(seed.need, seed.effectiveQuery)\n', '            val search = searchBest(seed.need, seed.effectiveQuery, allowCategoryFallback = retryAttempt > 1)\n', 1)

old = '''                    "queryUsed" to search.queryUsed.take(180),
                    "searchRequests" to search.requestCount.toString(),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),
'''
new = '''                    "queryUsed" to search.queryUsed.take(180),
                    "searchRequests" to search.requestCount.toString(),
                    "categoryUsed" to search.categoryUsed,
                    "selectedName" to search.selectedName.take(180),
                    "selectedDurationSec" to "%.2f".format(java.util.Locale.US, search.selectedDurationSec),
                    "selectedScore" to "%.3f".format(java.util.Locale.US, search.selectedScore),
                    "selectedLexicalCoverage" to "%.3f".format(java.util.Locale.US, search.selectedLexicalCoverage),
                    "excludedSoundIds" to search.excludedSoundIds.sorted().joinToString(","),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),
'''
if old not in text: raise SystemExit('search diagnostics anchor missing')
text = text.replace(old, new, 1)

# If an import is retryable, blacklist that exact remote ID before the next runtime attempt.
old = '''                        retryableFailure = retryableFailure || retryableImport
                        diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=$retryableImport errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
'''
new = '''                        if (retryableImport) rememberFailedSound(need, remote.id)
                        retryableFailure = retryableFailure || retryableImport
                        diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=$retryableImport blacklistedForRetry=$retryableImport errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
'''
if old not in text: raise SystemExit('import failure anchor missing')
text = text.replace(old, new, 1)

# Replace searchBest/searchBestOnce with strict-first + controlled category fallback and exclusion-aware ranking.
pattern = r'''    private suspend fun searchBest\(\n        need: FreesoundAutoSearchNeed,\n        effectiveQuery: String,\n    \): FreesoundAutoSearchOutcome \{.*?\n    private suspend fun normalizationTarget'''
replacement = '''    private suspend fun searchBest(
        need: FreesoundAutoSearchNeed,
        effectiveQuery: String,
        allowCategoryFallback: Boolean,
    ): FreesoundAutoSearchOutcome {
        val queries = linkedSetOf<String>().apply {
            effectiveQuery.trim().takeIf(String::isNotBlank)?.let(::add)
            searchQueryForRetry(need.query, 2).trim().takeIf(String::isNotBlank)?.let(::add)
            if (need.importance == FreesoundRequirementImportance.REQUIRED) {
                searchQueryForRetry(need.query, 3).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.ifEmpty { linkedSetOf(need.query.trim()) }

        var totalResults = 0
        var requests = 0
        var lastOutcome: FreesoundAutoSearchOutcome? = null
        val strictCategory = need.kind.toFreesoundCategory()
        for (query in queries) {
            val categories = if (allowCategoryFallback && strictCategory != FreesoundCategory.ALL) {
                listOf(strictCategory, FreesoundCategory.ALL)
            } else listOf(strictCategory)
            for (category in categories) {
                requests += 1
                val outcome = searchBestOnce(need, query, category)
                totalResults += outcome.resultCount
                val withTotals = outcome.copy(
                    resultCount = totalResults,
                    queryUsed = query,
                    requestCount = requests,
                )
                if (withTotals.sound != null) return withTotals
                lastOutcome = withTotals
                if (!withTotals.failureMessage.isNullOrBlank()) return withTotals
            }
        }
        return lastOutcome ?: FreesoundAutoSearchOutcome(
            sound = null,
            resultCount = 0,
            httpCode = 200,
            queryUsed = effectiveQuery,
            requestCount = requests,
            excludedSoundIds = failedSoundIds(need),
        )
    }

    private suspend fun searchBestOnce(
        need: FreesoundAutoSearchNeed,
        query: String,
        category: FreesoundCategory,
    ): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = query,
            category = category,
            duration = FreesoundDuration.RECOMMENDED,
            sort = FreesoundSort.RELEVANCE,
            page = 1,
            pageSize = SEARCH_PAGE_SIZE,
        )
        val excluded = failedSoundIds(need)
        return when (val result = client.search(request)) {
            is FreesoundSearchResult.Failure -> FreesoundAutoSearchOutcome(
                sound = null,
                failureMessage = result.message,
                retryable = isRetryableSearchFailure(result.httpCode),
                resultCount = 0,
                httpCode = result.httpCode,
                queryUsed = query,
                categoryUsed = category.name,
                excludedSoundIds = excluded,
            )
            is FreesoundSearchResult.Success -> {
                val selected = result.page.results
                    .asSequence()
                    .filterNot { it.id in excluded }
                    .mapIndexed { index, sound -> sound to scoreCandidate(need, sound, index) }
                    .filter { (sound, _) ->
                        sound.preferredPreviewUrl != null &&
                            candidateMeetsLexicalFloor(need, sound) &&
                            candidateMeetsDurationLimit(need, sound)
                    }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                FreesoundAutoSearchOutcome(
                    sound = selected?.first,
                    resultCount = result.page.results.size,
                    httpCode = 200,
                    queryUsed = query,
                    categoryUsed = category.name,
                    selectedName = selected?.first?.name.orEmpty(),
                    selectedDurationSec = selected?.first?.durationSeconds ?: 0.0,
                    selectedScore = selected?.second ?: 0.0,
                    selectedLexicalCoverage = selected?.first?.let { candidateLexicalCoverage(need, it) } ?: 0.0,
                    excludedSoundIds = excluded,
                )
            }
        }
    }

    private suspend fun normalizationTarget'''
new_text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
if count != 1: raise SystemExit(f'searchBest block match count {count}')
text = new_text

# Retry relaxation must preserve an acoustic source/instrument anchor, never collapse to an adjective such as "light".
old = '''        internal fun searchQueryForRetry(query: String, retryAttempt: Int): String {
            val original = query.trim()
            if (retryAttempt <= 1 || original.isBlank()) return original
            val tokens = FreesoundAutoRequirementAggregator.normalizeQuery(original)
                .split(' ')
                .map(String::trim)
                .filter { it.length >= 2 && it !in RETRY_QUERY_STOPWORDS }
            if (tokens.isEmpty()) return original
            return when {
                retryAttempt == 2 && tokens.size == 1 -> tokens.first()
                retryAttempt == 2 && tokens.size == 2 -> tokens.last()
                retryAttempt == 2 -> tokens.take(2).joinToString(" ")
                else -> tokens.first()
            }.ifBlank { original }
        }
'''
new = '''        internal fun searchQueryForRetry(query: String, retryAttempt: Int): String {
            val original = query.trim()
            if (retryAttempt <= 1 || original.isBlank()) return original
            val tokens = FreesoundAutoRequirementAggregator.normalizeQuery(original)
                .split(' ')
                .map(String::trim)
                .filter { it.length >= 2 && it !in RETRY_QUERY_STOPWORDS }
            if (tokens.isEmpty()) return original
            val acoustic = tokens.filterNot(RETRY_QUERY_MODIFIERS::contains).ifEmpty { tokens }
            return when {
                retryAttempt == 2 && acoustic.size >= 2 -> acoustic.take(2).joinToString(" ")
                retryAttempt == 2 && acoustic.size == 1 -> {
                    val modifier = tokens.lastOrNull { it != acoustic.first() && it in RETRY_QUERY_MODIFIERS }
                    listOfNotNull(modifier, acoustic.first()).distinct().joinToString(" ")
                }
                else -> acoustic.first()
            }.ifBlank { original }
        }
'''
if old not in text: raise SystemExit('retry query function anchor missing')
text = text.replace(old, new, 1)

# Widen first-page candidate pool modestly; still bounded and only 4 searches in parallel.
text = text.replace('private const val SEARCH_PAGE_SIZE = 15', 'private const val SEARCH_PAGE_SIZE = 30', 1)
old = '''        private val RETRY_QUERY_STOPWORDS = setOf(
            "a", "an", "the", "on", "in", "at", "with", "and", "or", "of", "to", "from", "for", "by",
            "into", "onto", "single", "one", "sound", "effect", "audio",
        )
'''
new = old + '''        private val RETRY_QUERY_MODIFIERS = setOf(
            "light", "quiet", "peaceful", "sad", "romantic", "tense", "heavy", "soft", "gentle",
            "distant", "far", "near", "close", "night", "day", "dark", "bright", "slow", "fast",
            "deep", "warm", "cold", "dramatic", "epic", "strong", "intense",
        )
'''
if old not in text: raise SystemExit('retry stopword anchor missing')
text = text.replace(old, new, 1)
resolver.write_text(text, encoding='utf-8')

# Prefer MP3 first: model.preferredPreviewUrl already does this, but importer had the opposite order.
importer = Path('app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt')
replace_once(
    importer,
    '            listOfNotNull(sound.previewHqOgg, sound.previewHqMp3)\n',
    '            listOfNotNull(sound.previewHqMp3, sound.previewHqOgg)\n',
    'preview order',
)

# Partial Mode-3 plans must remain playable while a missing asset is being retried / after exhaustion.
coord = Path('app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt')
text = coord.read_text(encoding='utf-8')
text = text.replace('''        if (StoryAudioModeRouter.usesAiFreesound(sourceMode) && freesoundResolutionRetryRequired(effectiveContent)) {
            return null
        }
''', '', 1)

old = '''        if (cachedFreesoundRetryExhausted) {
            freesoundRetryExhaustedChapters += content.chapter.id
            restoredFreesound = FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("Freesound đã thất bại sau 3 lần; cần bắt đầu một lượt phân vai mới."),
                retryableFailure = true,
                diagnostics = listOf("RESOLVE_BLOCKED retryExhausted=true attempts=3"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
            warnings += restoredFreesound.warnings
        }
'''
new = '''        if (cachedFreesoundRetryExhausted) {
            freesoundRetryExhaustedChapters += content.chapter.id
            val reusable = cachedFreesoundRequirements?.let { cachedFreesoundTrackIds(it, freesoundKinds) }.orEmpty()
            restoredFreesound = FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = reusable.size,
                warnings = listOf(
                    if (reusable.isEmpty()) "Freesound còn thiếu sau 3 lần; phần TTS vẫn được phát và có thể phân vai lại để thử âm thanh mới."
                    else "Một số âm thanh Freesound còn thiếu sau 3 lần; vẫn phát ${reusable.size} asset đã chuẩn bị hợp lệ.",
                ),
                reusedTrackIds = reusable,
                retryableFailure = false,
                diagnostics = listOf("RESOLVE_PARTIAL_REUSE retryExhausted=true attempts=3 reusable=${reusable.size}"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
            warnings += restoredFreesound.warnings
        }
'''
if old not in text: raise SystemExit('cached retry exhausted anchor missing')
text = text.replace(old, new, 1)

old = '''        return latest.copy(
            warnings = (warnings + "Freesound không tạo được kế hoạch âm thanh hợp lệ sau 3 lần thử.").distinct(),
            downloadedTrackIds = downloadedTrackIds,
            reusedTrackIds = reusedTrackIds - downloadedTrackIds,
            retryableFailure = true,
            diagnostics = (diagnostics + "RUNTIME_RETRY_EXHAUSTED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS").distinct(),
            attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
            retryExhausted = true,
        )
'''
new = '''        val partialResolved = latest.resolvedAssets > 0
        return latest.copy(
            warnings = (warnings + if (partialResolved) {
                "Một số âm thanh Freesound còn thiếu sau 3 lần; ứng dụng sẽ phát phần đã tải hợp lệ thay vì làm câm cả chương."
            } else {
                "Freesound chưa tải được asset nào sau 3 lần; TTS vẫn được phép phát."
            }).distinct(),
            downloadedTrackIds = downloadedTrackIds,
            reusedTrackIds = reusedTrackIds - downloadedTrackIds,
            retryableFailure = false,
            diagnostics = (diagnostics + "RUNTIME_RETRY_EXHAUSTED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS partialResolved=$partialResolved").distinct(),
            attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
            retryExhausted = true,
        )
'''
if old not in text: raise SystemExit('runtime exhausted return anchor missing')
text = text.replace(old, new, 1)

old = '''            .put("resolution_state", when {
                requirements.isEmpty() -> "AI_EMPTY"
                retryRequired -> "INCOMPLETE"
                else -> "COMPLETE"
            })
            .put("resolution_retry_required", retryRequired)
'''
new = '''            .put("resolution_state", when {
                requirements.isEmpty() -> "AI_EMPTY"
                retryExhausted && resolvedAssets > 0 -> "PARTIAL"
                retryExhausted -> "FAILED"
                retryRequired -> "INCOMPLETE"
                else -> "COMPLETE"
            })
            .put("resolution_retry_required", retryRequired && !retryExhausted)
'''
if old not in text: raise SystemExit('resolution state anchor missing')
text = text.replace(old, new, 1)
coord.write_text(text, encoding='utf-8')

# Playback must never stop TTS or discard valid music/ambience merely because one Mode-3 asset failed.
service = Path('app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt')
text = service.read_text(encoding='utf-8')
pattern = r'''\n                if \(planResult\?\.freesoundRetryExhausted == true\) \{.*?\n                    return@launch\n                \}\n\n                val mode3Incomplete = StoryAudioModeRouter\.usesAiFreesound\(storyAudioSourceMode\) &&\n                    planResult\?\.freesoundRetryRequired == true\n                if \(assignmentCount > 0 && !mode3Incomplete\) \{'''
replacement = '''
                if (planResult?.freesoundRetryExhausted == true) {
                    diagnostic(
                        "FREESOUND_MODE3_RETRY_EXHAUSTED",
                        if (planResult.freesoundResolvedAssets > 0) DiagnosticSeverity.WARN else DiagnosticSeverity.ERROR,
                        mapOf(
                            "attempts" to planResult.freesoundRetryAttempts.toString(),
                            "resolvedAssets" to planResult.freesoundResolvedAssets.toString(),
                            "partialPlaybackAllowed" to "true",
                        ),
                    )
                }

                if (assignmentCount > 0) {'''
new_text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
if count != 1: raise SystemExit(f'service all-or-nothing block match {count}')
service.write_text(new_text, encoding='utf-8')

# Prompt: make intensity/proximity explicit and protect sustained romantic scenes from being suppressed by ambience.
prompt = Path('app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt')
text = prompt.read_text(encoding='utf-8')
old = '                appendLine("- Nhạc hỗ trợ chức năng kể chuyện, hướng cảm xúc, nhịp, mức căng thẳng và quy mô; không dùng BGM như SFX để nhấn một hành động đơn lẻ.")\n'
new = old + '                appendLine("- Cảnh thân mật/lãng mạn hoặc bước ngoặt cảm xúc kéo dài là ứng viên MUSIC mạnh. Mưa, gió hay ambience đang phát không phải lý do bỏ nhạc nếu nhạc thực sự nâng đỡ cảm xúc; các lớp được phối hợp độc lập.")\n'
if old not in text: raise SystemExit('music prompt anchor missing')
text = text.replace(old, new, 1)
old = '                appendLine("- Không suy diễn ambience từ so sánh, ẩn dụ, hồi tưởng, dự đoán hoặc lời kể gián tiếp. Query ưu tiên nguồn vật lý + môi trường: forest wind, heavy rain, cave water.")\n'
new = old + '                appendLine("- Cường độ và khoảng cách phải bám đúng cảnh: mưa đang trút trực tiếp quanh nhân vật dùng heavy rain/rain roof/rain awning; chỉ dùng distant/far khi truyện thật sự mô tả nguồn ở xa. Không làm yếu nguồn gần chỉ để query dễ tìm.")\n'
if old not in text: raise SystemExit('ambience prompt anchor missing')
text = text.replace(old, new, 1)
prompt.write_text(text, encoding='utf-8')

# Sanity checks.
r = resolver.read_text(encoding='utf-8')
c = coord.read_text(encoding='utf-8')
s = service.read_text(encoding='utf-8')
i = importer.read_text(encoding='utf-8')
p = prompt.read_text(encoding='utf-8')
assert 'freesound_auto_query_cache_v2' in r
assert 'rememberFailedSound(need, remote.id)' in r
assert 'FreesoundCategory.ALL' in r
assert 'RETRY_QUERY_MODIFIERS' in r
assert 'SEARCH_PAGE_SIZE = 30' in r
assert 'listOfNotNull(sound.previewHqMp3, sound.previewHqOgg)' in i
assert 'partialPlaybackAllowed' in s
assert 'if (assignmentCount > 0)' in s
assert 'resolution_retry_required", retryRequired && !retryExhausted' in c
assert 'return null\n        }\n        if (!effectiveAmbience' not in c
assert 'Cảnh thân mật/lãng mạn' in p
print('Applied Freesound reliability, quality, retry, and partial-playback fixes')
