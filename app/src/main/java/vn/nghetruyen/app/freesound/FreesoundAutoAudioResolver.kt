package vn.nghetruyen.app.freesound

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
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

private enum class FreesoundAutoResolutionSource { CACHE, LIBRARY, FREESOUND, UNRESOLVED }

internal data class FreesoundAutoSearchOutcome(
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
    /** Combined evidence from the original need and the current retry query. */
    val selectedLexicalCoverage: Double = 0.0,
    val excludedSoundIds: Set<Int> = emptySet(),
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
    val localMatch: Mode3LibraryAssetMatcher.Match? = null,
    val resolvedSource: FreesoundAutoResolutionSource? = null,
    val effectiveQuery: String = "",
    val strategy: String = "CACHE",
    val search: FreesoundAutoSearchOutcome? = null,
    val searchElapsedMs: Long = 0L,
)

private data class FreesoundSourceDecision(
    val useLibrary: Boolean,
    val localFit: Double,
    val remoteFit: Double,
    val remoteCoreCoverage: Double,
    val remoteEventCoverage: Double,
    val remoteQualified: Boolean,
    val sameAsset: Boolean,
    val reason: String,
)

private data class FreesoundAutoImportOutcome(
    val index: Int,
    val result: Result<FreesoundImportResult>,
    val elapsedMs: Long,
)

private data class CompletedResolutionCycle(
    val resolvedTrackIdsByNeed: Map<String, String>,
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
    val resolvedTrackIds: Set<String> get() = resolved.mapNotNull { it.trackId?.takeIf(String::isNotBlank) }.toSet()
    val downloadedTrackCount: Int get() = importedTrackIds.size
    val reusedTrackCount: Int get() = (resolvedTrackIds - importedTrackIds).size
    val unresolvedCount: Int get() = resolved.size - resolvedCount
    val unresolvedRequiredCount: Int get() = resolved.count {
        it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED
    }
    val shouldRetryIncomplete: Boolean get() =
        retryableFailure || (resolved.isNotEmpty() && (resolvedCount == 0 || unresolvedRequiredCount > 0))
}

/** Query -> reusable library track cache. It stores only app-internal ids, never source-specific paths. */
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
        private const val PREFERENCES = "freesound_auto_query_cache_v2"
    }
}

