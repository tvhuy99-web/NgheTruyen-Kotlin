from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{label}: start marker not found")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"{label}: end marker not found")
    return text[:a] + replacement + text[b:]


# ---------------------------------------------------------------------------
# 1) Local matcher: stable metadata index + metadata-quality-aware scoring +
#    source-neutral fit score shared with remote arbitration.
# ---------------------------------------------------------------------------
matcher_path = Path("app/src/main/java/vn/nghetruyen/app/freesound/Mode3LibraryAssetMatcher.kt")
text = matcher_path.read_text(encoding="utf-8")

text = replace_once(
    text,
    " * Selects an existing Mode-3 library asset before any Freesound request is made.\n",
    " * Scores existing Mode-3 library assets as source-neutral candidates beside Freesound results.\n",
    "matcher doc",
)

text = replace_once(
    text,
    "        val rejectReason: String = \"\",\n        val structured: Boolean = false,\n    )\n\n    data class Evaluation(\n",
    "        val rejectReason: String = \"\",\n        val structured: Boolean = false,\n        /** Coverage of the most important audible/source query token. */\n        val coreCoverage: Double = 0.0,\n        /** Normalized fit used only to compare candidates; source provenance never contributes. */\n        val selectionScore: Double = 0.0,\n        val metadataQuality: String = \"RAW\",\n    )\n\n    data class Evaluation(\n",
    "matcher match fields",
)

text = replace_once(
    text,
    "        val indexCacheHit: Boolean,\n    )\n\n    private data class LocalText(\n",
    "        val indexCacheHit: Boolean,\n    )\n\n    data class RemoteFit(\n        val score: Double,\n        val coreCoverage: Double,\n    )\n\n    private data class LocalText(\n",
    "matcher remote fit type",
)

text = replace_once(
    text,
    "    private data class NeedProfile(\n        val queryTokens: Set<String>,\n        val hints: List<LocalHint>,\n    ) {\n",
    "    private data class NeedProfile(\n        val queryTokens: Set<String>,\n        val coreToken: String?,\n        val hints: List<LocalHint>,\n    ) {\n",
    "matcher need profile",
)

text = replace_once(
    text,
    "        val profile = needProfile(need)\n        val candidateIndices = candidateIndices(profile, index)\n        val ranked = candidateIndices.asSequence()\n            .mapNotNull { entryIndex -> score(profile, index.entries[entryIndex], nowMillis) }\n            .sortedWith(\n                compareByDescending<Match> { it.score }\n                    .thenByDescending { it.contextScore }\n                    .thenByDescending { it.coverage },\n            )\n            .toList()\n",
    "        val profile = needProfile(need)\n        val candidateIndices = candidateIndices(profile, index)\n        // The index contains only stable metadata. Playback counters remain live and are read from\n        // the current DB snapshot so playCount/lastPlayedAt never force an expensive index rebuild.\n        val currentTrackById = tracks.associateBy(SceneMusicTrackEntity::id)\n        val ranked = candidateIndices.asSequence()\n            .mapNotNull { entryIndex ->\n                val indexed = index.entries[entryIndex]\n                val currentTrack = currentTrackById[indexed.track.id] ?: indexed.track\n                score(profile, indexed, currentTrack, nowMillis)\n            }\n            .sortedWith(\n                compareByDescending<Match> { it.selectionScore }\n                    .thenByDescending { it.contextScore }\n                    .thenByDescending { it.coverage }\n                    .thenByDescending { it.score },\n            )\n            .toList()\n",
    "matcher evaluation",
)

text = replace_once(
    text,
    "        return score(profile, indexTrack(track), nowMillis)?.takeIf(Match::accepted)\n",
    "        return score(profile, indexTrack(track), track, nowMillis)?.takeIf(Match::accepted)\n",
    "matcher strongMatch",
)

old_fingerprint = '''    private fun libraryFingerprint(kind: AudioAssetKind, tracks: List<SceneMusicTrackEntity>): String {
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
'''
new_fingerprint = '''    private fun libraryFingerprint(kind: AudioAssetKind, tracks: List<SceneMusicTrackEntity>): String {
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
'''
text = replace_once(text, old_fingerprint, new_fingerprint, "matcher fingerprint")

