package vn.nghetruyen.app.freesound

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.SfxCadence

enum class FreesoundRequirementImportance { REQUIRED, OPTIONAL }

data class FreesoundAutoRequirement(
    val kind: AudioAssetKind,
    val query: String,
    val importance: FreesoundRequirementImportance = FreesoundRequirementImportance.OPTIONAL,
    val startUnitId: String? = null,
    val endUnitId: String? = null,
    val unitId: String? = null,
    val stopUnitId: String? = null,
    val repeatCount: Int = 1,
    val cadence: SfxCadence = SfxCadence.NORMAL,
    val loopUntilStop: Boolean = false,
    /**
     * Story text attached locally after the AI has already chosen the timeline usage. This field is
     * only evidence for selecting an existing library file. It is deliberately absent from toJson()
     * so it never changes the short Freesound query or any AI-owned playback instruction.
     */
    val localContext: String = "",
)

data class FreesoundAutoSearchNeed(
    val kind: AudioAssetKind,
    val query: String,
    val importance: FreesoundRequirementImportance,
    val usages: List<FreesoundAutoRequirement>,
)

/** Contract/parser for the extra field returned by the SAME unified narration AI request. */
object FreesoundAutoRequirementCodec {
    const val JSON_KEY = "freesound_requirements"
    private const val MAX_QUERY_CHARS = 160

