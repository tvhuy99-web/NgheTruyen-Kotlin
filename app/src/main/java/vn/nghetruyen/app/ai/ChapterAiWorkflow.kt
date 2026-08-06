package vn.nghetruyen.app.ai

import java.security.MessageDigest

object ChapterAiWorkflow {
    fun markedParagraphs(paragraphs: List<String>): String = paragraphs.mapIndexed { index, paragraph ->
        "[[P:$index]] ${paragraph.trim()}"
    }.joinToString("\n\n")

    fun parseMarkedParagraphs(raw: String, expectedCount: Int): List<String> {
        require(expectedCount in 1..20_000) { "Số đoạn không hợp lệ." }
        val marker = Regex("(?m)^\\s*\\[\\[P:(\\d+)]]\\s*")
        val matches = marker.findAll(raw).toList()
        require(matches.isNotEmpty()) { "Bản dịch không chứa marker đoạn." }
        val values = MutableList<String?>(expectedCount) { null }
        matches.forEachIndexed { order, match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            if (index !in values.indices) return@forEachIndexed
            val start = match.range.last + 1
            val end = matches.getOrNull(order + 1)?.range?.first ?: raw.length
            values[index] = raw.substring(start, end).trim()
        }
        require(values.all { !it.isNullOrBlank() }) { "Bản dịch thiếu một hoặc nhiều đoạn." }
        return values.map { it.orEmpty() }
    }

    fun sha256(paragraphs: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paragraphs.forEachIndexed { index, value ->
            digest.update(index.toString().toByteArray())
            digest.update(0)
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }


    fun translationFingerprint(
        paragraphs: List<String>,
        endpoint: String,
        model: String,
        instruction: String,
    ): String = sha256(
        paragraphs + listOf(
            "\u0001endpoint=${endpoint.trim()}",
            "\u0001model=${model.trim()}",
            "\u0001instruction=${instruction.trim()}",
        ),
    )

    fun serializeParagraphs(paragraphs: List<String>): String = paragraphs.joinToString(PARAGRAPH_SEPARATOR)
    fun deserializeParagraphs(value: String): List<String> = value.split(PARAGRAPH_SEPARATOR).map(String::trim).filter(String::isNotBlank)

    const val KIND_VIETPHRASE = "VIETPHRASE"
    const val KIND_AI_TRANSLATION = "AI_TRANSLATION"
    const val KIND_VOICE_CAST = "VOICE_CAST"
    const val KIND_SCENE_MUSIC = "SCENE_MUSIC"
    private const val PARAGRAPH_SEPARATOR = "\n\u0000\n"
}