old_profile = '''    private fun needProfile(need: FreesoundAutoSearchNeed): NeedProfile {
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
'''
new_profile = '''    private fun needProfile(need: FreesoundAutoSearchNeed): NeedProfile {
        val rawQueryTokens = FreesoundAutoRequirementAggregator.queryTokens(need.query).toList()
        val meaningful = rawQueryTokens.filterNot(LOCAL_QUERY_ANCHORS::contains)
        val queryTokens = meaningful.toSet().ifEmpty { rawQueryTokens.toSet() }
        val coreToken = coreQueryToken(rawQueryTokens)
        val hints = need.usages.asSequence()
            .map(FreesoundAutoRequirement::localContext)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map(::parseLocalHint)
            .filter(LocalHint::isPresent)
            .toList()
        return NeedProfile(queryTokens = queryTokens, coreToken = coreToken, hints = hints)
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

    fun remoteFit(
        need: FreesoundAutoSearchNeed,
        sound: FreesoundSound,
        lexicalCoverage: Double,
        selectedScore: Double,
    ): RemoteFit {
        val core = coreQueryToken(need.query)
        val remoteTokens = FreesoundAutoRequirementAggregator.queryTokens(
            buildString {
                append(sound.name).append(' ')
                append(sound.description).append(' ')
                append(sound.tags.joinToString(" "))
            },
        )
        val coreCoverage = if (core != null && core in remoteTokens) 1.0 else 0.0
        // Same 0..1 fit space used by local candidates. The remote ranking score is intentionally a
        // minority signal; audible-source lexical evidence matters more than popularity/rank bonuses.
        val fit = (
            coreCoverage * 0.45 +
                lexicalCoverage.coerceIn(0.0, 1.0) * 0.35 +
                selectedScore.coerceIn(0.0, 1.0) * 0.20
            ).coerceIn(0.0, 1.0)
        return RemoteFit(score = fit, coreCoverage = coreCoverage)
    }
'''
text = replace_once(text, old_profile, new_profile, "matcher profile + remote fit")

new_score = '''    private fun score(
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
            // the unchanged English query/title instead of punishing an otherwise exact asset.
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
        val queryEvidence = (
            coreCoverage * 0.52 +
                queryCoverage * 0.43 +
                titleQueryCoverage * 0.05
            ).coerceIn(0.0, 1.0)
        val selectionScore = (
            max(contextEvidence, queryEvidence) -
                avoidCoverage * 0.45 -
                repetitionPenalty * 0.50
            ).coerceIn(0.0, 1.0)

        val rejectReason = rejectReason(
            hintAware = hintAware,
            semanticMetadata = semanticMetadata,
            score = finalScore,
            contextScore = contextScore,
            queryCoverage = queryCoverage,
            coreCoverage = coreCoverage,
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
            selectionScore = selectionScore,
            metadataQuality = metadataQuality,
        )
    }

'''
text = replace_between(text, "    private fun score(\n", "    private fun rejectReason(\n", new_score, "matcher score")

new_reject = '''    private fun rejectReason(
        hintAware: Boolean,
        semanticMetadata: Boolean,
        score: Double,
        contextScore: Double,
        queryCoverage: Double,
        coreCoverage: Double,
        conflict: Double,
    ): String {
        val reasons = buildList {
            if (conflict >= MAX_CONFLICT) add("conflict>=$MAX_CONFLICT")
            if (!hintAware) {
                if (score < LEGACY_MIN_SCORE) add("score<$LEGACY_MIN_SCORE")
                if (queryCoverage < LEGACY_MIN_QUERY_COVERAGE) add("queryCoverage<$LEGACY_MIN_QUERY_COVERAGE")
            } else if (semanticMetadata) {
                val semanticPass =
                    contextScore >= DECISIVE_CONTEXT_SCORE ||
                    (
                        contextScore >= BALANCED_CONTEXT_SCORE &&
                            queryCoverage >= BALANCED_QUERY_COVERAGE &&
                            score >= BALANCED_MIN_SCORE
                        ) ||
                    (queryCoverage >= EXACT_QUERY_COVERAGE && coreCoverage >= CORE_PRESENT_COVERAGE)
                if (!semanticPass) {
                    add(
                        "semanticEvidence<context=$DECISIVE_CONTEXT_SCORE" +
                            "|balanced=$BALANCED_CONTEXT_SCORE+$BALANCED_QUERY_COVERAGE+$BALANCED_MIN_SCORE" +
                            "|exactQuery=$EXACT_QUERY_COVERAGE",
                    )
                }
            } else {
                val queryPass =
                    queryCoverage >= RAW_MIN_QUERY_COVERAGE ||
                    (coreCoverage >= CORE_PRESENT_COVERAGE && queryCoverage >= CORE_ONLY_MIN_QUERY_COVERAGE)
                if (!queryPass) {
                    add("rawQueryEvidence<query=$RAW_MIN_QUERY_COVERAGE|core+$CORE_ONLY_MIN_QUERY_COVERAGE")
                }
            }
        }
        return reasons.joinToString("+")
    }

'''
text = replace_between(text, "    private fun rejectReason(\n", "    private fun sections(\n", new_reject, "matcher reject")

