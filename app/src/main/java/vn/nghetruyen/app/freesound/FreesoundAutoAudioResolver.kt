package vn.nghetruyen.app.freesound

import android.content.Context
import java.security.MessageDigest
import kotlin.math.max
import vn.nghetruyen.app.ai.SceneMusicCue
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.audio.AmbienceScene
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.SoundEffectCue
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository

private enum class FreesoundAutoResolutionSource { CACHE, FREESOUND, UNRESOLVED }

internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
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

    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {
        val startedNanos = System.nanoTime()
        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        val diagnostics = mutableListOf<String>()
        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size}"
        if (needs.isEmpty()) {
            diagnostics += "RESOLVE_EMPTY no aggregated Freesound needs were produced"
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
        var clientSearches = 0
        var importAttempts = 0

        for ((index, need) in needs.withIndex()) {
            diagnostics += "NEED_START index=${index + 1}/${needs.size} kind=${need.kind.name} importance=${need.importance.name} usages=${need.usages.size} query=${need.query.take(160)}"
            val currentTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
            val cachedId = queryCache.get(need.kind, need.query)
            val cachedTrack = cachedId?.let { id -> currentTracks.firstOrNull { it.id == id } }
                ?.takeIf {
                    it.enabled &&
                        AudioAssetClassifier.classify(it) == need.kind &&
                        FreesoundImporter.soundIdFromManagedUri(it.uri) != null &&
                        FreesoundImporter.managedFileExists(appContext, it.uri)
                }
            if (cachedTrack != null) {
                queryCacheHits += 1
                diagnostics += "QUERY_CACHE_HIT kind=${need.kind.name} trackId=${cachedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri)} fileExists=true query=${need.query.take(140)}"
                resolutions += FreesoundAutoResolvedNeed(need, cachedTrack.id, FreesoundAutoResolutionSource.CACHE.name)
                continue
            } else if (cachedId != null) {
                diagnostics += "QUERY_CACHE_STALE kind=${need.kind.name} trackId=$cachedId reason=missing_disabled_wrong_kind_or_file_missing query=${need.query.take(140)}"
                queryCache.remove(need.kind, need.query)
            } else {
                diagnostics += "QUERY_CACHE_MISS kind=${need.kind.name} query=${need.query.take(140)}"
            }

            clientSearches += 1
            val searchStartedNanos = System.nanoTime()
            diagnostics += "CLIENT_SEARCH_START index=${index + 1} kind=${need.kind.name} query=${need.query.take(160)}"
            val search = searchBest(need)
            val searchElapsedMs = (System.nanoTime() - searchStartedNanos) / 1_000_000L
            val remote = search.sound
            diagnostics += "CLIENT_SEARCH_DONE index=${index + 1} kind=${need.kind.name} elapsedMs=$searchElapsedMs resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} failure=${search.failureMessage.orEmpty().take(180)}"
            if (!search.failureMessage.isNullOrBlank()) {
                warnings += "Freesound ‘${need.query}’: ${search.failureMessage}"
                retryableFailure = retryableFailure || search.retryable
            }

            var resolvedTrack: SceneMusicTrackEntity? = null
            if (remote != null) {
                importAttempts += 1
                val importStartedNanos = System.nanoTime()
                diagnostics += "IMPORT_START kind=${need.kind.name} soundId=${remote.id} durationSec=${"%.2f".format(java.util.Locale.US, remote.durationSeconds)} previewAvailable=${remote.preferredPreviewUrl != null} query=${need.query.take(140)}"
                val import = importer.importPreview(
                    sound = remote,
                    kind = need.kind,
                    normalizationTargetLufs = normalizationTarget(need.kind),
                )
                val importElapsedMs = (System.nanoTime() - importStartedNanos) / 1_000_000L
                if (import.isSuccess) {
                    val result = import.getOrThrow()
                    imported += result.trackId
                    resolvedTrack = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                        .firstOrNull { it.id == result.trackId && it.enabled }
                    diagnostics += "IMPORT_SUCCESS kind=${need.kind.name} soundId=${remote.id} trackId=${result.trackId} elapsedMs=$importElapsedMs fileExists=${resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true}"
                } else if (import.exceptionOrNull() is FreesoundDuplicateException) {
                    resolvedTrack = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                        .firstOrNull { track ->
                            track.enabled &&
                                FreesoundImporter.managedFileExists(appContext, track.uri) &&
                                FreesoundImporter.soundIdFromManagedUri(track.uri) == remote.id &&
                                AudioAssetClassifier.classify(track) == need.kind
                        }
                    diagnostics += "IMPORT_DUPLICATE kind=${need.kind.name} soundId=${remote.id} reusedTrackId=${resolvedTrack?.id.orEmpty()} elapsedMs=$importElapsedMs fileExists=${resolvedTrack != null}"
                } else {
                    val error = import.exceptionOrNull()
                    val message = error?.message?.takeIf(String::isNotBlank)
                        ?: "Không nhập/chuẩn hóa được preview đã chọn."
                    warnings += "Freesound ‘${need.query}’: $message"
                    if (error is FreesoundNormalizationException && error.retryable) retryableFailure = true
                    diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=${error is FreesoundNormalizationException && error.retryable} errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
                }
            } else {
                diagnostics += "SEARCH_NO_SELECTION kind=${need.kind.name} resultCount=${search.resultCount} query=${need.query.take(160)}"
            }

            if (resolvedTrack != null && resolvedTrack.enabled &&
                FreesoundImporter.managedFileExists(appContext, resolvedTrack.uri) &&
                FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri) != null
            ) {
                queryCache.put(need.kind, need.query, resolvedTrack.id)
                resolutions += FreesoundAutoResolvedNeed(need, resolvedTrack.id, FreesoundAutoResolutionSource.FREESOUND.name)
                diagnostics += "NEED_RESOLVED kind=${need.kind.name} source=FREESOUND trackId=${resolvedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri)} query=${need.query.take(140)}"
                continue
            }

            resolutions += FreesoundAutoResolvedNeed(need, null, FreesoundAutoResolutionSource.UNRESOLVED.name)
            diagnostics += "NEED_UNRESOLVED kind=${need.kind.name} query=${need.query.take(160)}"
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
        diagnostics += "RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired queryCacheHits=$queryCacheHits clientSearches=$clientSearches importAttempts=$importAttempts imported=${imported.size} retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs"
        return FreesoundAutoResolveResult(
            resolved = resolutions,
            warnings = warnings.distinct(),
            importedTrackIds = imported,
            retryableFailure = retryableFailure,
            diagnostics = diagnostics.distinct(),
        )
    }

    private suspend fun searchBest(need: FreesoundAutoSearchNeed): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = need.query,
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
                    .mapIndexed { index, sound -> sound to scoreCandidate(need.query, sound, index) }
                    .filter { it.first.preferredPreviewUrl != null }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,
                resultCount = result.page.results.size,
                httpCode = 200,
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

        internal fun scoreCandidate(query: String, sound: FreesoundSound, rankIndex: Int): Double {
            val queryNorm = FreesoundAutoRequirementAggregator.normalizeQuery(query)
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(queryNorm)
            if (queryTokens.isEmpty()) return 0.0
            val titleNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.name)
            val descriptionNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.description)
            val titleTokens = FreesoundAutoRequirementAggregator.queryTokens(titleNorm)
            val descriptionTokens = FreesoundAutoRequirementAggregator.queryTokens(descriptionNorm)
            fun coverage(tokens: Set<String>): Double =
                queryTokens.count(tokens::contains).toDouble() / queryTokens.size.toDouble()
            val titleCoverage = coverage(titleTokens)
            val descriptionCoverage = coverage(descriptionTokens)
            val phraseBonus = when {
                titleNorm.contains(queryNorm) -> 0.30
                descriptionNorm.contains(queryNorm) -> 0.15
                else -> 0.0
            }
            val rankBonus = 0.15 / (rankIndex.coerceAtLeast(0) + 1.0)
            return (max(titleCoverage, descriptionCoverage * 0.82) * 0.72 + phraseBonus + rankBonus)
                .coerceIn(0.0, 1.0)
        }
    }
}

