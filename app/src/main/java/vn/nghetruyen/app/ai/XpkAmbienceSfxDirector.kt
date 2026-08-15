package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.audio.AmbienceScene
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.SoundEffectCue

/**
 * Validator/persistence codec for the Ambience and SFX portions of the unified XPK chapter plan.
 * Prompt composition lives only in [XpkUnifiedNarrationPrompt] so there is no second AI contract.
 *
 * Ambience keeps the v1 persisted schema (one ambience_id per row), but two rows may overlap to
 * express two compatible logical ambience layers. This keeps existing exports/backups readable.
 */
object XpkAmbienceSfxDirector {
    const val ENGINE = "xpk-audio-direction-v1"

    private data class IndexedAmbience(
        val start: Int,
        val end: Int,
        val ambienceId: String,
    )

    fun parseAndValidate(
        raw: String,
        validUnitIds: List<String>,
        validAmbienceIds: Set<String>,
        validSfxIds: Set<String>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
    ): AmbienceSfxPlan {
        require(validUnitIds.isNotEmpty()) { "Timeline UNIT hợp lệ đang trống." }
        val root = JSONObject(raw.trim())
        val keys = root.keys().asSequence().toSet()
        require(keys == setOf("ambience_scenes", "sfx_cues")) {
            "Kết quả audio direction nội bộ phải có ambience_scenes và sfx_cues."
        }
        val order = validUnitIds.withIndex().associate { it.value to it.index }

        val ambienceArray = root.optJSONArray("ambience_scenes") ?: JSONArray()
        val indexedAmbience = mutableListOf<IndexedAmbience>()
        var previousStart = -1
        for (index in 0 until ambienceArray.length()) {
            val row = ambienceArray.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("start_id", "end_id", "ambience_id")) {
                "ambience_scenes[$index] có trường không hợp lệ."
            }
            val startId = row.getString("start_id").trim()
            val endId = row.getString("end_id").trim()
            val ambienceId = row.getString("ambience_id").trim()
            val start = order[startId] ?: error("ambience_scenes[$index] dùng start_id không tồn tại.")
            val end = order[endId] ?: error("ambience_scenes[$index] dùng end_id không tồn tại.")
            require(ambienceEnabled) { "AI trả ambience trong khi lớp ambience đang tắt." }
            require(ambienceId in validAmbienceIds) { "ambience_scenes[$index] dùng ambience_id không tồn tại." }
            require(end >= start) { "ambience_scenes[$index] có ranh giới đảo ngược." }
            require(start >= previousStart) { "ambience_scenes sai thứ tự timeline." }
            val minimumSpan = AudioDirectionLimits.MIN_AMBIENCE_SCENE_UNITS.coerceAtMost(validUnitIds.size)
            require(end - start + 1 >= minimumSpan) {
                "ambience_scenes[$index] quá ngắn; ambience phải đủ bền để tránh bật/tắt theo một câu thoáng qua."
            }
            indexedAmbience += IndexedAmbience(start, end, ambienceId)
            previousStart = start
        }

        validateAmbienceConcurrency(indexedAmbience, validUnitIds.size)
        val ambienceScenes = mergeAdjacentAmbience(indexedAmbience, validUnitIds)
        validateAmbienceConcurrency(
            ambienceScenes.map { scene ->
                IndexedAmbience(
                    start = order.getValue(scene.startUnitId),
                    end = order.getValue(scene.endUnitId),
                    ambienceId = scene.ambienceId,
                )
            },
            validUnitIds.size,
        )

