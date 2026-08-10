package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.data.local.VoiceRoleEntity

class XpkNarrationProtocolTest {
    private val narrator = role("narrator-row", "Người kể chuyện", narrator = true)
    private val male = role("voice-male", "Nam chính")
    private val female = role("voice-female", "Nữ chính")

    @Test
    fun voiceOnlyPromptUsesDialogueAndTwoUnitContextWindow() {
        val body = listOf(
            "Mở đầu rất xa.",
            "Đoạn kể gần một.",
            "Đoạn kể gần hai.",
            "“Ta đến rồi.”",
            "Đoạn kể sau một.",
            "Đoạn kể sau hai.",
            "Kết thúc rất xa.",
        ).joinToString("\n")
        val bundle = build(body = body, includeMusic = false)
        assertTrue(bundle.prompt.contains("[DIALOGUE id=P0004-U01"))
        assertTrue(bundle.prompt.contains("[CONTEXT id=P0002-U01"))
        assertTrue(bundle.prompt.contains("[CONTEXT id=P0006-U01"))
        assertFalse(bundle.prompt.contains("[CONTEXT id=P0001-U01"))
        assertFalse(bundle.prompt.contains("[CONTEXT id=P0007-U01"))
    }

    @Test
    fun voiceOnlyPromptAddsContextBreakBetweenDistantDialogues() {
        val body = listOf(
            "“Một.”",
            "kể 1", "kể 2", "kể 3", "kể 4", "kể 5", "kể 6",
            "“Hai.”",
        ).joinToString("\n")
        val prompt = build(body = body, includeMusic = false).prompt
        assertTrue(prompt.contains("[CONTEXT_BREAK omitted_units="))
    }

    @Test
    fun unifiedPromptUsesEveryUnitInTimeline() {
        val body = "Kể một.\n“Thoại.”\nKể hai."
        val bundle = build(body = body, includeMusic = true)
        bundle.unitIds.forEach { id -> assertTrue("Thiếu $id", bundle.prompt.contains("id=$id")) }
        assertTrue(bundle.prompt.contains("music_scenes"))
    }

    @Test
    fun promptUsesJsonUnitContractNotLegacyParagraphProtocol() {
        val prompt = build(body = "“Ta biết.”", includeMusic = false).prompt
        assertTrue(prompt.contains("\"id\""))
        assertTrue(prompt.contains("\"voice\""))
        assertTrue(prompt.contains("speed_adjust_pct"))
        assertTrue(prompt.contains("pitch_adjust_pct"))
        assertTrue(prompt.contains("volume_adjust_pct"))
        assertFalse(prompt.contains("ASSIGN|"))
        assertFalse(prompt.contains("ROLE|"))
        assertFalse(prompt.contains("[[P:"))
    }

    @Test
    fun promptUsesStableProfileIdsAndReservesNarrator() {
        val bundle = build(body = "“Ta biết.”", includeMusic = false)
        assertTrue(bundle.voiceIds.contains("voice-male"))
        assertTrue(bundle.voiceIds.contains("voice-female"))
        assertTrue(bundle.voiceIds.contains(XpkVoiceCastSplitter.NARRATOR_ID))
        assertTrue(bundle.prompt.contains("- ID: voice-male"))
        assertTrue(bundle.prompt.contains("voice_narrator dành riêng"))
    }

    @Test
    fun repairRestoresCanonicalOrderAndRepairsBadRows() {
        val options = options(ids = listOf("P0001-U01", "P0002-U01", "P0003-U01"))
        val plan = AiLineProtocol.repairXpkAssignments(
            listOf(
                AiLineProtocol.XpkRawAssignment("P0002-U01", "voice-female", 2f, 1f, 0f),
                AiLineProtocol.XpkRawAssignment("P0002-U01", "voice-male", 9f, 9f, 9f),
                AiLineProtocol.XpkRawAssignment("P9999-U01", "voice-male"),
                AiLineProtocol.XpkRawAssignment("P0001-U01", "unknown-voice", 4f, 3f, 2f),
            ),
            options,
        )
        assertEquals(listOf("P0001-U01", "P0002-U01", "P0003-U01"), plan.assignments.map { it.unitId })
        assertEquals("voice-male", plan.assignments[0].voiceId)
        assertEquals("voice-female", plan.assignments[1].voiceId)
        assertEquals("voice-male", plan.assignments[2].voiceId)
        assertTrue(plan.warnings.any { "ID thiếu" in it })
        assertTrue(plan.warnings.any { "ID lặp" in it })
        assertTrue(plan.warnings.any { "ID lạ" in it })
        assertTrue(plan.warnings.any { "mã giọng sai" in it })
    }

