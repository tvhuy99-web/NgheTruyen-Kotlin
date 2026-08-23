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
 * Source-neutral matcher used by Mode 3.
 *
 * Local semantic evidence is intentionally DESCRIPTION-ONLY: title/file name never contributes
 * to local matching. Freesound remote matching is handled separately and may use name + description
 * + tags because remote metadata quality is less predictable.
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
        val coreCoverage: Double = 0.0,
        val eventCoverage: Double = 0.0,
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
        val sourceConceptCoverage: Double = 0.0,
        val sourceConflictConfidence: Double = 0.0,
    )

    private data class LocalText(
        val text: String,
    ) {
        val isPresent: Boolean get() = text.isNotBlank()
    }

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
        val positive: LocalText = all,
    )

    private data class LocalHint(
        val shadeText: String,
        val useText: String,
        val avoidText: String,
        val shade: LocalText,
        val use: LocalText,
        val avoid: LocalText,
        val positive: LocalText = use,
    ) {
        val isPresent: Boolean
            get() = shade.isPresent || use.isPresent || avoid.isPresent
    }

    private data class NeedProfile(
        val kind: AudioAssetKind,
        val queryTokens: Set<String>,
        val coreToken: String?,
        val eventToken: String?,
        val queryText: LocalText,
        val hints: List<LocalHint>,
    ) {
        val hintAware: Boolean get() = hints.isNotEmpty()
    }

    private data class IndexedTrack(
        val key: String,
        val track: SceneMusicTrackEntity,
        val metadataTokens: Set<String>,
        val sections: Sections,
    )

    @Volatile
    private var appContext: Context? = null

    private val cacheLock = Any()
    private val trackCache = object : LinkedHashMap<String, IndexedTrack>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IndexedTrack>?): Boolean =
            size > MAX_TRACK_CACHE_ENTRIES
    }
    private val recentDiagnosticAt = LinkedHashMap<String, Long>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        Mode3E5SemanticEngine.initialize(context.applicationContext)
    }

    fun prewarmSemanticIndex(tracks: List<SceneMusicTrackEntity>) {
        val passages = tracks.asSequence()
            .filter { it.enabled }
            .map { indexTrackCached(it).first.sections }
            .flatMap(Sections::semanticPassages)
            .toList()
        Mode3E5SemanticEngine.requestPrewarm(passages)
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
        val profile = needProfile(need)
        var cacheHits = 0
        val eligible = tracks.asSequence()
            .filter { it.enabled && AudioAssetClassifier.classify(it) == need.kind }
            .map { track ->
                val (indexed, hit) = indexTrackCached(track)
                if (hit) cacheHits += 1
                indexed
            }
            .toList()

        val semanticPassages = eligible.asSequence()
            .flatMap { it.sections.semanticPassages() }
            .distinct()
            .toList()
        val e5Ready = Mode3E5SemanticEngine.status().ready
        val fullyIndexed = e5Ready && semanticPassages.isNotEmpty() &&
            Mode3E5SemanticEngine.allPassagesCached(semanticPassages)
        if (!fullyIndexed) {
            if (semanticPassages.isNotEmpty()) Mode3E5SemanticEngine.requestPrewarm(semanticPassages)
            val evaluation = Evaluation(
                accepted = null,
                topCandidates = emptyList(),
                indexedTracks = eligible.size,
                candidateTracks = 0,
                elapsedMs = (System.nanoTime() - started) / 1_000_000L,
                indexCacheHit = eligible.isNotEmpty() && cacheHits == eligible.size,
            )
            emitDiagnostics(need, evaluation)
            return evaluation
        }

        val ranked = eligible.asSequence()
            .mapNotNull { indexed -> score(profile, indexed, indexed.track, nowMillis) }
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
            indexedTracks = eligible.size,
            candidateTracks = ranked.size,
            elapsedMs = (System.nanoTime() - started) / 1_000_000L,
            indexCacheHit = eligible.isNotEmpty() && cacheHits == eligible.size,
        )
        emitDiagnostics(need, evaluation)
        return evaluation
    }

    fun strongMatch(
        need: FreesoundAutoSearchNeed,
        track: SceneMusicTrackEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? {
        if (!track.enabled || AudioAssetClassifier.classify(track) != need.kind) return null
        val profile = needProfile(need)
        val indexed = indexTrackCached(track).first
        val passages = indexed.sections.semanticPassages().toList()
        val ready = Mode3E5SemanticEngine.status().ready && passages.isNotEmpty() &&
            Mode3E5SemanticEngine.allPassagesCached(passages)
        if (!ready) {
            Mode3E5SemanticEngine.requestPrewarm(passages)
            return null
        }
        return score(profile, indexed, track, nowMillis)?.takeIf(Match::accepted)
    }

    fun isDecisive(match: Match?): Boolean {
        if (match == null || !match.accepted || match.avoidCoverage >= HARD_AVOID_CONFLICT) return false
        if (match.metadataQuality != "STRUCTURED") return false
        if (match.selectionScore < DECISIVE_SELECTION_FIT) return false
        if (match.score < DECISIVE_SEMANTIC_SCORE) return false
        if (match.contextScore < DECISIVE_MIN_CONTEXT_SCORE) return false
        return true
    }

    fun remoteFit(
        need: FreesoundAutoSearchNeed,
        sound: FreesoundSound,
        lexicalCoverage: Double,
        selectedScore: Double,
    ): RemoteFit {
        @Suppress("UNUSED_VARIABLE")
        val rankOnlyScore = selectedScore
        val profile = needProfile(need)

        val titleText = sound.name
        val metadataText = buildString {
            append(sound.description).append(' ')
            append(sound.tags.joinToString(" "))
        }
        val remoteText = "$titleText $metadataText"
        val remoteTokens = FreesoundAutoRequirementAggregator.queryTokens(remoteText)
        val titleConcepts = audibleConcepts(titleText)
        val metadataConcepts = audibleConcepts(metadataText)
        val candidateConcepts = linkedSetOf<String>().apply {
            addAll(titleConcepts)
            addAll(metadataConcepts)
        }

        val rawCoreCoverage = profile.coreToken?.let { if (it in remoteTokens) 1.0 else 0.0 } ?: 0.0
        val coreConcepts = profile.coreToken?.let(::audibleConcepts).orEmpty()
        val coreCoverage = if (
            coreConcepts.any(AUDIBLE_SOURCE_CONCEPTS::contains) &&
            coreConcepts.none(candidateConcepts::contains)
        ) 0.0 else rawCoreCoverage

        val rawEventCoverage = profile.eventToken?.let { if (it in remoteTokens) 1.0 else 0.0 } ?: 0.0
        val eventConcepts = profile.eventToken?.let(::audibleConcepts).orEmpty()
        val eventCoverage = if (
            eventConcepts.isNotEmpty() &&
            eventConcepts.none(candidateConcepts::contains)
        ) 0.0 else rawEventCoverage

        val lexical = lexicalCoverage.coerceIn(0.0, 1.0)
        val corePresent = coreCoverage >= CORE_PRESENT_COVERAGE
        val eventPresent = eventCoverage >= CORE_PRESENT_COVERAGE
        val lexicalQualified = when (need.kind) {
            AudioAssetKind.SFX -> if (profile.eventToken != null) {
                lexical >= REMOTE_SFX_STRONG_QUERY_COVERAGE ||
                    (lexical >= REMOTE_SFX_RELAXED_QUERY_COVERAGE && (corePresent || eventPresent))
            } else {
                lexical >= RAW_MIN_QUERY_COVERAGE ||
                    (corePresent && lexical >= CORE_ONLY_MIN_QUERY_COVERAGE)
            }
            else -> lexical >= LEGACY_MIN_QUERY_COVERAGE
        }

        val required = remoteRequiredConcepts(need)
        val sourceCoverage = sourceConceptCoverage(required, candidateConcepts)
        val titleSourceCoverage = sourceConceptCoverage(required, titleConcepts)
        val metadataSourceCoverage = sourceConceptCoverage(required, metadataConcepts)
        val titleSourceConflict = sourceConflictConfidence(required, titleConcepts)
        val metadataSourceConflict = sourceConflictConfidence(required, metadataConcepts)
        val titleSpecializationConflict = unwantedSpecializationConfidence(
            kind = need.kind,
            required = required,
            candidate = titleConcepts,
        )
        val metadataSpecializationConflict = unwantedSpecializationConfidence(
            kind = need.kind,
            required = required,
            candidate = metadataConcepts,
        )

        val requiredSources = required.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)
        val titleSources = titleConcepts.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)
        val partialTitleIdentityConflict = when {
            requiredSources.isEmpty() || titleSources.isEmpty() -> 0.0
            titleSourceCoverage >= REMOTE_TITLE_MIN_SOURCE_COVERAGE -> 0.0
            metadataSourceCoverage >= REMOTE_TITLE_MIN_SOURCE_COVERAGE -> 0.0
            titleSourceCoverage > 0.0 -> 0.90
            else -> 0.96
        }
        val titleIdentityConflict = max(
            partialTitleIdentityConflict,
            max(titleSourceConflict, titleSpecializationConflict),
        )
        val metadataConflict = max(metadataSourceConflict, metadataSpecializationConflict)

        val conceptContext = if (required.isEmpty()) 0.0 else conceptCoverage(required, candidateConcepts)
        val legacyFit = (
            commonSemanticFit(
                kind = need.kind,
                lexicalCoverage = lexical,
                coreCoverage = coreCoverage,
                eventCoverage = eventCoverage,
                sourceCoverage = sourceCoverage,
                hasSourceRequirement = required.any(AUDIBLE_SOURCE_CONCEPTS::contains),
                contextScore = conceptContext,
                hasStructuredContext = profile.hintAware && required.isNotEmpty(),
            ) -
                titleIdentityConflict * REMOTE_TITLE_IDENTITY_PENALTY -
                metadataConflict * REMOTE_METADATA_CONFLICT_PENALTY
        ).coerceIn(0.0, 1.0)
        val semanticNeed = profile.hints.joinToString(" ") { "${it.shadeText} ${it.useText}" }.trim()
            .ifBlank { need.query }
        val e5Fit = Mode3E5SemanticEngine.similarityOrNull(
            queryText = semanticNeed,
            passageText = "$titleText $metadataText",
            allowPassageInference = true,
        )
        val fit = if (e5Fit != null) {
            (legacyFit * REMOTE_LEGACY_WEIGHT + e5Fit * REMOTE_E5_WEIGHT).coerceIn(0.0, 1.0)
        } else legacyFit
        val semanticQualified = e5Fit != null && e5Fit >= REMOTE_E5_MIN_FIT
        val qualified = (lexicalQualified || semanticQualified) &&
            titleIdentityConflict < HARD_REMOTE_TITLE_IDENTITY_CONFLICT &&
            fit >= minimumSelectionFit(need.kind)

        return RemoteFit(
            score = if (qualified) fit else 0.0,
            coreCoverage = coreCoverage,
            eventCoverage = eventCoverage,
            qualified = qualified,
            sourceConceptCoverage = sourceCoverage,
            sourceConflictConfidence = max(titleIdentityConflict, metadataConflict * 0.55),
        )
    }

    private fun indexTrackCached(track: SceneMusicTrackEntity): Pair<IndexedTrack, Boolean> {
        val key = buildString {
            append(track.id).append('|')
            append(track.updatedAt).append('|')
            append(track.enabled).append('|')
            append(track.tagsCsv.hashCode())
        }
        synchronized(cacheLock) {
            trackCache[key]?.let { return it to true }
        }
        val sections = sections(track.tagsCsv)
        val indexed = IndexedTrack(
            key = key,
            track = track,
            metadataTokens = FreesoundAutoRequirementAggregator.queryTokens(track.tagsCsv),
            sections = sections,
        )
        synchronized(cacheLock) {
            trackCache[key] = indexed
        }
        return indexed to false
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
            queryText = localText(need.query),
            hints = hints,
        )
    }

    private fun remoteRequiredConcepts(need: FreesoundAutoSearchNeed): Set<String> = buildSet {
        addAll(audibleConcepts(need.query))
        need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::parseLocalHint)
            .forEach { hint -> addAll(audibleConcepts("${hint.shadeText} ${hint.useText}")) }
    }

    private fun coreQueryToken(rawTokens: List<String>): String? {
        if (rawTokens.isEmpty()) return null
        val meaningful = rawTokens.filterNot(LOCAL_QUERY_ANCHORS::contains)
        return meaningful.firstOrNull { it !in QUERY_MODIFIERS }
            ?: rawTokens.firstOrNull { it !in QUERY_MODIFIERS }
            ?: meaningful.firstOrNull()
            ?: rawTokens.firstOrNull()
    }

    private fun eventQueryToken(rawTokens: List<String>, kind: AudioAssetKind): String? {
        if (kind != AudioAssetKind.SFX) return null
        val meaningful = rawTokens
            .filterNot(LOCAL_QUERY_ANCHORS::contains)
            .filterNot(QUERY_MODIFIERS::contains)
        if (meaningful.size < 2) return null
        val core = coreQueryToken(rawTokens)
        return meaningful.lastOrNull { it != core }
    }

    private fun score(
        profile: NeedProfile,
        indexed: IndexedTrack,
        currentTrack: SceneMusicTrackEntity,
        nowMillis: Long,
    ): Match? {
        val queryCoverage = tokenCoverage(profile.queryTokens, indexed.metadataTokens)
        val coreCoverage = profile.coreToken?.let { core ->
            if (core in indexed.metadataTokens) 1.0 else 0.0
        } ?: 0.0
        val eventCoverage = profile.eventToken?.let { event ->
            if (event in indexed.metadataTokens) 1.0 else 0.0
        } ?: 0.0

        val hintAware = profile.hintAware
        val querySemanticScore = max(
            localSimilarity(profile.queryText, indexed.sections.positive),
            localSimilarity(profile.queryText, indexed.sections.all) * 0.96,
        )
        val useScore = if (hintAware) average(profile.hints.map { hint ->
            val direct = localSimilarity(hint.use, indexed.sections.use)
            val shadeToUse = localSimilarity(hint.shade, indexed.sections.use) * 0.32
            val useToShade = localSimilarity(hint.use, indexed.sections.shade) * 0.42
            val positiveBridge = localSimilarity(hint.positive, indexed.sections.positive) * 0.52
            max(max(direct, shadeToUse), max(useToShade, positiveBridge))
        }) else querySemanticScore
        val shadeScore = if (hintAware) average(profile.hints.map { hint ->
            val expectedShade = if (hint.shade.isPresent) hint.shade else hint.use
            max(
                localSimilarity(expectedShade, indexed.sections.shade),
                localSimilarity(hint.use, indexed.sections.shade) * 0.42,
            )
        }) else querySemanticScore
        val allScore = if (hintAware) average(profile.hints.map { hint ->
            localSimilarity(hint.positive, indexed.sections.positive)
        }) else querySemanticScore

        val candidateAvoidConflict = if (indexed.sections.avoid.isPresent) {
            if (hintAware) {
                average(profile.hints.map { hint ->
                    max(
                        localSimilarity(hint.positive, indexed.sections.avoid),
                        max(
                            localSimilarity(hint.use, indexed.sections.avoid),
                            localSimilarity(hint.shade, indexed.sections.avoid) * 0.78,
                        ),
                    )
                })
            } else {
                localSimilarity(profile.queryText, indexed.sections.avoid)
            }
        } else 0.0
        val needAvoidConflict = if (hintAware) {
            average(profile.hints.map { hint ->
                if (!hint.avoid.isPresent) 0.0 else max(
                    localSimilarity(hint.avoid, indexed.sections.positive),
                    max(
                        localSimilarity(hint.avoid, indexed.sections.use),
                        localSimilarity(hint.avoid, indexed.sections.shade) * 0.78,
                    ),
                )
            })
        } else 0.0
        val avoidCoverage = max(candidateAvoidConflict, needAvoidConflict)

        val contextScore = if (hintAware) {
            max(useScore, max(shadeScore * 0.93, allScore * 0.88))
        } else querySemanticScore
        val semanticMetadata = indexed.sections.structured &&
            (indexed.sections.use.isPresent || indexed.sections.shade.isPresent)
        val metadataQuality = when {
            semanticMetadata -> "STRUCTURED"
            indexed.sections.structured -> "PARTIAL"
            else -> "RAW"
        }

        if (contextScore <= 0.0) return null

        val repetitionPenalty = repetitionPenalty(currentTrack, nowMillis)
        val structuredDescriptionScore = (
            useScore * LOCAL_DESCRIPTION_USE_WEIGHT +
                shadeScore * LOCAL_DESCRIPTION_SHADE_WEIGHT +
                allScore * LOCAL_DESCRIPTION_WHOLE_WEIGHT -
                avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY -
                repetitionPenalty
        ).coerceIn(0.0, 1.0)
        val unstructuredHintScore = (
            allScore - avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY - repetitionPenalty
        ).coerceIn(0.0, 1.0)
        val queryDescriptionScore = (
            querySemanticScore - avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY - repetitionPenalty
        ).coerceIn(0.0, 1.0)

        val selectionScore = when {
            hintAware && semanticMetadata -> structuredDescriptionScore
            hintAware -> unstructuredHintScore
            else -> queryDescriptionScore
        }
        val rejectReason = localRejectReason(
            kind = profile.kind,
            selectionScore = selectionScore,
            conflict = avoidCoverage,
        )
        return Match(
            track = currentTrack,
            score = selectionScore,
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

    private fun localRejectReason(
        kind: AudioAssetKind,
        selectionScore: Double,
        conflict: Double,
    ): String {
        val reasons = buildList {
            if (conflict >= HARD_AVOID_CONFLICT) {
                add("descriptionAvoidConflict>=${HARD_AVOID_CONFLICT}")
            }
            val minimumFit = minimumLocalSelectionFit(kind)
            if (selectionScore < minimumFit) {
                add("descriptionFit<$minimumFit")
            }
        }
        return reasons.joinToString("+")
    }

    private fun minimumLocalSelectionFit(kind: AudioAssetKind): Double = when (kind) {
        AudioAssetKind.SFX -> MIN_LOCAL_SELECTION_FIT_SFX
        AudioAssetKind.MUSIC -> MIN_LOCAL_SELECTION_FIT_MUSIC
        AudioAssetKind.AMBIENCE -> MIN_LOCAL_SELECTION_FIT_AMBIENCE
    }

    private fun sections(raw: String): Sections {
        val clean = stripProvenance(raw)
        val value = clean.trim()
        val lower = value.lowercase(Locale.ROOT)
        val shadeAt = firstMarker(lower, "sắc thái:", "sac thai:")
        val useAt = firstMarker(lower, "dùng:", "dung:")
        val avoidAt = firstMarker(lower, "tránh:", "tranh:")
        val structured = shadeAt >= 0 || useAt >= 0 || avoidAt >= 0
        if (!structured) {
            val allText = semanticText(value)
            val all = localText(allText)
            return Sections(allText, "", "", "", all, EMPTY_LOCAL_TEXT, EMPTY_LOCAL_TEXT, EMPTY_LOCAL_TEXT, false)
        }

        fun slice(start: Int, markerLength: Int, endCandidates: List<Int>): String {
            if (start < 0) return ""
            val contentStart = (start + markerLength).coerceAtMost(value.length)
            val end = endCandidates.filter { it > contentStart }.minOrNull() ?: value.length
            return semanticText(value.substring(contentStart, end))
        }

        val shadeText = slice(shadeAt, markerLengthAt(lower, shadeAt, "sắc thái:", "sac thai:"), listOf(useAt, avoidAt))
        val useText = slice(useAt, markerLengthAt(lower, useAt, "dùng:", "dung:"), listOf(avoidAt))
        val avoidText = slice(avoidAt, markerLengthAt(lower, avoidAt, "tránh:", "tranh:"), emptyList())
        val allText = semanticText(value)
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
            positive = localText("$shadeText $useText"),
        )
    }

    private fun parseLocalHint(raw: String): LocalHint {
        val value = raw.trim()
        val lower = value.lowercase(Locale.ROOT)
        val shadeAt = firstMarker(lower, "sắc thái:", "sac thai:")
        val useAt = firstMarker(lower, "dùng:", "dung:")
        val avoidAt = firstMarker(lower, "tránh:", "tranh:")
        if (shadeAt < 0 && useAt < 0 && avoidAt < 0) {
            val use = semanticText(value)
            return LocalHint("", use, "", EMPTY_LOCAL_TEXT, localText(use), EMPTY_LOCAL_TEXT)
        }

        fun slice(start: Int, markerLength: Int, endCandidates: List<Int>): String {
            if (start < 0) return ""
            val contentStart = (start + markerLength).coerceAtMost(value.length)
            val end = endCandidates.filter { it > contentStart }.minOrNull() ?: value.length
            return semanticText(value.substring(contentStart, end))
        }

        val shadeText = slice(shadeAt, markerLengthAt(lower, shadeAt, "sắc thái:", "sac thai:"), listOf(useAt, avoidAt))
        val useText = slice(useAt, markerLengthAt(lower, useAt, "dùng:", "dung:"), listOf(avoidAt))
        val avoidText = slice(avoidAt, markerLengthAt(lower, avoidAt, "tránh:", "tranh:"), emptyList())
        return LocalHint(
            shadeText = shadeText,
            useText = useText,
            avoidText = avoidText,
            shade = localText(shadeText),
            use = localText(useText),
            avoid = localText(avoidText),
            positive = localText("$shadeText $useText"),
        )
    }

    private fun stripProvenance(value: String): String = value
        .replace(
            Regex("""(?i)(?:^|[,;]\s*)freesound_(?:id|user|license|url)\s*:[^,;]*"""),
            "",
        )
        .replace(
            Regex("""(?i)(?:type\s*[:=]\s*(?:sfx[_-]?continuous|continuous|sfx|sound[_-]?effect|ambience|environment|music))"""),
            "",
        )
        .trim().trim(',', ';').trim()

    private fun audibleConcepts(value: String): Set<String> {
        val normalized = localNormalize(value)
        if (normalized.isBlank()) return emptySet()
        val padded = " $normalized "
        val concepts = AUDIBLE_CONCEPT_ALIASES.asSequence()
            .filter { (_, aliases) -> aliases.any { alias -> padded.contains(" $alias ") } }
            .mapTo(linkedSetOf()) { it.key }

        val firearmContext = FIREARM_CONTEXT_TERMS.any { term -> padded.contains(" $term ") }
        val literalFireEvidence = LITERAL_FIRE_TERMS.any { term -> padded.contains(" $term ") }
        if (firearmContext) {
            concepts += "GUNFIRE"
            if (!literalFireEvidence) concepts -= "FIRE"
        }
        return concepts
    }

    private fun unwantedSpecializationConfidence(
        kind: AudioAssetKind,
        required: Set<String>,
        candidate: Set<String>,
    ): Double {
        val requested = required.filterTo(linkedSetOf(), SPECIALIZATION_CONCEPTS::contains)
        val candidateSpecial = candidate.filterTo(linkedSetOf(), SPECIALIZATION_CONCEPTS::contains)
        val extras = candidateSpecial - requested
        if (extras.isEmpty()) return 0.0

        if (kind == AudioAssetKind.MUSIC) {
            return (0.28 + (extras.size - 1).coerceAtLeast(0) * 0.08).coerceAtMost(0.52)
        }

        val requestedConcrete = required.any(AUDIBLE_SOURCE_CONCEPTS::contains)
        return when {
            extras.size >= 2 -> 1.0
            requestedConcrete -> 0.92
            else -> 0.84
        }
    }

    private fun avoidConceptConflict(positiveConcepts: Set<String>, avoidText: String): Double {
        if (positiveConcepts.isEmpty() || avoidText.isBlank()) return 0.0
        val normalized = localNormalize(avoidText)
        if (normalized.isBlank()) return 0.0
        val padded = " $normalized "
        if (NEGATION_TERMS.any { term -> padded.contains(" $term ") }) return 0.0
        val avoidConcepts = audibleConcepts(normalized)
        if (avoidConcepts.isEmpty()) return 0.0
        val matched = positiveConcepts.count(avoidConcepts::contains)
        if (matched <= 0) return 0.0
        val coverage = matched.toDouble() / positiveConcepts.size.toDouble()
        return if (positiveConcepts.size >= 2 && matched == 1) coverage * 0.35 else coverage
    }

    private fun minimumSelectionFit(kind: AudioAssetKind): Double = when (kind) {
        AudioAssetKind.SFX -> MIN_SELECTION_FIT_SFX
        AudioAssetKind.MUSIC -> MIN_SELECTION_FIT_MUSIC
        AudioAssetKind.AMBIENCE -> MIN_SELECTION_FIT_AMBIENCE
    }

    private fun conceptCoverage(required: Set<String>, candidate: Set<String>): Double {
        if (required.isEmpty()) return 1.0
        if (candidate.isEmpty()) return 0.0
        return required.count(candidate::contains).toDouble() / required.size.toDouble()
    }

    private fun sourceConceptCoverage(required: Set<String>, candidate: Set<String>): Double {
        val requiredSources = required.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)
        if (requiredSources.isEmpty()) return 1.0
        val candidateSources = candidate.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)
        if (candidateSources.isEmpty()) return 0.0
        return requiredSources.count(candidateSources::contains).toDouble() / requiredSources.size.toDouble()
    }

    private fun sourceConflictConfidence(required: Set<String>, candidate: Set<String>): Double {
        val requiredSources = required.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)
        val candidateSources = candidate.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)
        if (requiredSources.isEmpty() || candidateSources.isEmpty()) return 0.0
        if (requiredSources.any(candidateSources::contains)) return 0.0
        fun specificity(size: Int): Double = when (size) {
            1 -> 1.0
            2 -> 0.88
            else -> 0.74
        }
        return ((specificity(requiredSources.size) + specificity(candidateSources.size)) / 2.0)
            .coerceIn(0.0, 1.0)
    }

    private fun commonSemanticFit(
        kind: AudioAssetKind,
        lexicalCoverage: Double,
        coreCoverage: Double,
        eventCoverage: Double,
        sourceCoverage: Double,
        hasSourceRequirement: Boolean,
        contextScore: Double,
        hasStructuredContext: Boolean,
    ): Double {
        val lexical = lexicalCoverage.coerceIn(0.0, 1.0)
        val core = coreCoverage.coerceIn(0.0, 1.0)
        val event = eventCoverage.coerceIn(0.0, 1.0)
        val source = sourceCoverage.coerceIn(0.0, 1.0)
        val base = when (kind) {
            AudioAssetKind.SFX -> if (hasSourceRequirement) {
                lexical * 0.30 + core * 0.20 + event * 0.20 + source * 0.30
            } else {
                lexical * 0.45 + core * 0.30 + event * 0.25
            }
            AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE -> if (hasSourceRequirement) {
                lexical * 0.35 + core * 0.25 + source * 0.40
            } else {
                lexical * 0.58 + core * 0.42
            }
        }
        val contextAdjustment = if (hasStructuredContext) {
            ((contextScore.coerceIn(0.0, 1.0) - 0.50) * 0.18).coerceIn(-0.09, 0.09)
        } else 0.0
        return (base + contextAdjustment).coerceIn(0.0, 1.0)
    }

    private fun Sections.semanticPassages(): Sequence<String> = sequenceOf(
        use.text,
        shade.text,
        positive.text,
        avoid.text,
    ).filter(String::isNotBlank)

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
        if (!first.isPresent || !second.isPresent) return 0.0
        return Mode3E5SemanticEngine.similarityOrNull(first.text, second.text) ?: 0.0
    }

    private fun localText(value: String): LocalText = LocalText(semanticText(value))

    private fun semanticText(value: String): String = value.replace(Regex("\\s+"), " ").trim()

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

    private fun emitDiagnostics(need: FreesoundAutoSearchNeed, evaluation: Evaluation) {
        val context = appContext as? NgheTruyenApplication ?: return
        val hintText = need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" || ")
        val diagnosticKey = "${need.kind.name}|${need.query}|$hintText"
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
                "localHintPresent" to hintText.isNotBlank().toString(),
                "localHint" to hintText.take(MAX_DIAGNOSTIC_HINT_CHARS),
                "indexedTracks" to evaluation.indexedTracks.toString(),
                "candidateTracks" to evaluation.candidateTracks.toString(),
                "indexCacheHit" to evaluation.indexCacheHit.toString(),
                "elapsedMs" to evaluation.elapsedMs.toString(),
                "accepted" to (evaluation.accepted != null).toString(),
                "acceptedTrackId" to evaluation.accepted?.track?.id.orEmpty(),
                "acceptedTitle" to evaluation.accepted?.track?.title.orEmpty().take(180),
                "acceptedFit" to format(evaluation.accepted?.selectionScore ?: 0.0),
                "acceptedCoreCoverage" to format(evaluation.accepted?.coreCoverage ?: 0.0),
                "acceptedEventCoverage" to format(evaluation.accepted?.eventCoverage ?: 0.0),
                "acceptedMetadataQuality" to evaluation.accepted?.metadataQuality.orEmpty(),
                "acceptedDecisive" to isDecisive(evaluation.accepted).toString(),
                "bestTrackId" to best?.track?.id.orEmpty(),
                "bestFit" to format(best?.selectionScore ?: 0.0),
                "bestRejectReason" to (best?.rejectReason ?: if (evaluation.candidateTracks == 0) "NO_SEMANTIC_CANDIDATES" else "NO_ACCEPTED_CANDIDATE"),
                "localSemanticPolicy" to Mode3E5SemanticEngine.backendName(),
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
                    "conflict" to format(candidate.avoidCoverage),
                    "metadataQuality" to candidate.metadataQuality,
                    "metadataPreview" to candidate.track.tagsCsv.replace(Regex("\\s+"), " ").trim().take(MAX_DIAGNOSTIC_METADATA_CHARS),
                ),
            )
        }
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

    private const val DECISIVE_MIN_CONTEXT_SCORE = 0.60
    private const val DECISIVE_SEMANTIC_SCORE = 0.64
    private const val RAW_MIN_QUERY_COVERAGE = 0.78
    private const val REMOTE_SFX_STRONG_QUERY_COVERAGE = 0.78
    private const val REMOTE_SFX_RELAXED_QUERY_COVERAGE = 0.60
    private const val CORE_PRESENT_COVERAGE = 0.92
    private const val CORE_ONLY_MIN_QUERY_COVERAGE = 0.32
    private const val LEGACY_MIN_QUERY_COVERAGE = 0.50
    private const val HARD_AVOID_CONFLICT = 0.66
    private const val HARD_SOURCE_CONFLICT_CONFIDENCE = 0.82
    private const val HARD_SPECIALIZATION_CONFLICT_CONFIDENCE = 0.82
    private const val MIN_SELECTION_FIT_MUSIC = 0.52
    private const val MIN_SELECTION_FIT_AMBIENCE = 0.52
    private const val MIN_SELECTION_FIT_SFX = 0.56
    private const val DECISIVE_SELECTION_FIT = 0.66
    private const val SOFT_SOURCE_CONFLICT_PENALTY = 0.18
    private const val SOFT_SPECIALIZATION_PENALTY = 0.34
    private const val MIN_LOCAL_SELECTION_FIT_MUSIC = 0.44
    private const val MIN_LOCAL_SELECTION_FIT_AMBIENCE = 0.48
    private const val MIN_LOCAL_SELECTION_FIT_SFX = 0.52
    private const val LOCAL_DESCRIPTION_USE_WEIGHT = 0.58
    private const val LOCAL_DESCRIPTION_SHADE_WEIGHT = 0.27
    private const val LOCAL_DESCRIPTION_WHOLE_WEIGHT = 0.15
    private const val LOCAL_DESCRIPTION_AVOID_PENALTY = 0.72
    private const val REMOTE_TITLE_MIN_SOURCE_COVERAGE = 0.60
    private const val HARD_REMOTE_TITLE_IDENTITY_CONFLICT = 0.90
    private const val REMOTE_TITLE_IDENTITY_PENALTY = 0.48
    private const val REMOTE_METADATA_CONFLICT_PENALTY = 0.12
    private const val REMOTE_LEGACY_WEIGHT = 0.30
    private const val REMOTE_E5_WEIGHT = 0.70
    private const val REMOTE_E5_MIN_FIT = 0.48
    private const val MAX_LOCAL_TOKENS = 72
    private const val MAX_TRACK_CACHE_ENTRIES = 1024
    private const val MAX_DIAGNOSTIC_CANDIDATES = 5
    private const val MAX_DIAGNOSTIC_HINT_CHARS = 900
    private const val MAX_DIAGNOSTIC_METADATA_CHARS = 700
    private const val MAX_RECENT_DIAGNOSTICS = 100
    private const val DIAGNOSTIC_DEDUP_MS = 1_500L

    private val EMPTY_LOCAL_TEXT = LocalText("")

    private val LOCAL_QUERY_ANCHORS = setOf(
        "music", "cinematic", "background", "audio", "sound", "effect", "ambience", "ambient",
    )

    private val QUERY_MODIFIERS = setOf(
        "light", "quiet", "peaceful", "sad", "romantic", "tense", "heavy", "soft", "gentle",
        "distant", "far", "near", "close", "night", "day", "dark", "bright", "slow", "fast",
        "deep", "warm", "cold", "dramatic", "epic", "strong", "intense", "mysterious", "eerie",
        "emotional", "suspenseful", "calm", "melancholic", "happy", "angry", "scary", "creepy", "wet",
        "magic", "magical", "fantasy", "mind", "mental", "psychic",
    )

    private val AUDIBLE_CONCEPT_ALIASES = linkedMapOf(
        "WIND" to setOf("wind", "winds", "windy", "gust", "gusts", "breeze", "breezy", "gio", "gió"),
        "RAIN" to setOf("rain", "rainy", "rainfall", "raindrop", "raindrops", "drizzle", "mua", "mưa", "hat mua", "hạt mưa"),
        "WATER" to setOf("water", "river", "stream", "waves", "wave", "ocean", "sea", "nuoc", "nước", "song", "sông", "suoi", "suối", "sóng", "bien", "biển"),
        "FIRE" to setOf("fire", "flame", "flames", "burn", "burning", "lua", "lửa", "chay", "cháy", "hoa", "hỏa", "hoa thuat", "hỏa thuật", "hoa cau", "hỏa cầu"),
        "THUNDER" to setOf("thunder", "thunderclap", "sam", "sấm", "sam set", "sấm sét"),
        "LIGHTNING" to setOf("lightning", "electric", "electricity", "set", "sét", "tia set", "tia sét", "dien", "điện"),
        "EXPLOSION" to setOf("explosion", "explosions", "explode", "explosive", "blast", "boom", "no", "nổ", "vu no", "vụ nổ"),
        "SWORD" to setOf("sword", "blade", "katana", "kiem", "kiếm", "luoi kiem", "lưỡi kiếm", "dao", "đao"),
        "SHIELD" to setOf("shield", "khien", "khiên", "tam chan", "tấm chắn"),
        "SHATTER" to setOf("shatter", "shattering", "break", "breaking", "crack", "cracking", "vo", "vỡ", "vo vun", "vỡ vụn", "nut", "nứt", "gay", "gãy"),
        "STRIKE" to setOf("strike", "hit", "impact", "slam", "slash", "smash", "clash", "crash", "crashing", "danh", "đánh", "dap", "đập", "chem", "chém", "va cham", "va chạm"),
        "WHOOSH" to setOf("whoosh", "woosh", "swoosh", "swish", "vut", "vút", "rit gio", "rít gió"),
        "FOOTSTEP" to setOf("footstep", "footsteps", "walking", "running", "steps", "buoc chan", "bước chân"),
        "DOOR" to setOf("door", "cua", "cửa"),
        "BELL" to setOf("bell", "bells", "chime", "chuong", "chuông"),
        "CROWD" to setOf("crowd", "chatter", "people", "conversation", "talk", "talking", "students", "student", "voices", "dam dong", "đám đông", "tro chuyen", "trò chuyện", "noi chuyen", "nói chuyện", "hoc sinh", "học sinh", "tieng nguoi", "tiếng người"),
        "TRAFFIC" to setOf("traffic", "cars", "car", "vehicle", "vehicles", "road", "street", "giao thong", "giao thông", "xe co", "xe cộ"),
        "BICYCLE" to setOf("bicycle", "bike", "cycling", "xe dap", "xe đạp"),
        "PIANO" to setOf("piano", "keyboard", "dan piano", "đàn piano", "duong cam", "dương cầm"),
        "GUITAR" to setOf("guitar", "acoustic guitar", "dan guitar", "đàn guitar"),
        "ORCHESTRA" to setOf("orchestra", "orchestral", "dan nhac", "dàn nhạc"),
        "STRINGS" to setOf("strings", "string", "violin", "violins", "cello", "dan day", "đàn dây", "vi cam", "vĩ cầm"),
        "DRUMS" to setOf("drum", "drums", "percussion", "trong", "trống", "bo go", "bộ gõ"),
        "FLUTE" to setOf("flute", "sao", "sáo"),
        "VOCAL" to setOf("vocal", "vocals", "choir", "singing", "voice", "giong hat", "giọng hát", "hop xuong", "hợp xướng"),
        "COMBAT" to setOf("battle", "combat", "fight", "fighting", "war", "chien dau", "chiến đấu", "dai chien", "đại chiến", "giao chien", "giao chiến", "tong cong kich", "tổng công kích"),
        "EPIC" to setOf("epic", "grand", "su thi", "sử thi", "hung trang", "hùng tráng", "hoanh trang", "hoành tráng"),
        "HEROIC" to setOf("heroic", "heroism", "triumphant", "anh hung", "anh hùng", "hao hung", "hào hùng", "chien thang", "chiến thắng"),
        "TENSION" to setOf("tension", "tense", "suspense", "suspenseful", "ominous", "cang thang", "căng thẳng", "ap luc", "áp lực", "nguy hiem", "nguy hiểm"),
        "SAD" to setOf("sad", "sadness", "tragic", "tragedy", "melancholy", "melancholic", "buon", "buồn", "bi thuong", "bi thương", "mat mat", "mất mát", "co don", "cô đơn"),
        "ROMANTIC" to setOf("romantic", "romance", "love", "lang man", "lãng mạn", "tinh cam", "tình cảm"),
        "MYSTERY" to setOf("mysterious", "mystery", "mystical", "bi an", "bí ẩn", "huyen bi", "huyền bí", "mo ho", "mơ hồ"),
        "CALM" to setOf("calm", "peaceful", "relaxing", "serene", "yen binh", "yên bình", "thu gian", "thư giãn", "nhe nhang", "nhẹ nhàng"),
        "ACTION" to setOf("action", "chase", "pursuit", "rush", "hanh dong", "hành động", "truy duoi", "truy đuổi"),
        "PUNCH" to setOf("punch", "punching", "fist", "dam", "đấm", "cu dam", "cú đấm", "quyen", "quyền"),
        "KICK" to setOf("kick", "kicking", "da", "đá", "cu da", "cú đá"),
        "FALL" to setOf("fall", "falling", "body fall", "drop", "collapse", "nga", "ngã", "roi", "rơi", "do nguoi", "đổ người"),
        "CLOTH" to setOf("cloth", "clothes", "clothing", "fabric", "jacket", "ao", "áo", "quan ao", "quần áo", "vai", "vải"),
        "GRAB" to setOf("grab", "grabbing", "grip", "grasp", "nam", "nắm", "chop", "chộp", "giat", "giật"),
        "BIRD" to setOf("bird", "birds", "birdsong", "chim", "tieng chim", "tiếng chim"),
        "FOREST" to setOf("forest", "woods", "woodland", "rung", "rừng"),
        "GUNFIRE" to setOf("gun", "gunfire", "gunshot", "rifle", "pistol", "firearm", "artillery", "cannon", "shooting", "shot", "sung", "súng", "dan", "đạn"),
        "SNOW" to setOf("snow", "snowy", "tuyet", "tuyết"),
        "EARTHQUAKE" to setOf("earthquake", "quake", "tremor", "dong dat", "động đất", "chan dong", "chấn động"),
        "STONE" to setOf("stone", "stones", "rock", "rocks", "rocky", "da tang", "đá tảng", "da nui", "đá núi"),
        "DEBRIS" to setOf("debris", "rubble", "wreckage", "crumble", "crumbling", "manh vo", "mảnh vỡ", "do vo", "đổ vỡ", "vung vo", "vụn vỡ"),
        "ICE" to setOf("ice", "icy", "frozen", "freezing", "freeze", "bang", "băng", "dong bang", "đóng băng"),
        "MAGIC" to setOf("magic", "magical", "spell", "sorcery", "enchant", "enchantment", "rune", "mana", "aura", "fantasy", "phep", "phép", "ma thuat", "ma thuật", "linh luc", "linh lực"),
        "SCI_FI" to setOf("sci fi", "scifi", "spaceship", "starship", "spacecraft", "cruiser", "laser", "pew", "blaster", "cyber", "futuristic", "phi thuyen", "phi thuyền", "tau vu tru", "tàu vũ trụ"),
        "ENERGY" to setOf("energy", "beam", "charge", "charging", "pulse", "nang luong", "năng lượng"),
        "EARTH" to setOf("earth", "ground", "soil", "earth magic", "dat", "đất", "tho", "thổ", "tho phap", "thổ pháp"),
        "ENGINE" to setOf("engine", "motor", "reactor", "turbine", "engine room", "machine hum", "dong co", "động cơ", "may moc", "máy móc"),
        "DRAGON" to setOf("dragon", "dragons", "rong", "rồng"),
        "ROAR" to setOf("roar", "roaring", "growl", "growling", "gam", "gầm", "gao", "gào"),
        "SUCTION" to setOf("suction", "suck", "vacuum", "hut", "hút", "luc hut", "lực hút"),
        "NOISE" to setOf("white noise", "pink noise", "brown noise", "noise", "tieng on", "tiếng ồn"),
    )

    private val AUDIBLE_SOURCE_CONCEPTS = setOf(
        "WIND", "RAIN", "WATER", "FIRE", "THUNDER", "LIGHTNING", "EXPLOSION",
        "SWORD", "SHIELD", "SHATTER", "STRIKE", "WHOOSH", "FOOTSTEP", "DOOR", "BELL", "CROWD",
        "TRAFFIC", "BICYCLE", "PIANO", "GUITAR", "ORCHESTRA", "STRINGS", "DRUMS",
        "FLUTE", "VOCAL", "BIRD", "PUNCH", "KICK", "FALL", "CLOTH", "GRAB", "GUNFIRE", "SNOW",
        "EARTHQUAKE", "STONE", "DEBRIS", "ICE", "MAGIC", "SCI_FI", "ENERGY", "EARTH", "ENGINE",
        "DRAGON", "ROAR", "SUCTION", "NOISE",
    )

    private val SPECIALIZATION_CONCEPTS = setOf("MAGIC", "SCI_FI", "EARTH", "ENGINE")

    private val FIREARM_CONTEXT_TERMS = setOf(
        "gun", "gunfire", "gunshot", "rifle", "pistol", "firearm", "artillery", "cannon", "shooting", "shot",
    )
    private val LITERAL_FIRE_TERMS = setOf(
        "flame", "flames", "burn", "burning", "fireball", "lua", "lửa", "chay", "cháy", "hoa", "hỏa",
    )
    private val NEGATION_TERMS = setOf(
        "khong", "không", "without", "not", "excluding", "except",
    )

    private val LOCAL_STOPWORDS = setOf(
        "và", "hoặc", "nhưng", "của", "cho", "trong", "ngoài", "khi", "sau", "trước", "với", "đến",
        "đang", "được", "không", "một", "những", "các", "này", "đó", "thì", "mà", "theo", "rất", "hơi",
        "cảnh", "tiếng", "âm", "thanh", "hiệu", "ứng", "sắc", "thái", "dùng", "tránh", "phù", "hợp",
        "tạo", "có", "là", "nền", "nghe", "được", "kéo", "dài", "ngắn", "liên", "tục",
        "and", "or", "the", "a", "an", "with", "for", "from", "into", "this", "that",
    )
}