text = replace_once(
    text,
    "                \"acceptedScore\" to format(evaluation.accepted?.score ?: 0.0),\n                \"bestTrackId\" to best?.track?.id.orEmpty(),\n                \"bestScore\" to format(best?.score ?: 0.0),\n                \"bestRejectReason\" to (best?.rejectReason ?: if (evaluation.candidateTracks == 0) \"NO_LEXICAL_CANDIDATES\" else \"NO_SCORED_CANDIDATE\"),\n                \"hintScoreThreshold\" to HINT_MIN_SCORE.toString(),\n                \"hintContextThreshold\" to HINT_MIN_CONTEXT_SCORE.toString(),\n                \"legacyScoreThreshold\" to LEGACY_MIN_SCORE.toString(),\n                \"maxConflict\" to MAX_CONFLICT.toString(),\n",
    "                \"acceptedScore\" to format(evaluation.accepted?.score ?: 0.0),\n                \"acceptedFit\" to format(evaluation.accepted?.selectionScore ?: 0.0),\n                \"acceptedCoreCoverage\" to format(evaluation.accepted?.coreCoverage ?: 0.0),\n                \"acceptedMetadataQuality\" to evaluation.accepted?.metadataQuality.orEmpty(),\n                \"bestTrackId\" to best?.track?.id.orEmpty(),\n                \"bestScore\" to format(best?.score ?: 0.0),\n                \"bestFit\" to format(best?.selectionScore ?: 0.0),\n                \"bestCoreCoverage\" to format(best?.coreCoverage ?: 0.0),\n                \"bestMetadataQuality\" to best?.metadataQuality.orEmpty(),\n                \"bestRejectReason\" to (best?.rejectReason ?: if (evaluation.candidateTracks == 0) \"NO_LEXICAL_CANDIDATES\" else \"NO_SCORED_CANDIDATE\"),\n                \"decisiveContextThreshold\" to DECISIVE_CONTEXT_SCORE.toString(),\n                \"balancedContextThreshold\" to BALANCED_CONTEXT_SCORE.toString(),\n                \"rawQueryThreshold\" to RAW_MIN_QUERY_COVERAGE.toString(),\n                \"coreOnlyMinQueryCoverage\" to CORE_ONLY_MIN_QUERY_COVERAGE.toString(),\n                \"legacyScoreThreshold\" to LEGACY_MIN_SCORE.toString(),\n                \"maxConflict\" to MAX_CONFLICT.toString(),\n",
    "matcher eval diagnostics",
)