    fun parse(
        root: JSONObject,
        validUnitIds: List<String>,
        enabledKinds: Set<AudioAssetKind>,
    ): List<FreesoundAutoRequirement> {
        require(root.has(JSON_KEY)) { "AI không trả $JSON_KEY dù chế độ Freesound tự động đang bật." }
        if (enabledKinds.isEmpty()) return emptyList()
        val source = root.optJSONArray(JSON_KEY) ?: error("$JSON_KEY phải là một mảng JSON.")
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val rows = buildList {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: error("$JSON_KEY[$index] không phải object.")
                val kind = runCatching {
                    AudioAssetKind.valueOf(row.optString("kind").trim().uppercase(Locale.ROOT))
                }.getOrElse { error("$JSON_KEY[$index] có kind không hợp lệ.") }
                require(kind in enabledKinds) { "$JSON_KEY[$index] yêu cầu lớp âm thanh đang tắt." }
                val query = canonicalSearchQuery(oneLine(row.optString("query")).take(MAX_QUERY_CHARS), kind)
                // A semantically wrong-layer query is safer to omit than to download an unrelated
                // asset. Other valid requirements in the same AI response must still survive.
                if (query.isBlank()) continue
                val importance = runCatching {
                    FreesoundRequirementImportance.valueOf(
                        row.optString("importance", FreesoundRequirementImportance.OPTIONAL.name)
                            .trim().uppercase(Locale.ROOT),
                    )
                }.getOrDefault(FreesoundRequirementImportance.OPTIONAL)

                when (kind) {
                    AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE -> {
                        val start = row.optString("start_id").trim()
                        val end = row.optString("end_id").trim()
                        val startIndex = order[start] ?: error("$JSON_KEY[$index] dùng start_id không tồn tại.")
                        val endIndex = order[end] ?: error("$JSON_KEY[$index] dùng end_id không tồn tại.")
                        require(endIndex >= startIndex) { "$JSON_KEY[$index] có ranh giới đảo ngược." }
                        // No artificial minimum duration: a one-UNIT region is valid when the
                        // story really changes there. AI decides duration from narrative evidence.
                        val requestedMinSpan = 1
                        val (safeStartIndex, safeEndIndex) = expandDurableRange(
                            startIndex = startIndex,
                            endIndex = endIndex,
                            minimumSpan = requestedMinSpan,
                            lastIndex = validUnitIds.lastIndex,
                        )
                        add(
                            FreesoundAutoRequirement(
                                kind = kind,
                                query = query,
                                importance = importance,
                                startUnitId = validUnitIds[safeStartIndex],
                                endUnitId = validUnitIds[safeEndIndex],
                            ),
                        )
                    }
                    AudioAssetKind.SFX -> {
                        val unit = row.optString("unit_id").trim()
                        val unitIndex = order[unit] ?: error("$JSON_KEY[$index] dùng unit_id không tồn tại.")
                        val stop = row.optString("stop_unit_id").trim().takeIf(String::isNotBlank)
                        val stopIndex = stop?.let { order[it] ?: error("$JSON_KEY[$index] dùng stop_unit_id không tồn tại.") }
                        val repeat = row.optInt("repeat_count", 1)
                        val cadence = runCatching {
                            SfxCadence.valueOf(row.optString("cadence", SfxCadence.NORMAL.name).trim().uppercase(Locale.ROOT))
                        }.getOrDefault(SfxCadence.NORMAL)
                        val loop = row.optBoolean("loop_until_stop", false)
                        require(stopIndex == null || stopIndex > unitIndex) { "$JSON_KEY[$index] có stop_unit_id không nằm sau cue." }
                        require(repeat >= 1) { "$JSON_KEY[$index] có repeat_count phải lớn hơn hoặc bằng 1." }
                        require(!loop || stop != null) { "$JSON_KEY[$index] loop_until_stop bắt buộc có stop_unit_id." }
                        require(!loop || repeat == 1) { "$JSON_KEY[$index] không được vừa loop vừa repeat_count > 1." }
                        add(
                            FreesoundAutoRequirement(
                                kind = kind,
                                query = query,
                                importance = importance,
                                unitId = unit,
                                stopUnitId = stop,
                                repeatCount = repeat,
                                cadence = cadence,
                                loopUntilStop = loop,
                            ),
                        )
                    }
                }
            }
        }
        return rows
    }

    fun toJson(requirements: List<FreesoundAutoRequirement>): JSONArray = JSONArray().also { array ->
        requirements.forEach { requirement ->
            array.put(
                JSONObject()
                    .put("kind", requirement.kind.name)
                    .put("query", requirement.query)
                    .put("importance", requirement.importance.name)
                    .also { row ->
                        when (requirement.kind) {
                            AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE -> {
                                row.put("start_id", requirement.startUnitId)
                                row.put("end_id", requirement.endUnitId)
                            }
                            AudioAssetKind.SFX -> {
                                row.put("unit_id", requirement.unitId)
                                requirement.stopUnitId?.let { row.put("stop_unit_id", it) }
                                if (requirement.repeatCount != 1) row.put("repeat_count", requirement.repeatCount)
                                if (requirement.cadence != SfxCadence.NORMAL) row.put("cadence", requirement.cadence.name)
                                if (requirement.loopUntilStop) row.put("loop_until_stop", true)
                            }
                        }
                    },
            )
        }
    }

    private fun expandDurableRange(
        startIndex: Int,
        endIndex: Int,
        minimumSpan: Int,
        lastIndex: Int,
    ): Pair<Int, Int> {
        var start = startIndex
        var end = endIndex
        while (end - start + 1 < minimumSpan && end < lastIndex) end += 1
        while (end - start + 1 < minimumSpan && start > 0) start -= 1
        return start to end
    }

    internal fun canonicalSearchQuery(value: String, kind: AudioAssetKind): String {
        val normalized = FreesoundAutoRequirementAggregator.normalizeQuery(value)
        if (normalized.isBlank()) return ""
        val tokens = normalized.split(' ')
            .map(String::trim)
            .filter { it.length >= 2 && it !in QUERY_STOPWORDS }
            .filterNot { token -> token in QUERY_GENERIC_TERMS && token != "music" && kind == AudioAssetKind.MUSIC }
        if (tokens.isEmpty()) return normalized.split(' ').filter(String::isNotBlank).take(MAX_QUERY_TERMS).joinToString(" ")
        val selected = tokens.take(MAX_QUERY_TERMS)
        if (kind == AudioAssetKind.MUSIC && selected.none(MUSIC_ANCHOR_TERMS::contains)) {
            // Abstract mood-only queries such as "mysterious magic" are poor music searches.
            // Add a neutral musical style anchor without imposing any duration restriction.
            return (selected.take(MAX_QUERY_TERMS - 1) + "cinematic")
                .distinct()
                .take(MAX_QUERY_TERMS)
                .joinToString(" ")
        }
        if (kind == AudioAssetKind.SFX) {
            if ("wind" in selected && selected.none(SFX_EVENT_TERMS::contains)) return "wind gust"
            if (selected.any(SFX_PERSISTENT_BED_TERMS::contains) && selected.none(SFX_EVENT_TERMS::contains)) {
                // Persistent beds belong to AMBIENCE. Do not waste a network request/download on a
                // wrong-layer SFX such as "ethereal drone" or "room tone".
                return ""
            }
        }
        return selected.joinToString(" ")
    }

    private fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private val QUERY_STOPWORDS = setOf(
        "a", "an", "the", "with", "on", "in", "of", "to", "for", "from", "by", "into", "onto",
        "very", "single", "one", "some", "and", "or",
    )
    private val QUERY_GENERIC_TERMS = setOf("sound", "audio", "effect", "ambience")
    private val MUSIC_ANCHOR_TERMS = setOf(
        "music", "cinematic", "orchestral", "orchestra", "score", "trailer", "ambient", "electronic",
        "classical", "folk", "rock", "jazz", "acoustic", "guzheng", "guqin", "erhu", "dizi", "koto",
        "shamisen", "flute", "piano", "violin", "cello", "harp", "strings", "drums", "percussion",
        "choir", "synth",
    )
    private val SFX_EVENT_TERMS = setOf(
        "gust", "whoosh", "slash", "hit", "thud", "crash", "clash", "strike", "slam", "break",
        "burst", "pulse", "snap", "drop", "knock", "creak", "step", "steps", "footstep", "footsteps", "splash",
        "shout", "bang", "boom", "click", "ring", "tear", "rip", "burn",
    )
    private val SFX_PERSISTENT_BED_TERMS = setOf(
        "drone", "hum", "humming", "tone", "roomtone", "room", "ambience", "ambient", "atmosphere",
        "forest", "river", "ocean", "sea", "crowd", "rain", "storm", "waterfall", "traffic",
    )
    private const val MAX_QUERY_TERMS = 3
}

