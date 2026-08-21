package vn.nghetruyen.app.freesound

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ln
import kotlin.math.max
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.SceneMusicCue
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.audio.AmbienceScene
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.SoundEffectCue
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

private enum class FreesoundAutoResolutionSource { CACHE, FREESOUND, UNRESOLVED }

internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
    val queryUsed: String = "",
    val requestCount: Int = 1,
)

internal object FreesoundParallelSearchPolicy {
    const val MAX_PARALLEL_SEARCHES = 4

    suspend fun <T, R> mapOrdered(
        values: List<T>,
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_SEARCHES)
        values.map { value ->
            async { semaphore.withPermit { transform(value) } }
        }.awaitAll()
    }
}

internal object FreesoundParallelImportPolicy {
    const val MAX_PARALLEL_IMPORTS = 4

    suspend fun <T, R> mapOrdered(
        values: List<T>,
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_IMPORTS)
        values.map { value ->
            async { semaphore.withPermit { transform(value) } }
        }.awaitAll()
    }
}

private data class FreesoundAutoPreparedNeed(
    val index: Int,
    val need: FreesoundAutoSearchNeed,
    val cachedTrack: SceneMusicTrackEntity? = null,
    val effectiveQuery: String = "",
    val strategy: String = "CACHE",
    val search: FreesoundAutoSearchOutcome? = null,
    val searchElapsedMs: Long = 0L,
)

private data class FreesoundAutoImportOutcome(
    val index: Int,
    val result: Result<FreesoundImportResult>,
    val elapsedMs: Long,
)

data class FreesoundAutoResolvedNeed(
    val need: FreesoundAutoSearchNeed,
    val trackId: String?,
    val source: String,
)

data class FreesoundAutoResolveResult(
    val resolved: List<FreesoundAutoResolvedNeed>,
    val warnings: List<String>,
    val importedTrackIds: Set<String>,
    val retryableFailure: Boolean = false,
    val diagnostics: List<String> = emptyList(),
) {
    val resolvedCount: Int get() = resolved.count { !it.trackId.isNullOrBlank() }
    val unresolvedCount: Int get() = resolved.size - resolvedCount
    val unresolvedRequiredCount: Int get() = resolved.count {
        it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED
    }
    val shouldRetryIncomplete: Boolean get() =
        retryableFailure || (resolved.isNotEmpty() && (resolvedCount == 0 || unresolvedRequiredCount > 0))
}

/** Query -> managed Freesound track cache. It stores only app-internal ids. */
class FreesoundAutoQueryCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(kind: AudioAssetKind, query: String): String? =
        preferences.getString(key(kind, query), null)?.trim()?.takeIf(String::isNotBlank)

    fun put(kind: AudioAssetKind, query: String, trackId: String) {
        val clean = trackId.trim()
        if (clean.isBlank()) return
        preferences.edit().putString(key(kind, query), clean).apply()
    }

    fun remove(kind: AudioAssetKind, query: String) {
        preferences.edit().remove(key(kind, query)).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(kind: AudioAssetKind, query: String): String {
        val normalized = FreesoundAutoRequirementAggregator.normalizeQuery(query)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${kind.name}\u0000$normalized".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "q_$digest"
    }

    companion object {
        private const val PREFERENCES = "freesound_auto_query_cache_v1"
    }
}

/**
 * Mode-3 resolver. Search order is deliberately strict: previously resolved managed Freesound file
 * -> Freesound network search/import -> silence. A normal local-library asset is never substituted,
 * because that would silently mix Mode 2 into Mode 3.
 */
