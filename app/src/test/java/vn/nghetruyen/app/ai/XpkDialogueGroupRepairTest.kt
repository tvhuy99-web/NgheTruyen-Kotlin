package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class XpkDialogueGroupRepairTest {
    @Test
    fun laterExplicitCharacterVoiceBeatsEarlierNarratorInsideSameGroup() {
        val first = "P0001-U01"
        val second = "P0001-U02"
        val plan = AiLineProtocol.repairXpkAssignments(
            rows = listOf(
                AiLineProtocol.XpkRawAssignment(first, XpkVoiceCastSplitter.NARRATOR_ID, speedAdjustPct = 4f),
                AiLineProtocol.XpkRawAssignment(second, "voice-female", speedAdjustPct = -3f),
            ),
            options = AiLineProtocol.XpkParseOptions(
                validDialogueIds = listOf(first, second),
                validUnitIds = listOf(first, second),
                validVoiceIds = listOf(XpkVoiceCastSplitter.NARRATOR_ID, "voice-male", "voice-female"),
                dialogueGroupByUnitId = mapOf(first to "P0001-D01", second to "P0001-D01"),
            ),
        )

        assertEquals(listOf("voice-female", "voice-female"), plan.assignments.map { it.voiceId })
        assertEquals(0f, plan.assignments[0].speedAdjustPct)
        assertEquals(-3f, plan.assignments[1].speedAdjustPct)
    }
}