/**
 * Reduces API usage while preserving every timeline usage. Similar repeated queries share one
 * Freesound lookup and one downloaded asset; the resulting track can then be reused at every cue.
 */
object FreesoundAutoRequirementAggregator {
    // Kept for source compatibility with older diagnostics/tests. They no longer cap AI output.
    const val MAX_MUSIC_SEARCHES = Int.MAX_VALUE
    const val MAX_AMBIENCE_SEARCHES = Int.MAX_VALUE
    const val MAX_SFX_SEARCHES = Int.MAX_VALUE

    fun aggregate(requirements: List<FreesoundAutoRequirement>): List<FreesoundAutoSearchNeed> {
        if (requirements.isEmpty()) return emptyList()
        return AudioAssetKind.entries.flatMap { kind ->
            val kindRows = requirements.filter { it.kind == kind }
                .sortedWith(compareBy<FreesoundAutoRequirement> { it.importance != FreesoundRequirementImportance.REQUIRED })
            val groups = mutableListOf<MutableList<FreesoundAutoRequirement>>()
            kindRows.forEach { requirement ->
                val normalized = normalizeQuery(requirement.query)
                if (normalized.isBlank()) return@forEach
                val tokens = queryTokens(normalized)
                val existing = groups.firstOrNull { group ->
                    val representative = normalizeQuery(group.first().query)
                    representative == normalized || jaccard(tokens, queryTokens(representative)) >= 0.80
                }
                if (existing != null) existing += requirement else groups += mutableListOf(requirement)
            }
            groups
                .sortedWith(
                    compareByDescending<MutableList<FreesoundAutoRequirement>> { group ->
                        group.any { it.importance == FreesoundRequirementImportance.REQUIRED }
                    }.thenByDescending { it.size },
                )
                .map { group ->
                    val representative = group.minWithOrNull(
                        compareBy<FreesoundAutoRequirement> { queryTokens(normalizeQuery(it.query)).size }
                            .thenBy { normalizeQuery(it.query).length },
                    ) ?: group.first()
                    FreesoundAutoSearchNeed(
                        kind = kind,
                        query = representative.query.trim(),
                        importance = if (group.any { it.importance == FreesoundRequirementImportance.REQUIRED }) {
                            FreesoundRequirementImportance.REQUIRED
                        } else FreesoundRequirementImportance.OPTIONAL,
                        usages = group.toList(),
                    )
                }
        }
    }

    fun normalizeQuery(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    internal fun queryTokens(value: String): Set<String> = normalizeQuery(value)
        .split(' ')
        .map(String::trim)
        .filter { it.length >= 2 }
        .toSet()

    private fun jaccard(first: Set<String>, second: Set<String>): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val intersection = first.count(second::contains)
        val union = first.size + second.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}