class FreesoundAutoAudioResolver(
    context: Context,
    private val repository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val client: FreesoundClient,
    private val existingTracksProvider: suspend () -> List<SceneMusicTrackEntity>,
) {
    private val appContext = context.applicationContext
    private val queryCache = FreesoundAutoQueryCache(appContext)
    private val importer = FreesoundImporter(
        context = appContext,
        repository = repository,
        existingTracksProvider = existingTracksProvider,
    )

    private fun liveDiagnostic(
        traceId: String,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val operationState = when (name) {
            "FREESOUND_RESOLVE_START" -> DiagnosticOperationState.STARTED
            "FREESOUND_RESOLVE_DONE", "FREESOUND_RESOLVE_EMPTY" -> DiagnosticOperationState.COMPLETED
            "FREESOUND_RETRY_EXHAUSTED" -> DiagnosticOperationState.FAILED
            else -> DiagnosticOperationState.STAGE
        }
        val operationAttributes = DiagnosticOperationContract.attributes(
            id = traceId,
            kind = "FREESOUND_RESOLVE",
            flow = "runtime",
            state = operationState,
            stage = name,
        )
        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(
            name = name,
            category = DiagnosticCategory.RUNTIME,
            severity = severity,
            sourceId = "freesound",
            traceId = traceId,
            attributes = operationAttributes + attributes,
        )
    }

    suspend fun usableManagedTrackIds(kinds: Set<AudioAssetKind>): Set<String> {
        if (kinds.isEmpty()) return emptySet()
        return runCatching { existingTracksProvider() }.getOrDefault(emptyList())
            .asSequence()
            .filter { track ->
                track.enabled &&
                    AudioAssetClassifier.classify(track) in kinds &&
                    FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&
                    FreesoundImporter.managedFileExists(appContext, track.uri)
            }
            .map(SceneMusicTrackEntity::id)
            .toSet()
    }

    suspend fun cachedManagedTrackId(kind: AudioAssetKind, query: String): String? {
        val cachedId = queryCache.get(kind, query) ?: return null
        val track = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
            .firstOrNull { it.id == cachedId }
            ?.takeIf {
                it.enabled &&
                    AudioAssetClassifier.classify(it) == kind &&
                    FreesoundImporter.soundIdFromManagedUri(it.uri) != null &&
                    FreesoundImporter.managedFileExists(appContext, it.uri)
            }
        if (track == null) queryCache.remove(kind, query)
        return track?.id
    }

    fun clearResolutionCaches() {
        // Rebuilding a chapter must invalidate transient HTTP pages, but NOT the durable
        // query -> downloaded-track index. Keeping that index is what lets the next voice-cast
        // reuse a verified file on disk without spending another Freesound request or data.
        client.clearSearchCache()
    }

    fun clearPersistentAssetQueryCache() {
        queryCache.clear()
    }

    fun clearNetworkSearchCache() {
        client.clearSearchCache()
    }

    suspend fun resolve(
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
        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS}"
        liveDiagnostic(
            traceId,
            "FREESOUND_RESOLVE_START",
            attributes = baseAttributes + mapOf(
                "requirements" to requirements.size.toString(),
                "aggregatedNeeds" to needs.size.toString(),
                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
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
                    "queryUsed" to search.queryUsed.take(180),
                    "searchRequests" to search.requestCount.toString(),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),
                ),
            )
            seed.copy(search = search, searchElapsedMs = elapsedMs)
        }
        val networkSearchWallMs = if (networkSeeds.isEmpty()) 0L
        else (System.nanoTime() - networkStartedNanos) / 1_000_000L
        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)

        // Resolve known soundIds from the local managed library before any download. Invalidly
        // normalized files are intentionally NOT counted as reusable: importer will resume their
        // normalization without downloading the bytes again.
        val preexistingSoundTrackByIndex = searched.mapNotNull { seed ->
            val remote = seed.search?.sound ?: return@mapNotNull null
            val track = knownTracks.firstOrNull { candidate ->
                candidate.enabled &&
                    AudioAssetClassifier.classify(candidate) == seed.need.kind &&
                    FreesoundImporter.soundIdFromManagedUri(candidate.uri) == remote.id &&
                    FreesoundImporter.managedFileExists(appContext, candidate.uri)
            } ?: return@mapNotNull null
            seed.index to track
        }.toMap()
        val localReusableByIndex = preexistingSoundTrackByIndex.filterValues(FreesoundImporter::hasValidNormalization)

        // Download + normalization is one bounded unit of work. At most four such units are active.
        // FreesoundImporter additionally serializes identical soundIds, so two different queries that
        // select the same remote sound cannot download or normalize it twice.
        val importSeeds = searched.filter { seed ->
            seed.search?.sound != null && localReusableByIndex[seed.index] == null
        }
        val parallelImportStartedNanos = System.nanoTime()
        val parallelImports = FreesoundParallelImportPolicy.mapOrdered(importSeeds) { seed ->
            val remote = requireNotNull(seed.search?.sound)
            liveDiagnostic(
                traceId,
                "FREESOUND_IMPORT_START",
                attributes = baseAttributes + mapOf(
                    "index" to (seed.index + 1).toString(),
                    "kind" to seed.need.kind.name,
                    "soundId" to remote.id.toString(),
                    "durationSec" to "%.2f".format(java.util.Locale.US, remote.durationSeconds),
                    "previewAvailable" to (remote.preferredPreviewUrl != null).toString(),
                    "query" to seed.need.query.take(180),
                    "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
                ),
            )
            val importStartedNanos = System.nanoTime()
            val result = importer.importPreview(
                sound = remote,
                kind = seed.need.kind,
                normalizationTargetLufs = normalizationTarget(seed.need.kind),
            )
            FreesoundAutoImportOutcome(
                index = seed.index,
                result = result,
                elapsedMs = (System.nanoTime() - importStartedNanos) / 1_000_000L,
            )
        }
        val parallelImportWallMs = if (importSeeds.isEmpty()) 0L
        else (System.nanoTime() - parallelImportStartedNanos) / 1_000_000L
        val parallelImportsByIndex = parallelImports.associateBy(FreesoundAutoImportOutcome::index)
        importAttempts = importSeeds.size
        importElapsedTotalMs = parallelImports.sumOf(FreesoundAutoImportOutcome::elapsedMs)
        if (parallelImports.isNotEmpty()) {
            knownTracks = runCatching { existingTracksProvider() }.getOrDefault(knownTracks)
        }

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
            diagnostics += "CLIENT_SEARCH_DONE index=${seed.index + 1} kind=${need.kind.name} elapsedMs=${resolvedSearch.searchElapsedMs} resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} queryUsed=${search.queryUsed.take(140)} searchRequests=${search.requestCount} strategy=${resolvedSearch.strategy}"
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
                val existingSoundTrack = localReusableByIndex[seed.index]
                if (existingSoundTrack != null) {
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
                    val importOutcome = requireNotNull(parallelImportsByIndex[seed.index])
                    val import = importOutcome.result
                    val importElapsedMs = importOutcome.elapsedMs
                    if (import.isSuccess) {
                        val result = import.getOrThrow()
                        resolvedTrack = knownTracks.firstOrNull { it.id == result.trackId && it.enabled }
                        if (preexistingSoundTrackByIndex[seed.index] == null) imported += result.trackId else normalizationResumes += 1
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
        val clientSearches = searched.sumOf { it.search?.requestCount ?: 0 }
        diagnostics += "RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired queryCacheHits=$queryCacheHits clientSearches=$clientSearches parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS} cacheLookupMs=$cacheLookupMs networkSearchWallMs=$networkSearchWallMs importWallMs=$parallelImportWallMs importAttempts=$importAttempts localSoundIdReuses=$localSoundIdReuses normalizationResumes=$normalizationResumes imported=${imported.size} importElapsedTotalMs=$importElapsedTotalMs retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs"
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
                "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
                "cacheLookupMs" to cacheLookupMs.toString(),
                "networkSearchWallMs" to networkSearchWallMs.toString(),
                "importWallMs" to parallelImportWallMs.toString(),
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

    private suspend fun searchBest(
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
    }

    private suspend fun normalizationTarget(kind: AudioAssetKind): Float = when (kind) {
        AudioAssetKind.MUSIC -> settingsRepository.snapshot().sceneMusicTargetLufs
        AudioAssetKind.AMBIENCE -> AudioDirectionPreferences.shared(appContext).snapshot().ambienceNormalizationTargetLufs
        AudioAssetKind.SFX -> AudioDirectionPreferences.shared(appContext).snapshot().soundEffectsNormalizationTargetLufs
    }

    companion object {
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
            return when {
                retryAttempt == 2 && tokens.size == 1 -> tokens.first()
                retryAttempt == 2 && tokens.size == 2 -> tokens.last()
                retryAttempt == 2 -> tokens.take(2).joinToString(" ")
                else -> tokens.first()
            }.ifBlank { original }
        }

        internal fun scoreCandidate(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            rankIndex: Int,
        ): Double {
            val queryNorm = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(queryNorm)
            if (queryTokens.isEmpty()) return 0.0
            val titleNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.name)
            val descriptionNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.description)
            val tagNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.tags.joinToString(" "))
            val titleTokens = FreesoundAutoRequirementAggregator.queryTokens(titleNorm)
            val descriptionTokens = FreesoundAutoRequirementAggregator.queryTokens(descriptionNorm)
            val tagTokens = FreesoundAutoRequirementAggregator.queryTokens(tagNorm)
            fun coverage(tokens: Set<String>): Double =
                queryTokens.count(tokens::contains).toDouble() / queryTokens.size.toDouble()
            val titleCoverage = coverage(titleTokens)
            val descriptionCoverage = coverage(descriptionTokens)
            val tagCoverage = coverage(tagTokens)
            val lexicalCoverage = max(titleCoverage, max(tagCoverage * 0.96, descriptionCoverage * 0.78))
            if (lexicalCoverage <= 0.0) return 0.0

            val phraseBonus = when {
                titleNorm.contains(queryNorm) -> 0.20
                tagNorm.contains(queryNorm) -> 0.14
                descriptionNorm.contains(queryNorm) -> 0.08
                else -> 0.0
            }
            val expectedCategory = when (need.kind) {
                AudioAssetKind.MUSIC -> "Music"
                AudioAssetKind.AMBIENCE -> "Soundscapes"
                AudioAssetKind.SFX -> "Sound effects"
            }
            val categoryBonus = when {
                sound.category.isBlank() -> 0.0
                sound.category.equals(expectedCategory, ignoreCase = true) -> 0.18
                else -> -0.24
            }
            val durationBonus = when (need.kind) {
                AudioAssetKind.MUSIC -> when (sound.durationSeconds) {
                    in 45.0..360.0 -> 0.10
                    in 30.0..<45.0 -> 0.05
                    in 360.0..900.0 -> 0.03
                    else -> -0.10
                }
                AudioAssetKind.AMBIENCE -> when (sound.durationSeconds) {
                    in 30.0..180.0 -> 0.12
                    in 15.0..300.0 -> 0.07
                    in 10.0..<15.0 -> 0.02
                    else -> -0.10
                }
                AudioAssetKind.SFX -> {
                    val actionLoop = need.usages.any { it.loopUntilStop }
                    if (actionLoop) {
                        when (sound.durationSeconds) {
                            in 2.0..15.0 -> 0.10
                            in 0.1..<2.0 -> 0.04
                            else -> -0.10
                        }
                    } else {
                        when (sound.durationSeconds) {
                            in 0.1..6.0 -> 0.12
                            in 6.0..10.0 -> 0.06
                            in 10.0..15.0 -> 0.01
                            else -> -0.10
                        }
                    }
                }
            }
            val ratingConfidence = (sound.numRatings / 8.0).coerceIn(0.0, 1.0)
            val ratingBonus = (sound.avgRating / 5.0).coerceIn(0.0, 1.0) * 0.07 * ratingConfidence
            val downloadsBonus = if (sound.numDownloads > 0) {
                (ln(1.0 + sound.numDownloads.toDouble()) / ln(10_001.0)).coerceIn(0.0, 1.0) * 0.05
            } else 0.0
            val apiScoreBonus = if (sound.searchScore > 0.0) {
                (sound.searchScore / (1.0 + sound.searchScore)).coerceIn(0.0, 1.0) * 0.04
            } else 0.0
            val rankBonus = 0.08 / (rankIndex.coerceAtLeast(0) + 1.0)
            return (
                lexicalCoverage * 0.62 + phraseBonus + categoryBonus + durationBonus +
                    ratingBonus + downloadsBonus + apiScoreBonus + rankBonus
                ).coerceIn(0.0, 1.0)
        }
    }
}

