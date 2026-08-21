from pathlib import Path


def patch(path: str, old: str, new: str, marker: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if marker in text:
        print(f"SKIP {path}: {marker}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 occurrence, found {count}; marker={marker!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"PATCH {path}: {marker}")


resolver = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
patch(
    resolver,
    '''internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
)''',
    '''internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
    val queryUsed: String = "",
    val requestCount: Int = 1,
)''',
    "val requestCount: Int = 1",
)
patch(
    resolver,
    '''    private suspend fun searchBest(
        need: FreesoundAutoSearchNeed,
        effectiveQuery: String,
    ): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = effectiveQuery,
            category = need.kind.toFreesoundCategory(),
            duration = FreesoundDuration.RECOMMENDED,
            sort = FreesoundSort.RELEVANCE,
            page = 1,
            pageSize = SEARCH_PAGE_SIZE,
        )
        return when (val result = client.search(request)) {
            is FreesoundSearchResult.Failure -> FreesoundAutoSearchOutcome(
                sound = null,
                failureMessage = result.message,
                retryable = result.httpCode == null || result.httpCode in setOf(401, 403, 429) ||
                    (result.httpCode ?: 0) >= 500,
                resultCount = 0,
                httpCode = result.httpCode,
            )
            is FreesoundSearchResult.Success -> FreesoundAutoSearchOutcome(
                sound = result.page.results
                    .mapIndexed { index, sound -> sound to scoreCandidate(need, sound, index) }
                    .filter { it.first.preferredPreviewUrl != null }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,
                resultCount = result.page.results.size,
                httpCode = 200,
            )
        }
    }''',
    '''    private suspend fun searchBest(
        need: FreesoundAutoSearchNeed,
        effectiveQuery: String,
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
        for (query in queries) {
            requests += 1
            val outcome = searchBestOnce(need, query)
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
        return lastOutcome ?: FreesoundAutoSearchOutcome(
            sound = null,
            resultCount = 0,
            httpCode = 200,
            queryUsed = effectiveQuery,
            requestCount = requests,
        )
    }

    private suspend fun searchBestOnce(
        need: FreesoundAutoSearchNeed,
        query: String,
    ): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = query,
            category = need.kind.toFreesoundCategory(),
            duration = FreesoundDuration.RECOMMENDED,
            sort = FreesoundSort.RELEVANCE,
            page = 1,
            pageSize = SEARCH_PAGE_SIZE,
        )
        return when (val result = client.search(request)) {
            is FreesoundSearchResult.Failure -> FreesoundAutoSearchOutcome(
                sound = null,
                failureMessage = result.message,
                retryable = result.httpCode == null || result.httpCode in setOf(401, 403, 429) ||
                    (result.httpCode ?: 0) >= 500,
                resultCount = 0,
                httpCode = result.httpCode,
                queryUsed = query,
            )
            is FreesoundSearchResult.Success -> FreesoundAutoSearchOutcome(
                sound = result.page.results
                    .mapIndexed { index, sound -> sound to scoreCandidate(need, sound, index) }
                    .filter { it.first.preferredPreviewUrl != null }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,
                resultCount = result.page.results.size,
                httpCode = 200,
                queryUsed = query,
            )
        }
    }''',
    "private suspend fun searchBestOnce(",
)
patch(
    resolver,
    '''                    "effectiveQuery" to seed.effectiveQuery.take(180),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),''',
    '''                    "effectiveQuery" to seed.effectiveQuery.take(180),
                    "queryUsed" to search.queryUsed.take(180),
                    "searchRequests" to search.requestCount.toString(),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),''',
    '"searchRequests" to search.requestCount.toString()',
)
patch(
    resolver,
    '''            diagnostics += "CLIENT_SEARCH_DONE index=${seed.index + 1} kind=${need.kind.name} elapsedMs=${resolvedSearch.searchElapsedMs} resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} strategy=${resolvedSearch.strategy}"''',
    '''            diagnostics += "CLIENT_SEARCH_DONE index=${seed.index + 1} kind=${need.kind.name} elapsedMs=${resolvedSearch.searchElapsedMs} resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} queryUsed=${search.queryUsed.take(140)} searchRequests=${search.requestCount} strategy=${resolvedSearch.strategy}"''',
    "searchRequests=${search.requestCount}",
)
patch(
    resolver,
    "        val clientSearches = networkSeeds.size",
    "        val clientSearches = searched.sumOf { it.search?.requestCount ?: 0 }",
    "searched.sumOf { it.search?.requestCount ?: 0 }",
)

importer = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt"
patch(
    importer,
    "val buffer = ByteArray(DEFAULT_BUFFER_SIZE)",
    "val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)",
    "ByteArray(DOWNLOAD_BUFFER_BYTES)",
)
patch(
    importer,
    '''        private const val NORMALIZATION_POLL_MS = 120L''',
    '''        private const val NORMALIZATION_POLL_MS = 120L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024''',
    "DOWNLOAD_BUFFER_BYTES = 64 * 1024",
)
patch(
    importer,
    '''        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()''',
    '''        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()''',
    ".callTimeout(30, TimeUnit.SECONDS)",
)

reader = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
patch(
    reader,
    '''        if (result != null && result.freesoundResolvedAssets == 0) {''',
    '''        if (
            result != null &&
            result.freesoundResolvedAssets == 0 &&
            (result.freesoundRetryRequired || result.freesoundRetryExhausted)
        ) {''',
    "(result.freesoundRetryRequired || result.freesoundRetryExhausted)",
)
patch(
    reader,
    '''            // BASIC diagnostics must still expose every Freesound stage while debugging Mode 3.
            val severity = DiagnosticSeverity.WARN''',
    '''            // BASIC diagnostics keeps INFO, so normal Freesound stages stay visible without
            // inflating the warning count. Only actual failed/unresolved stages are warnings.
            val normalizedDetail = detail.uppercase(Locale.ROOT)
            val severity = if (
                normalizedDetail.contains("FAILED") ||
                normalizedDetail.contains("ERROR") ||
                normalizedDetail.contains("RETRY_EXHAUSTED") ||
                normalizedDetail.contains("NEED_UNRESOLVED")
            ) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO''',
    "val normalizedDetail = detail.uppercase(Locale.ROOT)",
)
patch(
    reader,
    "    private var activeSceneTrackId: String? = null",
    '''    private var activeSceneTrackId: String? = null
    private var lastSceneMusicLookupTraceState: String = ""''',
    "lastSceneMusicLookupTraceState",
)
patch(
    reader,
    '''        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_MUSIC_LOOKUP",
                DiagnosticSeverity.INFO,
                mapOf(
                    "canonicalPlan" to canonicalPlanActive.toString(),
                    "requestedTrackId" to requestedTrackId.orEmpty(),
                    "unitId" to unitId.orEmpty(),
                    "paragraphIndex" to paragraphIndex.toString(),
                    "availableTracks" to sceneMusicTracks.size.toString(),
                ),
            )
        }''',
    '''        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            val lookupTraceState = "$canonicalPlanActive:${requestedTrackId.orEmpty()}"
            if (lookupTraceState != lastSceneMusicLookupTraceState) {
                diagnostic(
                    "FREESOUND_RUNTIME_MUSIC_LOOKUP",
                    DiagnosticSeverity.INFO,
                    mapOf(
                        "canonicalPlan" to canonicalPlanActive.toString(),
                        "requestedTrackId" to requestedTrackId.orEmpty(),
                        "unitId" to unitId.orEmpty(),
                        "paragraphIndex" to paragraphIndex.toString(),
                        "availableTracks" to sceneMusicTracks.size.toString(),
                    ),
                )
                lastSceneMusicLookupTraceState = lookupTraceState
            }
        }''',
    "val lookupTraceState =",
)
patch(
    reader,
    '''        activeSceneTrackId = sceneMusicController.activeTrackId
        PlaybackQueueStore.updateVoice(config.rate, config.pitch, config.volume)''',
    '''        activeSceneTrackId = sceneMusicController.activeTrackId
        lastSceneMusicLookupTraceState = ""
        PlaybackQueueStore.updateVoice(config.rate, config.pitch, config.volume)''',
    'lastSceneMusicLookupTraceState = ""\n        PlaybackQueueStore.updateVoice',
)

runtime = "app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt"
patch(
    runtime,
    '''        if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_AUDIO_PLAN_CHECK",
                DiagnosticSeverity.INFO,
                mapOf(
                    "force" to force.toString(),
                    "ambienceEnabled" to settings.ambienceEnabled.toString(),
                    "sfxEnabled" to settings.soundEffectsEnabled.toString(),
                    "preparedChapterId" to preparedChapterId,
                ),
            )
        }
        val mode3RuntimeEmpty = StoryAudioModeRouter.usesAiFreesound(sourceMode) &&
            (settings.ambienceEnabled || settings.soundEffectsEnabled) &&
            assetsById.values.none { asset ->
                (settings.ambienceEnabled && asset.kind == AudioAssetKind.AMBIENCE) ||
                    (settings.soundEffectsEnabled && asset.kind == AudioAssetKind.SFX)
            }
        if (!force &&
            !mode3RuntimeEmpty &&
            preparedChapterId == snapshot.chapterId &&
            preparedSignature.isNotBlank() &&
            validatedFastKey == fastKey &&
            now - validatedFastAtMillis < PLAN_REVALIDATE_INTERVAL_MS
        ) {
            return true
        }

        var rawTracks = libraryRepository.listEnabledSceneMusicTracks()''',
    '''        if (!force &&
            preparedChapterId == snapshot.chapterId &&
            preparedSignature.isNotBlank() &&
            validatedFastKey == fastKey &&
            now - validatedFastAtMillis < PLAN_REVALIDATE_INTERVAL_MS
        ) {
            return true
        }
        if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_AUDIO_PLAN_CHECK",
                DiagnosticSeverity.INFO,
                mapOf(
                    "force" to force.toString(),
                    "ambienceEnabled" to settings.ambienceEnabled.toString(),
                    "sfxEnabled" to settings.soundEffectsEnabled.toString(),
                    "preparedChapterId" to preparedChapterId,
                    "revalidation" to "true",
                ),
            )
        }

        var rawTracks = libraryRepository.listEnabledSceneMusicTracks()''',
    '"revalidation" to "true"',
)
patch(
    runtime,
    "                if (activeAudioAssets.isEmpty()) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,",
    "                DiagnosticSeverity.INFO,",
    '"FREESOUND_RUNTIME_AUDIO_ASSETS",\n                DiagnosticSeverity.INFO',
)
patch(
    runtime,
    "                    if (outcome.freesoundResolvedAssets > 0 && !outcome.freesoundRetryRequired) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,",
    "                    if (outcome.freesoundRetryRequired || outcome.freesoundRetryExhausted) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,",
    "outcome.freesoundRetryRequired || outcome.freesoundRetryExhausted",
)
patch(
    runtime,
    '                diagnostic("FREESOUND_RUNTIME_AMBIENCE_DISABLED", DiagnosticSeverity.WARN)',
    '                diagnostic("FREESOUND_RUNTIME_AMBIENCE_DISABLED", DiagnosticSeverity.INFO)',
    '"FREESOUND_RUNTIME_AMBIENCE_DISABLED", DiagnosticSeverity.INFO',
)
patch(
    runtime,
    '''        val assets = ambienceByUnitId[unitId]
            .orEmpty()
            .asSequence()
            .mapNotNull(assetsById::get)
            .filter { it.kind == AudioAssetKind.AMBIENCE }
            .distinctBy(AudioDirectionAsset::id)
            .toList()
        val state = assets.joinToString(",") { it.id }.ifBlank { "empty" }
        if (mode3Active() && state != lastAmbienceTraceState) {
            diagnostic(
                if (assets.isEmpty()) "FREESOUND_RUNTIME_AMBIENCE_EMPTY" else "FREESOUND_RUNTIME_AMBIENCE_PLAY",
                if (assets.isEmpty()) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                mapOf(
                    "unitId" to unitId,
                    "assetCount" to assets.size.toString(),
                    "assetIds" to assets.joinToString(",") { it.id }.take(260),
                    "filesExist" to (assets.isNotEmpty() &&
                        assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }).toString(),
                ),
            )
            lastAmbienceTraceState = state
        }''',
    '''        val plannedAssetIds = ambienceByUnitId[unitId].orEmpty()
        val assets = plannedAssetIds
            .asSequence()
            .mapNotNull(assetsById::get)
            .filter { it.kind == AudioAssetKind.AMBIENCE }
            .distinctBy(AudioDirectionAsset::id)
            .toList()
        val missingPlannedAssets = plannedAssetIds.isNotEmpty() && assets.size < plannedAssetIds.distinct().size
        val state = when {
            assets.isNotEmpty() -> "play:${assets.joinToString(",") { it.id }}"
            missingPlannedAssets -> "missing:${plannedAssetIds.joinToString(",")}"
            else -> "silent"
        }
        if (mode3Active() && state != lastAmbienceTraceState) {
            val eventName = when {
                assets.isNotEmpty() -> "FREESOUND_RUNTIME_AMBIENCE_PLAY"
                missingPlannedAssets -> "FREESOUND_RUNTIME_AMBIENCE_MISSING"
                else -> "FREESOUND_RUNTIME_AMBIENCE_SILENT"
            }
            diagnostic(
                eventName,
                if (missingPlannedAssets) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                mapOf(
                    "unitId" to unitId,
                    "plannedAssetCount" to plannedAssetIds.distinct().size.toString(),
                    "assetCount" to assets.size.toString(),
                    "assetIds" to assets.joinToString(",") { it.id }.take(260),
                    "filesExist" to (assets.isNotEmpty() &&
                        assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }).toString(),
                ),
            )
            lastAmbienceTraceState = state
        }''',
    '"FREESOUND_RUNTIME_AMBIENCE_SILENT"',
)
patch(
    runtime,
    "        private const val PLAN_REVALIDATE_INTERVAL_MS = 5_000L",
    "        private const val PLAN_REVALIDATE_INTERVAL_MS = 15_000L",
    "PLAN_REVALIDATE_INTERVAL_MS = 15_000L",
)

vbook = "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt"
patch(
    vbook,
    '''        "last" -> fn { elements.lastOrNull()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "text" -> fn { elements.text() }''',
    '''        "last" -> fn { elements.lastOrNull()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "select" -> fn { args -> JsoupElementsObject(elements.select(Context.toString(args.getOrNull(0) ?: "")), ownerScope) }
        "remove" -> fn { elements.toList().forEach { it.remove() }; this }
        "text" -> fn { elements.text() }''',
    '"remove" -> fn { elements.toList().forEach { it.remove() }; this }',
)
patch(
    vbook,
    '''        "children" -> fn { JsoupElementsObject(Elements(element.children()), ownerScope) }
        else -> super.get(name, start)''',
    '''        "children" -> fn { JsoupElementsObject(Elements(element.children()), ownerScope) }
        "remove" -> fn { element.remove(); this }
        else -> super.get(name, start)''',
    '"remove" -> fn { element.remove(); this }',
)

diagnostics = "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt"
patch(
    diagnostics,
    '''    private fun inferOriginGenerationLocked(event: DiagnosticEvent): Long {
        val traceId = event.traceId.trim()
        val upper = event.name.uppercase(Locale.ROOT)
        val state = DiagnosticOperationContract.state(event)
        val explicitId = DiagnosticOperationContract.id(event)
        val operationId = explicitId ?: legacyOperationKey(event, upper)
        val isStart = state == DiagnosticOperationState.STARTED || state == null && isLegacyStart(upper)

        if (isStart) {
            if (operationId.isNotBlank()) rememberOrigin(operationOrigins, operationId, screenGeneration)
            if (traceId.isNotBlank()) rememberOrigin(traceOrigins, traceId, screenGeneration, overwrite = true)
            return screenGeneration
        }

        return operationOrigins[operationId]
            ?: traceOrigins[traceId]
            ?: screenGeneration
    }

    private fun updateOriginBindingsLocked(event: DiagnosticEvent, origin: Long) {
        val traceId = event.traceId.trim()
        val upper = event.name.uppercase(Locale.ROOT)
        val state = DiagnosticOperationContract.state(event)
        val operationId = DiagnosticOperationContract.id(event) ?: legacyOperationKey(event, upper)

        if (traceId.isNotBlank()) rememberOrigin(traceOrigins, traceId, origin)
        if (operationId.isNotBlank() && state !in TERMINAL_STATES && !(state == null && isLegacyTerminal(upper))) {
            rememberOrigin(operationOrigins, operationId, origin)
        }

        if (state in TERMINAL_STATES || state == null && isLegacyTerminal(upper)) {
            operationOrigins.remove(operationId)
        }
    }''',
    '''    private fun inferOriginGenerationLocked(event: DiagnosticEvent): Long {
        val traceId = event.traceId.trim()
        val upper = event.name.uppercase(Locale.ROOT)
        val state = DiagnosticOperationContract.state(event)
        val explicitId = DiagnosticOperationContract.id(event)
        val legacyStart = state == null && isLegacyStart(upper)
        val legacyTerminal = state == null && isLegacyTerminal(upper)
        val operationId = explicitId ?: if (legacyStart || legacyTerminal) legacyOperationKey(event, upper) else ""
        val isStart = state == DiagnosticOperationState.STARTED || legacyStart

        if (isStart) {
            if (operationId.isNotBlank()) rememberOrigin(operationOrigins, operationId, screenGeneration)
            if (traceId.isNotBlank()) rememberOrigin(traceOrigins, traceId, screenGeneration, overwrite = true)
            return screenGeneration
        }

        return operationOrigins[operationId]
            ?: traceOrigins[traceId]
            ?: screenGeneration
    }

    private fun updateOriginBindingsLocked(event: DiagnosticEvent, origin: Long) {
        val traceId = event.traceId.trim()
        val upper = event.name.uppercase(Locale.ROOT)
        val state = DiagnosticOperationContract.state(event)
        val explicitId = DiagnosticOperationContract.id(event)
        val legacyStart = state == null && isLegacyStart(upper)
        val legacyTerminal = state == null && isLegacyTerminal(upper)
        val operationId = explicitId ?: if (legacyStart || legacyTerminal) legacyOperationKey(event, upper) else ""

        if (traceId.isNotBlank()) rememberOrigin(traceOrigins, traceId, origin)
        if (operationId.isNotBlank() && state !in TERMINAL_STATES && !legacyTerminal) {
            rememberOrigin(operationOrigins, operationId, origin)
        }

        if (operationId.isNotBlank() && (state in TERMINAL_STATES || legacyTerminal)) {
            operationOrigins.remove(operationId)
        }
    }''',
    "val legacyTerminal = state == null && isLegacyTerminal(upper)",
)

print("All runtime/Freesound/diagnostics patches applied or already present.")
