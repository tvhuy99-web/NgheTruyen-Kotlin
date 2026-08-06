package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLineProtocolTest {
    @Test fun parsesVoiceRolesAndKeepsOneAssignmentPerParagraph() {
        val plan = AiLineProtocol.parseVoiceCast("""
            ROLE|Lan|Tiểu Lan
            ASSIGN|2|Lan|0.9
            ASSIGN|2|Người khác|0.7
            ASSIGN|-1|Lan|0.8
        """.trimIndent())
        assertTrue(plan.roles.any { it.character == "Lan" })
        assertTrue(plan.roles.any { it.character.equals("Người kể chuyện", true) })
        assertEquals(1, plan.assignments.size)
        assertEquals(2, plan.assignments.single().paragraphIndex)
    }

    @Test fun sceneCuesAreSortedDeduplicatedAndClamped() {
        val cues = AiLineProtocol.parseSceneCues("""
            CUE|8|calm|2.0|cao trào
            CUE|2|soft|-1|êm
            CUE|2|other|0.5|trùng
        """.trimIndent())
        assertEquals(listOf(2, 8), cues.map { it.startParagraph })
        assertEquals(0f, cues[0].volume)
        assertEquals(1f, cues[1].volume)
    }
}
