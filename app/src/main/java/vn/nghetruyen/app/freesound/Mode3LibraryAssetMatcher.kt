package vn.nghetruyen.app.freesound

import java.util.Locale
import kotlin.math.max
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

/**
 * Selects an existing Mode-3 library asset before any Freesound request is made.
 *
 * The short English [FreesoundAutoSearchNeed.query] is still the network query and is never expanded
 * or rewritten here. New AI responses additionally carry a short Vietnamese local hint in each
 * usage, formatted as "Dùng: ...; Tránh: ...". Local matching compares that hint directly with the
 * asset metadata fields Sắc thái / Dùng / Tránh. There is deliberately no English↔Vietnamese
 * dictionary and no attempt to infer a new scene description from story text inside the app.
 *
 * This class only chooses a concrete file. It never changes timeline boundaries, repeat_count,
 * cadence, looping, number of layers, coverage or any other decision owned by the narration AI.
 */
internal object Mode3LibraryAssetMatcher {
    data class Match(
        val track: SceneMusicTrackEntity,
        val score: Double,
        /** Exact lexical support from the unchanged short English query. */
        val coverage: Double,
        /** Direct match of the AI-provided Vietnamese local hint against library metadata. */
        val contextScore: Double,
        /** Kept for source compatibility; no bilingual acoustic-anchor bridge is used anymore. */
        val anchorCoverage: Double,
        /** Strongest semantic conflict between Dùng/Tránh on the two sides. */
        val avoidCoverage: Double,
        /** True when the AI returned a non-empty local_hint. */
        val contextAware: Boolean,
        /** Kept for source compatibility. Local hints, not hard-coded anchors, drive new matching. */
        val anchorRequired: Boolean = false,
    )

    private data class Sections(
        val all: String,
        val shade: String,
        val use: String,
        val avoid: String,
        val structured: Boolean,
    )

    private data class LocalHint(
        val use: String,
        val avoid: String,
    ) {
        val isPresent: Boolean get() = use.isNotBlank() || avoid.isNotBlank()
    }