    @Test
    fun repairRejectsNarratorForDialogueAndUsesCharacterFallback() {
        val plan = AiLineProtocol.repairXpkAssignments(
            listOf(AiLineProtocol.XpkRawAssignment("P0001-U01", XpkVoiceCastSplitter.NARRATOR_ID, 8f, 8f, 8f)),
            options(ids = listOf("P0001-U01")),
        )
        val assignment = plan.assignments.single()
        assertEquals("voice-male", assignment.voiceId)
        assertEquals(8f, assignment.speedAdjustPct)
        assertTrue(plan.warnings.any { "mã giọng sai" in it })
    }

    @Test
    fun narratorFallbackZerosAdjustmentsWhenNoCharacterVoiceExists() {
        val options = AiLineProtocol.XpkParseOptions(
            validDialogueIds = listOf("P0001-U01"),
            validUnitIds = listOf("P0001-U01"),
            validVoiceIds = listOf(XpkVoiceCastSplitter.NARRATOR_ID),
        )
        val assignment = AiLineProtocol.repairXpkAssignments(
            listOf(AiLineProtocol.XpkRawAssignment("P0001-U01", XpkVoiceCastSplitter.NARRATOR_ID, 8f, -7f, 6f)),
            options,
        ).assignments.single()
        assertEquals(XpkVoiceCastSplitter.NARRATOR_ID, assignment.voiceId)
        assertEquals(0f, assignment.speedAdjustPct)
        assertEquals(0f, assignment.pitchAdjustPct)
        assertEquals(0f, assignment.volumeAdjustPct)
    }

    @Test
    fun repairClampsAllExpressionAdjustments() {
        val options = options(ids = listOf("P0001-U01")).copy(
            speedLimitPct = 5f,
            pitchLimitPct = 3f,
            volumeLimitPct = 2f,
        )
        val assignment = AiLineProtocol.repairXpkAssignments(
            listOf(AiLineProtocol.XpkRawAssignment("P0001-U01", "voice-female", 50f, -40f, 30f)),
            options,
        ).assignments.single()
        assertEquals(5f, assignment.speedAdjustPct)
        assertEquals(-3f, assignment.pitchAdjustPct)
        assertEquals(2f, assignment.volumeAdjustPct)
    }

    @Test
    fun dialogueGroupIsPresentInPrompt() {
        val body = "“" + "lời thoại rất dài ".repeat(120) + "”"
        val bundle = build(body = body, includeMusic = false)
        val groups = bundle.units.filter { it.fixedVoice == null }.mapNotNull { it.dialogueGroupId }.distinct()
        assertEquals(1, groups.size)
        assertTrue(bundle.prompt.contains("group=${groups.single()}"))
    }

    private fun build(body: String, includeMusic: Boolean): XpkVoiceCastPrompt.Bundle = XpkVoiceCastPrompt.build(
        title = "Chương test",
        body = body,
        profiles = listOf(narrator, male, female),
        storyNote = "",
        expressiveAdjustment = true,
        speedLimitPct = 10,
        pitchLimitPct = 10,
        volumeLimitPct = 10,
        expressionPrompt = "",
        includeVoiceCast = true,
        includeSceneMusic = includeMusic,
        tracks = if (includeMusic) listOf(SceneMusicTrackOption("track-1", "Nhạc 1", listOf("êm"))) else emptyList(),
    )

    private fun options(ids: List<String>) = AiLineProtocol.XpkParseOptions(
        validDialogueIds = ids,
        validUnitIds = ids,
        validVoiceIds = listOf(XpkVoiceCastSplitter.NARRATOR_ID, "voice-male", "voice-female"),
    )

    private fun role(id: String, name: String, narrator: Boolean = false) = VoiceRoleEntity(
        id = id,
        storyId = "story",
        roleName = name,
        aliasesCsv = "",
        description = "Giọng thử",
        enginePackage = null,
        voiceName = null,
        languageTag = "vi-VN",
        rate = 1f,
        pitch = 1f,
        volume = 1f,
        expression = "NEUTRAL",
        expressionStrength = 0.5f,
        sonicSpeed = 1f,
        sonicPitch = 1f,
        isNarrator = narrator,
        enabled = true,
        updatedAt = 0L,
    )
}