text = replace_once(
    text,
    "                    \"score\" to format(candidate.score),\n                    \"queryCoverage\" to format(candidate.coverage),\n                    \"contextScore\" to format(candidate.contextScore),\n",
    "                    \"score\" to format(candidate.score),\n                    \"selectionFit\" to format(candidate.selectionScore),\n                    \"queryCoverage\" to format(candidate.coverage),\n                    \"coreCoverage\" to format(candidate.coreCoverage),\n                    \"contextScore\" to format(candidate.contextScore),\n",
    "matcher candidate diagnostics score",
)
text = replace_once(
    text,
    "                    \"structuredMetadata\" to candidate.structured.toString(),\n                    \"metadataPreview\" to candidate.track.tagsCsv.replace(Regex(\"\\\\s+\"), \" \").trim().take(MAX_DIAGNOSTIC_METADATA_CHARS),\n",
    "                    \"structuredMetadata\" to candidate.structured.toString(),\n                    \"metadataQuality\" to candidate.metadataQuality,\n                    \"metadataPreview\" to candidate.track.tagsCsv.replace(Regex(\"\\\\s+\"), \" \").trim().take(MAX_DIAGNOSTIC_METADATA_CHARS),\n",
    "matcher candidate diagnostics metadata",
)
text = replace_once(
    text,
    "                \"acceptedScore\" to format(evaluation.accepted?.score ?: 0.0),\n                \"candidateTracks\" to evaluation.candidateTracks.toString(),\n",
    "                \"acceptedScore\" to format(evaluation.accepted?.score ?: 0.0),\n                \"acceptedFit\" to format(evaluation.accepted?.selectionScore ?: 0.0),\n                \"candidateTracks\" to evaluation.candidateTracks.toString(),\n",
    "matcher final diagnostics",
)

old_constants = '''    private const val HINT_MIN_SCORE = 0.46
    private const val HINT_MIN_CONTEXT_SCORE = 0.36
    private const val LEGACY_MIN_SCORE = 0.56
    private const val LEGACY_MIN_QUERY_COVERAGE = 0.50
    private const val MAX_CONFLICT = 0.48
'''
new_constants = '''    private const val DECISIVE_CONTEXT_SCORE = 0.56
    private const val BALANCED_CONTEXT_SCORE = 0.38
    private const val BALANCED_QUERY_COVERAGE = 0.33
    private const val BALANCED_MIN_SCORE = 0.40
    private const val EXACT_QUERY_COVERAGE = 0.85
    private const val RAW_MIN_QUERY_COVERAGE = 0.78
    private const val CORE_PRESENT_COVERAGE = 0.92
    private const val CORE_ONLY_MIN_QUERY_COVERAGE = 0.32
    private const val LEGACY_MIN_SCORE = 0.56
    private const val LEGACY_MIN_QUERY_COVERAGE = 0.50
    private const val MAX_CONFLICT = 0.48
'''
text = replace_once(text, old_constants, new_constants, "matcher constants")

text = replace_once(
    text,
    "    private val LOCAL_STOPWORDS = setOf(\n",
    "    private val QUERY_MODIFIERS = setOf(\n        \"light\", \"quiet\", \"peaceful\", \"sad\", \"romantic\", \"tense\", \"heavy\", \"soft\", \"gentle\",\n        \"distant\", \"far\", \"near\", \"close\", \"night\", \"day\", \"dark\", \"bright\", \"slow\", \"fast\",\n        \"deep\", \"warm\", \"cold\", \"dramatic\", \"epic\", \"strong\", \"intense\", \"mysterious\", \"eerie\",\n        \"emotional\", \"suspenseful\", \"calm\", \"melancholic\", \"happy\", \"angry\", \"scary\", \"creepy\", \"wet\",\n    )\n\n    private val LOCAL_STOPWORDS = setOf(\n",
    "matcher query modifiers",
)

