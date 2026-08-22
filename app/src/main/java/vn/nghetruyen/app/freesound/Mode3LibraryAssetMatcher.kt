package vn.nghetruyen.app.freesound

import android.content.Context
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

/**
 * Scores existing Mode-3 library assets as source-neutral candidates beside Freesound results.
 *
 * The short Freesound query remains the network-search signal. New AI responses additionally carry
 * a Vietnamese local hint in each usage, formatted as "Dùng: ...; Tránh: ...". Local matching compares
 * that hint directly with the asset metadata fields Sắc thái / Dùng / Tránh. Source provenance never
 * contributes to fit: user-imported assets and earlier Freesound imports are evaluated identically.
 *
 * Performance rule: expensive metadata parsing/tokenization is cached per library fingerprint. Each
 * need then evaluates only tracks sharing at least one relevant query/local token. This keeps local
 * lookup cheaper than rebuilding metadata for the whole library on every short query.
 */
internal object Mode3LibraryAssetMatcher {
    data class Match(
        val track: SceneMusicTrackEntity,
        val score: Double,
        val coverage: Double,
        val contextScore: Double,
        val anchorCoverage: Double,
        val avoidCoverage: Double,
        val contextAware: Boolean,
        val anchorRequired: Boolean = false,
        val useScore: Double = 0.0,
        val shadeScore: Double = 0.0,
        val allScore: Double = 0.0,
        val repetitionPenalty: Double = 0.0,
        val accepted: Boolean = false,
        val rejectReason: String = "",
        val structured: Boolean = false,
        /** Coverage of the most important audible/source query token. */
        val coreCoverage: Double = 0.0,
        /** SFX action/event token coverage (e.g. explosion/shatter/slam). */
        val eventCoverage: Double = 0.0,
        /** Normalized fit used only to compare candidates; source provenance never contributes. */
        val selectionScore: Double = 0.0,
        val metadataQuality: String = "RAW",
    )

    data class Evaluation(
        val accepted: Match?,
        val topCandidates: List<Match>,
        val indexedTracks: Int,
        val candidateTracks: Int,
        val elapsedMs: Long,
        val indexCacheHit: Boolean,
    )

    data class RemoteFit(
        val score: Double,
        val coreCoverage: Double,
        val eventCoverage: Double,
        val qualified: Boolean,
    )

    private data class LocalText(
        val tokens: LinkedHashSet<String>,
        val bigrams: Set<String>,
    )

    private data class Sections(
        val allText: String,
        val shadeText: String,
        val useText: String,
        val avoidText: String,
        val all: LocalText,
        val shade: LocalText,
        val use: LocalText,
        val avoid: LocalText,
        val structured: Boolean,
    )

    private data class LocalHint(
        val raw: String,
        val use: LocalText,
        val avoid: LocalText,
    ) {
        val isPresent: Boolean get() = use.tokens.isNotEmpty() || avoid.tokens.isNotEmpty()
    }

    private data class NeedProfile(
        val kind: AudioAssetKind,
        val queryTokens: Set<String>,
        val coreToken: String?,
        val eventToken: String?,
        val hints: List<LocalHint>,
    ) {
        val hintAware: Boolean get() = hints.isNotEmpty()
        val localCandidateTokens: Set<String>
            get() = hints.flatMapTo(linkedSetOf()) { it.use.tokens + it.avoid.tokens }
    }

    private data class IndexedTrack(
        val track: SceneMusicTrackEntity,
        val englishTitleTokens: Set<String>,
        val englishMetadataTokens: Set<String>,
        val englishAvoidTokens: Set<String>,
        val sections: Sections,
    )

    private data class LibraryIndex(
        val kind: AudioAssetKind,
        val fingerprint: String,
        val entries: List<IndexedTrack>,
        val englishInverted: Map<String, Set<Int>>,
        val localInverted: Map<String, Set<Int>>,
    )

    @Volatile
    private var appContext: Context? = null