/** Converts resolved search needs into the same local runtime cue types used by the existing player. */
object FreesoundAutoPlanBuilder {
    data class RequiredCoverage(
        val missingMusicUsages: Int = 0,
        val missingAmbienceUsages: Int = 0,
        val missingSfxUsages: Int = 0,
    ) {
        val complete: Boolean
            get() = missingMusicUsages == 0 && missingAmbienceUsages == 0 && missingSfxUsages == 0
    }

    private data class MusicRun(val start: Int, val end: Int, val trackId: String)
    private data class PrioritizedAmbience(
        val scene: AmbienceScene,
        val importance: FreesoundRequirementImportance,
    )
    private data class PrioritizedSfx(
        val cue: SoundEffectCue,
        val importance: FreesoundRequirementImportance,
    )

    fun musicCues(
        resolved: List<FreesoundAutoResolvedNeed>,
        validUnitIds: List<String>,
        validTrackIds: Set<String>? = null,
    ): List<SceneMusicCue> {
        if (validUnitIds.isEmpty()) return emptyList()
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val selectedByUnit = MutableList(validUnitIds.size) { XpkSceneMusicParity.SILENCE_TRACK_ID }
        val music = resolved.filter { it.need.kind == AudioAssetKind.MUSIC && !it.trackId.isNullOrBlank() }
            .sortedWith(compareBy<FreesoundAutoResolvedNeed> { it.need.importance != FreesoundRequirementImportance.REQUIRED })
        music.forEach { resolution ->
            val rawTrackId = resolution.trackId ?: return@forEach
            val trackId = if (validTrackIds == null || rawTrackId in validTrackIds) rawTrackId
            else XpkSceneMusicParity.SILENCE_TRACK_ID
            resolution.need.usages
                .sortedBy { usage -> usage.startUnitId?.let(order::get) ?: Int.MAX_VALUE }
                .forEach { usage ->
                    val start = usage.startUnitId?.let(order::get) ?: return@forEach
                    val end = usage.endUnitId?.let(order::get) ?: return@forEach
                    for (index in start..end) {
                        if (selectedByUnit[index] == XpkSceneMusicParity.SILENCE_TRACK_ID) {
                            selectedByUnit[index] = trackId
                        }
                    }
                }
        }
        stabilizeMusicAssignments(selectedByUnit)
        return musicAssignmentsToCues(selectedByUnit, validUnitIds)
    }