    fun bestMatch(
        need: FreesoundAutoSearchNeed,
        tracks: List<SceneMusicTrackEntity>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? = tracks.asSequence()
        .filter { it.enabled && AudioAssetClassifier.classify(it) == need.kind }
        .mapNotNull { score(need, it, nowMillis) }
        .maxWithOrNull(
            compareBy<Match> { it.score }
                .thenBy { it.contextScore }
                .thenBy { it.coverage },
        )
        ?.takeIf(::isStrongEnough)

    fun strongMatch(
        need: FreesoundAutoSearchNeed,
        track: SceneMusicTrackEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? = score(need, track, nowMillis)?.takeIf(::isStrongEnough)

    private fun score(
        need: FreesoundAutoSearchNeed,
        track: SceneMusicTrackEntity,
        nowMillis: Long,
    ): Match? {
        if (!track.enabled || AudioAssetClassifier.classify(track) != need.kind) return null
        val sections = sections(track.tagsCsv)

        // Query scoring is intentionally plain English lexical overlap only. It remains useful for
        // English titles/raw Freesound metadata, but it no longer tries to translate into Vietnamese.
        val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(need.query)
            .filterNot(LOCAL_QUERY_ANCHORS::contains)
            .toSet()
            .ifEmpty { FreesoundAutoRequirementAggregator.queryTokens(need.query) }
        if (queryTokens.isEmpty()) return null
        val titleQueryCoverage = englishQueryCoverage(queryTokens, track.title)
        val metadataQueryCoverage = englishQueryCoverage(queryTokens, track.tagsCsv)
        val queryCoverage = max(titleQueryCoverage, metadataQueryCoverage * 0.92)
        val queryAvoidCoverage = englishQueryCoverage(queryTokens, sections.avoid)

        val hints = need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::parseLocalHint)
            .filter(LocalHint::isPresent)
            .distinct()
            .toList()
        val hintAware = hints.isNotEmpty()

        val useScore = if (hintAware) average(hints.map { hint ->
            val direct = localSimilarity(hint.use, sections.use)
            val shade = localSimilarity(hint.use, sections.shade) * 0.82
            val fallback = localSimilarity(hint.use, sections.all) * 0.62
            max(direct, max(shade, fallback))
        }) else 0.0
        val shadeScore = if (hintAware) average(hints.map { localSimilarity(it.use, sections.shade) }) else 0.0
        val allScore = if (hintAware) average(hints.map { localSimilarity(it.use, sections.all) }) else 0.0

        // Two conflict directions matter:
        // 1) AI says this scene should use X, while the track explicitly says avoid X.
        // 2) AI says avoid X, while the track explicitly says it is for X.
        val hintConflict = if (hintAware) hints.maxOf { hint ->
            max(
                localSimilarity(hint.use, sections.avoid),
                localSimilarity(hint.avoid, sections.use),
            )
        } else 0.0
        val avoidCoverage = max(queryAvoidCoverage, hintConflict)
        val contextScore = max(useScore, max(shadeScore * 0.86, allScore * 0.66))

        if (!hintAware && queryCoverage <= 0.0) return null
        if (hintAware && contextScore <= 0.0 && queryCoverage <= 0.0) return null

        val structuredBonus = if (sections.structured) 0.06 else if (sections.all.length >= 24) 0.02 else 0.0
        val titleBonus = titleQueryCoverage * 0.04
        val repetitionPenalty = repetitionPenalty(track, nowMillis)
        val finalScore = if (hintAware) {
            // AI-written local_hint is the primary local-selection signal. Query remains a small
            // supporting signal so previously downloaded English-described assets are still reusable.
            useScore * 0.56 +
                shadeScore * 0.13 +
                allScore * 0.09 +
                queryCoverage * 0.12 +
                structuredBonus +
                titleBonus -
                avoidCoverage * 0.72 -
                repetitionPenalty
        } else {
            // Compatibility path for old cached requirements that predate local_hint. No bilingual
            // expansion occurs; if English query/title/metadata do not match strongly, fall through
            // to the unchanged Freesound pipeline instead of guessing a Vietnamese local asset.
            queryCoverage * 0.84 +
                structuredBonus * 0.35 +
                titleBonus -
                queryAvoidCoverage * 0.62 -
                repetitionPenalty
        }.coerceIn(0.0, 1.0)

        return Match(
            track = track,
            score = finalScore,
            coverage = queryCoverage,
            contextScore = contextScore,
            anchorCoverage = queryCoverage,
            avoidCoverage = avoidCoverage,
            contextAware = hintAware,
            anchorRequired = false,
        )
    }

    private fun isStrongEnough(match: Match): Boolean {
        if (!match.contextAware) {
            return match.score >= LEGACY_MIN_SCORE &&
                match.coverage >= LEGACY_MIN_QUERY_COVERAGE &&
                match.avoidCoverage < MAX_CONFLICT
        }
        return match.score >= HINT_MIN_SCORE &&
            match.contextScore >= HINT_MIN_CONTEXT_SCORE &&
            match.avoidCoverage < MAX_CONFLICT
    }

    private fun sections(raw: String): Sections {
        val value = raw.trim()
        val lower = value.lowercase(Locale.ROOT)
        val shadeAt = firstMarker(lower, "sắc thái:", "sac thai:")
        val useAt = firstMarker(lower, "dùng:", "dung:")
        val avoidAt = firstMarker(lower, "tránh:", "tranh:")
        val structured = shadeAt >= 0 || useAt >= 0 || avoidAt >= 0
        if (!structured) {
            val all = localNormalize(value)
            return Sections(all = all, shade = "", use = "", avoid = "", structured = false)
        }

        fun slice(start: Int, markerLength: Int, endCandidates: List<Int>): String {
            if (start < 0) return ""
            val contentStart = (start + markerLength).coerceAtMost(value.length)
            val end = endCandidates.filter { it > contentStart }.minOrNull() ?: value.length
            return localNormalize(value.substring(contentStart, end))
        }

        val shadeMarkerLength = markerLengthAt(lower, shadeAt, "sắc thái:", "sac thai:")
        val useMarkerLength = markerLengthAt(lower, useAt, "dùng:", "dung:")
        val avoidMarkerLength = markerLengthAt(lower, avoidAt, "tránh:", "tranh:")
        return Sections(
            all = localNormalize(value),
            shade = slice(shadeAt, shadeMarkerLength, listOf(useAt, avoidAt)),
            use = slice(useAt, useMarkerLength, listOf(avoidAt)),
            avoid = slice(avoidAt, avoidMarkerLength, emptyList()),
            structured = true,
        )
    }

    private fun parseLocalHint(raw: String): LocalHint {
        val value = raw.trim()
        val lower = value.lowercase(Locale.ROOT)
        val useAt = firstMarker(lower, "dùng:", "dung:")
        val avoidAt = firstMarker(lower, "tránh:", "tranh:")
        if (useAt < 0 && avoidAt < 0) {
            // Tolerate an old/partial AI response: treat the whole short hint as positive evidence.
            return LocalHint(use = localNormalize(value), avoid = "")
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
        return LocalHint(use = use, avoid = avoid)
    }

    private fun firstMarker(lower: String, vararg markers: String): Int = markers
        .map(lower::indexOf)
        .filter { it >= 0 }
        .minOrNull()
        ?: -1

    private fun markerLengthAt(lower: String, index: Int, vararg markers: String): Int {
        if (index < 0) return 0
        return markers.firstOrNull { marker -> lower.startsWith(marker, index) }?.length ?: 0
    }

    private fun englishQueryCoverage(queryTokens: Set<String>, text: String): Double {
        if (queryTokens.isEmpty() || text.isBlank()) return 0.0
        val target = FreesoundAutoRequirementAggregator.queryTokens(text)
        if (target.isEmpty()) return 0.0
        return queryTokens.count(target::contains).toDouble() / queryTokens.size.toDouble()
    }

    /**
     * Accent-preserving Vietnamese lexical similarity. Because both sides are generated/written in
     * Vietnamese, keeping accents prevents collisions such as cửa/của, tối/tôi, mưa/mua and nổ/nó.
     */
    private fun localSimilarity(first: String, second: String): Double {
        if (first.isBlank() || second.isBlank()) return 0.0
        val firstTokens = localTokens(first)
        val secondTokens = localTokens(second)
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0
        val shared = firstTokens.count(secondTokens::contains)
        val firstCoverage = shared.toDouble() / firstTokens.size.toDouble()
        val secondCoverage = shared.toDouble() / secondTokens.size.coerceAtMost(12).toDouble()

        val firstBigrams = bigrams(firstTokens.toList())
        val secondBigrams = bigrams(secondTokens.toList())
        val bigramCoverage = if (firstBigrams.isEmpty()) 0.0 else {
            firstBigrams.count(secondBigrams::contains).toDouble() / firstBigrams.size.toDouble()
        }
        return max(
            firstCoverage * 0.86 + secondCoverage.coerceIn(0.0, 1.0) * 0.14,
            bigramCoverage * 0.94,
        ).coerceIn(0.0, 1.0)
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
        return (0 until tokens.lastIndex).mapTo(linkedSetOf()) { index ->
            "${tokens[index]} ${tokens[index + 1]}"
        }
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

    private const val HINT_MIN_SCORE = 0.46
    private const val HINT_MIN_CONTEXT_SCORE = 0.36
    private const val LEGACY_MIN_SCORE = 0.56
    private const val LEGACY_MIN_QUERY_COVERAGE = 0.50
    private const val MAX_CONFLICT = 0.48
    private const val MAX_LOCAL_TOKENS = 72

    private val LOCAL_QUERY_ANCHORS = setOf(
        "music", "cinematic", "background", "audio", "sound", "effect", "ambience", "ambient",
    )

    private val LOCAL_STOPWORDS = setOf(
        "và", "hoặc", "nhưng", "của", "cho", "trong", "ngoài", "khi", "sau", "trước", "với", "đến",
        "đang", "được", "không", "một", "những", "các", "này", "đó", "thì", "mà", "theo", "rất", "hơi",
        "and", "or", "the", "a", "an", "with", "for", "from", "into", "this", "that",
    )
}
