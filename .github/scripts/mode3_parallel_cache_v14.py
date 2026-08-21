from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_section(path: Path, start: str, end: str, replacement: str, label: str):
    text = path.read_text(encoding="utf-8")
    first = text.find(start)
    if first < 0:
        raise RuntimeError(f"{label}: start marker not found")
    second = text.find(end, first + len(start))
    if second < 0:
        raise RuntimeError(f"{label}: end marker not found")
    path.write_text(text[:first] + replacement + text[second:], encoding="utf-8")


# ---------------------------------------------------------------------------
# Resolver: persistent local-first query cache + bounded 4-way network search.
# ---------------------------------------------------------------------------
resolver = ROOT / "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    resolver,
    "import java.util.UUID\n",
    """import java.util.UUID\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.awaitAll\nimport kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withPermit\n""",
    "resolver coroutine imports",
)
replace_once(
    resolver,
    """import vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n""",
    """import vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticOperationContract\nimport vn.nghetruyen.source.diagnostics.DiagnosticOperationState\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n""",
    "diagnostic operation imports",
)
replace_once(
    resolver,
    """internal data class FreesoundAutoSearchOutcome(\n    val sound: FreesoundSound?,\n    val failureMessage: String? = null,\n    val retryable: Boolean = false,\n    val resultCount: Int = 0,\n    val httpCode: Int? = null,\n)\n\n""",
    """internal data class FreesoundAutoSearchOutcome(\n    val sound: FreesoundSound?,\n    val failureMessage: String? = null,\n    val retryable: Boolean = false,\n    val resultCount: Int = 0,\n    val httpCode: Int? = null,\n)\n\ninternal object FreesoundParallelSearchPolicy {\n    const val MAX_PARALLEL_SEARCHES = 4\n\n    suspend fun <T, R> mapOrdered(\n        values: List<T>,\n        transform: suspend (T) -> R,\n    ): List<R> = coroutineScope {\n        val semaphore = Semaphore(MAX_PARALLEL_SEARCHES)\n        values.map { value ->\n            async { semaphore.withPermit { transform(value) } }\n        }.awaitAll()\n    }\n}\n\nprivate data class FreesoundAutoPreparedNeed(\n    val index: Int,\n    val need: FreesoundAutoSearchNeed,\n    val cachedTrack: SceneMusicTrackEntity? = null,\n    val effectiveQuery: String = \"\",\n    val strategy: String = \"CACHE\",\n    val search: FreesoundAutoSearchOutcome? = null,\n    val searchElapsedMs: Long = 0L,\n)\n\n""",
    "parallel search policy",
)
replace_section(
    resolver,
    """    private fun liveDiagnostic(\n""",
    """    suspend fun usableManagedTrackIds(kinds: Set<AudioAssetKind>): Set<String> {\n""",
    """    private fun liveDiagnostic(\n        traceId: String,\n        name: String,\n        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,\n        attributes: Map<String, String> = emptyMap(),\n    ) {\n        val operationState = when (name) {\n            \"FREESOUND_RESOLVE_START\" -> DiagnosticOperationState.STARTED\n            \"FREESOUND_RESOLVE_DONE\", \"FREESOUND_RESOLVE_EMPTY\" -> DiagnosticOperationState.COMPLETED\n            \"FREESOUND_RETRY_EXHAUSTED\" -> DiagnosticOperationState.FAILED\n            else -> DiagnosticOperationState.STAGE\n        }\n        val operationAttributes = DiagnosticOperationContract.attributes(\n            id = traceId,\n            kind = \"FREESOUND_RESOLVE\",\n            flow = \"runtime\",\n            state = operationState,\n            stage = name,\n        )\n        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(\n            name = name,\n            category = DiagnosticCategory.RUNTIME,\n            severity = severity,\n            sourceId = \"freesound\",\n            traceId = traceId,\n            attributes = operationAttributes + attributes,\n        )\n    }\n\n""",
    "explicit Freesound operation lifecycle",
)
replace_once(
    resolver,
    """    fun clearResolutionCaches() {\n        queryCache.clear()\n        client.clearSearchCache()\n    }\n\n""",
    """    fun clearResolutionCaches() {\n        // Rebuilding a chapter must invalidate transient HTTP pages, but NOT the durable\n        // query -> downloaded-track index. Keeping that index is what lets the next voice-cast\n        // reuse a verified file on disk without spending another Freesound request or data.\n        client.clearSearchCache()\n    }\n\n    fun clearPersistentAssetQueryCache() {\n        queryCache.clear()\n    }\n\n""",
    "preserve durable asset query cache",
)