        val sfxArray = root.optJSONArray("sfx_cues") ?: JSONArray()
        val maxSfx = maxSfxForUnits(validUnitIds.size)
        require(sfxArray.length() <= maxSfx) { "AI trả quá nhiều SFX cho một chương." }
        val soundEffectCues = mutableListOf<SoundEffectCue>()
        val usedUnits = hashSetOf<String>()
        var previousSfxIndex = -1
        for (index in 0 until sfxArray.length()) {
            val row = sfxArray.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("unit_id", "effect_id")) {
                "sfx_cues[$index] có trường không hợp lệ."
            }
            val unitId = row.getString("unit_id").trim()
            val effectId = row.getString("effect_id").trim()
            val unitIndex = order[unitId] ?: error("sfx_cues[$index] dùng unit_id không tồn tại.")
            require(soundEffectsEnabled) { "AI trả SFX trong khi lớp SFX đang tắt." }
            require(effectId in validSfxIds) { "sfx_cues[$index] dùng effect_id không tồn tại." }
            require(unitIndex >= previousSfxIndex) { "sfx_cues sai thứ tự timeline." }
            require(usedUnits.add(unitId)) { "Mỗi UNIT chỉ được có tối đa một SFX." }
            soundEffectCues += SoundEffectCue(unitId, effectId)
            previousSfxIndex = unitIndex
        }

        if (!ambienceEnabled) require(ambienceScenes.isEmpty())
        if (!soundEffectsEnabled) require(soundEffectCues.isEmpty())
        return AmbienceSfxPlan(ambienceScenes, soundEffectCues)
    }

    fun encode(plan: AmbienceSfxPlan): String = JSONObject()
        .put("engine", ENGINE)
        .put(
            "ambience_scenes",
            JSONArray().also { array ->
                plan.ambienceScenes.forEach { cue ->
                    array.put(
                        JSONObject()
                            .put("start_id", cue.startUnitId)
                            .put("end_id", cue.endUnitId)
                            .put("ambience_id", cue.ambienceId),
                    )
                }
            },
        )
        .put(
            "sfx_cues",
            JSONArray().also { array ->
                plan.soundEffectCues.forEach { cue ->
                    array.put(JSONObject().put("unit_id", cue.unitId).put("effect_id", cue.effectId))
                }
            },
        )
        .toString()

    fun decodePersisted(
        text: String,
        validUnitIds: List<String>,
        validAmbienceIds: Set<String>,
        validSfxIds: Set<String>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
    ): AmbienceSfxPlan {
        val root = JSONObject(text)
        require(root.optString("engine") == ENGINE) { "Audio direction cache dùng engine cũ." }
        val raw = JSONObject()
            .put("ambience_scenes", root.optJSONArray("ambience_scenes") ?: JSONArray())
            .put("sfx_cues", root.optJSONArray("sfx_cues") ?: JSONArray())
            .toString()
        return parseAndValidate(
            raw,
            validUnitIds,
            validAmbienceIds,
            validSfxIds,
            ambienceEnabled,
            soundEffectsEnabled,
        )
    }

    private fun validateAmbienceConcurrency(rows: List<IndexedAmbience>, unitCount: Int) {
        if (rows.isEmpty()) return
        val activeIds = Array(unitCount) { linkedSetOf<String>() }
        rows.forEachIndexed { rowIndex, row ->
            for (unit in row.start..row.end) {
                require(activeIds[unit].add(row.ambienceId)) {
                    "ambience_scenes[$rowIndex] lặp cùng ambience_id trên cùng một UNIT."
                }
                require(activeIds[unit].size <= AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE) {
                    "Mỗi UNIT chỉ được có tối đa ${AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE} ambience đồng thời."
                }
            }
        }
    }

    private fun mergeAdjacentAmbience(
        rows: List<IndexedAmbience>,
        validUnitIds: List<String>,
    ): List<AmbienceScene> {
        if (rows.isEmpty()) return emptyList()
        val merged = mutableListOf<IndexedAmbience>()
        rows.groupBy(IndexedAmbience::ambienceId).forEach { (ambienceId, sameAssetRows) ->
            var current: IndexedAmbience? = null
            sameAssetRows.sortedWith(compareBy<IndexedAmbience> { it.start }.thenBy { it.end }).forEach { row ->
                val previous = current
                current = if (previous == null) {
                    row
                } else if (row.start <= previous.end + 1) {
                    previous.copy(end = maxOf(previous.end, row.end))
                } else {
                    merged += previous
                    row
                }
            }
            current?.let(merged::add)
        }
        return merged
            .sortedWith(compareBy<IndexedAmbience> { it.start }.thenBy { it.end }.thenBy { it.ambienceId })
            .map { row -> AmbienceScene(validUnitIds[row.start], validUnitIds[row.end], row.ambienceId) }
    }

    private fun maxSfxForUnits(unitCount: Int): Int = ((unitCount + 3) / 4).coerceIn(4, 48)
}
