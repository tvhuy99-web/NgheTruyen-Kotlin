package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import vn.nghetruyen.app.audio.AmbienceSfxPlan

object AiLineProtocol {
    data class XpkParseOptions(
        val validDialogueIds: List<String>,
        val validUnitIds: List<String>,
        val validVoiceIds: List<String>,
        val validTrackIds: List<String> = emptyList(),
        val validAmbienceIds: Set<String> = emptySet(),
        val validSfxIds: Set<String> = emptySet(),
        val includeVoiceCast: Boolean = true,
        val includeSceneMusic: Boolean = false,
        val includeAmbience: Boolean = false,
        val includeSoundEffects: Boolean = false,
        val speedLimitPct: Float = 10f,
        val pitchLimitPct: Float = 10f,
        val volumeLimitPct: Float = 10f,
        val expressiveAdjustment: Boolean = true,
        val incomingTrackId: String? = null,
        val dialogueGroupByUnitId: Map<String, String> = emptyMap(),
    )

    data class XpkRawAssignment(
        val id: String,
        val voice: String,
        val speedAdjustPct: Float = 0f,
        val pitchAdjustPct: Float = 0f,
        val volumeAdjustPct: Float = 0f,
    )

    fun parseXpkNarration(raw: String, options: XpkParseOptions): NarrationPlan {
        val root = JSONObject(extractJsonObject(raw))
        val voicePlan = if (options.includeVoiceCast) parseXpkVoiceCast(root, options) else VoiceCastPlan(emptyList(), emptyList())
        var musicSceneError = ""
        val scenes = if (options.includeSceneMusic) {
            runCatching { parseXpkMusicScenes(root, options) }.getOrElse { error ->
                val fallback = XpkSceneMusicParity.fallbackScene(
                    validUnitIds = options.validUnitIds,
                    validTrackIds = options.validTrackIds,
                    incomingTrackId = options.incomingTrackId,
                )
                if (fallback.isNotEmpty()) {
                    musicSceneError = (error.message ?: "Kết quả nhạc không hợp lệ") + "; đã phục hồi bằng một cảnh liên tục"
                    fallback
                } else {
                    musicSceneError = error.message ?: "Kết quả nhạc không hợp lệ"
                    emptyList()
                }
            }
        } else emptyList()

        var audioDirectionError = ""
        val audioPlan = if (options.includeAmbience || options.includeSoundEffects) {
            runCatching {
                if (options.includeAmbience) {
                    require(root.has("ambience_scenes")) { "AI không trả ambience_scenes dù lớp ambience đang bật." }
                }
                if (options.includeSoundEffects) {
                    require(root.has("sfx_cues")) { "AI không trả sfx_cues dù lớp SFX đang bật." }
                }
                XpkAmbienceSfxDirector.parseAndValidate(
                    JSONObject()
                        .put("ambience_scenes", root.optJSONArray("ambience_scenes") ?: JSONArray())
                        .put("sfx_cues", root.optJSONArray("sfx_cues") ?: JSONArray())
                        .toString(),
                    validUnitIds = options.validUnitIds,
                    validAmbienceIds = options.validAmbienceIds,
                    validSfxIds = options.validSfxIds,
                    ambienceEnabled = options.includeAmbience,
                    soundEffectsEnabled = options.includeSoundEffects,
                )
            }.getOrElse { error ->
                audioDirectionError = error.message ?: "Kết quả ambience/SFX không hợp lệ"
                AmbienceSfxPlan()
            }
        } else AmbienceSfxPlan()

        return NarrationPlan(
            voiceCast = voicePlan,
            musicCues = scenes,
            musicSceneError = musicSceneError,
            ambienceScenes = audioPlan.ambienceScenes,
            soundEffectCues = audioPlan.soundEffectCues,
            audioDirectionError = audioDirectionError,
        )
    }

