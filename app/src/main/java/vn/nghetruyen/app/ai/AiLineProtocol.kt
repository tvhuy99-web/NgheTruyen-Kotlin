package vn.nghetruyen.app.ai

import java.util.Locale

object AiLineProtocol {
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
}
