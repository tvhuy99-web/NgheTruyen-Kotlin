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
 * Selects an existing Mode-3 library asset before any Freesound request is made.
 *
 * The short English Freesound query remains untouched. New AI responses additionally carry a short
 * Vietnamese local hint in each usage, formatted as "Dùng: ...; Tránh: ...". Local matching compares
 * that hint directly with the asset metadata fields Sắc thái / Dùng / Tránh. There is deliberately no
 * English↔Vietnamese dictionary and no attempt to infer a replacement description from story text.
 *
 * Performance rule: expensive metadata parsing/tokenization is cached per library fingerprint. Each
 * need then evaluates only tracks sharing at least one relevant English/local token. This keeps local
 * lookup cheaper than rebuilding metadata for the whole library on every 2–3 word query.
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
    )

    data class Evaluation(
        val accepted: Match?,
        val topCandidates: List<Match>,
        val indexedTracks: Int,
        val candidateTracks: Int,
        val elapsedMs: Long,
        val indexCacheHit: Boolean,
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
        val queryTokens: Set<String>,
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
        val ranked = candidateIndices.asSequence()
            .mapNotNull { entryIndex -> score(profile, index.entries[entryIndex], nowMillis) }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenByDescending { it.contextScore }
                    .thenByDescending { it.coverage },
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
        return score(profile, indexTrack(track), nowMillis)?.takeIf(Match::accepted)
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
        tracks.forEach { track ->
            hash = hash * 31L + track.id.hashCode()
            hash = hash * 31L + track.title.hashCode()
            hash = hash * 31L + track.tagsCsv.hashCode()
            hash = hash * 31L + track.playCount
            hash = hash * 31L + track.lastPlayedAt.hashCode()
            hash = hash * 31L + if (track.enabled) 1 else 0
        }
        return "${kind.name}-${tracks.size}-${java.lang.Long.toUnsignedString(hash, 16)}"
    }

    private fun candidateIndices(profile: NeedProfile, index: LibraryIndex): Set<Int> {
        val candidates = linkedSetOf<Int>()
        profile.queryTokens.forEach { token -> index.englishInverted[token]?.let(candidates::addAll) }
        profile.localCandidateTokens.forEach { token -> index.localInverted[token]?.let(candidates::addAll) }
        return candidates
    }

    private fun needProfile(need: FreesoundAutoSearchNeed): NeedProfile {
        val rawQueryTokens = FreesoundAutoRequirementAggregator.queryTokens(need.query)
        val queryTokens = rawQueryTokens.filterNot(LOCAL_QUERY_ANCHORS::contains).toSet().ifEmpty { rawQueryTokens }
        val hints = need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map(::parseLocalHint)
            .filter(LocalHint::isPresent)
            .toList()
        return NeedProfile(queryTokens = queryTokens, hints = hints)
    }

    private fun score(
        profile: NeedProfile,
        indexed: IndexedTrack,
        nowMillis: Long,
    ): Match? {
        val queryTokens = profile.queryTokens
        val titleQueryCoverage = tokenCoverage(queryTokens, indexed.englishTitleTokens)
        val metadataQueryCoverage = tokenCoverage(queryTokens, indexed.englishMetadataTokens)
        val queryCoverage = max(titleQueryCoverage, metadataQueryCoverage * 0.92)
        val queryAvoidCoverage = tokenCoverage(queryTokens, indexed.englishAvoidTokens)

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

        if (!hintAware && queryCoverage <= 0.0) return null
        if (hintAware && contextScore <= 0.0 && queryCoverage <= 0.0) return null

        val structuredBonus = if (indexed.sections.structured) 0.06 else if (indexed.sections.allText.length >= 24) 0.02 else 0.0
        val titleBonus = titleQueryCoverage * 0.04
        val repetitionPenalty = repetitionPenalty(indexed.track, nowMillis)
        val finalScore = if (hintAware) {
            useScore * 0.56 +
                shadeScore * 0.13 +
                allScore * 0.09 +
                queryCoverage * 0.12 +
                structuredBonus +
                titleBonus -
                avoidCoverage * 0.72 -
                repetitionPenalty
        } else {
            queryCoverage * 0.84 +
                structuredBonus * 0.35 +
                titleBonus -
                queryAvoidCoverage * 0.62 -
                repetitionPenalty
        }.coerceIn(0.0, 1.0)

        val rejectReason = rejectReason(
            hintAware = hintAware,
            score = finalScore,
            contextScore = contextScore,
            queryCoverage = queryCoverage,
            conflict = avoidCoverage,
        )
        return Match(
            track = indexed.track,
            score = finalScore,
            coverage = queryCoverage,
            contextScore = contextScore,
            anchorCoverage = queryCoverage,
            avoidCoverage = avoidCoverage,
            contextAware = hintAware,
            anchorRequired = false,
            useScore = useScore,
            shadeScore = shadeScore,
            allScore = allScore,
            repetitionPenalty = repetitionPenalty,
            accepted = rejectReason.isBlank(),
            rejectReason = rejectReason,
            structured = indexed.sections.structured,
        )
    }

    private fun rejectReason(
        hintAware: Boolean,
        score: Double,
        contextScore: Double,
        queryCoverage: Double,
        conflict: Double,
    ): String {
        val reasons = buildList {
            if (hintAware) {
                if (score < HINT_MIN_SCORE) add("score<$HINT_MIN_SCORE")
                if (contextScore < HINT_MIN_CONTEXT_SCORE) add("context<$HINT_MIN_CONTEXT_SCORE")
            } else {
                if (score < LEGACY_MIN_SCORE) add("score<$LEGACY_MIN_SCORE")
                if (queryCoverage < LEGACY_MIN_QUERY_COVERAGE) add("queryCoverage<$LEGACY_MIN_QUERY_COVERAGE")
            }
            if (conflict >= MAX_CONFLICT) add("conflict>=$MAX_CONFLICT")
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
                "bestTrackId" to best?.track?.id.orEmpty(),
                "bestScore" to format(best?.score ?: 0.0),
                "bestRejectReason" to (best?.rejectReason ?: if (evaluation.candidateTracks == 0) "NO_LEXICAL_CANDIDATES" else "NO_SCORED_CANDIDATE"),
                "hintScoreThreshold" to HINT_MIN_SCORE.toString(),
                "hintContextThreshold" to HINT_MIN_CONTEXT_SCORE.toString(),
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
                    "rejectReason" to candidate.rejectReason,
                    "score" to format(candidate.score),
                    "queryCoverage" to format(candidate.coverage),
                    "contextScore" to format(candidate.contextScore),
                    "useScore" to format(candidate.useScore),
                    "shadeScore" to format(candidate.shadeScore),
                    "allScore" to format(candidate.allScore),
                    "conflict" to format(candidate.avoidCoverage),
                    "repetitionPenalty" to format(candidate.repetitionPenalty),
                    "structuredMetadata" to candidate.structured.toString(),
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

    private const val HINT_MIN_SCORE = 0.46
    private const val HINT_MIN_CONTEXT_SCORE = 0.36
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

    private val LOCAL_STOPWORDS = setOf(
        "và", "hoặc", "nhưng", "của", "cho", "trong", "ngoài", "khi", "sau", "trước", "với", "đến",
        "đang", "được", "không", "một", "những", "các", "này", "đó", "thì", "mà", "theo", "rất", "hơi",
        "and", "or", "the", "a", "an", "with", "for", "from", "into", "this", "that",
    )
}