    fun salvageMusicCues(
        cues: List<SceneMusicCue>,
        validUnitIds: List<String>,
        validTrackIds: Set<String>,
    ): List<SceneMusicCue> {
        if (validUnitIds.isEmpty()) return emptyList()
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val selected = MutableList(validUnitIds.size) { XpkSceneMusicParity.SILENCE_TRACK_ID }
        cues.forEach { cue ->
            val start = order[cue.startUnitId] ?: return@forEach
            val end = order[cue.endUnitId] ?: return@forEach
            if (end < start) return@forEach
            val safeTrack = cue.trackId.takeIf { it in validTrackIds } ?: XpkSceneMusicParity.SILENCE_TRACK_ID
            for (index in start..end) selected[index] = safeTrack
        }
        stabilizeMusicAssignments(selected)
        return musicAssignmentsToCues(selected, validUnitIds)
    }

    private fun stabilizeMusicAssignments(rows: MutableList<String>) {
        if (rows.size < 3) return
        while (true) {
            val runs = musicRuns(rows)
            if (runs.size <= 2) return
            val short = runs.subList(1, runs.lastIndex).firstOrNull { it.end - it.start + 1 < 2 } ?: return
            val runIndex = runs.indexOf(short)
            val left = runs[runIndex - 1]
            val right = runs[runIndex + 1]
            val replacement = when {
                left.trackId == right.trackId -> left.trackId
                left.trackId != XpkSceneMusicParity.SILENCE_TRACK_ID -> left.trackId
                else -> right.trackId
            }
            for (index in short.start..short.end) rows[index] = replacement
        }
    }

