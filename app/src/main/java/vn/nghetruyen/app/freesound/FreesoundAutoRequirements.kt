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
    private const val MAX_RAW_REQUIREMENTS = 80

    fun parse(
        root: JSONObject,
        validUnitIds: List<String>,
        enabledKinds: Set<AudioAssetKind>,
    ): List<FreesoundAutoRequirement> {
        require(root.has(JSON_KEY)) { "AI không trả $JSON_KEY dù chế độ Freesound tự động đang bật." }
        if (enabledKinds.isEmpty()) return emptyList()
        val source = root.optJSONArray(JSON_KEY) ?: error("$JSON_KEY phải là một mảng JSON.")
        require(source.length() <= MAX_RAW_REQUIREMENTS) { "AI trả quá nhiều nhu cầu âm thanh Freesound." }
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val rows = buildList {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: error("$JSON_KEY[$index] không phải object.")
                val kind = runCatching {
                    AudioAssetKind.valueOf(row.optString("kind").trim().uppercase(Locale.ROOT))
                }.getOrElse { error("$JSON_KEY[$index] có kind không hợp lệ.") }
                require(kind in enabledKinds) { "$JSON_KEY[$index] yêu cầu lớp âm thanh đang tắt." }
                val query = oneLine(row.optString("query")).take(MAX_QUERY_CHARS)
                require(query.isNotBlank()) { "$JSON_KEY[$index] thiếu query Freesound." }
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
                        add(
                            FreesoundAutoRequirement(
                                kind = kind,
                                query = query,
                                importance = importance,
                                startUnitId = start,
                                endUnitId = end,
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
                        require(repeat in 1..AudioDirectionLimits.MAX_SFX_REPEAT_COUNT) { "$JSON_KEY[$index] có repeat_count ngoài giới hạn." }
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

    private fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}

/**
 * Reduces API usage while preserving every timeline usage. Similar repeated queries share one
 * Freesound lookup and one downloaded asset; the resulting track can then be reused at every cue.
 */
object FreesoundAutoRequirementAggregator {
    const val MAX_MUSIC_SEARCHES = 3
    const val MAX_AMBIENCE_SEARCHES = 6
    const val MAX_SFX_SEARCHES = 15

    fun aggregate(requirements: List<FreesoundAutoRequirement>): List<FreesoundAutoSearchNeed> {
        if (requirements.isEmpty()) return emptyList()
        return AudioAssetKind.entries.flatMap { kind ->
            val limit = when (kind) {
                AudioAssetKind.MUSIC -> MAX_MUSIC_SEARCHES
                AudioAssetKind.AMBIENCE -> MAX_AMBIENCE_SEARCHES
                AudioAssetKind.SFX -> MAX_SFX_SEARCHES
            }
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
                .take(limit)
                .map { group ->
                    val representative = group.maxByOrNull { queryTokens(normalizeQuery(it.query)).size } ?: group.first()
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
