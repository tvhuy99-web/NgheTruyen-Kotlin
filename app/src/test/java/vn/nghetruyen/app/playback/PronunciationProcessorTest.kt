package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.data.local.PronunciationEntity

class PronunciationProcessorTest {
    private fun rule(id: Long, original: String, replacement: String, enabled: Boolean = true) =
        PronunciationEntity(id, original, replacement, enabled, 0, 0)

    @Test fun longestRuleWins() {
        val rules = listOf(rule(1, "AI", "ây ai"), rule(2, "AI model", "mô hình ai"))
        assertEquals("mô hình ai mới", PronunciationProcessor.apply("AI model mới", rules))
    }

    @Test fun replacementDoesNotCascade() {
        val rules = listOf(rule(1, "A", "B"), rule(2, "B", "C"))
        assertEquals("B", PronunciationProcessor.apply("A", rules))
    }

    @Test fun disabledAndEmptyRulesAreIgnored() {
        val rules = listOf(rule(1, "GPU", "gi pi diu", false), rule(2, " ", "x"))
        assertEquals("GPU", PronunciationProcessor.apply("GPU", rules))
    }
}
