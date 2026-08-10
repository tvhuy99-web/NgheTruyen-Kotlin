package vn.nghetruyen.app.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.ai.AiLineProtocol
import vn.nghetruyen.app.ai.NarrationPlanContext
import vn.nghetruyen.app.ai.NarrationPlanner
import vn.nghetruyen.app.ai.OnlineTextAiServices
import vn.nghetruyen.app.ai.SceneMusicPlanner
import vn.nghetruyen.app.ai.SceneMusicTrackOption
import vn.nghetruyen.app.ai.VoiceCastEngine
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.ai.XpkVoiceCastPrompt
import vn.nghetruyen.app.ai.XpkVoiceCastSplitter
import vn.nghetruyen.app.data.local.VoiceRoleEntity

class XpkStrictParityTest {
    @Test
    fun firstVoiceInDialogueGroupWinsButEachFragmentKeepsItsProsody() {
        val first = "P0001-U01"
        val second = "P0001-U02"
        val plan = AiLineProtocol.repairXpkAssignments(
            rows = listOf(
                AiLineProtocol.XpkRawAssignment(first, "voice-male", speedAdjustPct = 2f),
                AiLineProtocol.XpkRawAssignment(second, "voice-female", speedAdjustPct = -4f),
            ),
            options = AiLineProtocol.XpkParseOptions(
                validDialogueIds = listOf(first, second),
                validUnitIds = listOf(first, second),
                validVoiceIds = listOf(XpkVoiceCastSplitter.NARRATOR_ID, "voice-male", "voice-female"),
                dialogueGroupByUnitId = mapOf(first to "P0001-D01", second to "P0001-D01"),
            ),
        )

        assertEquals(listOf("voice-male", "voice-male"), plan.assignments.map { it.voiceId })
        assertEquals(2f, plan.assignments[0].speedAdjustPct)
        assertEquals(-4f, plan.assignments[1].speedAdjustPct)
    }

