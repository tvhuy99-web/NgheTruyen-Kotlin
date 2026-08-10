package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XpkVoiceCastSplitterTest {
    @Test
    fun quoteDialogueCreatesStableUnitAndGroupIds() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "Chương 1",
            "Hắn nhìn nàng. “Ngươi đến rồi.” Nàng khẽ gật đầu.",
        )
        assertEquals("TITLE-U01", units.first().id)
        val dialogue = units.single { it.isDialogue }
        assertEquals("P0001-U02", dialogue.id)
        assertEquals("P0001-D02", dialogue.dialogueGroupId)
        assertEquals("dialogue", dialogue.unitKind)
    }

    @Test
    fun dashDialogueAlternatesDialogueAndNarrationLikeXpk() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "— Ta sẽ đi. — Hắn đứng dậy. — Ngươi ở lại.",
        )
        assertEquals(3, units.size)
        assertTrue(units[0].isDialogue)
        assertFalse(units[1].isDialogue)
        assertTrue(units[2].isDialogue)
        assertTrue(units[0].dashDialogue)
        assertTrue(units[2].dashDialogue)
    }

    @Test
    fun colonDialogueKeepsSpeakerLabelNarratedAndAddsSpeakerHint() {
        val units = XpkVoiceCastSplitter.buildUnits("", "Lâm Phong: Ta đã quyết định.")
        assertEquals(2, units.size)
        assertEquals("speaker_label", units[0].unitKind)
        assertEquals(XpkVoiceCastSplitter.NARRATOR_ID, units[0].fixedVoice)
        assertTrue(units[0].colonSpeakerLabel)
        assertTrue(units[1].isDialogue)
        assertEquals("Lâm Phong", units[1].speakerHint)
        assertTrue(units[1].colonDialogue)
    }

    @Test
    fun thoughtCueKeepsQuotedThoughtOnNarrator() {
        val units = XpkVoiceCastSplitter.buildUnits("", "Hắn thầm nghĩ: “Không thể nào.”")
        assertTrue(units.none { it.isDialogue })
        assertTrue(units.any { it.fixedVoice == XpkVoiceCastSplitter.NARRATOR_ID })
    }

    @Test
    fun quotedNarrationCueDoesNotBecomeCharacterDialogue() {
        val units = XpkVoiceCastSplitter.buildUnits("", "Trên đó ghi “Thiên Môn”.")
        assertTrue(units.none { it.isDialogue })
        assertTrue(units.any { it.parsingNote?.contains("trích dẫn") == true })
    }

    @Test
    fun headingAndNumericColonAreNotMisclassifiedAsDialogue() {
        val heading = XpkVoiceCastSplitter.buildUnits("", "Chương 12: Trận chiến bắt đầu")
        val time = XpkVoiceCastSplitter.buildUnits("", "Thời gian: 12:30")
        assertTrue(heading.none { it.isDialogue })
        assertTrue(time.none { it.isDialogue })
    }

    @Test
    fun unclosedQuoteIsMarkedAndCappedAt640Utf8Bytes() {
        val units = XpkVoiceCastSplitter.buildUnits(
            "",
            "Hắn nói “Đây là lời thoại chưa đóng. Sau đó mọi người im lặng. Và câu chuyện tiếp tục",
        )
        val dialogue = units.firstOrNull { it.isDialogue }
        assertNotNull(dialogue)
        assertTrue(dialogue!!.unclosedQuote)
        assertTrue(dialogue.text.toByteArray(Charsets.UTF_8).size <= 640)
    }

    @Test
    fun longDialogueSplitsAt1200Utf8BytesAndKeepsOneDialogueGroup() {
        val text = "“" + "lời thoại rất dài ".repeat(120) + "”"
        val dialogueUnits = XpkVoiceCastSplitter.buildUnits("", text).filter { it.isDialogue }
        assertTrue(dialogueUnits.size > 1)
        assertEquals(1, dialogueUnits.mapNotNull { it.dialogueGroupId }.distinct().size)
        assertTrue(dialogueUnits.all { it.text.toByteArray(Charsets.UTF_8).size <= 1200 })
    }

    @Test
    fun blankLinesDoNotConsumeParagraphIds() {
        val units = XpkVoiceCastSplitter.buildUnits("", "Dòng một.\n\n“Dòng thoại.”")
        assertTrue(units.any { it.id.startsWith("P0001-") })
        assertTrue(units.any { it.id.startsWith("P0002-") })
        assertTrue(units.none { it.id.startsWith("P0003-") })
    }
}
