package vn.nghetruyen.app.playback

import vn.nghetruyen.app.data.local.PronunciationEntity






object PronunciationProcessor {
    fun apply(text: String, rules: List<PronunciationEntity>): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        val enabled = rules.asSequence()
            .filter(PronunciationEntity::enabled)
            .map { it.copy(original = it.original.trim(), replacement = it.replacement.trim()) }
            .filter { it.original.isNotEmpty() && it.replacement.isNotEmpty() }
            .sortedWith(compareByDescending<PronunciationEntity> { it.original.length }.thenBy { it.original })
            .toList()
        if (enabled.isEmpty()) return text

        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val match = enabled.firstOrNull { rule -> text.regionMatches(index, rule.original, 0, rule.original.length) }
            if (match == null) {
                output.append(text[index])
                index += 1
            } else {
                output.append(match.replacement)
                index += match.original.length
            }
        }
        return output.toString()
    }
}