    private fun musicRuns(rows: List<String>): List<MusicRun> {
        if (rows.isEmpty()) return emptyList()
        val out = mutableListOf<MusicRun>()
        var start = 0
        var track = rows.first()
        for (index in 1..rows.size) {
            if (index == rows.size || rows[index] != track) {
                out += MusicRun(start, index - 1, track)
                if (index < rows.size) {
                    start = index
                    track = rows[index]
                }
            }
        }
        return out
    }

    private fun musicAssignmentsToCues(rows: List<String>, validUnitIds: List<String>): List<SceneMusicCue> =
        musicRuns(rows).map { run ->
            SceneMusicCue(
                startParagraph = run.start,
                endParagraph = run.end,
                trackId = run.trackId,
                startUnitId = validUnitIds[run.start],
                endUnitId = validUnitIds[run.end],
            )
        }

    fun ambienceScenes(
        resolved: List<FreesoundAutoResolvedNeed>,
        validUnitIds: List<String>,
    ): List<AmbienceScene> {
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val candidates = buildList {
            resolved.filter { it.need.kind == AudioAssetKind.AMBIENCE && !it.trackId.isNullOrBlank() }.forEach { resolution ->
                val trackId = resolution.trackId ?: return@forEach
                resolution.need.usages.forEach { usage ->
                    val start = usage.startUnitId ?: return@forEach
                    val end = usage.endUnitId ?: return@forEach
                    if (order[start] == null || order[end] == null) return@forEach
                    add(PrioritizedAmbience(AmbienceScene(start, end, trackId), usage.importance))
                }
            }
        }.sortedWith(
            compareBy<PrioritizedAmbience> { it.importance != FreesoundRequirementImportance.REQUIRED }
                .thenBy { order[it.scene.startUnitId] ?: Int.MAX_VALUE }
                .thenBy { order[it.scene.endUnitId] ?: Int.MAX_VALUE },
        )
        val accepted = mutableListOf<PrioritizedAmbience>()
        candidates.forEach { candidate ->
            val start = order.getValue(candidate.scene.startUnitId)
            val end = order.getValue(candidate.scene.endUnitId)
            val duplicate = accepted.any { existing ->
                existing.scene.ambienceId == candidate.scene.ambienceId &&
                    rangesOverlap(start, end, order.getValue(existing.scene.startUnitId), order.getValue(existing.scene.endUnitId))
            }
            if (duplicate) return@forEach
            val wouldOverflow = (start..end).any { unit ->
                accepted.count { row ->
                    val rowStart = order.getValue(row.scene.startUnitId)
                    val rowEnd = order.getValue(row.scene.endUnitId)
                    unit in rowStart..rowEnd
                } >= AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE
            }
            if (!wouldOverflow) accepted += candidate
        }
        return accepted.map(PrioritizedAmbience::scene)
            .sortedWith(compareBy<AmbienceScene> { order[it.startUnitId] ?: Int.MAX_VALUE }.thenBy { order[it.endUnitId] ?: Int.MAX_VALUE })
    }

