package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XpkInlineDashDialogueRegressionTest {
    @Test
    fun compactSameLineDialogueRestartsCreateIndependentTurns() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "- Tao làm đúng hay sai?- Sao lại hỏi tao?- Vì tao không biết!- Thì tự nghĩ đi!",
        )

        assertEquals(4, units.size)
        assertTrue(units.all { it.isDialogue })
        assertEquals(
            listOf(
                "- Tao làm đúng hay sai?",
                "- Sao lại hỏi tao?",
                "- Vì tao không biết!",
                "- Thì tự nghĩ đi!",
            ),
            units.map { it.text },
        )
        assertEquals(4, units.mapNotNull { it.dialogueGroupId }.distinct().size)
    }

    @Test
    fun compactRestartAfterInlineAttributionReturnsToDialogue() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "- Cậu phải chịu trách nhiệm! – Nam tiếp lời.- Là sao? – Tôi ngơ ngác.- Không được chọn lại! – Nam đáp.",
        )

        assertEquals(6, units.size)
        assertEquals(
            listOf(true, false, true, false, true, false),
            units.map { it.isDialogue },
        )
        assertEquals("Nam tiếp lời.", units[1].text)
        assertEquals("- Là sao?", units[2].text)
        assertEquals("Tôi ngơ ngác.", units[3].text)
        assertEquals("- Không được chọn lại!", units[4].text)
        assertEquals("Nam đáp.", units[5].text)
    }

    @Test
    fun consecutiveClassroomPromptsOnOneLineStaySeparateDialogueTurns() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "- Ê, thầy gọi mày kìa!- Đứng dậy mau!- Nghe thấy chưa?",
        )

        assertEquals(3, units.size)
        assertTrue(units.all { it.isDialogue })
        assertEquals(3, units.mapNotNull { it.dialogueGroupId }.distinct().size)
    }

    @Test
    fun malformedOpeningCurlyQuoteWrapperIsTreatedAsInnerThought() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "- “ Người ấy có ai bên cạnh chứ? “",
        )

        assertEquals(1, units.size)
        assertFalse(units.single().isDialogue)
        assertEquals(XpkVoiceCastSplitter.NARRATOR_ID, units.single().fixedVoice)
        assertEquals("inner_thought", units.single().unitKind)
    }

    @Test
    fun punctuationOnlySilentTurnIsNotSentToTts() {
        val units = XpkVoiceCastSplitter.buildUnits("", "- ………!")
        assertTrue(units.isEmpty())
    }

    @Test
    fun hyphenInsideVersionOrWordDoesNotCreateFalseDialogueBoundary() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "- Bản 1.2-rc1 vẫn chạy bình thường, đúng không?",
        )

        assertEquals(1, units.size)
        assertTrue(units.single().isDialogue)
    }

    @Test
    fun malformedRepeatedOpeningCurlyQuoteIsRecoveredWithoutEatingNarration() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "Vy tủm tỉm cười trêu “ hai vợ chồng cùng dính nợ “, thằng K quệt mồ hôi hú thằng L lên phụ.",
        )

        assertEquals(3, units.size)
        assertFalse(units[0].isDialogue)
        assertEquals("Vy tủm tỉm cười trêu", units[0].text)
        assertTrue(units[1].isDialogue)
        assertEquals("“ hai vợ chồng cùng dính nợ “", units[1].text)
        assertFalse(units[2].isDialogue)
        assertEquals(", thằng K quệt mồ hôi hú thằng L lên phụ.", units[2].text)
    }

    @Test
    fun dashPrefixedThirdPersonNarrationIsNotForcedIntoCharacterVoice() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "- Và nàng bỏ ra ngoài trước ánh mắt ngạc nhiên của mọi người.",
        )

        assertEquals(1, units.size)
        assertFalse(units.single().isDialogue)
        assertEquals(XpkVoiceCastSplitter.NARRATOR_ID, units.single().fixedVoice)
        assertEquals("narration", units.single().unitKind)
    }

    @Test
    fun realQuestionAboutThirdPersonSubjectRemainsDialogue() {
        val units = XpkVoiceCastSplitter.buildUnits("", "- Nàng đi đâu rồi?")

        assertEquals(1, units.size)
        assertTrue(units.single().isDialogue)
    }

    @Test
    fun shortQuotedTermInsideNarrationStaysWithNarrator() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "Nghe đến \"Cổ Thần cấp năm\", Tô Tông sững người lại.",
        )

        assertEquals(1, units.size)
        assertFalse(units.single().isDialogue)
        assertEquals(XpkVoiceCastSplitter.NARRATOR_ID, units.single().fixedVoice)
        assertEquals("Nghe đến \"Cổ Thần cấp năm\", Tô Tông sững người lại.", units.single().text)
    }

    @Test
    fun shortQuotedSpeechWithSpeechCueRemainsDialogue() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "Tô Tông khẽ nói \"Được\", rồi quay đi.",
        )

        assertEquals(3, units.size)
        assertFalse(units[0].isDialogue)
        assertTrue(units[1].isDialogue)
        assertEquals("\"Được\"", units[1].text)
        assertFalse(units[2].isDialogue)
    }
}