matcher_path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# 2) Prompt: local_hint must distinguish the actual scene/near-miss instead of
#    returning only generic moods. Query rules remain unchanged.
# ---------------------------------------------------------------------------
prompt_path = Path("app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt")
prompt = prompt_path.read_text(encoding="utf-8")
prompt = replace_once(
    prompt,
    '                add("local_hint phải do chính AI viết từ hiểu biết về cảnh, không phải bản dịch máy móc của query. Viết theo đúng dạng ngắn: Dùng: các tình huống/cảnh/nguồn âm phù hợp; Tránh: các tình huống gần nghĩa nhưng sẽ làm asset sai cảnh.")\n',
    '                add("local_hint phải do chính AI viết từ hiểu biết về cảnh, không phải bản dịch máy móc của query. Viết ngắn nhưng PHẢI có tín hiệu phân biệt tình tiết/nguồn âm cụ thể của cảnh và các near-miss dễ chọn nhầm; không chỉ liệt kê mood chung như buồn, bí ẩn, căng thẳng.")\n',
    "prompt coordination local_hint",
)
old_rule12 = '            12. Mỗi requirement BẮT BUỘC thêm local_hint bằng tiếng Việt, tối đa 240 ký tự, đúng dạng "Dùng: ...; Tránh: ...". Dùng 3–7 cụm ngắn, phổ quát và sát metadata cho phần Dùng; 1–4 cụm cho phần Tránh. Không kể lại cốt truyện, không tên nhân vật, không ID, không tên file. Ví dụ MUSIC: "Dùng: chia tay, từ biệt, nhớ người, mưa, một mình; Tránh: chiến đấu, chiến thắng, hài hước". Ví dụ AMBIENCE: "Dùng: rừng ban đêm, dế, gió nhẹ, yên tĩnh; Tránh: thành phố, trong nhà, bão lớn". Ví dụ SFX: "Dùng: mở cửa gỗ, cửa cũ, mở chậm; Tránh: cửa kim loại, đóng cửa".\n'
new_rule12 = '            12. Mỗi requirement BẮT BUỘC thêm local_hint bằng tiếng Việt, tối đa 240 ký tự, đúng dạng "Dùng: ...; Tránh: ...". Phần Dùng dùng 3–6 cụm ngắn; ít nhất 2 cụm phải phân biệt đúng tình tiết/không gian/vật liệu/cách phát âm của cảnh, không chỉ là mood chung. Phần Tránh dùng 2–4 near-miss dễ bị chọn nhầm nhưng nghe/cảm xúc khác cảnh. Không kể lại cốt truyện, không tên nhân vật, không ID, không tên file. Ví dụ MUSIC: "Dùng: đối đầu căng thẳng trước giao chiến, nguy hiểm cận kề, thế lực áp đảo; Tránh: phiêu lưu vui, chiến thắng, lãng mạn". Ví dụ AMBIENCE: "Dùng: hang băng, gió lạnh luồn hang, không gian đá kín; Tránh: gió đồng trống, thành phố, phòng trong nhà". Ví dụ SFX: "Dùng: xích sắt đứt, kim loại gãy vỡ, mắt xích bật mạnh; Tránh: kính vỡ, gỗ gãy, kim loại va nhẹ".\n'
prompt = replace_once(prompt, old_rule12, new_rule12, "prompt rule 12")
prompt_path.write_text(prompt, encoding="utf-8")


# ---------------------------------------------------------------------------
# 3) Resolver: source-neutral arbitration. Local and Freesound are both scored;
#    source itself contributes zero points. Download only when remote actually wins.
# ---------------------------------------------------------------------------
resolver_path = Path("app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt")
resolver = resolver_path.read_text(encoding="utf-8")

resolver = replace_once(
    resolver,
    "    val cachedTrack: SceneMusicTrackEntity? = null,\n    val resolvedSource: FreesoundAutoResolutionSource? = null,\n",
    "    val cachedTrack: SceneMusicTrackEntity? = null,\n    val localMatch: Mode3LibraryAssetMatcher.Match? = null,\n    val resolvedSource: FreesoundAutoResolutionSource? = null,\n",
    "resolver prepared local match",
)
resolver = replace_once(
    resolver,
    "private data class FreesoundAutoImportOutcome(\n",
    "private data class FreesoundSourceDecision(\n    val useLibrary: Boolean,\n    val localFit: Double,\n    val remoteFit: Double,\n    val remoteCoreCoverage: Double,\n    val reason: String,\n)\n\nprivate data class FreesoundAutoImportOutcome(\n",
    "resolver decision type",
)
resolver = replace_once(
    resolver,
    " * Mode-3 resolver. Search order is library-first for every audio kind:\n * previously resolved query -> strong match from the enabled shared library -> Freesound\n * network search/import -> silence. The library pass includes both user-added assets and files\n * downloaded by earlier Mode-3 runs. Source provenance remains intact; it no longer limits reuse.\n",
    " * Mode-3 resolver. Existing-library and Freesound candidates are evaluated without source\n * priority: provenance contributes zero points, and the candidate with stronger fit evidence wins.\n * A remote preview is downloaded only after that source-neutral arbitration says it is the better\n * fit. Existing files include both user-added assets and earlier Freesound imports.\n",
    "resolver class doc",
)