    fun soundEffectCues(
        resolved: List<FreesoundAutoResolvedNeed>,
        validUnitIds: List<String>,
    ): List<SoundEffectCue> {
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val candidates = buildList {
            resolved.filter { it.need.kind == AudioAssetKind.SFX && !it.trackId.isNullOrBlank() }.forEach { resolution ->
                val trackId = resolution.trackId ?: return@forEach
                resolution.need.usages.forEach { usage ->
                    val unit = usage.unitId ?: return@forEach
                    if (order[unit] == null) return@forEach
                    add(
                        PrioritizedSfx(
                            SoundEffectCue(
                                unitId = unit,
                                effectId = trackId,
                                stopUnitId = usage.stopUnitId,
                                repeatCount = usage.repeatCount,
                                cadence = usage.cadence,
                                loopUntilStop = usage.loopUntilStop,
                            ),
                            usage.importance,
                        ),
                    )
                }
            }
        }.distinctBy { row ->
            listOf(
                row.cue.unitId, row.cue.effectId, row.cue.stopUnitId.orEmpty(), row.cue.repeatCount.toString(),
                row.cue.cadence.name, row.cue.loopUntilStop.toString(),
            ).joinToString("|")
        }.sortedWith(
            compareBy<PrioritizedSfx> { it.importance != FreesoundRequirementImportance.REQUIRED }
                .thenBy { order[it.cue.unitId] ?: Int.MAX_VALUE },
        )
        val accepted = mutableListOf<PrioritizedSfx>()
        candidates.forEach { candidate ->
            val start = order.getValue(candidate.cue.unitId)
            val stopExclusive = candidate.cue.stopUnitId?.let(order::get) ?: (start + 1)
            val wouldOverflow = (start until stopExclusive.coerceAtMost(validUnitIds.size)).any { unit ->
                accepted.count { row ->
                    val rowStart = order.getValue(row.cue.unitId)
                    val rowStop = row.cue.stopUnitId?.let(order::get) ?: (rowStart + 1)
                    unit in rowStart until rowStop.coerceAtMost(validUnitIds.size)
                } >= AudioDirectionLimits.MAX_CONCURRENT_SFX
            }
            if (!wouldOverflow) accepted += candidate
        }
        return accepted.map(PrioritizedSfx::cue)
            .sortedWith(compareBy<SoundEffectCue> { order[it.unitId] ?: Int.MAX_VALUE }.thenBy { it.effectId })
    }