    private val cacheLock = Any()
    private val indexCache = object : LinkedHashMap<String, LibraryIndex>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LibraryIndex>?): Boolean = size > MAX_INDEX_CACHE_ENTRIES
    }
    private val recentDiagnosticAt = LinkedHashMap<String, Long>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun bestMatch(
        need: FreesoundAutoSearchNeed,
        tracks: List<SceneMusicTrackEntity>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? = evaluate(need, tracks, nowMillis).accepted

    fun evaluate(
        need: FreesoundAutoSearchNeed,
        tracks: List<SceneMusicTrackEntity>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Evaluation {
        val started = System.nanoTime()
        val (index, cacheHit) = indexFor(need.kind, tracks)
        val profile = needProfile(need)
        val candidateIndices = candidateIndices(profile, index)
        // The index contains only stable metadata. Playback counters remain live and are read from
        // the current DB snapshot so playCount/lastPlayedAt never force an expensive index rebuild.
        val currentTrackById = tracks.associateBy(SceneMusicTrackEntity::id)
        val ranked = candidateIndices.asSequence()
            .mapNotNull { entryIndex ->
                val indexed = index.entries[entryIndex]
                val currentTrack = currentTrackById[indexed.track.id] ?: indexed.track
                score(profile, indexed, currentTrack, nowMillis)
            }
            .sortedWith(
                compareByDescending<Match> { it.selectionScore }
                    .thenByDescending { it.contextScore }
                    .thenByDescending { it.coverage }
                    .thenByDescending { it.score },
            )
            .toList()
        val accepted = ranked.firstOrNull(Match::accepted)
        val evaluation = Evaluation(
            accepted = accepted,
            topCandidates = ranked.take(MAX_DIAGNOSTIC_CANDIDATES),
            indexedTracks = index.entries.size,
            candidateTracks = candidateIndices.size,
            elapsedMs = (System.nanoTime() - started) / 1_000_000L,
            indexCacheHit = cacheHit,
        )
        emitDiagnostics(need, index, evaluation)
        return evaluation
    }

    fun strongMatch(
        need: FreesoundAutoSearchNeed,
        track: SceneMusicTrackEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? {
        if (!track.enabled || AudioAssetClassifier.classify(track) != need.kind) return null
        val profile = needProfile(need)
        return score(profile, indexTrack(track), track, nowMillis)?.takeIf(Match::accepted)
    }

    /**
     * A decisive library fit can safely stop the network path. This is deliberately source-neutral:
     * an already-downloaded Freesound asset and a user-imported asset use the exact same rule.
     * Borderline accepted matches still go online and compete against the remote candidate.
     */
    fun isDecisive(match: Match?): Boolean {
        if (match == null || !match.accepted) return false
        if (match.selectionScore < LOCAL_NETWORK_SKIP_FIT || match.avoidCoverage >= MAX_CONFLICT) return false
        return when (match.metadataQuality) {
            "STRUCTURED" -> match.contextScore >= DECISIVE_CONTEXT_SCORE
            else -> match.coverage >= EXACT_QUERY_COVERAGE && match.coreCoverage >= CORE_PRESENT_COVERAGE
        }
    }

    private fun indexFor(
        kind: AudioAssetKind,
        tracks: List<SceneMusicTrackEntity>,
    ): Pair<LibraryIndex, Boolean> {
        val fingerprint = libraryFingerprint(kind, tracks)
        val key = "${kind.name}:$fingerprint"
        synchronized(cacheLock) {
            indexCache[key]?.let { return it to true }
        }

        val entries = tracks.asSequence()
            .filter { it.enabled && AudioAssetClassifier.classify(it) == kind }
            .map(::indexTrack)
            .toList()
        val englishMutable = linkedMapOf<String, MutableSet<Int>>()
        val localMutable = linkedMapOf<String, MutableSet<Int>>()
        entries.forEachIndexed { index, entry ->
            (entry.englishTitleTokens + entry.englishMetadataTokens).forEach { token ->
                englishMutable.getOrPut(token) { linkedSetOf() }.add(index)
            }
            entry.sections.all.tokens.forEach { token ->
                localMutable.getOrPut(token) { linkedSetOf() }.add(index)
            }
        }
        val built = LibraryIndex(
            kind = kind,
            fingerprint = fingerprint,
            entries = entries,
            englishInverted = englishMutable.mapValues { it.value.toSet() },
            localInverted = localMutable.mapValues { it.value.toSet() },
        )
        synchronized(cacheLock) {
            indexCache[key] = built
        }
        return built to false
    }

    private fun indexTrack(track: SceneMusicTrackEntity): IndexedTrack {
        val sections = sections(track.tagsCsv)
        return IndexedTrack(
            track = track,
            englishTitleTokens = FreesoundAutoRequirementAggregator.queryTokens(track.title),
            englishMetadataTokens = FreesoundAutoRequirementAggregator.queryTokens(track.tagsCsv),
            englishAvoidTokens = FreesoundAutoRequirementAggregator.queryTokens(sections.avoidText),
            sections = sections,
        )
    }

    private fun libraryFingerprint(kind: AudioAssetKind, tracks: List<SceneMusicTrackEntity>): String {
        var hash = 1125899906842597L
        var count = 0
        // Only stable search/index inputs belong in this fingerprint. In particular playCount and
        // lastPlayedAt are intentionally excluded: they affect the tiny repetition penalty only and
        // must not make hundreds of metadata rows get tokenized again after playback.
        tracks.asSequence()
            .filter { AudioAssetClassifier.classify(it) == kind }
            .sortedBy(SceneMusicTrackEntity::id)
            .forEach { track ->
                count += 1
                hash = hash * 31L + track.id.hashCode()
                hash = hash * 31L + track.title.hashCode()
                hash = hash * 31L + track.tagsCsv.hashCode()
                hash = hash * 31L + if (track.enabled) 1 else 0
            }
        return "${kind.name}-$count-${java.lang.Long.toUnsignedString(hash, 16)}"
    }

    private fun candidateIndices(profile: NeedProfile, index: LibraryIndex): Set<Int> {
        val candidates = linkedSetOf<Int>()
        profile.queryTokens.forEach { token -> index.englishInverted[token]?.let(candidates::addAll) }
        profile.localCandidateTokens.forEach { token -> index.localInverted[token]?.let(candidates::addAll) }
        return candidates
    }

    private fun needProfile(need: FreesoundAutoSearchNeed): NeedProfile {
        val rawQueryTokens = FreesoundAutoRequirementAggregator.queryTokens(need.query).toList()
        val meaningful = rawQueryTokens.filterNot(LOCAL_QUERY_ANCHORS::contains)
        val queryTokens = meaningful.toSet().ifEmpty { rawQueryTokens.toSet() }
        val coreToken = coreQueryToken(rawQueryTokens)
        val eventToken = eventQueryToken(rawQueryTokens, need.kind)
        val hints = need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map(::parseLocalHint)
            .filter(LocalHint::isPresent)
            .toList()
        return NeedProfile(
            kind = need.kind,
            queryTokens = queryTokens,
            coreToken = coreToken,
            eventToken = eventToken,
            hints = hints,
        )
    }

    private fun coreQueryToken(rawTokens: List<String>): String? {
        if (rawTokens.isEmpty()) return null
        val meaningful = rawTokens.filterNot(LOCAL_QUERY_ANCHORS::contains)
        return meaningful.firstOrNull { it !in QUERY_MODIFIERS }
            ?: rawTokens.firstOrNull { it !in QUERY_MODIFIERS }
            ?: meaningful.firstOrNull()
            ?: rawTokens.firstOrNull()
    }

    private fun coreQueryToken(query: String): String? =
        coreQueryToken(FreesoundAutoRequirementAggregator.queryTokens(query).toList())

    private fun eventQueryToken(rawTokens: List<String>, kind: AudioAssetKind): String? {
        if (kind != AudioAssetKind.SFX) return null
        val meaningful = rawTokens
            .filterNot(LOCAL_QUERY_ANCHORS::contains)
            .filterNot(QUERY_MODIFIERS::contains)
        if (meaningful.size < 2) return null
        val core = coreQueryToken(rawTokens)
        return meaningful.lastOrNull { it != core }
    }

    fun remoteFit(
        need: FreesoundAutoSearchNeed,
        sound: FreesoundSound,
        lexicalCoverage: Double,
        selectedScore: Double,
    ): RemoteFit {
        @Suppress("UNUSED_VARIABLE")
        val rankOnlyScore = selectedScore
        val rawTokens = FreesoundAutoRequirementAggregator.queryTokens(need.query).toList()
        val core = coreQueryToken(rawTokens)
        val event = eventQueryToken(rawTokens, need.kind)
        val remoteTokens = FreesoundAutoRequirementAggregator.queryTokens(
            buildString {
                append(sound.name).append(' ')
                append(sound.description).append(' ')
                append(sound.tags.joinToString(" "))
            },
        )
        val coreCoverage = if (core != null && core in remoteTokens) 1.0 else 0.0
        val eventCoverage = if (event != null && event in remoteTokens) 1.0 else 0.0
        val lexical = lexicalCoverage.coerceIn(0.0, 1.0)
        val corePresent = coreCoverage >= CORE_PRESENT_COVERAGE
        val eventPresent = eventCoverage >= CORE_PRESENT_COVERAGE
        val qualified = when (need.kind) {
            AudioAssetKind.SFX -> if (event != null) {
                lexical >= REMOTE_SFX_STRONG_QUERY_COVERAGE ||
                    (lexical >= REMOTE_SFX_RELAXED_QUERY_COVERAGE && (corePresent || eventPresent))
            } else {
                lexical >= RAW_MIN_QUERY_COVERAGE ||
                    (corePresent && lexical >= CORE_ONLY_MIN_QUERY_COVERAGE)
            }
            else -> lexical >= LEGACY_MIN_QUERY_COVERAGE
        }
        val fit = if (!qualified) {
            0.0
        } else if (need.kind == AudioAssetKind.SFX && event != null) {
            // Do not require both exact source and exact event words. A strong combined lexical match
            // is valid evidence on its own; exact core/event tokens add confidence when present.
            (coreCoverage * 0.24 + eventCoverage * 0.24 + lexical * 0.52).coerceIn(0.0, 1.0)
        } else {
            (coreCoverage * 0.52 + lexical * 0.48).coerceIn(0.0, 1.0)
        }
        return RemoteFit(
            score = fit,
            coreCoverage = coreCoverage,
            eventCoverage = eventCoverage,
            qualified = qualified,
        )
    }

    private fun score(
        profile: NeedProfile,
        indexed: IndexedTrack,
        currentTrack: SceneMusicTrackEntity,
        nowMillis: Long,
    ): Match? {
        val queryTokens = profile.queryTokens
        val titleQueryCoverage = tokenCoverage(queryTokens, indexed.englishTitleTokens)
        val metadataQueryCoverage = tokenCoverage(queryTokens, indexed.englishMetadataTokens)
        val queryCoverage = max(titleQueryCoverage, metadataQueryCoverage * 0.92)
        val queryAvoidCoverage = tokenCoverage(queryTokens, indexed.englishAvoidTokens)
        val coreCoverage = profile.coreToken?.let { core ->
            max(
                if (core in indexed.englishTitleTokens) 1.0 else 0.0,
                if (core in indexed.englishMetadataTokens) 0.92 else 0.0,
            )
        } ?: 0.0
        val eventCoverage = profile.eventToken?.let { event ->
            max(
                if (event in indexed.englishTitleTokens) 1.0 else 0.0,
                if (event in indexed.englishMetadataTokens) 0.92 else 0.0,
            )
        } ?: 0.0

        val hintAware = profile.hintAware
        val useScore = if (hintAware) average(profile.hints.map { hint ->
            val direct = localSimilarity(hint.use, indexed.sections.use)
            val shade = localSimilarity(hint.use, indexed.sections.shade) * 0.82
            val fallback = localSimilarity(hint.use, indexed.sections.all) * 0.62
            max(direct, max(shade, fallback))
        }) else 0.0
        val shadeScore = if (hintAware) average(profile.hints.map { localSimilarity(it.use, indexed.sections.shade) }) else 0.0
        val allScore = if (hintAware) average(profile.hints.map { localSimilarity(it.use, indexed.sections.all) }) else 0.0
        val hintConflict = if (hintAware) profile.hints.maxOf { hint ->
            max(
                localSimilarity(hint.use, indexed.sections.avoid),
                localSimilarity(hint.avoid, indexed.sections.use),
            )
        } else 0.0
        val avoidCoverage = max(queryAvoidCoverage, hintConflict)
        val contextScore = max(useScore, max(shadeScore * 0.86, allScore * 0.66))
        val semanticMetadata = indexed.sections.structured &&
            (indexed.sections.use.tokens.isNotEmpty() || indexed.sections.shade.tokens.isNotEmpty())
        val metadataQuality = when {
            semanticMetadata -> "STRUCTURED"
            indexed.sections.structured -> "PARTIAL"
            else -> "RAW"
        }

        if (!hintAware && queryCoverage <= 0.0) return null
        if (hintAware && contextScore <= 0.0 && queryCoverage <= 0.0 && coreCoverage <= 0.0) return null

        val structuredBonus = if (semanticMetadata) 0.06 else if (indexed.sections.allText.length >= 24) 0.02 else 0.0
        val titleBonus = titleQueryCoverage * 0.04
        val repetitionPenalty = repetitionPenalty(currentTrack, nowMillis)
        val finalScore = if (hintAware && semanticMetadata) {
            // Rich Vietnamese Dùng/Sắc thái/Tránh metadata: AI local_hint is the main evidence.
            useScore * 0.56 +
                shadeScore * 0.13 +
                allScore * 0.09 +
                queryCoverage * 0.12 +
                structuredBonus +
                titleBonus -
                avoidCoverage * 0.72 -
                repetitionPenalty
        } else {
            // Weak/raw metadata cannot be judged fairly against Vietnamese local_hint. Fall back to
            // the unchanged query/title instead of punishing an otherwise exact asset.
            queryCoverage * 0.78 +
                coreCoverage * 0.16 +
                structuredBonus * 0.30 +
                titleBonus -
                queryAvoidCoverage * 0.62 -
                repetitionPenalty
        }.coerceIn(0.0, 1.0)

        val contextEvidence = if (hintAware && semanticMetadata && contextScore > 0.0) {
            (0.55 + contextScore * 0.45).coerceIn(0.0, 1.0)
        } else 0.0
        val queryEvidence = if (profile.kind == AudioAssetKind.SFX && profile.eventToken != null) {
            (
                coreCoverage * 0.36 +
                    eventCoverage * 0.36 +
                    queryCoverage * 0.23 +
                    titleQueryCoverage * 0.05
                ).coerceIn(0.0, 1.0)
        } else {
            (
                coreCoverage * 0.52 +
                    queryCoverage * 0.43 +
                    titleQueryCoverage * 0.05
                ).coerceIn(0.0, 1.0)
        }
        val selectionScore = (
            max(contextEvidence, queryEvidence) -
                avoidCoverage * 0.45 -
                repetitionPenalty * 0.50
            ).coerceIn(0.0, 1.0)

        val rejectReason = rejectReason(
            kind = profile.kind,
            eventRequired = profile.eventToken != null,
            hintAware = hintAware,
            semanticMetadata = semanticMetadata,
            score = finalScore,
            contextScore = contextScore,
            queryCoverage = queryCoverage,
            coreCoverage = coreCoverage,
            eventCoverage = eventCoverage,
            conflict = avoidCoverage,
        )
        return Match(
            track = currentTrack,
            score = finalScore,
            coverage = queryCoverage,
            contextScore = contextScore,
            anchorCoverage = coreCoverage,
            avoidCoverage = avoidCoverage,
            contextAware = hintAware,
            anchorRequired = profile.coreToken != null,
            useScore = useScore,
            shadeScore = shadeScore,
            allScore = allScore,
            repetitionPenalty = repetitionPenalty,
            accepted = rejectReason.isBlank(),
            rejectReason = rejectReason,
            structured = indexed.sections.structured,
            coreCoverage = coreCoverage,
            eventCoverage = eventCoverage,
            selectionScore = selectionScore,
            metadataQuality = metadataQuality,
        )
    }

    private fun rejectReason(
        kind: AudioAssetKind,
        eventRequired: Boolean,
        hintAware: Boolean,
        semanticMetadata: Boolean,
        score: Double,
        contextScore: Double,
        queryCoverage: Double,
        coreCoverage: Double,
        eventCoverage: Double,
        conflict: Double,
    ): String {
        val reasons = buildList {
            if (conflict >= MAX_CONFLICT) add("conflict>=$MAX_CONFLICT")
            if (!hintAware) {
                if (kind == AudioAssetKind.SFX && eventRequired) {
                    val hasStrongQueryEvidence =
                        queryCoverage >= REMOTE_SFX_STRONG_QUERY_COVERAGE ||
                            (queryCoverage >= REMOTE_SFX_RELAXED_QUERY_COVERAGE &&
                                (coreCoverage >= CORE_PRESENT_COVERAGE || eventCoverage >= CORE_PRESENT_COVERAGE))
                    if (!hasStrongQueryEvidence) add("sfxSourceEventMismatch")
                } else {
                    if (score < LEGACY_MIN_SCORE) add("score<$LEGACY_MIN_SCORE")
                    if (queryCoverage < LEGACY_MIN_QUERY_COVERAGE) add("queryCoverage<$LEGACY_MIN_QUERY_COVERAGE")
                }
            } else if (semanticMetadata) {
                val semanticPass =
                    contextScore >= CONTEXT_ONLY_SCORE ||
                    (
                        contextScore >= BALANCED_CONTEXT_SCORE &&
                            queryCoverage >= BALANCED_QUERY_COVERAGE &&
                            score >= BALANCED_MIN_SCORE
                        ) ||
                    (queryCoverage >= EXACT_QUERY_COVERAGE && coreCoverage >= CORE_PRESENT_COVERAGE)
                if (!semanticPass) {
                    add(
                        "semanticEvidence<context=$CONTEXT_ONLY_SCORE" +
                            "|balanced=$BALANCED_CONTEXT_SCORE+$BALANCED_QUERY_COVERAGE+$BALANCED_MIN_SCORE" +
                            "|exactQuery=$EXACT_QUERY_COVERAGE",
                    )
                }
            } else {
                val queryPass = if (kind == AudioAssetKind.SFX && eventRequired) {
                    queryCoverage >= REMOTE_SFX_STRONG_QUERY_COVERAGE ||
                        (queryCoverage >= REMOTE_SFX_RELAXED_QUERY_COVERAGE &&
                            (coreCoverage >= CORE_PRESENT_COVERAGE || eventCoverage >= CORE_PRESENT_COVERAGE))
                } else {
                    queryCoverage >= RAW_MIN_QUERY_COVERAGE ||
                        (coreCoverage >= CORE_PRESENT_COVERAGE && queryCoverage >= CORE_ONLY_MIN_QUERY_COVERAGE)
                }
                if (!queryPass) {
                    add(
                        if (kind == AudioAssetKind.SFX && eventRequired) "sfxSourceEventMismatch"
                        else "rawQueryEvidence<query=$RAW_MIN_QUERY_COVERAGE|core+$CORE_ONLY_MIN_QUERY_COVERAGE",
                    )
                }
            }
        }
        return reasons.joinToString("+")
    }

    private fun sections(raw: String): Sections {
        val value = raw.trim()
        val lower = value.lowercase(Locale.ROOT)
        val shadeAt = firstMarker(lower, "sắc thái:", "sac thai:")
        val useAt = firstMarker(lower, "dùng:", "dung:")
        val avoidAt = firstMarker(lower, "tránh:", "tranh:")
        val structured = shadeAt >= 0 || useAt >= 0 || avoidAt >= 0
        if (!structured) {
            val allText = localNormalize(value)
            val all = localText(allText)
            return Sections(allText, "", "", "", all, EMPTY_LOCAL_TEXT, EMPTY_LOCAL_TEXT, EMPTY_LOCAL_TEXT, false)
        }

        fun slice(start: Int, markerLength: Int, endCandidates: List<Int>): String {
            if (start < 0) return ""
            val contentStart = (start + markerLength).coerceAtMost(value.length)
            val end = endCandidates.filter { it > contentStart }.minOrNull() ?: value.length
            return localNormalize(value.substring(contentStart, end))
        }

        val shadeText = slice(shadeAt, markerLengthAt(lower, shadeAt, "sắc thái:", "sac thai:"), listOf(useAt, avoidAt))
        val useText = slice(useAt, markerLengthAt(lower, useAt, "dùng:", "dung:"), listOf(avoidAt))
        val avoidText = slice(avoidAt, markerLengthAt(lower, avoidAt, "tránh:", "tranh:"), emptyList())
        val allText = localNormalize(value)
        return Sections(
            allText = allText,
            shadeText = shadeText,
            useText = useText,
            avoidText = avoidText,
            all = localText(allText),
            shade = localText(shadeText),
            use = localText(useText),
            avoid = localText(avoidText),
            structured = true,
        )
    }

    private fun parseLocalHint(raw: String): LocalHint {
        val value = raw.trim()
        val lower = value.lowercase(Locale.ROOT)
        val useAt = firstMarker(lower, "dùng:", "dung:")
        val avoidAt = firstMarker(lower, "tránh:", "tranh:")
        if (useAt < 0 && avoidAt < 0) {
            return LocalHint(raw = value, use = localText(localNormalize(value)), avoid = EMPTY_LOCAL_TEXT)
        }

        val useMarkerLength = markerLengthAt(lower, useAt, "dùng:", "dung:")
        val avoidMarkerLength = markerLengthAt(lower, avoidAt, "tránh:", "tranh:")
        val use = if (useAt >= 0) {
            val start = (useAt + useMarkerLength).coerceAtMost(value.length)
            val end = avoidAt.takeIf { it > start } ?: value.length
            localNormalize(value.substring(start, end))
        } else ""
        val avoid = if (avoidAt >= 0) {
            val start = (avoidAt + avoidMarkerLength).coerceAtMost(value.length)
            localNormalize(value.substring(start))
        } else ""
        return LocalHint(raw = value, use = localText(use), avoid = localText(avoid))
    }

    private fun firstMarker(lower: String, vararg markers: String): Int = markers
        .map { marker -> lower.indexOf(marker) }
        .filter { it >= 0 }
        .minOrNull()
        ?: -1

    private fun markerLengthAt(lower: String, index: Int, vararg markers: String): Int {
        if (index < 0) return 0
        return markers.firstOrNull { marker -> lower.startsWith(marker, index) }?.length ?: 0
    }

    private fun tokenCoverage(queryTokens: Set<String>, targetTokens: Set<String>): Double {
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0.0
        return queryTokens.count(targetTokens::contains).toDouble() / queryTokens.size.toDouble()
    }

    private fun localSimilarity(first: LocalText, second: LocalText): Double {
        if (first.tokens.isEmpty() || second.tokens.isEmpty()) return 0.0
        val shared = first.tokens.count(second.tokens::contains)
        val firstCoverage = shared.toDouble() / first.tokens.size.toDouble()
        val secondCoverage = shared.toDouble() / second.tokens.size.coerceAtMost(12).toDouble()
        val bigramCoverage = if (first.bigrams.isEmpty()) 0.0 else {
            first.bigrams.count(second.bigrams::contains).toDouble() / first.bigrams.size.toDouble()
        }
        return max(
            firstCoverage * 0.86 + secondCoverage.coerceIn(0.0, 1.0) * 0.14,
            bigramCoverage * 0.94,
        ).coerceIn(0.0, 1.0)
    }

    private fun localText(value: String): LocalText {
        val tokens = localTokens(value)
        return LocalText(tokens = tokens, bigrams = bigrams(tokens.toList()))
    }

    private fun localTokens(value: String): LinkedHashSet<String> = localNormalize(value)
        .split(' ')
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 2 && it !in LOCAL_STOPWORDS }
        .take(MAX_LOCAL_TOKENS)
        .toCollection(linkedSetOf())

    private fun bigrams(tokens: List<String>): Set<String> {
        if (tokens.size < 2) return emptySet()
        return (0 until tokens.lastIndex).mapTo(linkedSetOf()) { index -> "${tokens[index]} ${tokens[index + 1]}" }
    }

    private fun localNormalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun average(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.average()

    private fun repetitionPenalty(track: SceneMusicTrackEntity, nowMillis: Long): Double {
        val playPenalty = track.playCount.coerceIn(0, 20) * 0.003
        val age = (nowMillis - track.lastPlayedAt).coerceAtLeast(0L)
        val recencyPenalty = when {
            track.lastPlayedAt <= 0L -> 0.0
            age < 30L * 60L * 1_000L -> 0.06
            age < 6L * 60L * 60L * 1_000L -> 0.03
            else -> 0.0
        }
        return (playPenalty + recencyPenalty).coerceAtMost(0.10)
    }

    private fun emitDiagnostics(
        need: FreesoundAutoSearchNeed,
        index: LibraryIndex,
        evaluation: Evaluation,
    ) {
        val context = appContext as? NgheTruyenApplication ?: return
        val hints = need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        val hintText = hints.joinToString(" || ")
        val diagnosticKey = "${index.fingerprint}|${need.kind.name}|${need.query}|$hintText"
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val previous = recentDiagnosticAt[diagnosticKey] ?: 0L
            if (now - previous < DIAGNOSTIC_DEDUP_MS) return
            recentDiagnosticAt[diagnosticKey] = now
            while (recentDiagnosticAt.size > MAX_RECENT_DIAGNOSTICS) {
                recentDiagnosticAt.remove(recentDiagnosticAt.keys.first())
            }
        }

        val traceId = "mode3-local:${UUID.randomUUID()}"
        val best = evaluation.topCandidates.firstOrNull()
        mark(
            context = context,
            name = "MODE3_LOCAL_MATCH_EVAL",
            traceId = traceId,
            attributes = mapOf(
                "kind" to need.kind.name,
                "query" to need.query.take(180),
                "usages" to need.usages.size.toString(),
                "localHintPresent" to hints.isNotEmpty().toString(),
                "localHintCount" to hints.size.toString(),
                "localHintChars" to hintText.length.toString(),
                "localHint" to hintText.take(MAX_DIAGNOSTIC_HINT_CHARS),
                "indexedTracks" to evaluation.indexedTracks.toString(),
                "candidateTracks" to evaluation.candidateTracks.toString(),
                "indexCacheHit" to evaluation.indexCacheHit.toString(),
                "elapsedMs" to evaluation.elapsedMs.toString(),
                "accepted" to (evaluation.accepted != null).toString(),
                "acceptedTrackId" to evaluation.accepted?.track?.id.orEmpty(),
                "acceptedTitle" to evaluation.accepted?.track?.title.orEmpty().take(180),
                "acceptedScore" to format(evaluation.accepted?.score ?: 0.0),
                "acceptedFit" to format(evaluation.accepted?.selectionScore ?: 0.0),
                "acceptedCoreCoverage" to format(evaluation.accepted?.coreCoverage ?: 0.0),
                "acceptedEventCoverage" to format(evaluation.accepted?.eventCoverage ?: 0.0),
                "acceptedMetadataQuality" to evaluation.accepted?.metadataQuality.orEmpty(),
                "acceptedDecisive" to isDecisive(evaluation.accepted).toString(),
                "bestTrackId" to best?.track?.id.orEmpty(),
                "bestScore" to format(best?.score ?: 0.0),
                "bestFit" to format(best?.selectionScore ?: 0.0),
                "bestCoreCoverage" to format(best?.coreCoverage ?: 0.0),
                "bestEventCoverage" to format(best?.eventCoverage ?: 0.0),
                "bestMetadataQuality" to best?.metadataQuality.orEmpty(),
                "bestRejectReason" to (best?.rejectReason ?: if (evaluation.candidateTracks == 0) "NO_LEXICAL_CANDIDATES" else "NO_SCORED_CANDIDATE"),
                "decisiveContextThreshold" to DECISIVE_CONTEXT_SCORE.toString(),
                "contextOnlyThreshold" to CONTEXT_ONLY_SCORE.toString(),
                "localNetworkSkipFit" to LOCAL_NETWORK_SKIP_FIT.toString(),
                "balancedContextThreshold" to BALANCED_CONTEXT_SCORE.toString(),
                "rawQueryThreshold" to RAW_MIN_QUERY_COVERAGE.toString(),
                "coreOnlyMinQueryCoverage" to CORE_ONLY_MIN_QUERY_COVERAGE.toString(),
                "legacyScoreThreshold" to LEGACY_MIN_SCORE.toString(),
                "maxConflict" to MAX_CONFLICT.toString(),
            ),
        )

        evaluation.topCandidates.forEachIndexed { rank, candidate ->
            mark(
                context = context,
                name = "MODE3_LOCAL_CANDIDATE",
                traceId = traceId,
                attributes = mapOf(
                    "rank" to (rank + 1).toString(),
                    "kind" to need.kind.name,
                    "query" to need.query.take(180),
                    "trackId" to candidate.track.id,
                    "title" to candidate.track.title.take(180),
                    "accepted" to candidate.accepted.toString(),
                    "decisive" to isDecisive(candidate).toString(),
                    "rejectReason" to candidate.rejectReason,
                    "score" to format(candidate.score),
                    "selectionFit" to format(candidate.selectionScore),
                    "queryCoverage" to format(candidate.coverage),
                    "coreCoverage" to format(candidate.coreCoverage),
                    "eventCoverage" to format(candidate.eventCoverage),
                    "contextScore" to format(candidate.contextScore),
                    "useScore" to format(candidate.useScore),
                    "shadeScore" to format(candidate.shadeScore),
                    "allScore" to format(candidate.allScore),
                    "conflict" to format(candidate.avoidCoverage),
                    "repetitionPenalty" to format(candidate.repetitionPenalty),
                    "structuredMetadata" to candidate.structured.toString(),
                    "metadataQuality" to candidate.metadataQuality,
                    "metadataPreview" to candidate.track.tagsCsv.replace(Regex("\\s+"), " ").trim().take(MAX_DIAGNOSTIC_METADATA_CHARS),
                ),
            )
        }

        mark(
            context = context,
            name = if (evaluation.accepted != null) "MODE3_LOCAL_MATCH_ACCEPTED" else "MODE3_LOCAL_MATCH_MISS",
            traceId = traceId,
            attributes = mapOf(
                "kind" to need.kind.name,
                "query" to need.query.take(180),
                "acceptedTrackId" to evaluation.accepted?.track?.id.orEmpty(),
                "acceptedTitle" to evaluation.accepted?.track?.title.orEmpty().take(180),
                "acceptedScore" to format(evaluation.accepted?.score ?: 0.0),
                "acceptedFit" to format(evaluation.accepted?.selectionScore ?: 0.0),
                "acceptedDecisive" to isDecisive(evaluation.accepted).toString(),
                "candidateTracks" to evaluation.candidateTracks.toString(),
                "indexedTracks" to evaluation.indexedTracks.toString(),
                "elapsedMs" to evaluation.elapsedMs.toString(),
                "fallback" to if (evaluation.accepted == null) "FREESOUND" else "NONE",
            ),
        )
    }

    private fun mark(
        context: NgheTruyenApplication,
        name: String,
        traceId: String,
        attributes: Map<String, String>,
    ) {
        runCatching {
            context.container.sourceDiagnostics.mark(
                name = name,
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                sourceId = "freesound",
                traceId = traceId,
                attributes = attributes,
            )
        }
    }

    private fun format(value: Double): String = "%.3f".format(Locale.US, value)

    private const val DECISIVE_CONTEXT_SCORE = 0.56
    private const val CONTEXT_ONLY_SCORE = 0.40
    private const val LOCAL_NETWORK_SKIP_FIT = 0.80
    private const val BALANCED_CONTEXT_SCORE = 0.38
    private const val BALANCED_QUERY_COVERAGE = 0.33
    private const val BALANCED_MIN_SCORE = 0.40
    private const val EXACT_QUERY_COVERAGE = 0.85
    private const val RAW_MIN_QUERY_COVERAGE = 0.78
    private const val REMOTE_SFX_STRONG_QUERY_COVERAGE = 0.78
    private const val REMOTE_SFX_RELAXED_QUERY_COVERAGE = 0.60
    private const val CORE_PRESENT_COVERAGE = 0.92
    private const val CORE_ONLY_MIN_QUERY_COVERAGE = 0.32
    private const val LEGACY_MIN_SCORE = 0.56
    private const val LEGACY_MIN_QUERY_COVERAGE = 0.50
    private const val MAX_CONFLICT = 0.48
    private const val MAX_LOCAL_TOKENS = 72
    private const val MAX_INDEX_CACHE_ENTRIES = 6
    private const val MAX_DIAGNOSTIC_CANDIDATES = 5
    private const val MAX_DIAGNOSTIC_HINT_CHARS = 900
    private const val MAX_DIAGNOSTIC_METADATA_CHARS = 700
    private const val MAX_RECENT_DIAGNOSTICS = 100
    private const val DIAGNOSTIC_DEDUP_MS = 1_500L

    private val EMPTY_LOCAL_TEXT = LocalText(linkedSetOf(), emptySet())

    private val LOCAL_QUERY_ANCHORS = setOf(
        "music", "cinematic", "background", "audio", "sound", "effect", "ambience", "ambient",
    )

    private val QUERY_MODIFIERS = setOf(
        "light", "quiet", "peaceful", "sad", "romantic", "tense", "heavy", "soft", "gentle",
        "distant", "far", "near", "close", "night", "day", "dark", "bright", "slow", "fast",
        "deep", "warm", "cold", "dramatic", "epic", "strong", "intense", "mysterious", "eerie",
        "emotional", "suspenseful", "calm", "melancholic", "happy", "angry", "scary", "creepy", "wet",
        "magic", "magical", "mind", "mental", "psychic",
    )

    private val LOCAL_STOPWORDS = setOf(
        "và", "hoặc", "nhưng", "của", "cho", "trong", "ngoài", "khi", "sau", "trước", "với", "đến",
        "đang", "được", "không", "một", "những", "các", "này", "đó", "thì", "mà", "theo", "rất", "hơi",
        "and", "or", "the", "a", "an", "with", "for", "from", "into", "this", "that",
    )
}