    private fun parseXpkVoiceCast(root: JSONObject, options: XpkParseOptions): VoiceCastPlan {
        val source = root.optJSONArray("assignments") ?: error("AI không trả đúng JSON assignments")
        val rows = buildList {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: continue
                add(
                    XpkRawAssignment(
                        id = row.optString("id").trim(),
                        voice = row.optString("voice").trim(),
                        speedAdjustPct = adjustment(
                            row,
                            "speed_adjust_pct",
                            listOf("speed_pct", "speed_delta_pct", "speedAdjustPct", "speed_adjustment_pct", "rate_adjust_pct", "rate_pct", "speed", "rate"),
                        ),
                        pitchAdjustPct = adjustment(
                            row,
                            "pitch_adjust_pct",
                            listOf("pitch_pct", "pitch_delta_pct", "pitchAdjustPct", "pitch_adjustment_pct", "pitch"),
                        ),
                        volumeAdjustPct = adjustment(
                            row,
                            "volume_adjust_pct",
                            listOf("volume_pct", "volume_delta_pct", "volumeAdjustPct", "volume_adjustment_pct", "gain_adjust_pct", "gain_pct", "volume", "gain"),
                        ),
                    ),
                )
            }
        }
        return repairXpkAssignments(rows, options)
    }

    fun repairXpkAssignments(rows: List<XpkRawAssignment>, options: XpkParseOptions): VoiceCastPlan {
        val validIds = options.validDialogueIds.map(String::trim).filter(String::isNotBlank)
        val idSet = validIds.toHashSet()
        val voices = options.validVoiceIds.map(String::trim).filter(String::isNotBlank).distinct()
        val voiceSet = voices.toHashSet()
        val fallbackVoice = voices.firstOrNull { it != XpkVoiceCastSplitter.NARRATOR_ID }
            ?: voices.firstOrNull()
            ?: error("Danh sách giọng hợp lệ đang trống")
        val hasCharacterVoice = voices.any { it != XpkVoiceCastSplitter.NARRATOR_ID }
        val assignmentMap = linkedMapOf<String, ParagraphVoiceAssignment>()
        val explicitCharacterVoiceById = linkedMapOf<String, String>()
        var duplicateCount = 0
        var unexpectedCount = 0
        var invalidVoiceCount = 0

        rows.forEach { row ->
            val id = row.id.trim()
            val requestedVoice = row.voice.trim()
            if (id !in idSet) {
                if (id.isNotBlank()) unexpectedCount += 1
                return@forEach
            }
            if (assignmentMap.containsKey(id)) {
                duplicateCount += 1
                return@forEach
            }
            if (requestedVoice in voiceSet && requestedVoice != XpkVoiceCastSplitter.NARRATOR_ID) {
                explicitCharacterVoiceById[id] = requestedVoice
            }
            val validDialogueVoice = requestedVoice in voiceSet &&
                (requestedVoice != XpkVoiceCastSplitter.NARRATOR_ID || !hasCharacterVoice)
            val selectedVoice = if (validDialogueVoice) requestedVoice else fallbackVoice
            if (!validDialogueVoice) invalidVoiceCount += 1
            val adjustmentsEnabled = options.expressiveAdjustment && selectedVoice != XpkVoiceCastSplitter.NARRATOR_ID
            assignmentMap[id] = ParagraphVoiceAssignment(
                paragraphIndex = paragraphIndexFromUnitId(id),
                confidence = 1f,
                speedAdjustPct = if (adjustmentsEnabled) clamp(row.speedAdjustPct, options.speedLimitPct) else 0f,
                pitchAdjustPct = if (adjustmentsEnabled) clamp(row.pitchAdjustPct, options.pitchLimitPct) else 0f,
                volumeAdjustPct = if (adjustmentsEnabled) clamp(row.volumeAdjustPct, options.volumeLimitPct) else 0f,
                unitId = id,
                voiceId = selectedVoice,
            )
        }
        if (validIds.isNotEmpty() && assignmentMap.isEmpty()) error("Không có ID hợp lệ nào trong phản hồi AI")

        val groupVoice = linkedMapOf<String, String>()
        validIds.forEach { id ->
            val group = options.dialogueGroupByUnitId[id]?.trim().orEmpty()
            val explicitVoice = explicitCharacterVoiceById[id].orEmpty()
            if (group.isNotBlank() && group !in groupVoice && explicitVoice.isNotBlank()) {
                groupVoice[group] = explicitVoice
            }
        }

        var missingCount = 0
        val assignments = validIds.map { id ->
            val base = assignmentMap[id] ?: ParagraphVoiceAssignment(
                paragraphIndex = paragraphIndexFromUnitId(id),
                confidence = 1f,
                unitId = id,
                voiceId = fallbackVoice,
            ).also { missingCount += 1 }
            val group = options.dialogueGroupByUnitId[id]?.trim().orEmpty()
            val forcedVoice = groupVoice[group]
            if (forcedVoice != null && base.voiceId != forcedVoice) base.copy(voiceId = forcedVoice) else base
        }
        val warnings = buildList {
            if (missingCount > 0) add("$missingCount ID thiếu được dùng $fallbackVoice")
            if (duplicateCount > 0) add("$duplicateCount ID lặp đã bị bỏ")
            if (unexpectedCount > 0) add("$unexpectedCount ID lạ đã bị bỏ")
            if (invalidVoiceCount > 0) add("$invalidVoiceCount mã giọng sai được dùng $fallbackVoice")
        }
        return VoiceCastPlan(emptyList(), assignments, warnings)
    }

    private fun parseXpkMusicScenes(root: JSONObject, options: XpkParseOptions): List<SceneMusicCue> {
        val source = root.optJSONArray("music_scenes") ?: error("Kết quả không có music_scenes")
        if (source.length() == 0) error("Kết quả không có music_scenes")
        val rows = buildList {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: error("Cảnh nhạc thứ ${index + 1} không phải đối tượng")
                add(
                    XpkSceneMusicParity.RawScene(
                        startId = row.optString("start_id").ifBlank { row.optString("start_unit_id") }.trim(),
                        endId = row.optString("end_id").ifBlank { row.optString("end_unit_id") }.trim(),
                        trackId = row.optString("track_id")
                            .ifBlank { row.optString("selected_track_id") }
                            .ifBlank { row.optString("music_track_id") }
                            .trim(),
                    ),
                )
            }
        }
        return XpkSceneMusicParity.validateScenes(rows, options.validUnitIds, options.validTrackIds)
    }

    @Deprecated("Use parseXpkNarration; paragraph ROLE/ASSIGN protocol is not used by XPK narration runtime")
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

    @Deprecated("Use parseXpkNarration; paragraph CUE protocol is not used by XPK narration runtime")
    fun parseSceneCues(raw: String): List<SceneMusicCue> = raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("CUE|", ignoreCase = true) }
        .mapNotNull { line ->
            val parts = line.split('|').map(String::trim)
            val index = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val track = parts.getOrNull(2)?.takeIf(String::isNotBlank)?.take(120) ?: return@mapNotNull null
            SceneMusicCue(
                startParagraph = index.coerceAtLeast(0),
                trackId = track,
                volume = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.25f,
                mood = parts.getOrElse(4) { "" }.take(160),
            )
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

    private fun adjustment(row: JSONObject, primary: String, aliases: List<String>): Float {
        val keys = listOf(primary) + aliases
        fun value(container: JSONObject): Any? {
            keys.forEach { key -> if (container.has(key)) return container.opt(key) }
            return null
        }
        val raw = value(row)
            ?: row.optJSONObject("adjustments")?.let(::value)
            ?: row.optJSONObject("prosody")?.let(::value)
            ?: return 0f
        return when (raw) {
            is Number -> raw.toFloat()
            else -> Regex("[-+]?\\d+(?:[.,]\\d+)?").find(raw.toString())?.value?.replace(',', '.')?.toFloatOrNull()
        } ?: 0f
    }

    private fun clamp(value: Float, limit: Float): Float {
        val safe = limit.coerceAtLeast(0f)
        return value.coerceIn(-safe, safe)
    }

    private fun extractJsonObject(raw: String): String {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end >= start) { "AI không trả JSON hợp lệ." }
        return clean.substring(start, end + 1)
    }
}