/**
 * Mode-3 resolver. Existing-library and Freesound candidates are evaluated without source
 * priority: provenance contributes zero points, and the candidate with stronger fit evidence wins.
 * A remote preview is downloaded only after source-neutral arbitration says it is needed.
 * Existing files include both user-added assets and earlier Freesound imports.
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

    // Per-resolution-cycle retry state. Attempt 1 starts a new cycle. Later attempts keep both the
    // remote blacklist and successful resolutions so a retry searches only unresolved needs and can
    // never replace a winner that already succeeded in this cycle.
    private val failedSoundIdsByNeed = linkedMapOf<String, MutableSet<Int>>()
    private val resolvedTrackIdsByNeed = linkedMapOf<String, String>()
    private var activeResolutionCycleKey: String? = null
    private var activeResolutionCycleComplete: Boolean = false
    private val activeResolutionImportedTrackIds = linkedSetOf<String>()
    private val completedResolutionCycles = object : LinkedHashMap<String, CompletedResolutionCycle>(
        MAX_COMPLETED_CYCLE_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompletedResolutionCycle>?): Boolean =
            size > MAX_COMPLETED_CYCLE_CACHE_ENTRIES
    }

    private fun failedSoundKey(need: FreesoundAutoSearchNeed): String =
        "${need.kind.name}:${FreesoundAutoRequirementAggregator.normalizeQuery(need.query)}"

    private fun failedSoundIds(need: FreesoundAutoSearchNeed): Set<Int> =
        failedSoundIdsByNeed[failedSoundKey(need)]?.toSet().orEmpty()

    private fun rememberFailedSound(need: FreesoundAutoSearchNeed, soundId: Int) {
        if (soundId > 0) failedSoundIdsByNeed.getOrPut(failedSoundKey(need)) { linkedSetOf() }.add(soundId)
    }

    private fun rememberResolvedTrack(need: FreesoundAutoSearchNeed, trackId: String) {
        trackId.trim().takeIf(String::isNotBlank)?.let { resolvedTrackIdsByNeed[failedSoundKey(need)] = it }
    }

    private fun forgetResolvedTrack(need: FreesoundAutoSearchNeed) {
        resolvedTrackIdsByNeed.remove(failedSoundKey(need))
    }

    private fun resolutionCycleKey(needs: List<FreesoundAutoSearchNeed>): String =
        needs.sortedWith(
            compareBy<FreesoundAutoSearchNeed> { it.kind.name }
                .thenBy { FreesoundAutoRequirementAggregator.normalizeQuery(it.query) }
                .thenBy { it.importance.name },
        ).joinToString("\u001e") { need ->
            // Asset identity depends on semantic need, not on volatile unit/timeline ids.
            // Keeping unit ids in this fingerprint made the same completed 7/7 asset set resolve again
            // when prefetch and active playback represented the same scene with different boundaries.
            val contexts = need.usages.asSequence()
                .map { it.localContext.trim().replace(Regex("\\s+"), " ").lowercase() }
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
                .toList()
            buildString {
                append(need.kind.name)
                append('\u001f')
                append(FreesoundAutoRequirementAggregator.normalizeQuery(need.query))
                append('\u001f')
                append(need.importance.name)
                contexts.forEach { context -> append('\u001d').append(context) }
            }
        }

    private fun clearRuntimeResolutionState() {
        failedSoundIdsByNeed.clear()
        resolvedTrackIdsByNeed.clear()
        activeResolutionCycleKey = null
        activeResolutionCycleComplete = false
        activeResolutionImportedTrackIds.clear()
    }

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

    private fun isManagedFreesoundTrack(track: SceneMusicTrackEntity): Boolean =
        track.tagsCsv.contains("freesound_id:", ignoreCase = true) ||
            track.uri.contains("/audio/freesound/", ignoreCase = true) ||
            track.uri.contains("\\audio\\freesound\\", ignoreCase = true)

    private fun isUsableLibraryTrack(track: SceneMusicTrackEntity, kind: AudioAssetKind): Boolean {
        if (!track.enabled || track.id.isBlank() || track.uri.isBlank()) return false
        if (AudioAssetClassifier.classify(track) != kind) return false
        if (!isManagedFreesoundTrack(track)) return libraryUriExists(track.uri)
        return FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&
            FreesoundImporter.managedFileExists(appContext, track.uri)
    }

    private fun libraryUriExists(uriValue: String): Boolean {
        val clean = uriValue.trim()
        if (clean.isBlank()) return false
        val parsed = runCatching { Uri.parse(clean) }.getOrNull()
        return when (parsed?.scheme?.lowercase()) {
            "content" -> runCatching {
                appContext.contentResolver.openFileDescriptor(parsed, "r")?.use { descriptor ->
                    descriptor.statSize != 0L
                } ?: false
            }.getOrDefault(false)
            "file" -> parsed.path?.let(::File)?.let { it.isFile && it.length() > 0L } == true
            null, "" -> File(clean).let { it.isFile && it.length() > 0L }
            else -> runCatching {
                appContext.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
                    descriptor.length != 0L
                } ?: false
            }.getOrDefault(false)
        }
    }

    private fun cachedTrackForNeed(
        need: FreesoundAutoSearchNeed,
        cachedId: String,
        tracks: List<SceneMusicTrackEntity>,
    ): SceneMusicTrackEntity? {
        val track = tracks.firstOrNull { it.id == cachedId } ?: return null
        if (!isUsableLibraryTrack(track, need.kind)) return null
        val hasLocalContext = need.usages.any { it.localContext.isNotBlank() }
        if (!hasLocalContext) {
            return Mode3LibraryAssetMatcher.strongMatch(need, track)?.track
        }

        val contextualBest = Mode3LibraryAssetMatcher.bestMatch(
            need = need,
            tracks = tracks.filter { isUsableLibraryTrack(it, need.kind) },
        )
        if (contextualBest != null) {
            return track.takeIf { it.id == contextualBest.track.id }
        }

        return null
    }

    suspend fun usableLibraryTrackIds(kinds: Set<AudioAssetKind>): Set<String> {
        if (kinds.isEmpty()) return emptySet()
        return runCatching { existingTracksProvider() }.getOrDefault(emptyList())
            .asSequence()
            .filter { track -> kinds.any { kind -> isUsableLibraryTrack(track, kind) } }
            .map(SceneMusicTrackEntity::id)
            .toSet()
    }

    /** Kept for source compatibility; Mode 3 now treats the whole enabled library as reusable. */
    suspend fun usableManagedTrackIds(kinds: Set<AudioAssetKind>): Set<String> = usableLibraryTrackIds(kinds)

    suspend fun cachedLibraryTrackId(kind: AudioAssetKind, query: String): String? {
        val cachedId = queryCache.get(kind, query) ?: return null
        val need = FreesoundAutoSearchNeed(
            kind = kind,
            query = query,
            importance = FreesoundRequirementImportance.OPTIONAL,
            usages = emptyList(),
        )
        val track = cachedTrackForNeed(
            need = need,
            cachedId = cachedId,
            tracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList()),
        )
        if (track == null) queryCache.remove(kind, query)
        return track?.id
    }

    /** Kept for source compatibility with existing coordinator/tests. */
    suspend fun cachedManagedTrackId(kind: AudioAssetKind, query: String): String? =
        cachedLibraryTrackId(kind, query)

    fun clearResolutionCaches() {
        clearRuntimeResolutionState()
        synchronized(completedResolutionCycles) { completedResolutionCycles.clear() }
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
        val cycleKey = resolutionCycleKey(needs)
        if (retryAttempt <= 1 && activeResolutionCycleKey != cycleKey) {
            val historical = synchronized(completedResolutionCycles) { completedResolutionCycles[cycleKey] }
            if (historical != null) {
                clearRuntimeResolutionState()
                activeResolutionCycleKey = cycleKey
                resolvedTrackIdsByNeed.putAll(historical.resolvedTrackIdsByNeed)
                activeResolutionCycleComplete = true
            }
        }
        val completedCycleReuse = retryAttempt <= 1 &&
            activeResolutionCycleComplete &&
            activeResolutionCycleKey == cycleKey
        if (activeResolutionCycleKey != cycleKey || (retryAttempt <= 1 && !completedCycleReuse)) {
            clearRuntimeResolutionState()
            activeResolutionCycleKey = cycleKey
        }
        val baseAttributes = mapOf(
            "retryAttempt" to retryAttempt.coerceAtLeast(1).toString(),
            "retryMax" to retryMax.coerceAtLeast(1).toString(),
        )
        val diagnostics = mutableListOf<String>()
        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} completedCycleReuse=$completedCycleReuse parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS}"
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
        var localLibraryMatches = 0
        var retryLockedReuses = 0
        var decisiveLocalSkips = 0
        var importAttempts = 0
        var localSoundIdReuses = 0
        var normalizationResumes = 0
        var importElapsedTotalMs = 0L

        val cacheStartedNanos = System.nanoTime()
        var knownTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
        val usableTracksByKind = AudioAssetKind.entries.associateWith { kind ->
            knownTracks.filter { isUsableLibraryTrack(it, kind) }
        }
        if (completedCycleReuse) {
            val completedResolutions = needs.mapNotNull { need ->
                val lockedId = resolvedTrackIdsByNeed[failedSoundKey(need)] ?: return@mapNotNull null
                val lockedTrack = usableTracksByKind[need.kind].orEmpty().firstOrNull { it.id == lockedId }
                    ?: return@mapNotNull null
                FreesoundAutoResolvedNeed(
                    need = need,
                    trackId = lockedTrack.id,
                    source = if (isManagedFreesoundTrack(lockedTrack)) {
                        FreesoundAutoResolutionSource.FREESOUND.name
                    } else {
                        FreesoundAutoResolutionSource.LIBRARY.name
                    },
                )
            }
            if (completedResolutions.size == needs.size) {
                val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
                val importedForTransaction = activeResolutionImportedTrackIds.toSet()
                diagnostics += "COMPLETED_CYCLE_REUSE resolved=${completedResolutions.size} networkNeeds=0 imported=${importedForTransaction.size} elapsedMs=$elapsedMs"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_COMPLETED_CYCLE_REUSED",
                    attributes = baseAttributes + mapOf(
                        "resolved" to completedResolutions.size.toString(),
                        "networkNeeds" to "0",
                        "imported" to importedForTransaction.size.toString(),
                        "elapsedMs" to elapsedMs.toString(),
                    ),
                )
                liveDiagnostic(
                    traceId,
                    "FREESOUND_RESOLVE_DONE",
                    attributes = baseAttributes + mapOf(
                        "resolved" to completedResolutions.size.toString(),
                        "unresolved" to "0",
                        "networkNeeds" to "0",
                        "completedCycleReuse" to "true",
                        "imported" to importedForTransaction.size.toString(),
                        "importedThisCall" to "0",
                        "elapsedMs" to elapsedMs.toString(),
                    ),
                )
                return FreesoundAutoResolveResult(
                    resolved = completedResolutions,
                    warnings = emptyList(),
                    importedTrackIds = importedForTransaction,
                    retryableFailure = false,
                    diagnostics = diagnostics.distinct(),
                )
            }
            activeResolutionCycleComplete = false
        }
        val prepared = needs.mapIndexed { index, need ->
            val localHints = need.usages.asSequence()
                .map(FreesoundAutoRequirement::localContext)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            diagnostics += "NEED_START index=${index + 1}/${needs.size} kind=${need.kind.name} importance=${need.importance.name} usages=${need.usages.size} query=${need.query.take(160)} localHint=${localHints.joinToString(" || ").take(500)}"
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
                    "localHintPresent" to localHints.isNotEmpty().toString(),
                    "localHint" to localHints.joinToString(" || ").take(900),
                ),
            )

            if (retryAttempt > 1 || completedCycleReuse) {
                val lockedId = resolvedTrackIdsByNeed[failedSoundKey(need)]
                val lockedTrack = lockedId?.let { id ->
                    usableTracksByKind[need.kind].orEmpty().firstOrNull { it.id == id }
                }
                if (lockedTrack != null) {
                    retryLockedReuses += 1
                    diagnostics += "RETRY_LOCK_REUSE kind=${need.kind.name} trackId=${lockedTrack.id} query=${need.query.take(140)} networkSkipped=true"
                    liveDiagnostic(
                        traceId,
                        "FREESOUND_RETRY_LOCK_REUSED",
                        attributes = baseAttributes + mapOf(
                            "kind" to need.kind.name,
                            "trackId" to lockedTrack.id,
                            "assetSource" to if (isManagedFreesoundTrack(lockedTrack)) "FREESOUND" else "LOCAL",
                            "query" to need.query.take(180),
                            "networkSkipped" to "true",
                        ),
                    )
                    return@mapIndexed FreesoundAutoPreparedNeed(
                        index = index,
                        need = need,
                        cachedTrack = lockedTrack,
                        resolvedSource = if (isManagedFreesoundTrack(lockedTrack)) {
                            FreesoundAutoResolutionSource.FREESOUND
                        } else {
                            FreesoundAutoResolutionSource.LIBRARY
                        },
                        effectiveQuery = need.query,
                        strategy = if (completedCycleReuse) "COMPLETED_CYCLE_REUSE" else "LOCKED_SUCCESS",
                    )
                } else if (lockedId != null) {
                    forgetResolvedTrack(need)
                    diagnostics += "RETRY_LOCK_STALE kind=${need.kind.name} trackId=$lockedId query=${need.query.take(140)}"
                }
            }

            val cachedId = queryCache.get(need.kind, need.query)
            val cachedTrack = cachedId?.let { id ->
                usableTracksByKind[need.kind].orEmpty().firstOrNull { it.id == id }
            }
            if (cachedTrack != null) {
                queryCacheHits += 1
                diagnostics += "LIBRARY_QUERY_CACHE_AVAILABLE kind=${need.kind.name} trackId=${cachedTrack.id} source=${if (isManagedFreesoundTrack(cachedTrack)) "FREESOUND" else "LOCAL"} query=${need.query.take(140)}"
            } else if (cachedId != null) {
                queryCache.remove(need.kind, need.query)
                diagnostics += "LIBRARY_QUERY_CACHE_STALE kind=${need.kind.name} trackId=$cachedId query=${need.query.take(140)}"
            }

            val localEvaluation = Mode3LibraryAssetMatcher.evaluate(
                need = need,
                tracks = usableTracksByKind[need.kind].orEmpty(),
            )
            val localMatch = localEvaluation.accepted
            if (localMatch != null) localLibraryMatches += 1
            val decisiveLocal = Mode3LibraryAssetMatcher.isDecisive(localMatch)
            if (decisiveLocal) {
                decisiveLocalSkips += 1
                diagnostics += "LOCAL_DECISIVE_SKIP kind=${need.kind.name} trackId=${localMatch?.track?.id.orEmpty()} fit=${"%.3f".format(java.util.Locale.US, localMatch?.selectionScore ?: 0.0)} query=${need.query.take(140)} networkSkipped=true"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_LOCAL_DECISIVE_SKIP",
                    attributes = baseAttributes + mapOf(
                        "kind" to need.kind.name,
                        "trackId" to localMatch?.track?.id.orEmpty(),
                        "title" to localMatch?.track?.title.orEmpty().take(180),
                        "fit" to "%.3f".format(java.util.Locale.US, localMatch?.selectionScore ?: 0.0),
                        "contextScore" to "%.3f".format(java.util.Locale.US, localMatch?.contextScore ?: 0.0),
                        "queryCoverage" to "%.3f".format(java.util.Locale.US, localMatch?.coverage ?: 0.0),
                        "metadataQuality" to localMatch?.metadataQuality.orEmpty(),
                        "query" to need.query.take(180),
                        "networkSkipped" to "true",
                    ),
                )
            }

            val effectiveQuery = searchQueryForRetry(need.query, retryAttempt)
            val tokenCount = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
                .split(' ').count(String::isNotBlank)
            val strategy = when {
                decisiveLocal -> "LOCAL_DECISIVE"
                retryAttempt <= 1 -> "EXACT"
                retryAttempt == 2 && tokenCount <= 2 -> "RELAXED_1_TERM_ALTERNATE"
                retryAttempt == 2 -> "RELAXED_2_TERMS"
                else -> "RELAXED_1_TERM_ANCHOR"
            }
            FreesoundAutoPreparedNeed(
                index = index,
                need = need,
                cachedTrack = localMatch?.track,
                localMatch = localMatch,
                resolvedSource = if (decisiveLocal) FreesoundAutoResolutionSource.LIBRARY else null,
                effectiveQuery = effectiveQuery,
                strategy = strategy,
            )
        }
        val cacheLookupMs = (System.nanoTime() - cacheStartedNanos) / 1_000_000L

        // Only unresolved/uncertain needs reach Freesound. A decisive local winner or a winner locked
        // by an earlier retry is already final for this resolution cycle.
        val networkSeeds = prepared.filter { it.resolvedSource == null }
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
            val search = searchBest(seed.need, seed.effectiveQuery, allowCategoryFallback = retryAttempt > 1)
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
                    "categoryUsed" to search.categoryUsed,
                    "selectedName" to search.selectedName.take(180),
                    "selectedDurationSec" to "%.2f".format(java.util.Locale.US, search.selectedDurationSec),
                    "selectedScore" to "%.3f".format(java.util.Locale.US, search.selectedScore),
                    "selectedLexicalCoverage" to "%.3f".format(java.util.Locale.US, search.selectedLexicalCoverage),
                    "excludedSoundIds" to search.excludedSoundIds.sorted().joinToString(","),
                    "strategy" to seed.strategy,
                    "failure" to search.failureMessage.orEmpty().take(220),
                ),
            )
            seed.copy(search = search, searchElapsedMs = elapsedMs)
        }
        val networkSearchWallMs = if (networkSeeds.isEmpty()) 0L
        else (System.nanoTime() - networkStartedNanos) / 1_000_000L
        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)

        val preparedByIndex = prepared.associateBy(FreesoundAutoPreparedNeed::index)
        val sourceDecisionByIndex = searched.associate { searchedSeed ->
            val local = preparedByIndex.getValue(searchedSeed.index).localMatch
            val search = requireNotNull(searchedSeed.search)
            val remote = search.sound
            val remoteFit = if (remote != null) {
                Mode3LibraryAssetMatcher.remoteFit(
                    need = searchedSeed.need,
                    sound = remote,
                    lexicalCoverage = search.selectedLexicalCoverage,
                    selectedScore = search.selectedScore,
                )
            } else Mode3LibraryAssetMatcher.RemoteFit(0.0, 0.0, 0.0, false)
            val localFit = local?.selectionScore ?: 0.0
            val localSoundId = local?.track?.let { FreesoundImporter.soundIdFromManagedUri(it.uri) }
            val sameAsset = remote != null && localSoundId != null && localSoundId == remote.id
            val remoteQualified = remote != null && remoteFit.qualified
            val useLibrary = when {
                local == null -> false
                sameAsset -> true
                !remoteQualified -> true
                localFit > remoteFit.score + SOURCE_FIT_TIE_EPSILON -> true
                remoteFit.score > localFit + SOURCE_FIT_TIE_EPSILON -> false
                else -> true
            }
            val reason = when {
                local == null && !remoteQualified -> "REMOTE_REJECTED_WEAK_FIT"
                local == null -> "REMOTE_ONLY"
                sameAsset -> "SAME_AS_REMOTE_ALREADY_DOWNLOADED"
                !remoteQualified -> "REMOTE_REJECTED_WEAK_FIT_LIBRARY_USED"
                kotlin.math.abs(localFit - remoteFit.score) <= SOURCE_FIT_TIE_EPSILON -> "FIT_TIE_REUSE_EXISTING"
                useLibrary -> "LIBRARY_HIGHER_FIT"
                else -> "REMOTE_HIGHER_FIT"
            }
            diagnostics += "SOURCE_ARBITRATION kind=${searchedSeed.need.kind.name} query=${searchedSeed.need.query.take(140)} localTrackId=${local?.track?.id.orEmpty()} localFit=${"%.3f".format(java.util.Locale.US, localFit)} remoteSoundId=${remote?.id ?: 0} remoteFit=${"%.3f".format(java.util.Locale.US, remoteFit.score)} remoteCoreCoverage=${"%.3f".format(java.util.Locale.US, remoteFit.coreCoverage)} remoteEventCoverage=${"%.3f".format(java.util.Locale.US, remoteFit.eventCoverage)} remoteQualified=$remoteQualified sameAsset=$sameAsset winner=${if (useLibrary) "LIBRARY" else if (remoteQualified) "FREESOUND" else "NONE"} reason=$reason"
            liveDiagnostic(
                traceId,
                "FREESOUND_SOURCE_ARBITRATION",
                attributes = baseAttributes + mapOf(
                    "kind" to searchedSeed.need.kind.name,
                    "query" to searchedSeed.need.query.take(180),
                    "localTrackId" to local?.track?.id.orEmpty(),
                    "localTitle" to local?.track?.title.orEmpty().take(180),
                    "localAssetSource" to local?.track?.let { if (isManagedFreesoundTrack(it)) "FREESOUND" else "LOCAL" }.orEmpty(),
                    "localFit" to "%.3f".format(java.util.Locale.US, localFit),
                    "localContextScore" to "%.3f".format(java.util.Locale.US, local?.contextScore ?: 0.0),
                    "localQueryCoverage" to "%.3f".format(java.util.Locale.US, local?.coverage ?: 0.0),
                    "localCoreCoverage" to "%.3f".format(java.util.Locale.US, local?.coreCoverage ?: 0.0),
                    "localMetadataQuality" to local?.metadataQuality.orEmpty(),
                    "remoteSoundId" to (remote?.id ?: 0).toString(),
                    "remoteName" to search.selectedName.take(180),
                    "remoteFit" to "%.3f".format(java.util.Locale.US, remoteFit.score),
                    "remoteLexicalCoverage" to "%.3f".format(java.util.Locale.US, search.selectedLexicalCoverage),
                    "remoteCoreCoverage" to "%.3f".format(java.util.Locale.US, remoteFit.coreCoverage),
                    "remoteEventCoverage" to "%.3f".format(java.util.Locale.US, remoteFit.eventCoverage),
                    "remoteSourceConceptCoverage" to "%.3f".format(java.util.Locale.US, remoteFit.sourceConceptCoverage),
                    "remoteSourceConflictConfidence" to "%.3f".format(java.util.Locale.US, remoteFit.sourceConflictConfidence),
                    "remoteQualified" to remoteQualified.toString(),
                    "sameAsset" to sameAsset.toString(),
                    "winner" to if (useLibrary) "LIBRARY" else if (remoteQualified) "FREESOUND" else "NONE",
                    "reason" to reason,
                ),
            )
            searchedSeed.index to FreesoundSourceDecision(
                useLibrary = useLibrary,
                localFit = localFit,
                remoteFit = remoteFit.score,
                remoteCoreCoverage = remoteFit.coreCoverage,
                remoteEventCoverage = remoteFit.eventCoverage,
                remoteQualified = remoteQualified,
                sameAsset = sameAsset,
                reason = reason,
            )
        }.toMap()

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

        val importSeeds = searched.filter { seed ->
            seed.search?.sound != null &&
                sourceDecisionByIndex.getValue(seed.index).remoteQualified &&
                sourceDecisionByIndex.getValue(seed.index).useLibrary.not() &&
                localReusableByIndex[seed.index] == null
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
                semanticDescription = semanticDescriptionForImport(seed.need),
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

            if (seed.resolvedSource != null) {
                val lockedTrack = seed.cachedTrack ?: seed.localMatch?.track
                if (lockedTrack != null && isUsableLibraryTrack(lockedTrack, need.kind)) {
                    val actualSource = if (isManagedFreesoundTrack(lockedTrack)) {
                        FreesoundAutoResolutionSource.FREESOUND
                    } else {
                        FreesoundAutoResolutionSource.LIBRARY
                    }
                    queryCache.put(need.kind, need.query, lockedTrack.id)
                    rememberResolvedTrack(need, lockedTrack.id)
                    resolutions += FreesoundAutoResolvedNeed(need, lockedTrack.id, actualSource.name)
                    diagnostics += "NEED_RESOLVED kind=${need.kind.name} source=${actualSource.name} trackId=${lockedTrack.id} query=${need.query.take(140)} strategy=${seed.strategy} networkSkipped=true cachePersisted=true"
                    liveDiagnostic(
                        traceId,
                        "FREESOUND_NEED_RESOLVED",
                        attributes = baseAttributes + mapOf(
                            "kind" to need.kind.name,
                            "source" to actualSource.name,
                            "trackId" to lockedTrack.id,
                            "assetSource" to if (isManagedFreesoundTrack(lockedTrack)) "FREESOUND" else "LOCAL",
                            "query" to need.query.take(180),
                            "strategy" to seed.strategy,
                            "networkSkipped" to "true",
                            "cachePersisted" to "true",
                        ),
                    )
                    continue
                }
                forgetResolvedTrack(need)
            }

            val localMatch = seed.localMatch
            val decision = sourceDecisionByIndex.getValue(seed.index)
            if (decision.useLibrary && localMatch != null) {
                val localTrack = localMatch.track
                queryCache.put(need.kind, need.query, localTrack.id)
                rememberResolvedTrack(need, localTrack.id)
                resolutions += FreesoundAutoResolvedNeed(
                    need,
                    localTrack.id,
                    FreesoundAutoResolutionSource.LIBRARY.name,
                )
                diagnostics += "NEED_RESOLVED kind=${need.kind.name} source=LIBRARY trackId=${localTrack.id} assetSource=${if (isManagedFreesoundTrack(localTrack)) "FREESOUND" else "LOCAL"} fit=${"%.3f".format(java.util.Locale.US, decision.localFit)} query=${need.query.take(140)} cachePersisted=true"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_NEED_RESOLVED",
                    attributes = baseAttributes + mapOf(
                        "kind" to need.kind.name,
                        "source" to "LIBRARY",
                        "assetSource" to if (isManagedFreesoundTrack(localTrack)) "FREESOUND" else "LOCAL",
                        "trackId" to localTrack.id,
                        "fit" to "%.3f".format(java.util.Locale.US, decision.localFit),
                        "query" to need.query.take(180),
                        "cachePersisted" to "true",
                    ),
                )
                continue
            }

            val resolvedSearch = requireNotNull(searchedByIndex[seed.index])
            val search = requireNotNull(resolvedSearch.search)
            val searchedRemote = search.sound
            val remote = searchedRemote.takeIf { decision.remoteQualified }
            diagnostics += "CLIENT_SEARCH_DONE index=${seed.index + 1} kind=${need.kind.name} elapsedMs=${resolvedSearch.searchElapsedMs} resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${searchedRemote?.id ?: 0} remoteQualified=${decision.remoteQualified} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} queryUsed=${search.queryUsed.take(140)} searchRequests=${search.requestCount} strategy=${resolvedSearch.strategy}"
            if (!search.failureMessage.isNullOrBlank()) {
                warnings += "Freesound ‘${need.query}’ (${resolvedSearch.effectiveQuery}): ${search.failureMessage}"
                retryableFailure = retryableFailure || search.retryable
            }

            var resolvedTrack: SceneMusicTrackEntity? = null
            if (remote != null) {
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
                        if (result.downloadedNewFile) imported += result.trackId
                        else if (result.reusedExistingFile || preexistingSoundTrackByIndex[seed.index] != null) normalizationResumes += 1
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
                        val retryableImport = when (error) {
                            is FreesoundImportException -> error.retryable
                            is FreesoundNormalizationException -> error.retryable
                            else -> FreesoundImporter.isRetryableImportFailure(error)
                        }
                        if (error is FreesoundImportException && error.downloadedNewFile) {
                            error.trackId?.let(imported::add)
                        }
                        if (retryableImport) rememberFailedSound(need, remote.id)
                        retryableFailure = retryableFailure || retryableImport
                        diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=$retryableImport blacklistedForRetry=$retryableImport errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
                        liveDiagnostic(
                            traceId,
                            "FREESOUND_IMPORT_FAILED",
                            DiagnosticSeverity.WARN,
                            baseAttributes + mapOf(
                                "kind" to need.kind.name,
                                "soundId" to remote.id.toString(),
                                "elapsedMs" to importElapsedMs.toString(),
                                "retryable" to retryableImport.toString(),
                                "errorType" to error?.javaClass?.simpleName.orEmpty(),
                                "error" to message.take(240),
                            ),
                        )
                    }
                }
            } else {
                diagnostics += "SEARCH_NO_SELECTION kind=${need.kind.name} resultCount=${search.resultCount} originalQuery=${need.query.take(140)} effectiveQuery=${resolvedSearch.effectiveQuery.take(140)} strategy=${resolvedSearch.strategy}"
            }

            if (resolvedTrack != null && isUsableLibraryTrack(resolvedTrack, need.kind)) {
                val managedSoundId = FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri)
                val resolvedSource = if (managedSoundId != null) {
                    FreesoundAutoResolutionSource.FREESOUND
                } else {
                    FreesoundAutoResolutionSource.LIBRARY
                }
                queryCache.put(need.kind, need.query, resolvedTrack.id)
                rememberResolvedTrack(need, resolvedTrack.id)
                resolutions += FreesoundAutoResolvedNeed(need, resolvedTrack.id, resolvedSource.name)
                diagnostics += "NEED_RESOLVED kind=${need.kind.name} source=${resolvedSource.name} trackId=${resolvedTrack.id} soundId=${managedSoundId ?: 0} query=${need.query.take(140)} cachePersisted=true"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_NEED_RESOLVED",
                    attributes = baseAttributes + mapOf(
                        "kind" to need.kind.name,
                        "source" to resolvedSource.name,
                        "trackId" to resolvedTrack.id,
                        "soundId" to (managedSoundId ?: 0).toString(),
                        "query" to need.query.take(180),
                        "cachePersisted" to "true",
                    ),
                )
                continue
            }

            forgetResolvedTrack(need)
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
                warnings += "$prefix ‘${need.query}’ chưa có asset thư viện đủ phù hợp và cũng chưa tìm thấy kết quả đủ phù hợp trên Freesound; khoảng đó sẽ im lặng ở lớp tương ứng."
            }
        }

        val totalElapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
        val unresolvedRequired = resolutions.count {
            it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED
        }
        val retryRecommended = retryableFailure ||
            (resolutions.isNotEmpty() && (resolutions.none { !it.trackId.isNullOrBlank() } || unresolvedRequired > 0))
        val clientSearches = searched.sumOf { it.search?.requestCount ?: 0 }
        activeResolutionImportedTrackIds += imported
        activeResolutionCycleComplete = resolutions.isNotEmpty() && resolutions.all { !it.trackId.isNullOrBlank() }
        if (activeResolutionCycleComplete) {
            synchronized(completedResolutionCycles) {
                completedResolutionCycles[cycleKey] = CompletedResolutionCycle(resolvedTrackIdsByNeed.toMap())
            }
        }
        val transactionImportedTrackIds = activeResolutionImportedTrackIds.toSet()
        diagnostics += "RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired retryLockedReuses=$retryLockedReuses decisiveLocalSkips=$decisiveLocalSkips networkNeeds=${networkSeeds.size} queryCacheHits=$queryCacheHits localLibraryMatches=$localLibraryMatches clientSearches=$clientSearches parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS} cacheLookupMs=$cacheLookupMs networkSearchWallMs=$networkSearchWallMs importWallMs=$parallelImportWallMs importAttempts=$importAttempts localSoundIdReuses=$localSoundIdReuses normalizationResumes=$normalizationResumes imported=${transactionImportedTrackIds.size} importedThisCall=${imported.size} importElapsedTotalMs=$importElapsedTotalMs retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs"
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
                "retryLockedReuses" to retryLockedReuses.toString(),
                "decisiveLocalSkips" to decisiveLocalSkips.toString(),
                "networkNeeds" to networkSeeds.size.toString(),
                "queryCacheHits" to queryCacheHits.toString(),
                "localLibraryMatches" to localLibraryMatches.toString(),
                "clientSearches" to clientSearches.toString(),
                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
                "cacheLookupMs" to cacheLookupMs.toString(),
                "networkSearchWallMs" to networkSearchWallMs.toString(),
                "importWallMs" to parallelImportWallMs.toString(),
                "importAttempts" to importAttempts.toString(),
                "localSoundIdReuses" to localSoundIdReuses.toString(),
                "normalizationResumes" to normalizationResumes.toString(),
                "imported" to transactionImportedTrackIds.size.toString(),
                "importedThisCall" to imported.size.toString(),
                "completedCycleReuse" to completedCycleReuse.toString(),
                "importElapsedTotalMs" to importElapsedTotalMs.toString(),
                "retryRecommended" to retryRecommended.toString(),
                "elapsedMs" to totalElapsedMs.toString(),
            ),
        )
        if (retryRecommended && retryAttempt >= retryMax) {
            liveDiagnostic(
                traceId,
                "FREESOUND_RETRY_EXHAUSTED",
                if (resolutions.any { !it.trackId.isNullOrBlank() }) DiagnosticSeverity.WARN else DiagnosticSeverity.ERROR,
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
            importedTrackIds = transactionImportedTrackIds,
            retryableFailure = retryableFailure,
            diagnostics = diagnostics.distinct(),
        )
    }

    private suspend fun searchBest(
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
                    .mapIndexed { index, sound -> sound to scoreCandidateForSearch(need, sound, index, query) }
                    .filter { (sound, _) ->
                        sound.preferredPreviewUrl != null &&
                            candidateMeetsLexicalFloor(need, sound, query) &&
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
                    selectedLexicalCoverage = selected?.first?.let { candidateSearchCoverage(need, it, query) } ?: 0.0,
                    excludedSoundIds = excluded,
                )
            }
        }
    }

    private fun semanticDescriptionForImport(need: FreesoundAutoSearchNeed): String =
        need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .maxByOrNull(String::length)
            ?.take(300)
            .orEmpty()

    private suspend fun normalizationTarget(kind: AudioAssetKind): Float = when (kind) {
        AudioAssetKind.MUSIC -> settingsRepository.snapshot().sceneMusicTargetLufs
        AudioAssetKind.AMBIENCE -> AudioDirectionPreferences.shared(appContext).snapshot().ambienceNormalizationTargetLufs
        AudioAssetKind.SFX -> AudioDirectionPreferences.shared(appContext).snapshot().soundEffectsNormalizationTargetLufs
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 30
        private const val REMOTE_MIN_SCORE = 0.22
        private const val REMOTE_MIN_LEXICAL_COVERAGE = 0.50
        private const val RETRY_TWO_TERM_EVIDENCE_WEIGHT = 0.90
        private const val RETRY_ONE_TERM_EVIDENCE_WEIGHT = 0.68
        private const val SOURCE_FIT_TIE_EPSILON = 0.015
        private const val MAX_COMPLETED_CYCLE_CACHE_ENTRIES = 12
        private val RETRY_QUERY_STOPWORDS = setOf(
            "a", "an", "the", "on", "in", "at", "with", "and", "or", "of", "to", "from", "for", "by",
            "into", "onto", "single", "one", "sound", "effect", "audio",
        )
        private val RETRY_QUERY_MODIFIERS = setOf(
            "light", "quiet", "peaceful", "sad", "romantic", "tense", "heavy", "soft", "gentle",
            "distant", "far", "near", "close", "night", "day", "dark", "bright", "slow", "fast",
            "deep", "warm", "cold", "dramatic", "epic", "strong", "intense",
        )

        internal fun isRetryableSearchFailure(httpCode: Int?): Boolean =
            httpCode == null || httpCode == 429 || httpCode >= 500

        internal fun searchQueryForRetry(query: String, retryAttempt: Int): String {
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

        private fun lexicalCoverageForQuery(query: String, sound: FreesoundSound): Double {
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(
                FreesoundAutoRequirementAggregator.normalizeQuery(query),
            )
            if (queryTokens.isEmpty()) return 0.0
            fun coverage(text: String): Double {
                val tokens = FreesoundAutoRequirementAggregator.queryTokens(
                    FreesoundAutoRequirementAggregator.normalizeQuery(text),
                )
                return queryTokens.count(tokens::contains).toDouble() / queryTokens.size.toDouble()
            }
            val titleCoverage = coverage(sound.name)
            val descriptionCoverage = coverage(sound.description)
            val tagCoverage = coverage(sound.tags.joinToString(" "))
            return max(titleCoverage, max(tagCoverage * 0.96, descriptionCoverage * 0.78))
        }

        internal fun candidateLexicalCoverage(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Double = lexicalCoverageForQuery(need.query, sound)

        private fun candidateSearchCoverage(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            queryUsed: String,
        ): Double {
            val original = candidateLexicalCoverage(need, sound)
            val normalizedOriginal = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
            val normalizedUsed = FreesoundAutoRequirementAggregator.normalizeQuery(queryUsed)
            if (normalizedUsed.isBlank() || normalizedUsed == normalizedOriginal) return original
            val effective = lexicalCoverageForQuery(queryUsed, sound)
            val termCount = FreesoundAutoRequirementAggregator.queryTokens(normalizedUsed).size
            val weight = if (termCount >= 2) RETRY_TWO_TERM_EVIDENCE_WEIGHT else RETRY_ONE_TERM_EVIDENCE_WEIGHT
            return max(original, effective * weight).coerceIn(0.0, 1.0)
        }

        internal fun candidateMeetsLexicalFloor(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Boolean = candidateLexicalCoverage(need, sound) >= REMOTE_MIN_LEXICAL_COVERAGE

        private fun candidateMeetsLexicalFloor(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            queryUsed: String,
        ): Boolean = candidateSearchCoverage(need, sound, queryUsed) >= REMOTE_MIN_LEXICAL_COVERAGE

        internal fun candidateMeetsDurationLimit(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Boolean = when (need.kind) {
            AudioAssetKind.MUSIC -> sound.durationSeconds in 10.0..480.0
            AudioAssetKind.AMBIENCE -> sound.durationSeconds in 8.0..180.0
            AudioAssetKind.SFX -> sound.durationSeconds in 0.05..20.0
        }

        internal fun scoreCandidate(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            rankIndex: Int,
        ): Double = scoreCandidateForSearch(need, sound, rankIndex, need.query)

        private fun scoreCandidateForSearch(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            rankIndex: Int,
            queryUsed: String,
        ): Double {
            val queryNorm = FreesoundAutoRequirementAggregator.normalizeQuery(queryUsed)
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(queryNorm)
            if (queryTokens.isEmpty()) return 0.0
            val titleNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.name)
            val descriptionNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.description)
            val tagNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.tags.joinToString(" "))
            val lexicalCoverage = candidateSearchCoverage(need, sound, queryUsed)
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
                    in 20.0..120.0 -> 0.14
                    in 10.0..180.0 -> 0.05
                    in 8.0..<10.0 -> 0.01
                    else -> -0.30
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
        return musicAssignmentsToCues(selected, validUnitIds)
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
                    existing.scene.startUnitId == candidate.scene.startUnitId &&
                    existing.scene.endUnitId == candidate.scene.endUnitId
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
}

private fun AudioAssetKind.toFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}