new_resolve = r'''    suspend fun resolve(
        requirements: List<FreesoundAutoRequirement>,
        retryAttempt: Int = 1,
        retryMax: Int = 1,
    ): FreesoundAutoResolveResult {
        val startedNanos = System.nanoTime()
        val traceId = "freesound-resolve:${UUID.randomUUID()}"
        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        val baseAttributes = mapOf(
            "retryAttempt" to retryAttempt.coerceAtLeast(1).toString(),
            "retryMax" to retryMax.coerceAtLeast(1).toString(),
        )
        val diagnostics = mutableListOf<String>()
        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES}"
        liveDiagnostic(
            traceId,
            "FREESOUND_RESOLVE_START",
            attributes = baseAttributes + mapOf(
                "requirements" to requirements.size.toString(),
                "aggregatedNeeds" to needs.size.toString(),
                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
            ),
        )
        if (needs.isEmpty()) {
            diagnostics += "RESOLVE_EMPTY no aggregated Freesound needs were produced"
            liveDiagnostic(
                traceId,
                "FREESOUND_RESOLVE_EMPTY",
                DiagnosticSeverity.WARN,
                baseAttributes + mapOf("requirements" to requirements.size.toString()),
            )
            return FreesoundAutoResolveResult(
                resolved = emptyList(),
                warnings = emptyList(),
                importedTrackIds = emptySet(),
                diagnostics = diagnostics,
            )
        }

        val warnings = mutableListOf<String>()
        val imported = linkedSetOf<String>()
        val resolutions = mutableListOf<FreesoundAutoResolvedNeed>()
        var retryableFailure = false
        var queryCacheHits = 0
        var importAttempts = 0
        var localSoundIdReuses = 0
        var normalizationResumes = 0
        var importElapsedTotalMs = 0L

        // One DB snapshot is enough for the local-first pass. A cached mapping is trusted only when
        // the row is enabled, has the requested kind, belongs to managed Freesound storage and the
        // physical file still exists. Stale mappings self-evict and fall back to network search.
        val cacheStartedNanos = System.nanoTime()
        var knownTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
        val prepared = needs.mapIndexed { index, need ->
            diagnostics += "NEED_START index=${index + 1}/${needs.size} kind=${need.kind.name} importance=${need.importance.name} usages=${need.usages.size} query=${need.query.take(160)}"
            liveDiagnostic(
                traceId,
                "FREESOUND_NEED_START",
                attributes = baseAttributes + mapOf(
                    "index" to (index + 1).toString(),
                    "total" to needs.size.toString(),
                    "kind" to need.kind.name,
                    "importance" to need.importance.name,
                    "usages" to need.usages.size.toString(),
                    "query" to need.query.take(180),
                ),
            )
            val cachedId = queryCache.get(need.kind, need.query)
            val cachedTrack = cachedId?.let { id -> knownTracks.firstOrNull { it.id == id } }
                ?.takeIf {
                    it.enabled &&
                        AudioAssetClassifier.classify(it) == need.kind &&
                        FreesoundImporter.soundIdFromManagedUri(it.uri) != null &&
                        FreesoundImporter.managedFileExists(appContext, it.uri)
                }
            if (cachedTrack != null) {
                queryCacheHits += 1
                diagnostics += "LOCAL_QUERY_CACHE_HIT kind=${need.kind.name} trackId=${cachedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri)} fileExists=true query=${need.query.take(140)}"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_LOCAL_QUERY_CACHE_HIT",
                    attributes = baseAttributes + mapOf(
                        "kind" to need.kind.name,
                        "trackId" to cachedTrack.id,
                        "soundId" to FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri).toString(),
                        "fileExists" to "true",
                        "networkSkipped" to "true",
                        "query" to need.query.take(180),
                    ),
                )
                FreesoundAutoPreparedNeed(index = index, need = need, cachedTrack = cachedTrack)
            } else {
                if (cachedId != null) {
                    diagnostics += "LOCAL_QUERY_CACHE_STALE kind=${need.kind.name} trackId=$cachedId reason=missing_disabled_wrong_kind_or_file_missing query=${need.query.take(140)}"
                    liveDiagnostic(
                        traceId,
                        "FREESOUND_LOCAL_QUERY_CACHE_STALE",
                        DiagnosticSeverity.WARN,
                        baseAttributes + mapOf(
                            "kind" to need.kind.name,
                            "trackId" to cachedId,
                            "query" to need.query.take(180),
                        ),
                    )
                    queryCache.remove(need.kind, need.query)
                } else {
                    diagnostics += "LOCAL_QUERY_CACHE_MISS kind=${need.kind.name} query=${need.query.take(140)}"
                }
                val effectiveQuery = searchQueryForRetry(need.query, retryAttempt)
                val tokenCount = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
                    .split(' ').count(String::isNotBlank)
                val strategy = when {
                    retryAttempt <= 1 -> "EXACT"
                    retryAttempt == 2 && tokenCount <= 2 -> "RELAXED_1_TERM_ALTERNATE"
                    retryAttempt == 2 -> "RELAXED_2_TERMS"
                    else -> "RELAXED_1_TERM_ANCHOR"
                }
                FreesoundAutoPreparedNeed(
                    index = index,
                    need = need,
                    effectiveQuery = effectiveQuery,
                    strategy = strategy,
                )
            }
        }
        val cacheLookupMs = (System.nanoTime() - cacheStartedNanos) / 1_000_000L

        // Network misses are the only work that enters this bounded pool. Four requests may be in
        // flight at once; cache hits never consume a permit. awaitAll preserves the seed order, and
        // final resolution is still committed in original need order for deterministic plans.
        val networkSeeds = prepared.filter { it.cachedTrack == null }
        val networkStartedNanos = System.nanoTime()
        val searched = FreesoundParallelSearchPolicy.mapOrdered(networkSeeds) { seed ->
            liveDiagnostic(
                traceId,
                "FREESOUND_CLIENT_SEARCH_START",
                attributes = baseAttributes + mapOf(
                    "index" to (seed.index + 1).toString(),
                    "total" to needs.size.toString(),
                    "kind" to seed.need.kind.name,
                    "query" to seed.need.query.take(180),
                    "effectiveQuery" to seed.effectiveQuery.take(180),
                    "strategy" to seed.strategy,
                    "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                ),
            )
            val searchStartedNanos = System.nanoTime()
            val search = searchBest(seed.need, seed.effectiveQuery)
            val elapsedMs = (System.nanoTime() - searchStartedNanos) / 1_000_000L
            liveDiagnostic(
                traceId,
                "FREESOUND_CLIENT_SEARCH_DONE",
                if (search.failureMessage.isNullOrBlank()) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                baseAttributes + mapOf(
                    "index" to (seed.index + 1).toString(),
                    "kind" to seed.need.kind.name,
                    "elapsedMs" to elapsedMs.toString(),
                    "resultCount" to search.resultCount.toString(),
                    "httpCode" to (search.httpCode ?: 0).toString(),
                    "selectedSoundId" to (search.sound?.id ?: 0).toString(),
                    "originalQuery" to seed.need.query.take(180),
                    "effectiveQuery" to seed.effectiveQuery.take(180),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),
                ),
            )
            seed.copy(search = search, searchElapsedMs = elapsedMs)
        }
        val networkSearchWallMs = if (networkSeeds.isEmpty()) 0L
        else (System.nanoTime() - networkStartedNanos) / 1_000_000L
        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)

        for (seed in prepared.sortedBy(FreesoundAutoPreparedNeed::index)) {
            val need = seed.need
            val cachedTrack = seed.cachedTrack
            if (cachedTrack != null) {
                resolutions += FreesoundAutoResolvedNeed(need, cachedTrack.id, FreesoundAutoResolutionSource.CACHE.name)
                continue
            }

            val resolvedSearch = requireNotNull(searchedByIndex[seed.index])
            val search = requireNotNull(resolvedSearch.search)
            val remote = search.sound
            diagnostics += "CLIENT_SEARCH_DONE index=${seed.index + 1} kind=${need.kind.name} elapsedMs=${resolvedSearch.searchElapsedMs} resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} strategy=${resolvedSearch.strategy}"
            if (!search.failureMessage.isNullOrBlank()) {
                warnings += "Freesound ‘${need.query}’ (${resolvedSearch.effectiveQuery}): ${search.failureMessage}"
                retryableFailure = retryableFailure || search.retryable
            }

            var resolvedTrack: SceneMusicTrackEntity? = null
            if (remote != null) {
                // A cold query cache can still point the search at a sound already stored on disk
                // (e.g. after upgrading from an older build). Reuse it before invoking importer or
                // normalization. This first cold lookup still needed search once to learn the mapping;
                // queryCache.put below guarantees future identical queries skip network entirely.
                val existingSoundTrack = knownTracks.firstOrNull { track ->
                    track.enabled &&
                        AudioAssetClassifier.classify(track) == need.kind &&
                        FreesoundImporter.soundIdFromManagedUri(track.uri) == remote.id &&
                        FreesoundImporter.managedFileExists(appContext, track.uri)
                }
                if (existingSoundTrack != null && FreesoundImporter.hasValidNormalization(existingSoundTrack)) {
                    resolvedTrack = existingSoundTrack
                    localSoundIdReuses += 1
                    diagnostics += "LOCAL_SOUND_ID_REUSE kind=${need.kind.name} soundId=${remote.id} trackId=${existingSoundTrack.id} importerSkipped=true"
                    liveDiagnostic(
                        traceId,
                        "FREESOUND_LOCAL_SOUND_ID_REUSE",
                        attributes = baseAttributes + mapOf(
                            "kind" to need.kind.name,
                            "soundId" to remote.id.toString(),
                            "trackId" to existingSoundTrack.id,
                            "importerSkipped" to "true",
                            "query" to need.query.take(180),
                        ),
                    )
                } else {
                    importAttempts += 1
                    val importStartedNanos = System.nanoTime()
                    diagnostics += "IMPORT_START kind=${need.kind.name} soundId=${remote.id} durationSec=${"%.2f".format(java.util.Locale.US, remote.durationSeconds)} previewAvailable=${remote.preferredPreviewUrl != null} query=${need.query.take(140)}"
                    liveDiagnostic(
                        traceId,
                        "FREESOUND_IMPORT_START",
                        attributes = baseAttributes + mapOf(
                            "kind" to need.kind.name,
                            "soundId" to remote.id.toString(),
                            "durationSec" to "%.2f".format(java.util.Locale.US, remote.durationSeconds),
                            "previewAvailable" to (remote.preferredPreviewUrl != null).toString(),
                            "query" to need.query.take(180),
                        ),
                    )
                    val import = importer.importPreview(
                        sound = remote,
                        kind = need.kind,
                        normalizationTargetLufs = normalizationTarget(need.kind),
                    )
                    val importElapsedMs = (System.nanoTime() - importStartedNanos) / 1_000_000L
                    importElapsedTotalMs += importElapsedMs
                    if (import.isSuccess) {
                        val result = import.getOrThrow()
                        knownTracks = runCatching { existingTracksProvider() }.getOrDefault(knownTracks)
                        resolvedTrack = knownTracks.firstOrNull { it.id == result.trackId && it.enabled }
                        if (existingSoundTrack == null) imported += result.trackId else normalizationResumes += 1
                        diagnostics += "IMPORT_SUCCESS kind=${need.kind.name} soundId=${remote.id} trackId=${result.trackId} elapsedMs=$importElapsedMs downloadMs=${result.downloadElapsedMs} normalizationMs=${result.normalizationElapsedMs} fileExists=${resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true}"
                        liveDiagnostic(
                            traceId,
                            "FREESOUND_IMPORT_SUCCESS",
                            attributes = baseAttributes + mapOf(
                                "kind" to need.kind.name,
                                "soundId" to remote.id.toString(),
                                "trackId" to result.trackId,
                                "elapsedMs" to importElapsedMs.toString(),
                                "downloadMs" to result.downloadElapsedMs.toString(),
                                "normalizationMs" to result.normalizationElapsedMs.toString(),
                                "fileExists" to (resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true).toString(),
                            ),
                        )
                    } else if (import.exceptionOrNull() is FreesoundDuplicateException) {
                        knownTracks = runCatching { existingTracksProvider() }.getOrDefault(knownTracks)
                        resolvedTrack = knownTracks.firstOrNull { track ->
                            track.enabled &&
                                FreesoundImporter.managedFileExists(appContext, track.uri) &&
                                FreesoundImporter.soundIdFromManagedUri(track.uri) == remote.id &&
                                AudioAssetClassifier.classify(track) == need.kind
                        }
                        if (resolvedTrack != null) localSoundIdReuses += 1
                        diagnostics += "IMPORT_DUPLICATE kind=${need.kind.name} soundId=${remote.id} reusedTrackId=${resolvedTrack?.id.orEmpty()} elapsedMs=$importElapsedMs fileExists=${resolvedTrack != null}"
                        liveDiagnostic(
                            traceId,
                            "FREESOUND_IMPORT_DUPLICATE",
                            attributes = baseAttributes + mapOf(
                                "kind" to need.kind.name,
                                "soundId" to remote.id.toString(),
                                "reusedTrackId" to resolvedTrack?.id.orEmpty(),
                                "elapsedMs" to importElapsedMs.toString(),
                            ),
                        )
                    } else {
                        val error = import.exceptionOrNull()
                        val message = error?.message?.takeIf(String::isNotBlank)
                            ?: "Không nhập/chuẩn hóa được preview đã chọn."
                        warnings += "Freesound ‘${need.query}’: $message"
                        if (error is FreesoundNormalizationException && error.retryable) retryableFailure = true
                        diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=${error is FreesoundNormalizationException && error.retryable} errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
                        liveDiagnostic(
                            traceId,
                            "FREESOUND_IMPORT_FAILED",
                            DiagnosticSeverity.WARN,
                            baseAttributes + mapOf(
                                "kind" to need.kind.name,
                                "soundId" to remote.id.toString(),
                                "elapsedMs" to importElapsedMs.toString(),
                                "retryable" to (error is FreesoundNormalizationException && error.retryable).toString(),
                                "errorType" to error?.javaClass?.simpleName.orEmpty(),
                                "error" to message.take(240),
                            ),
                        )
                    }
                }
            } else {
                diagnostics += "SEARCH_NO_SELECTION kind=${need.kind.name} resultCount=${search.resultCount} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} strategy=${resolvedSearch.strategy}"
            }

            if (resolvedTrack != null && resolvedTrack.enabled &&
                FreesoundImporter.managedFileExists(appContext, resolvedTrack.uri) &&
                FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri) != null
            ) {
                queryCache.put(need.kind, need.query, resolvedTrack.id)
                resolutions += FreesoundAutoResolvedNeed(need, resolvedTrack.id, FreesoundAutoResolutionSource.FREESOUND.name)
                diagnostics += "NEED_RESOLVED kind=${need.kind.name} source=FREESOUND trackId=${resolvedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri)} query=${need.query.take(140)} cachePersisted=true"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_NEED_RESOLVED",
                    attributes = baseAttributes + mapOf(
                        "kind" to need.kind.name,
                        "trackId" to resolvedTrack.id,
                        "soundId" to FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri).toString(),
                        "query" to need.query.take(180),
                        "cachePersisted" to "true",
                    ),
                )
                continue
            }

            resolutions += FreesoundAutoResolvedNeed(need, null, FreesoundAutoResolutionSource.UNRESOLVED.name)
            diagnostics += "NEED_UNRESOLVED kind=${need.kind.name} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} strategy=${resolvedSearch.strategy}"
            liveDiagnostic(
                traceId,
                "FREESOUND_NEED_UNRESOLVED",
                DiagnosticSeverity.WARN,
                baseAttributes + mapOf(
                    "kind" to need.kind.name,
                    "importance" to need.importance.name,
                    "originalQuery" to need.query.take(180),
                    "effectiveQuery" to resolvedSearch.effectiveQuery.take(180),
                    "strategy" to resolvedSearch.strategy,
                ),
            )
            if (search.failureMessage.isNullOrBlank() && remote == null) {
                val prefix = if (need.importance == FreesoundRequirementImportance.REQUIRED) "Âm thanh quan trọng" else "Âm thanh tùy chọn"
                warnings += "$prefix ‘${need.query}’ chưa tìm thấy kết quả đủ phù hợp trên Freesound; khoảng đó sẽ im lặng ở lớp tương ứng."
            }
        }

        val totalElapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
        val unresolvedRequired = resolutions.count {
            it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED
        }
        val retryRecommended = retryableFailure ||
            (resolutions.isNotEmpty() && (resolutions.none { !it.trackId.isNullOrBlank() } || unresolvedRequired > 0))
        val clientSearches = networkSeeds.size
        diagnostics += "RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired queryCacheHits=$queryCacheHits clientSearches=$clientSearches parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} cacheLookupMs=$cacheLookupMs networkSearchWallMs=$networkSearchWallMs importAttempts=$importAttempts localSoundIdReuses=$localSoundIdReuses normalizationResumes=$normalizationResumes imported=${imported.size} importElapsedTotalMs=$importElapsedTotalMs retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs"
        liveDiagnostic(
            traceId,
            "FREESOUND_RESOLVE_DONE",
            if (retryRecommended) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            baseAttributes + mapOf(
                "requirements" to requirements.size.toString(),
                "aggregatedNeeds" to needs.size.toString(),
                "resolved" to resolutions.count { !it.trackId.isNullOrBlank() }.toString(),
                "unresolved" to resolutions.count { it.trackId.isNullOrBlank() }.toString(),
                "unresolvedRequired" to unresolvedRequired.toString(),
                "queryCacheHits" to queryCacheHits.toString(),
                "clientSearches" to clientSearches.toString(),
                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                "cacheLookupMs" to cacheLookupMs.toString(),
                "networkSearchWallMs" to networkSearchWallMs.toString(),
                "importAttempts" to importAttempts.toString(),
                "localSoundIdReuses" to localSoundIdReuses.toString(),
                "normalizationResumes" to normalizationResumes.toString(),
                "imported" to imported.size.toString(),
                "importElapsedTotalMs" to importElapsedTotalMs.toString(),
                "retryRecommended" to retryRecommended.toString(),
                "elapsedMs" to totalElapsedMs.toString(),
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
            resolved = resolutions,
            warnings = warnings.distinct(),
            importedTrackIds = imported,
            retryableFailure = retryableFailure,
            diagnostics = diagnostics.distinct(),
        )
    }

'''
replace_section(
    resolver,
    """    suspend fun resolve(\n""",
    """    private suspend fun searchBest(\n""",
    new_resolve,
    "parallel resolver body",
)
replace_section(
    resolver,
    """        internal fun searchQueryForRetry(query: String, retryAttempt: Int): String {\n""",
    """        internal fun scoreCandidate(\n""",
    r'''        internal fun searchQueryForRetry(query: String, retryAttempt: Int): String {
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

''',
    "retry query never repeats two-term exact search",
)