new_prepare = '''        // One DB snapshot is enough for all local candidate evaluation in this cycle. The matcher
        // caches stable metadata/token indexes; playback counters are read live without rebuilding.
        val cacheStartedNanos = System.nanoTime()
        var knownTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
        val usableTracksByKind = AudioAssetKind.entries.associateWith { kind ->
            knownTracks.filter { isUsableLibraryTrack(it, kind) }
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

            val cachedId = queryCache.get(need.kind, need.query)
            val cachedTrack = cachedId?.let { id ->
                usableTracksByKind[need.kind].orEmpty().firstOrNull { it.id == id }
            }
            if (cachedTrack != null) {
                queryCacheHits += 1
                diagnostics += "LIBRARY_QUERY_CACHE_AVAILABLE kind=${need.kind.name} trackId=${cachedTrack.id} source=${if (isManagedFreesoundTrack(cachedTrack)) "FREESOUND" else "LOCAL"} networkSkipped=false reason=source_neutral_arbitration query=${need.query.take(140)}"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_LIBRARY_QUERY_CACHE_HIT",
                    attributes = baseAttributes + mapOf(
                        "kind" to need.kind.name,
                        "trackId" to cachedTrack.id,
                        "assetSource" to if (isManagedFreesoundTrack(cachedTrack)) "FREESOUND" else "LOCAL",
                        "networkSkipped" to "false",
                        "reason" to "source_neutral_arbitration",
                        "query" to need.query.take(180),
                    ),
                )
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
                cachedTrack = localMatch?.track,
                localMatch = localMatch,
                effectiveQuery = effectiveQuery,
                strategy = strategy,
            )
        }
'''
resolver = replace_between(
    resolver,
    "        // One DB snapshot is enough for cache validation + semantic local matching.",
    "        val cacheLookupMs = (System.nanoTime() - cacheStartedNanos) / 1_000_000L\n",
    new_prepare,
    "resolver prepare block",
)
# replace_between excludes end marker, so restore it.
resolver = resolver.replace(
    new_prepare + "\n        // Only true library misses enter the network pool.",
    new_prepare + "        val cacheLookupMs = (System.nanoTime() - cacheStartedNanos) / 1_000_000L\n\n        // Only true library misses enter the network pool.",
    1,
) if new_prepare + "\n        // Only true library misses enter the network pool." in resolver else resolver
# In case the exact comment follows directly, use a targeted replacement below.
resolver = resolver.replace(
    new_prepare + "        // Only true library misses enter the network pool.",
    new_prepare + "        val cacheLookupMs = (System.nanoTime() - cacheStartedNanos) / 1_000_000L\n\n        // Only true library misses enter the network pool.",
    1,
)

resolver = replace_once(
    resolver,
    "        // Only true library misses enter the network pool. Cache hits and semantic library matches do\n        // not consume a request permit. awaitAll preserves seed order for deterministic plans.\n        val networkSeeds = prepared.filter { it.cachedTrack == null }\n",
    "        // Source-neutral selection compares both sides. Every need gets the unchanged remote search\n        // candidate as well as the best accepted existing-library candidate. Search remains parallel.\n        val networkSeeds = prepared\n",
    "resolver network seeds",
)

