package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XpkUnifiedNarrationPlanTest {
    @Test
    fun audioOnlyPromptStillUsesCanonicalXpkTimelineAndFourOutputArrays() {
        val base = XpkVoiceCastPrompt.build(
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
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base,
            title = "Chương thử",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = true,
            includeSoundEffects = true,
            ambienceTracks = listOf(
                SceneMusicTrackOption("a1", "Mưa nhẹ.wav", emptyList(), "mưa nhẹ ngoài trời"),
            ),
            soundEffectTracks = listOf(
                SceneMusicTrackOption("s1", "Sấm.wav", emptyList(), "sấm gần đột ngột"),
            ),
        )

        assertTrue(prompt.contains("\"assignments\""))
        assertTrue(prompt.contains("\"music_scenes\""))
        assertTrue(prompt.contains("\"ambience_scenes\""))
        assertTrue(prompt.contains("\"sfx_cues\""))
        assertTrue(prompt.contains("[UNIT id="))
        assertTrue(prompt.contains("a1 | Mưa nhẹ | mưa nhẹ ngoài trời"))
        assertTrue(prompt.contains("s1 | Sấm | sấm gần đột ngột"))
        assertFalse(prompt.contains("timestamp", ignoreCase = true))
        assertFalse(prompt.contains("uri", ignoreCase = true))
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
              ],
              "sfx_cues": []
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