    fun requiredCoverage(
        resolved: List<FreesoundAutoResolvedNeed>,
        validUnitIds: List<String>,
        musicCues: List<SceneMusicCue> = emptyList(),
        ambienceScenes: List<AmbienceScene> = emptyList(),
        soundEffectCues: List<SoundEffectCue> = emptyList(),
    ): RequiredCoverage {
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        var missingMusic = 0
        var missingAmbience = 0
        var missingSfx = 0
        resolved.forEach { resolution ->
            val trackId = resolution.trackId ?: return@forEach
            resolution.need.usages.filter { it.importance == FreesoundRequirementImportance.REQUIRED }.forEach { usage ->
                when (usage.kind) {
                    AudioAssetKind.MUSIC -> {
                        val start = usage.startUnitId?.let(order::get)
                        val end = usage.endUnitId?.let(order::get)
                        val covered = start != null && end != null && (start..end).all { unit ->
                            musicCues.any { cue ->
                                cue.trackId == trackId &&
                                    unit >= (order[cue.startUnitId] ?: Int.MAX_VALUE) &&
                                    unit <= (order[cue.endUnitId] ?: Int.MIN_VALUE)
                            }
                        }
                        if (!covered) missingMusic += 1
                    }
                    AudioAssetKind.AMBIENCE -> {
                        val start = usage.startUnitId?.let(order::get)
                        val end = usage.endUnitId?.let(order::get)
                        val covered = start != null && end != null && (start..end).all { unit ->
                            ambienceScenes.any { scene ->
                                scene.ambienceId == trackId &&
                                    unit >= (order[scene.startUnitId] ?: Int.MAX_VALUE) &&
                                    unit <= (order[scene.endUnitId] ?: Int.MIN_VALUE)
                            }
                        }
                        if (!covered) missingAmbience += 1
                    }
                    AudioAssetKind.SFX -> {
                        val covered = soundEffectCues.any { cue ->
                            cue.unitId == usage.unitId &&
                                cue.effectId == trackId &&
                                cue.stopUnitId == usage.stopUnitId &&
                                cue.repeatCount == usage.repeatCount &&
                                cue.cadence == usage.cadence &&
                                cue.loopUntilStop == usage.loopUntilStop
                        }
                        if (!covered) missingSfx += 1
                    }
                }
            }
        }
        return RequiredCoverage(missingMusic, missingAmbience, missingSfx)
    }

    private fun rangesOverlap(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Boolean =
        firstStart <= secondEnd && secondStart <= firstEnd
}

private fun AudioAssetKind.toFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}