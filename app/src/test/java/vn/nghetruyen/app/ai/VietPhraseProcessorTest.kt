package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.data.local.VietPhraseEntity

class VietPhraseProcessorTest {
    private fun rule(id: Long, source: String, target: String, priority: Int = 0, enabled: Boolean = true) =
        VietPhraseEntity(id, source, target, priority, enabled, 0L, 0L)

    @Test fun priorityThenLongestRuleWinsWithoutCascading() {
        val rules = listOf(
            rule(1, "Thiên", "Trời", priority = 1),
            rule(2, "Thiên Đạo", "Đạo Trời", priority = 10),
            rule(3, "Đạo Trời", "không được thay tiếp", priority = 100),
        )
        assertEquals("Đạo Trời vận hành", VietPhraseProcessor.apply("Thiên Đạo vận hành", rules))
    }

    @Test fun wordBoundariesPreventPartialReplacement() {
        val rules = listOf(rule(1, "AI", "ây ai"))
        assertEquals("RAIL và ây ai", VietPhraseProcessor.apply("RAIL và AI", rules))
    }

    @Test fun disabledRulesAreIgnored() {
        assertEquals("Kim Đan", VietPhraseProcessor.apply("Kim Đan", listOf(rule(1, "Kim Đan", "Jindan", enabled = false))))
    }
}
