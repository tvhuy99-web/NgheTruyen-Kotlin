package vn.nghetruyen.app.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.ai.AiLineProtocol
import vn.nghetruyen.app.ai.NarrationPlanContext
import vn.nghetruyen.app.ai.SceneMusicTrackOption
import vn.nghetruyen.app.ai.XpkVoiceCastPrompt
import vn.nghetruyen.app.ai.XpkVoiceCastSplitter
import vn.nghetruyen.app.data.local.VoiceRoleEntity

/** Golden chain: XPK splitter -> prompt ids -> AI JSON repair -> persisted JSON -> playback runtime. */
class XpkEndToEndParityTest {
    private val narrator = role("narrator-row", "Người kể chuyện", narrator = true)
    private val male = role("voice-male", "Nam")
    private val female = role("voice-female", "Nữ")

    @Test
    fun oneFixtureKeepsTheSameCanonicalTimelineAcrossEveryStage() {
        val title = "Chương parity"
        val paragraphs = listOf(
            "Hắn nhìn nàng. “Ngươi đến rồi.” Nàng khẽ gật đầu.",
            "— Đứng lại! — cô gái gọi. — Ta chưa nói xong.",
            "Lâm Phong: Ta đã quyết định.",
            "Hắn thầm nghĩ: “Không thể nào.”",
            "Hắn nói “Câu này chưa đóng. Mọi người im lặng và chờ đợi",
        )
        val body = paragraphs.joinToString("\n")
        val splitterUnits = XpkVoiceCastSplitter.buildUnits(title, body)
        val bundle = XpkVoiceCastPrompt.build(
            title = title,
            body = body,
            profiles = listOf(narrator, male, female),
            storyNote = "",
            expressiveAdjustment = true,
            speedLimitPct = 10,
            pitchLimitPct = 10,
            volumeLimitPct = 10,
            expressionPrompt = "",
            includeVoiceCast = true,
            includeSceneMusic = true,
            tracks = listOf(
                SceneMusicTrackOption("track-a", "A.mp3", emptyList(), "Sắc thái: tĩnh; Dùng: suy tư"),
                SceneMusicTrackOption("track-b", "B.mp3", emptyList(), "Sắc thái: căng; Dùng: xung đột"),
            ),
            context = NarrationPlanContext(activeTrackId = "track-a", incomingSource = "final_scene"),
        )
        val runtime = XpkPlaybackRuntime.buildSpeechTimeline(title, paragraphs)

        val expectedIds = splitterUnits.map { it.id }
        assertEquals(expectedIds, bundle.unitIds)
        assertEquals(expectedIds, runtime.map { it.unitId })
        expectedIds.forEach { id -> assertTrue("Prompt thiếu $id", bundle.prompt.contains("id=$id")) }
        assertTrue(bundle.dialogueIds.isNotEmpty())
        assertTrue(bundle.dialogueIds.all { it.matches(Regex("P\\d{4}-U\\d{2}")) })
        assertTrue(splitterUnits.filter { it.isDialogue }.all { it.dialogueGroupId?.contains("-D") == true })
        assertTrue(splitterUnits.any { it.unclosedQuote })
        assertTrue(splitterUnits.filter { it.text.contains("Không thể nào") }.all { !it.isDialogue })

        val split = (expectedIds.size / 2).coerceIn(1, expectedIds.lastIndex)
        val aiJson = JSONObject()
            .put(
                "assignments",
                JSONArray().also { rows ->
                    bundle.dialogueIds.forEachIndexed { index, id ->
                        rows.put(
                            JSONObject()
                                .put("id", id)
                                .put("voice", if (index % 2 == 0) "voice-male" else "voice-female")
                                .put("speed_adjust_pct", index.coerceAtMost(5))
                                .put("pitch_adjust_pct", 0)
                                .put("volume_adjust_pct", 0),
                        )
                    }
                },
            )
            .put(
                "music_scenes",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("start_id", expectedIds.first())
                            .put("end_id", expectedIds[split - 1])
                            .put("track_id", "track-a"),
                    )
                    .put(
                        JSONObject()
                            .put("start_id", expectedIds[split])
                            .put("end_id", expectedIds.last())
                            .put("track_id", "track-b"),
                    ),
            )
            .toString()

