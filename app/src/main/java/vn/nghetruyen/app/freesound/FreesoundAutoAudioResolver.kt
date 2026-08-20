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
) {
    val resolvedCount: Int get() = resolved.count { !it.trackId.isNullOrBlank() }
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

    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {
        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        if (needs.isEmpty()) return FreesoundAutoResolveResult(emptyList(), emptyList(), emptySet())
        val warnings = mutableListOf<String>()
        val imported = linkedSetOf<String>()
        val resolutions = mutableListOf<FreesoundAutoResolvedNeed>()
        var retryableFailure = false

        for (need in needs) {
            val currentTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
            val cachedId = queryCache.get(need.kind, need.query)
            val cachedTrack = cachedId?.let { id -> currentTracks.firstOrNull { it.id == id } }
                ?.takeIf {
                    it.enabled &&
                        AudioAssetClassifier.classify(it) == need.kind &&
                        FreesoundImporter.soundIdFromManagedUri(it.uri) != null
                }
            if (cachedTrack != null) {
                resolutions += FreesoundAutoResolvedNeed(need, cachedTrack.id, FreesoundAutoResolutionSource.CACHE.name)
                continue
            } else if (cachedId != null) {
                queryCache.remove(need.kind, need.query)
            }

                    val search = searchBest(need)
            val remote = search.sound
            if (!search.failureMessage.isNullOrBlank()) {
                warnings += "Freesound ‘${need.query}’: ${search.failureMessage}"
                retryableFailure = retryableFailure || search.retryable
            }
            var resolvedTrack: SceneMusicTrackEntity? = null
            if (remote != null) {
                val import = importer.importPreview(
                    sound = remote,
                    kind = need.kind,
                    normalizationTargetLufs = normalizationTarget(need.kind),
                )
                if (import.isSuccess) {
                    val result = import.getOrThrow()
                    imported += result.trackId
                    resolvedTrack = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                        .firstOrNull { it.id == result.trackId && it.enabled }
                } else if (import.exceptionOrNull() is FreesoundDuplicateException) {
                    resolvedTrack = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                        .firstOrNull { track ->
                            track.enabled &&
                                FreesoundImporter.managedFileExists(appContext, track.uri) &&
                            FreesoundImporter.soundIdFromManagedUri(track.uri) == remote.id &&
                                AudioAssetClassifier.classify(track) == need.kind
                        }
                } else {
                    val error = import.exceptionOrNull()
                    val message = error?.message?.takeIf(String::isNotBlank)
                        ?: "Không nhập/chuẩn hóa được preview đã chọn."
                    warnings += "Freesound ‘${need.query}’: $message"
                    if (error is FreesoundNormalizationException && error.retryable) retryableFailure = true
                }
            }

            if (resolvedTrack != null && resolvedTrack.enabled &&
                FreesoundImporter.managedFileExists(appContext, resolvedTrack.uri) &&
                FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri) != null
            ) {
                queryCache.put(need.kind, need.query, resolvedTrack.id)
                resolutions += FreesoundAutoResolvedNeed(need, resolvedTrack.id, FreesoundAutoResolutionSource.FREESOUND.name)
                continue
            }

            resolutions += FreesoundAutoResolvedNeed(need, null, FreesoundAutoResolutionSource.UNRESOLVED.name)
    if (search.failureMessage.isNullOrBlank() && remote == null) {
            val prefix = if (need.importance == FreesoundRequirementImportance.REQUIRED) "Âm thanh quan trọng" else "Âm thanh tùy chọn"
        warnings += "$prefix ‘${need.query}’ chưa tìm thấy kết quả đủ phù hợp trên Freesound; khoảng đó sẽ im lặng ở lớp tương ứng."
    }
        }
        return FreesoundAutoResolveResult(resolutions, warnings.distinct(), imported, retryableFailure)
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
            )
            is FreesoundSearchResult.Success -> FreesoundAutoSearchOutcome(
                sound = result.page.results
                .mapIndexed { index, sound -> sound to scoreCandidate(need.query, sound, index) }
                .filter { it.first.preferredPreviewUrl != null }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,
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