# ---------------------------------------------------------------------------
# Importer: split download vs normalization timing in diagnostics.
# ---------------------------------------------------------------------------
importer = ROOT / "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt"
replace_once(
    importer,
    """data class FreesoundImportResult(\n    val trackId: String,\n    val uri: String,\n    val title: String,\n)\n""",
    """data class FreesoundImportResult(\n    val trackId: String,\n    val uri: String,\n    val title: String,\n    val downloadElapsedMs: Long = 0L,\n    val normalizationElapsedMs: Long = 0L,\n)\n""",
    "import timing result fields",
)
replace_once(
    importer,
    """            val workId = SceneMusicAnalysisWorker.enqueue(\n                context = appContext,\n                trackId = track.id,\n                targetLufs = normalizationTargetLufs,\n                fastFreesound = true,\n            )\n            awaitNormalization(workId, track.id)\n            marker?.delete()\n            Result.success(\n                FreesoundImportResult(\n                    trackId = track.id,\n                    uri = track.uri,\n                    title = track.title,\n                ),\n            )\n""",
    """            val normalizationStartedNanos = System.nanoTime()\n            val workId = SceneMusicAnalysisWorker.enqueue(\n                context = appContext,\n                trackId = track.id,\n                targetLufs = normalizationTargetLufs,\n                fastFreesound = true,\n            )\n            awaitNormalization(workId, track.id)\n            val normalizationElapsedMs = (System.nanoTime() - normalizationStartedNanos) / 1_000_000L\n            marker?.delete()\n            Result.success(\n                FreesoundImportResult(\n                    trackId = track.id,\n                    uri = track.uri,\n                    title = track.title,\n                    downloadElapsedMs = 0L,\n                    normalizationElapsedMs = normalizationElapsedMs,\n                ),\n            )\n""",
    "resume normalization timing",
)
replace_once(
    importer,
    """        return try {\n            val request = Request.Builder()\n""",
    """        return try {\n            val downloadStartedNanos = System.nanoTime()\n            val request = Request.Builder()\n""",
    "download timer start",
)
replace_once(
    importer,
    """            validateDownloadedAudio(finalFile)\n            markerFile.writeText(\"normalizing\", Charsets.UTF_8)\n\n            val uri = Uri.fromFile(finalFile).toString()\n""",
    """            validateDownloadedAudio(finalFile)\n            val downloadElapsedMs = (System.nanoTime() - downloadStartedNanos) / 1_000_000L\n            markerFile.writeText(\"normalizing\", Charsets.UTF_8)\n\n            val uri = Uri.fromFile(finalFile).toString()\n""",
    "download timer finish",
)
replace_once(
    importer,
    """            val workId = SceneMusicAnalysisWorker.enqueue(\n                context = appContext,\n                trackId = trackId,\n                targetLufs = normalizationTargetLufs,\n                fastFreesound = true,\n            )\n            awaitNormalization(workId, trackId)\n            markerFile.delete()\n\n            Result.success(\n                FreesoundImportResult(\n                    trackId = trackId,\n                    uri = uri,\n                    title = title,\n                ),\n            )\n""",
    """            val normalizationStartedNanos = System.nanoTime()\n            val workId = SceneMusicAnalysisWorker.enqueue(\n                context = appContext,\n                trackId = trackId,\n                targetLufs = normalizationTargetLufs,\n                fastFreesound = true,\n            )\n            awaitNormalization(workId, trackId)\n            val normalizationElapsedMs = (System.nanoTime() - normalizationStartedNanos) / 1_000_000L\n            markerFile.delete()\n\n            Result.success(\n                FreesoundImportResult(\n                    trackId = trackId,\n                    uri = uri,\n                    title = title,\n                    downloadElapsedMs = downloadElapsedMs,\n                    normalizationElapsedMs = normalizationElapsedMs,\n                ),\n            )\n""",
    "new import normalization timing",
)

