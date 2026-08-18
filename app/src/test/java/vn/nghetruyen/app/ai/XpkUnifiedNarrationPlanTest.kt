package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XpkUnifiedNarrationPlanTest {
    private fun base(): XpkVoiceCastPrompt.Bundle = XpkVoiceCastPrompt.build(
        title = "Chương thử",
        body = "Trời bắt đầu mưa.\nMột tiếng sấm vang lên.",
        profiles = emptyList(),
        storyNote = "",
        expressiveAdjustment = false,
        speedLimitPct = 0,
        pitchLimitPct = 0,
        volumeLimitPct = 0,
        expressionPrompt = "",
        includeVoiceCast = false,
        includeSceneMusic = false,
    )

    @Test
    fun audioOnlyPromptUsesNumericDescriptionOnlyCatalogsAndCanonicalTimeline() {
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base(),
            title = "Chương thử",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = true,
            includeSoundEffects = true,
            ambienceTracks = listOf(
                SceneMusicTrackOption(
                    "a1",
                    "Mưa nhẹ.wav",
                    emptyList(),
                    "Nền: mưa nhẹ ngoài trời | Dùng: cảnh mưa kéo dài | Tránh: mưa thoáng qua",
                ),
            ),
            soundEffectTracks = listOf(
                SceneMusicTrackOption(
                    "s1",
                    "Sấm.wav",
                    emptyList(),
                    "Sự kiện: sét đánh gần | Dùng: sét thực sự đánh gần | Tránh: sấm rền xa",
                ),
            ),
        )

        assertFalse(prompt.contains("\"assignments\""))
        assertFalse(prompt.contains("\"music_scenes\""))
        assertTrue(prompt.contains("\"ambience_scenes\""))
        assertTrue(prompt.contains("\"sfx_cues\""))
        assertTrue(prompt.contains("[UNIT id="))
        assertTrue(prompt.contains("1 | Nền: mưa nhẹ ngoài trời"))
        assertTrue(prompt.contains("1 | Sự kiện: sét đánh gần"))
        assertFalse(prompt.contains("Mưa nhẹ.wav"))
        assertFalse(prompt.contains("Sấm.wav"))
        assertFalse(prompt.contains("a1 |"))
        assertFalse(prompt.contains("s1 |"))
        assertFalse(prompt.contains("\"time\":"))
        assertFalse(prompt.contains("\"uri\":"))
        assertFalse(prompt.contains("\"volume\":"))
    }

    @Test
    fun ambienceAndSfxDescriptionsAreCappedAtThreeHundredCodePoints() {
        val description = "ổ".repeat(340)
        val normalized = XpkUnifiedNarrationPrompt.normalize(
            listOf(SceneMusicTrackOption("asset", "Tên.wav", emptyList(), description)),
        ).single()
        assertEquals(300, normalized.description.codePointCount(0, normalized.description.length))
    }

    @Test
    fun blankDescriptionIsExcludedInsteadOfFallingBackToFilename() {
        val aliases = XpkUnifiedNarrationPrompt.aliasToId(
            listOf(
                SceneMusicTrackOption("hidden", "Epic Thunder.wav", emptyList(), ""),
                SceneMusicTrackOption("kept", "Neutral.wav", emptyList(), "Nền: mưa | Dùng: ngoài trời | Tránh: trong nhà"),
            ),
        )
        assertEquals(mapOf("1" to "kept"), aliases)
    }

    @Test
    fun incomingAmbienceRealIdsAreConvertedToCurrentRequestAliases() {
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base(),
            title = "Chương thử",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = true,
            includeSoundEffects = false,
            ambienceTracks = listOf(
                SceneMusicTrackOption("rain-real", "Mưa.wav", emptyList(), "Nền: mưa | Dùng: mưa kéo dài | Tránh: khô ráo"),
                SceneMusicTrackOption("wind-real", "Gió.wav", emptyList(), "Nền: gió | Dùng: gió kéo dài | Tránh: phòng kín"),
            ),
            soundEffectTracks = emptyList(),
            incomingAmbienceId = "wind-real|rain-real",
        )
        assertTrue(prompt.contains("INCOMING_AMBIENCE_IDS: 2 | 1"))
        assertFalse(prompt.contains("wind-real"))
        assertFalse(prompt.contains("rain-real"))
    }

    @Test
    fun disabledAmbienceContributesNoPromptCatalogOrSchema() {
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base(),
            title = "Chương thử",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = false,
            includeSoundEffects = true,
            ambienceTracks = listOf(SceneMusicTrackOption("a-secret", "Mưa.wav", emptyList(), "Nền: mưa | Dùng: ngoài trời | Tránh: khô")),
            soundEffectTracks = listOf(SceneMusicTrackOption("s1", "Sấm.wav", emptyList(), "Sự kiện: sấm | Dùng: sấm gần | Tránh: sấm xa")),
        )

        assertFalse(prompt.contains("MODULE AMBIENCE"))
        assertFalse(prompt.contains("AMBIENCE_CATALOG"))
        assertFalse(prompt.contains("ambience_scenes"))
        assertFalse(prompt.contains("a-secret"))
        assertTrue(prompt.contains("MODULE SFX"))
        assertTrue(prompt.contains("sfx_cues"))
    }

    @Test
    fun disabledSfxContributesNoPromptCatalogOrSchema() {
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base(),
            title = "Chương thử",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = true,
            includeSoundEffects = false,
            ambienceTracks = listOf(SceneMusicTrackOption("a1", "Rừng đêm.wav", emptyList(), "Nền: rừng | Dùng: cảnh rừng | Tránh: thành thị")),
            soundEffectTracks = listOf(SceneMusicTrackOption("s-secret", "Kiếm.wav", emptyList(), "Sự kiện: kiếm | Dùng: va kiếm | Tránh: rút kiếm")),
        )

        assertTrue(prompt.contains("MODULE AMBIENCE"))
        assertTrue(prompt.contains("ambience_scenes"))
        assertFalse(prompt.contains("MODULE SFX"))
        assertFalse(prompt.contains("SFX_CATALOG"))
        assertFalse(prompt.contains("sfx_cues"))
        assertFalse(prompt.contains("s-secret"))
    }

    @Test
    fun musicPromptIsNotPresentWhenMusicIsDisabled() {
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base(),
            title = "Chương thử",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = true,
            includeSoundEffects = false,
            ambienceTracks = listOf(SceneMusicTrackOption("a1", "Mưa.wav", emptyList(), "Nền: mưa | Dùng: cảnh mưa | Tránh: khô")),
            soundEffectTracks = emptyList(),
        )

        assertFalse(prompt.contains("TRACK_CATALOG"))
        assertFalse(prompt.contains("INCOMING_TRACK_ID"))
        assertFalse(prompt.contains("music_scenes"))
    }

    @Test
    fun parserConsumesVoiceMusicAmbienceAndSfxFromOneJsonObject() {
        val raw = """
            {
              "assignments": [
                {"id":"P0001-U01","voice":"v1","speed_adjust_pct":0,"pitch_adjust_pct":0,"volume_adjust_pct":0}
              ],
              "music_scenes": [
                {"start_id":"P0001-U01","end_id":"P0002-U01","track_id":"m1"}
              ],
              "ambience_scenes": [
                {"start_id":"P0001-U01","end_id":"P0002-U01","ambience_id":"a1"}
              ],
              "sfx_cues": [
                {"unit_id":"P0002-U01","effect_id":"s1"}
              ]
            }
        """.trimIndent()

        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = listOf("P0001-U01"),
                validUnitIds = listOf("P0001-U01", "P0002-U01"),
                validVoiceIds = listOf("v1"),
                validTrackIds = listOf("m1"),
                validAmbienceIds = setOf("a1"),
                validSfxIds = setOf("s1"),
                includeVoiceCast = true,
                includeSceneMusic = true,
                includeAmbience = true,
                includeSoundEffects = true,
                expressiveAdjustment = false,
            ),
        )

        assertEquals("v1", plan.voiceCast.assignments.single().voiceId)
        assertEquals("m1", plan.musicCues.single().trackId)
        assertEquals("a1", plan.ambienceScenes.single().ambienceId)
        assertEquals("s1", plan.soundEffectCues.single().effectId)
        assertTrue(plan.musicSceneError.isBlank())
        assertTrue(plan.audioDirectionError.isBlank())
    }

    @Test
    fun parserResolvesNumericAliasesToRealIdsBeforeValidationAndPersistence() {
        val raw = """
            {
              "music_scenes": [
                {"start_id":"P0001-U01","end_id":"P0002-U01","track_id":"2"}
              ],
              "ambience_scenes": [
                {"start_id":"P0001-U01","end_id":"P0002-U01","ambience_id":"1"}
              ],
              "sfx_cues": [
                {"unit_id":"P0002-U01","effect_id":"3"}
              ]
            }
        """.trimIndent()

        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01", "P0002-U01"),
                validVoiceIds = emptyList(),
                validTrackIds = listOf("music-real"),
                validAmbienceIds = setOf("ambience-real"),
                validSfxIds = setOf("sfx-real"),
                includeVoiceCast = false,
                includeSceneMusic = true,
                includeAmbience = true,
                includeSoundEffects = true,
                incomingTrackId = "music-real",
                trackAliasToId = mapOf("2" to "music-real"),
                ambienceAliasToId = mapOf("1" to "ambience-real"),
                sfxAliasToId = mapOf("3" to "sfx-real"),
            ),
        )

        assertEquals("music-real", plan.musicCues.single().trackId)
        assertEquals("ambience-real", plan.ambienceScenes.single().ambienceId)
        assertEquals("sfx-real", plan.soundEffectCues.single().effectId)
        assertTrue(plan.musicSceneError.isBlank())
        assertTrue(plan.audioDirectionError.isBlank())
    }

    @Test
    fun parserMapsMusicPromptZeroToInternalSilence() {
        val raw = """
            {
              "music_scenes": [
                {"start_id":"P0001-U01","end_id":"P0002-U01","track_id":"0"}
              ]
            }
        """.trimIndent()
        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01", "P0002-U01"),
                validVoiceIds = emptyList(),
                validTrackIds = listOf("music-real"),
                includeVoiceCast = false,
                includeSceneMusic = true,
            ),
        )
        assertEquals(XpkSceneMusicParity.SILENCE_TRACK_ID, plan.musicCues.single().trackId)
        assertTrue(plan.musicSceneError.isBlank())
    }

    @Test
    fun invalidMusicAliasFallsBackToRealIncomingTrack() {
        val raw = """
            {
              "music_scenes": [
                {"start_id":"P0001-U01","end_id":"P0002-U01","track_id":"999"}
              ]
            }
        """.trimIndent()
        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01", "P0002-U01"),
                validVoiceIds = emptyList(),
                validTrackIds = listOf("previous-real"),
                includeVoiceCast = false,
                includeSceneMusic = true,
                incomingTrackId = "previous-real",
                trackAliasToId = mapOf("1" to "previous-real"),
            ),
        )
        assertEquals("previous-real", plan.musicCues.single().trackId)
        assertTrue(plan.musicSceneError.contains("đã phục hồi"))
    }

    @Test
    fun parserDoesNotRequireDisabledSfxKey() {
        val raw = """
            {
              "ambience_scenes": [
                {"start_id":"P0001-U01","end_id":"P0001-U01","ambience_id":"a1"}
              ]
            }
        """.trimIndent()
        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01"),
                validVoiceIds = emptyList(),
                validAmbienceIds = setOf("a1"),
                includeVoiceCast = false,
                includeSceneMusic = false,
                includeAmbience = true,
                includeSoundEffects = false,
            ),
        )
        assertEquals("a1", plan.ambienceScenes.single().ambienceId)
        assertTrue(plan.soundEffectCues.isEmpty())
        assertTrue(plan.audioDirectionError.isBlank())
    }

    @Test
    fun parserDoesNotRequireDisabledAmbienceKey() {
        val raw = """
            {
              "sfx_cues": [
                {"unit_id":"P0001-U01","effect_id":"s1"}
              ]
            }
        """.trimIndent()
        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01"),
                validVoiceIds = emptyList(),
                validSfxIds = setOf("s1"),
                includeVoiceCast = false,
                includeSceneMusic = false,
                includeAmbience = false,
                includeSoundEffects = true,
            ),
        )
        assertTrue(plan.ambienceScenes.isEmpty())
        assertEquals("s1", plan.soundEffectCues.single().effectId)
        assertTrue(plan.audioDirectionError.isBlank())
    }

    @Test
    fun invalidAudioDoesNotDestroyValidVoiceAndMusicPortions() {
        val raw = """
            {
              "assignments": [
                {"id":"P0001-U01","voice":"v1","speed_adjust_pct":0,"pitch_adjust_pct":0,"volume_adjust_pct":0}
              ],
              "music_scenes": [
                {"start_id":"P0001-U01","end_id":"P0001-U01","track_id":"m1"}
              ],
              "ambience_scenes": [
                {"start_id":"P0001-U01","end_id":"P0001-U01","ambience_id":"missing"}
              ]
            }
        """.trimIndent()

        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = listOf("P0001-U01"),
                validUnitIds = listOf("P0001-U01"),
                validVoiceIds = listOf("v1"),
                validTrackIds = listOf("m1"),
                validAmbienceIds = setOf("a1"),
                includeVoiceCast = true,
                includeSceneMusic = true,
                includeAmbience = true,
                includeSoundEffects = false,
            ),
        )

        assertEquals("v1", plan.voiceCast.assignments.single().voiceId)
        assertEquals("m1", plan.musicCues.single().trackId)
        assertTrue(plan.ambienceScenes.isEmpty())
        assertTrue(plan.audioDirectionError.isNotBlank())
    }
}