/** Converts resolved search needs into the same local runtime cue types used by the existing player. */
object FreesoundAutoPlanBuilder {
    fun musicCues(
        resolved: List<FreesoundAutoResolvedNeed>,
        validUnitIds: List<String>,
    ): List<SceneMusicCue> {
        if (validUnitIds.isEmpty()) return emptyList()
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val selectedByUnit = MutableList(validUnitIds.size) { XpkSceneMusicParity.SILENCE_TRACK_ID }
        val music = resolved.filter { it.need.kind == AudioAssetKind.MUSIC && !it.trackId.isNullOrBlank() }
            .sortedWith(compareBy<FreesoundAutoResolvedNeed> { it.need.importance != FreesoundRequirementImportance.REQUIRED })
        music.forEach { resolution ->
            val trackId = resolution.trackId ?: return@forEach
            resolution.need.usages.forEach { usage ->
                val start = usage.startUnitId?.let(order::get) ?: return@forEach
                val end = usage.endUnitId?.let(order::get) ?: return@forEach
                for (index in start..end) {
                    if (selectedByUnit[index] == XpkSceneMusicParity.SILENCE_TRACK_ID) selectedByUnit[index] = trackId
                }
            }
        }
        val cues = mutableListOf<SceneMusicCue>()
        var start = 0
        var track = selectedByUnit.first()
        for (index in 1..selectedByUnit.size) {
            val changed = index == selectedByUnit.size || selectedByUnit[index] != track
            if (!changed) continue
            cues += SceneMusicCue(
                startParagraph = start,
                endParagraph = index - 1,
                trackId = track,
                startUnitId = validUnitIds[start],
                endUnitId = validUnitIds[index - 1],
            )
            if (index < selectedByUnit.size) {
                start = index
                track = selectedByUnit[index]
            }
        }
        return cues
    }

    fun ambienceScenes(resolved: List<FreesoundAutoResolvedNeed>): List<AmbienceScene> = buildList {
        resolved.filter { it.need.kind == AudioAssetKind.AMBIENCE && !it.trackId.isNullOrBlank() }.forEach { resolution ->
            val trackId = resolution.trackId ?: return@forEach
            resolution.need.usages.forEach { usage ->
                val start = usage.startUnitId ?: return@forEach
                val end = usage.endUnitId ?: return@forEach
                add(AmbienceScene(startUnitId = start, endUnitId = end, ambienceId = trackId))
            }
        }
    }

    fun soundEffectCues(resolved: List<FreesoundAutoResolvedNeed>): List<SoundEffectCue> = buildList {
        resolved.filter { it.need.kind == AudioAssetKind.SFX && !it.trackId.isNullOrBlank() }.forEach { resolution ->
            val trackId = resolution.trackId ?: return@forEach
            resolution.need.usages.forEach { usage ->
                val unit = usage.unitId ?: return@forEach
                add(
                    SoundEffectCue(
                        unitId = unit,
                        effectId = trackId,
                        stopUnitId = usage.stopUnitId,
                        repeatCount = usage.repeatCount,
                        cadence = usage.cadence,
                        loopUntilStop = usage.loopUntilStop,
                    ),
                )
            }
        }
    }
}

private fun AudioAssetKind.toFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}