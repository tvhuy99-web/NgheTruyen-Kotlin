package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object AiLineProtocol {
    data class XpkParseOptions(
        val validDialogueIds: List<String>,
        val validUnitIds: List<String>,
        val validVoiceIds: List<String>,
        val validTrackIds: List<String> = emptyList(),
        val includeVoiceCast: Boolean = true,
        val includeSceneMusic: Boolean = false,
        val speedLimitPct: Float = 10f,
        val pitchLimitPct: Float = 10f,
        val volumeLimitPct: Float = 10f,
        val expressiveAdjustment: Boolean = true,
    )

    fun parseXpkNarration(raw: String, options: XpkParseOptions): NarrationPlan {
        val root = JSONObject(extractJsonObject(raw))
        val voicePlan = if (options.includeVoiceCast) parseXpkVoiceCast(root, options) else VoiceCastPlan(emptyList(), emptyList())
        val scenes = if (options.includeSceneMusic) parseXpkMusicScenes(root, options) else emptyList()
        return NarrationPlan(voiceCast = voicePlan, musicCues = scenes)
    }

    private fun parseXpkVoiceCast(root: JSONObject, options: XpkParseOptions): VoiceCastPlan {
        val source = root.optJSONArray("assignments") ?: error("AI không trả đúng JSON assignments")
        val validIds = options.validDialogueIds.map(String::trim).filter(String::isNotBlank)
        val idSet = validIds.toHashSet()
        val voices = options.validVoiceIds.map(String::trim).filter(String::isNotBlank).distinct()
        val voiceSet = voices.toHashSet()
        val fallbackVoice = voices.firstOrNull { it != XpkVoiceCastSplitter.NARRATOR_ID }
            ?: voices.firstOrNull()
            ?: error("Danh sách giọng hợp lệ đang trống")
        val hasCharacterVoice = voices.any { it != XpkVoiceCastSplitter.NARRATOR_ID }

        val assignmentMap = linkedMapOf<String, ParagraphVoiceAssignment>()
        val duplicateIds = mutableListOf<String>()
        val unexpectedIds = mutableListOf<String>()
        val invalidVoiceIds = mutableListOf<String>()

        for (index in 0 until source.length()) {
            val row = source.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            val requestedVoice = row.optString("voice").trim()
            if (id !in idSet) {
                if (id.isNotBlank()) unexpectedIds += id
                continue
            }
            if (assignmentMap.containsKey(id)) {
                duplicateIds += id
                continue
            }
            val validDialogueVoice = requestedVoice in voiceSet &&
                (requestedVoice != XpkVoiceCastSplitter.NARRATOR_ID || !hasCharacterVoice)
            val selectedVoice = if (validDialogueVoice) requestedVoice else fallbackVoice
            if (!validDialogueVoice) invalidVoiceIds += id
            val speed = if (options.expressiveAdjustment && selectedVoice != XpkVoiceCastSplitter.NARRATOR_ID) {
                adjustment(row, "speed_adjust_pct", listOf("speed_pct", "speed_delta_pct", "speedAdjustPct", "speed_adjustment_pct", "rate_adjust_pct", "rate_pct", "speed", "rate"), options.speedLimitPct)
            } else 0f
            val pitch = if (options.expressiveAdjustment && selectedVoice != XpkVoiceCastSplitter.NARRATOR_ID) {
                adjustment(row, "pitch_adjust_pct", listOf("pitch_pct", "pitch_delta_pct", "pitchAdjustPct", "pitch_adjustment_pct", "pitch"), options.pitchLimitPct)
            } else 0f
            val volume = if (options.expressiveAdjustment && selectedVoice != XpkVoiceCastSplitter.NARRATOR_ID) {
                adjustment(row, "volume_adjust_pct", listOf("volume_pct", "volume_delta_pct", "volumeAdjustPct", "volume_adjustment_pct", "gain_adjust_pct", "gain_pct", "volume", "gain"), options.volumeLimitPct)
            } else 0f
            assignmentMap[id] = ParagraphVoiceAssignment(
                paragraphIndex = paragraphIndexFromUnitId(id),
                confidence = 1f,
                speedAdjustPct = speed,
                pitchAdjustPct = pitch,
                volumeAdjustPct = volume,
                unitId = id,
                voiceId = selectedVoice,
            )
        }

        if (validIds.isNotEmpty() && assignmentMap.isEmpty()) error("Không có ID hợp lệ nào trong phản hồi AI")

        val missingIds = mutableListOf<String>()
        val assignments = validIds.map { id ->
            assignmentMap[id] ?: ParagraphVoiceAssignment(
                paragraphIndex = paragraphIndexFromUnitId(id),
                confidence = 1f,
                unitId = id,
                voiceId = fallbackVoice,
            ).also { missingIds += id }
        }
        val warnings = buildList {
            if (missingIds.isNotEmpty()) add("${missingIds.size} ID thiếu được dùng $fallbackVoice")
            if (duplicateIds.isNotEmpty()) add("${duplicateIds.size} ID lặp đã bị bỏ")
            if (unexpectedIds.isNotEmpty()) add("${unexpectedIds.size} ID lạ đã bị bỏ")
            if (invalidVoiceIds.isNotEmpty()) add("${invalidVoiceIds.size} mã giọng sai được dùng $fallbackVoice")
        }
        return VoiceCastPlan(emptyList(), assignments, warnings)
    }

    private fun parseXpkMusicScenes(root: JSONObject, options: XpkParseOptions): List<SceneMusicCue> {
        val source = root.optJSONArray("music_scenes") ?: error("Kết quả không có music_scenes")
        if (source.length() == 0) error("Kết quả không có music_scenes")
        val order = options.validUnitIds.withIndex().associate { it.value to it.index }
        if (order.isEmpty()) error("Danh sách UNIT hợp lệ đang trống")
        val trackSet = options.validTrackIds.toHashSet()
        if (trackSet.isEmpty()) error("Danh sách bài nhạc hợp lệ đang trống")
        return buildList {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: continue
                val startId = row.optString("start_id").trim()
                val endId = row.optString("end_id").trim()
                val trackId = row.optString("track_id").trim()
                val startOrder = order[startId] ?: continue
                val endOrder = order[endId] ?: continue
                if (endOrder < startOrder || trackId !in trackSet) continue
                add(
                    SceneMusicCue(
                        startParagraph = paragraphIndexFromUnitId(startId).coerceAtLeast(0),
                        trackId = trackId,
                        volume = 0.25f,
                        mood = "",
                        startUnitId = startId,
                        endUnitId = endId,
                    ),
                )
            }
        }
    }

    /** Legacy parser kept until the milestone-5 database/runtime migration removes paragraph contracts. */
    fun parseVoiceCast(raw: String): VoiceCastPlan {
        val roles = LinkedHashMap<String, VoiceRole>()
        val assignments = mutableListOf<ParagraphVoiceAssignment>()
        raw.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            val parts = line.split('|').map(String::trim)
            when (parts.firstOrNull()?.uppercase(Locale.ROOT)) {
                "ROLE" -> if (parts.size >= 2) {
                    val name = parts[1].take(80)
                    if (name.isNotBlank()) {
                        val expression = parts.getOrElse(3) { "NEUTRAL" }.uppercase(Locale.ROOT)
                            .takeIf { it in setOf("NEUTRAL", "CALM", "WARM", "SAD", "TENSE", "ANGRY", "EXCITED", "WHISPER") }
                            ?: "NEUTRAL"
                        roles.putIfAbsent(
                            name.lowercase(Locale.ROOT),
                            VoiceRole(
                                name,
                                aliases = parts.getOrElse(2) { "" }.split(',').map(String::trim).filter(String::isNotBlank).take(20),
                                expression = expression,
                            ),
                        )
                    }
                }
                "ASSIGN" -> if (parts.size >= 3) {
                    val index = parts[1].toIntOrNull() ?: return@forEach
                    if (index < 0) return@forEach
                    assignments += ParagraphVoiceAssignment(
                        paragraphIndex = index,
                        character = parts[2].take(80),
                        confidence = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f,
                        speedAdjustPct = parts.getOrNull(4)?.toFloatOrNull() ?: 0f,
                        pitchAdjustPct = parts.getOrNull(5)?.toFloatOrNull() ?: 0f,
                        volumeAdjustPct = parts.getOrNull(6)?.toFloatOrNull() ?: 0f,
                    )
                }
            }
        }
        if (roles.values.none { it.character.equals("Người kể chuyện", true) }) {
            roles["người kể chuyện"] = VoiceRole("Người kể chuyện", aliases = listOf("narrator"))
        }
        require(roles.size <= 40) { "AI trả quá nhiều vai." }
        require(assignments.size <= 20_000) { "AI trả quá nhiều ánh xạ đoạn." }
        return VoiceCastPlan(roles.values.toList(), assignments.distinctBy { it.paragraphIndex })
    }

    fun parseSceneCues(raw: String): List<SceneMusicCue> = raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("CUE|", ignoreCase = true) }
        .mapNotNull { line ->
            val parts = line.split('|').map(String::trim)
            val index = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val track = parts.getOrNull(2)?.takeIf(String::isNotBlank)?.take(120) ?: return@mapNotNull null
            SceneMusicCue(index.coerceAtLeast(0), track, parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.25f, parts.getOrElse(4) { "" }.take(160))
        }
        .distinctBy { it.startParagraph }
        .sortedBy { it.startParagraph }
        .take(12)
        .toList()

    fun paragraphIndexFromUnitId(unitId: String): Int {
        if (unitId == "TITLE-U01") return 0
        val paragraph = Regex("^P(\\d{4})-U\\d{2}$").matchEntire(unitId)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return -1
        return (paragraph - 1).coerceAtLeast(0)
    }

    private fun adjustment(row: JSONObject, primary: String, aliases: List<String>, limit: Float): Float {
        val keys = listOf(primary) + aliases
        fun value(container: JSONObject): Any? {
            keys.forEach { key -> if (container.has(key)) return container.opt(key) }
            return null
        }
        val raw = value(row)
            ?: row.optJSONObject("adjustments")?.let(::value)
            ?: row.optJSONObject("prosody")?.let(::value)
            ?: return 0f
        val number = when (raw) {
            is Number -> raw.toFloat()
            else -> Regex("[-+]?\\d+(?:[.,]\\d+)?").find(raw.toString())?.value?.replace(',', '.')?.toFloatOrNull()
        } ?: 0f
        return number.coerceIn(-limit.coerceAtLeast(0f), limit.coerceAtLeast(0f))
    }

    private fun extractJsonObject(raw: String): String {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end >= start) { "AI không trả JSON hợp lệ." }
        return clean.substring(start, end + 1)
    }
}