        val parsed = AiLineProtocol.parseXpkNarration(
            aiJson,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = bundle.dialogueIds,
                validUnitIds = bundle.unitIds,
                validVoiceIds = bundle.voiceIds,
                validTrackIds = listOf("track-a", "track-b"),
                includeVoiceCast = true,
                includeSceneMusic = true,
                incomingTrackId = "track-a",
            ),
        )
        assertEquals(bundle.dialogueIds, parsed.voiceCast.assignments.map { it.unitId })
        assertEquals("", parsed.musicSceneError)

        val persistedVoice = JSONObject()
            .put(
                "assignments",
                JSONArray().also { rows ->
                    parsed.voiceCast.assignments.forEach { assignment ->
                        rows.put(
                            JSONObject()
                                .put("id", assignment.unitId)
                                .put("voice", assignment.voiceId)
                                .put("speed_adjust_pct", assignment.speedAdjustPct)
                                .put("pitch_adjust_pct", assignment.pitchAdjustPct)
                                .put("volume_adjust_pct", assignment.volumeAdjustPct),
                        )
                    }
                },
            )
            .toString()
        val persistedMusic = JSONObject()
            .put(
                "music_scenes",
                JSONArray().also { rows ->
                    parsed.musicCues.forEach { scene ->
                        rows.put(
                            JSONObject()
                                .put("start_id", scene.startUnitId)
                                .put("end_id", scene.endUnitId)
                                .put("track_id", scene.trackId),
                        )
                    }
                },
            )
            .toString()

        val runtimeVoice = XpkPlaybackRuntime.parseVoiceAssignments(persistedVoice, expectedIds)
        val runtimeMusic = XpkPlaybackRuntime.parseSceneTimeline(
            persistedMusic,
            expectedIds,
            listOf("track-a", "track-b"),
        )
        assertEquals(bundle.dialogueIds.toSet(), runtimeVoice.keys)
        assertEquals("track-a", runtimeMusic[expectedIds[split - 1]])
        assertEquals("track-b", runtimeMusic[expectedIds[split]])
        assertEquals(expectedIds.size, runtimeMusic.size)
    }

    @Test
    fun timelineFingerprintChangesWhenTextChangesEvenIfIdsStayTheSame() {
        val original = XpkPlaybackRuntime.buildSpeechTimeline("Chương", listOf("Lời kể một."))
        val changed = XpkPlaybackRuntime.buildSpeechTimeline("Chương", listOf("Lời kể đã đổi."))

        assertEquals(original.map { it.unitId }, changed.map { it.unitId })
        assertNotEquals(
            XpkPlaybackRuntime.timelineFingerprint(original),
            XpkPlaybackRuntime.timelineFingerprint(changed),
        )
    }

    @Test
    fun badAiUnitIdCannotLeakIntoRuntimeAssignments() {
        val json = """
            {
              "assignments":[
                {"id":"P0001-U01","voice":"voice-male","speed_adjust_pct":0,"pitch_adjust_pct":0,"volume_adjust_pct":0},
                {"id":"P9999-U99","voice":"voice-female","speed_adjust_pct":0,"pitch_adjust_pct":0,"volume_adjust_pct":0}
              ]
            }
        """.trimIndent()
        val parsed = XpkPlaybackRuntime.parseVoiceAssignments(json, listOf("P0001-U01"))
        assertEquals(setOf("P0001-U01"), parsed.keys)
        assertFalse(parsed.containsKey("P9999-U99"))
    }

    private fun role(id: String, name: String, narrator: Boolean = false) = VoiceRoleEntity(
        id = id,
        storyId = "story",
        roleName = name,
        aliasesCsv = "",
        description = "Giọng test",
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