# ---------------------------------------------------------------------------
# Fast Mode-3 loudness measurement: bounded windows for long files.
# The original compressed file remains untouched; this only limits PCM measurement time.
# ---------------------------------------------------------------------------
worker = ROOT / "app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt"
replace_once(
    worker,
    """        internal fun fastAnalysisDurationUs(kind: AudioAssetKind): Long = when (kind) {\n            AudioAssetKind.MUSIC -> 45_000_000L\n            AudioAssetKind.AMBIENCE -> 30_000_000L\n            AudioAssetKind.SFX -> 15_000_000L\n        }\n""",
    """        internal fun fastAnalysisDurationUs(kind: AudioAssetKind): Long = when (kind) {\n            // Mode 3 only: cap the decoded measurement window so 70-150s previews do not\n            // block narration startup for tens of seconds. Original MP3/OGG bytes are untouched.\n            AudioAssetKind.MUSIC -> 24_000_000L\n            AudioAssetKind.AMBIENCE -> 20_000_000L\n            AudioAssetKind.SFX -> 10_000_000L\n        }\n""",
    "shorter long-file normalization windows",
)

# ---------------------------------------------------------------------------
# Tests: four-way concurrency is real + deterministic; two-term retry is relaxed.
# ---------------------------------------------------------------------------
retry_test = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundRetryQueryTest.kt"
replace_once(
    retry_test,
    """    @Test\n    fun thirdAttemptKeepsTheFirstImportantTerm() {\n""",
    """    @Test\n    fun twoTermQueryDoesNotRepeatTheExactSearchOnSecondAttempt() {\n        assertEquals(\"guzheng\", FreesoundAutoAudioResolver.searchQueryForRetry(\"mysterious guzheng\", 2))\n        assertEquals(\"mysterious\", FreesoundAutoAudioResolver.searchQueryForRetry(\"mysterious guzheng\", 3))\n        assertEquals(\"clash\", FreesoundAutoAudioResolver.searchQueryForRetry(\"sword clash\", 2))\n    }\n\n    @Test\n    fun thirdAttemptKeepsTheFirstImportantTerm() {\n""",
    "retry two-term regression test",
)
parallel_test = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundParallelSearchPolicyTest.kt"
parallel_test.write_text(
    r'''package vn.nghetruyen.app.freesound

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundParallelSearchPolicyTest {
    @Test
    fun searchesRunConcurrentlyWithHardLimitFourAndKeepOrder() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val values = (0 until 12).toList()

        val output = FreesoundParallelSearchPolicy.mapOrdered(values) { value ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(35)
                value * 10
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(values.map { it * 10 }, output)
        assertEquals(4, FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES)
        assertTrue("expected actual overlap", maximum.get() > 1)
        assertTrue("must never exceed four searches", maximum.get() <= 4)
    }
}
''',
    encoding="utf-8",
)

print("Mode 3 V14 local-first cache, 4-way search, timing and long-file optimization patch applied.")