# Insert source arbitration immediately after searchedByIndex.
arbitration = '''        val preparedByIndex = prepared.associateBy(FreesoundAutoPreparedNeed::index)
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
            } else Mode3LibraryAssetMatcher.RemoteFit(0.0, 0.0)
            val localFit = local?.selectionScore ?: 0.0
            val useLibrary = when {
                local == null -> false
                remote == null -> true
                localFit > remoteFit.score + SOURCE_FIT_TIE_EPSILON -> true
                remoteFit.score > localFit + SOURCE_FIT_TIE_EPSILON -> false
                else -> true // equal fit: avoid needless I/O; source never changed either score.
            }
            val reason = when {
                local == null && remote == null -> "NO_CANDIDATE"
                local == null -> "REMOTE_ONLY"
                remote == null -> "LIBRARY_ONLY"
                kotlin.math.abs(localFit - remoteFit.score) <= SOURCE_FIT_TIE_EPSILON -> "FIT_TIE_REUSE_EXISTING"
                useLibrary -> "LIBRARY_HIGHER_FIT"
                else -> "REMOTE_HIGHER_FIT"
            }
            diagnostics += "SOURCE_ARBITRATION kind=${searchedSeed.need.kind.name} query=${searchedSeed.need.query.take(140)} localTrackId=${local?.track?.id.orEmpty()} localFit=${"%.3f".format(java.util.Locale.US, localFit)} remoteSoundId=${remote?.id ?: 0} remoteFit=${"%.3f".format(java.util.Locale.US, remoteFit.score)} remoteCoreCoverage=${"%.3f".format(java.util.Locale.US, remoteFit.coreCoverage)} winner=${if (useLibrary) "LIBRARY" else if (remote != null) "FREESOUND" else "NONE"} reason=$reason"
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
                    "winner" to if (useLibrary) "LIBRARY" else if (remote != null) "FREESOUND" else "NONE",
                    "reason" to reason,
                ),
            )
            searchedSeed.index to FreesoundSourceDecision(
                useLibrary = useLibrary,
                localFit = localFit,
                remoteFit = remoteFit.score,
                remoteCoreCoverage = remoteFit.coreCoverage,
                reason = reason,
            )
        }.toMap()

'''
resolver = replace_once(
    resolver,
    "        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)\n\n        // Resolve known soundIds from the local managed library before any download.",
    "        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)\n\n" + arbitration + "        // Resolve known soundIds from the local managed library before any download.",
    "resolver arbitration insert",
)

resolver = replace_once(
    resolver,
    "        val importSeeds = searched.filter { seed ->\n            seed.search?.sound != null && localReusableByIndex[seed.index] == null\n        }\n",
    "        val importSeeds = searched.filter { seed ->\n            seed.search?.sound != null &&\n                sourceDecisionByIndex.getValue(seed.index).useLibrary.not() &&\n                localReusableByIndex[seed.index] == null\n        }\n",
    "resolver import only remote winners",
)

old_loop_start = '''        for (seed in prepared.sortedBy(FreesoundAutoPreparedNeed::index)) {
            val need = seed.need
            val cachedTrack = seed.cachedTrack
            if (cachedTrack != null) {
                resolutions += FreesoundAutoResolvedNeed(
                    need,
                    cachedTrack.id,
                    (seed.resolvedSource ?: FreesoundAutoResolutionSource.CACHE).name,
                )
                continue
            }

            val resolvedSearch = requireNotNull(searchedByIndex[seed.index])
'''
new_loop_start = '''        for (seed in prepared.sortedBy(FreesoundAutoPreparedNeed::index)) {
            val need = seed.need
            val localMatch = seed.localMatch
            val decision = sourceDecisionByIndex.getValue(seed.index)
            if (decision.useLibrary && localMatch != null) {
                val localTrack = localMatch.track
                queryCache.put(need.kind, need.query, localTrack.id)
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
'''
resolver = replace_once(resolver, old_loop_start, new_loop_start, "resolver loop source decision")

# Make remote resolution diagnostic explicitly carry the winning fit.
resolver = replace_once(
    resolver,
    "                        \"cachePersisted\" to \"true\",\n                    ),\n                )\n                continue\n",
    "                        \"cachePersisted\" to \"true\",\n                        \"source\" to \"FREESOUND\",\n                        \"fit\" to \"%.3f\".format(java.util.Locale.US, decision.remoteFit),\n                    ),\n                )\n                continue\n",
    "resolver remote winner diagnostics",
)

resolver = replace_once(
    resolver,
    "        private const val REMOTE_MIN_LEXICAL_COVERAGE = 0.50\n",
    "        private const val REMOTE_MIN_LEXICAL_COVERAGE = 0.50\n        private const val SOURCE_FIT_TIE_EPSILON = 0.015\n",
    "resolver source tie constant",
)

resolver_path.write_text(resolver, encoding="utf-8")

print("Mode 3 best-fit patch applied successfully")