    @Test
    fun promptRejectsMoreThanTenVoiceProfilesLikeXpk() {
        val profiles = buildList {
            add(role("narrator", narrator = true))
            repeat(10) { index -> add(role("voice-$index")) }
        }
        val error = runCatching {
            XpkVoiceCastPrompt.build(
                title = "Chương",
                body = "“Xin chào.”",
                profiles = profiles,
                storyNote = "",
                expressiveAdjustment = true,
                speedLimitPct = 10,
                pitchLimitPct = 10,
                volumeLimitPct = 10,
                expressionPrompt = "",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Tối đa 10 giọng", error?.message)
    }

    @Test
    fun promptUsesExplicitSonicMethodAndMethodSpecificBaseValues() {
        val profile = role("voice-a")
        val rendered = XpkVoiceCastPrompt.profilesForPrompt(
            profiles = listOf(profile),
            profileSettingsById = mapOf(
                profile.id to XpkVoiceCastPrompt.PromptProfileSettings(
                    processingMethod = "sonic",
                    speed = 1.25f,
                    pitch = 0.90f,
                    volume = 1.37f,
                ),
            ),
        )

        assertTrue(rendered.contains("tốc độ 1.25x"))
        assertTrue(rendered.contains("cao độ 0.90"))
        assertTrue(rendered.contains("âm lượng 137%"))
        assertTrue(rendered.contains("xử lý Sonic"))
        assertTrue(rendered.contains("tối đa 200%"))
    }

    @Test
    fun canonicalLinesPreserveInternalWhitespaceAndEmbeddedNewlines() {
        val lines = XpkPlaybackRuntime.canonicalLines(
            listOf("  A  B  \n C\tD ", "", " E "),
        )

        assertEquals(listOf("A  B", "C\tD", "E"), lines)
    }

    @Test
    fun canonicalXpkVoicePlanBypassesLocalExpressionLayer() {
        PlaybackQueueStore.load(
            sourceId = "test",
            storyId = "story",
            chapterId = "chapter",
            chapterIndex = 1,
            chapterTitle = "Tiêu đề",
            paragraphs = listOf("“Tôi buồn lắm!”"),
        )
        val initial = PlaybackQueueStore.state.value
        val dialogueIndex = initial.speechChunks.indexOfFirst { it.fixedVoiceId == null }
        assertTrue(dialogueIndex >= 0)
        PlaybackQueueStore.restoreSpeechPosition(0, dialogueIndex)
        val snapshot = PlaybackQueueStore.state.value
        val dialogueId = snapshot.currentUnitId!!
        val fingerprint = XpkPlaybackRuntime.timelineFingerprint(snapshot.speechChunks)
        val transform = JSONObject()
            .put("timeline_fingerprint_version", XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION)
            .put("timeline_fingerprint", fingerprint)
            .put(
                "assignments",
                JSONArray().put(
                    JSONObject()
                        .put("id", dialogueId)
                        .put("voice", "voice-a")
                        .put("speed_adjust_pct", 0)
                        .put("pitch_adjust_pct", 0)
                        .put("volume_adjust_pct", 0),
                ),
            )
            .toString()
        XpkPlaybackRuntime.parseVoiceAssignments(transform, snapshot.speechChunks.map { it.unitId })

        val speech = VoiceExpressionProcessor.resolve(snapshot.currentSpeechText!!, role("voice-a", expression = "SAD"))
        assertEquals(snapshot.currentSpeechText, speech.text)
        assertEquals(1f, speech.rateMultiplier)
        assertEquals(1f, speech.pitchMultiplier)
        assertEquals(1f, speech.volumeMultiplier)
        assertEquals(1f, speech.sonicSpeedMultiplier)
        assertEquals(1f, speech.sonicPitchMultiplier)
    }

    @Test
    fun scenePromptKeepsTheLastThreeThousandUtf8BytesOfPreviousTail() {
        val previous = "BEGIN_MARKER-" + "nội dung ".repeat(700) + "-END_MARKER"
        val block = XpkSceneMusicParity.promptBlock(
            title = "Chương",
            firstUnitId = "P0001-U01",
            lastUnitId = "P0001-U01",
            tracks = listOf(SceneMusicTrackOption("track-a", "A.mp3", emptyList(), "êm")),
            context = NarrationPlanContext(previousChapterEnding = previous),
        )

        assertFalse(block.instructions.contains("BEGIN_MARKER"))
        assertTrue(block.instructions.contains("END_MARKER"))
    }

    @Test
    fun voicePromptUsesTheReferenceJsonExamplePlaceholders() {
        val prompt = XpkVoiceCastPrompt.build(
            title = "Chương",
            body = "“Xin chào.”",
            profiles = listOf(role("narrator", narrator = true), role("voice-a")),
            storyNote = "",
            expressiveAdjustment = true,
            speedLimitPct = 10,
            pitchLimitPct = 10,
            volumeLimitPct = 10,
            expressionPrompt = "",
        ).prompt

        assertTrue(prompt.contains("\"id\": \"ID_THỰC_TẾ_1\""))
        assertTrue(prompt.contains("\"id\": \"ID_THỰC_TẾ_2\""))
        assertTrue(prompt.contains("\"speed_adjust_pct\": 6"))
        assertTrue(prompt.contains("\"pitch_adjust_pct\": 3"))
        assertTrue(prompt.contains("\"volume_adjust_pct\": 5"))
    }

    @Test
    fun productionTextAiSurfaceCannotBeUsedAsLegacyNarrationPlanner() {
        assertFalse(VoiceCastEngine::class.java.isAssignableFrom(OnlineTextAiServices::class.java))
        assertFalse(SceneMusicPlanner::class.java.isAssignableFrom(OnlineTextAiServices::class.java))
        assertFalse(NarrationPlanner::class.java.isAssignableFrom(OnlineTextAiServices::class.java))
    }

    private fun role(
        id: String,
        narrator: Boolean = false,
        expression: String = "NEUTRAL",
    ) = VoiceRoleEntity(
        id = id,
        storyId = "story",
        roleName = if (narrator) "Người kể chuyện" else id,
        aliasesCsv = "",
        description = "Giọng test",
        enginePackage = null,
        voiceName = null,
        languageTag = "vi-VN",
        rate = 1f,
        pitch = 1f,
        volume = 1f,
        expression = expression,
        expressionStrength = 1f,
        sonicSpeed = 1f,
        sonicPitch = 1f,
        isNarrator = narrator,
        enabled = true,
        updatedAt = 0L,
    )
}
