package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XpkSceneMusicResponseTest {
    @Test
    fun invalidSceneCoverageFallsBackWithoutDiscardingVoiceAssignments() {
        val result = AiLineProtocol.parseXpkNarration(
            raw = """
                {
                  "assignments": [
                    {"id":"P0001-U01","voice":"voice-a","speed_adjust_pct":2,"pitch_adjust_pct":0,"volume_adjust_pct":0}
                  ],
                  "music_scenes": [
                    {"start_id":"P0001-U01","end_id":"P0001-U01","track_id":"track-a"},
                    {"start_id":"P0003-U01","end_id":"P0003-U01","track_id":"track-b"}
                  ]
                }
            """.trimIndent(),
            options = AiLineProtocol.XpkParseOptions(
                validDialogueIds = listOf("P0001-U01"),
                validUnitIds = listOf("P0001-U01", "P0002-U01", "P0003-U01"),
                validVoiceIds = listOf(XpkVoiceCastSplitter.NARRATOR_ID, "voice-a"),
                validTrackIds = listOf("track-a", "track-b"),
                includeVoiceCast = true,
                includeSceneMusic = true,
                incomingTrackId = "track-b",
            ),
        )

        assertEquals("voice-a", result.voiceCast.assignments.single().voiceId)
        assertEquals(2f, result.voiceCast.assignments.single().speedAdjustPct)
        assertEquals(1, result.musicCues.size)
        assertEquals("track-b", result.musicCues.single().trackId)
        assertEquals("P0001-U01", result.musicCues.single().startUnitId)
        assertEquals("P0003-U01", result.musicCues.single().endUnitId)
        assertTrue(result.musicSceneError.contains("phục hồi"))
    }

    @Test
    fun validAdjacentEqualTracksAreMergedDuringParse() {
        val result = AiLineProtocol.parseXpkNarration(
            raw = """
                {
                  "assignments": [],
                  "music_scenes": [
                    {"start_id":"P0001-U01","end_id":"P0001-U01","track_id":"track-a"},
                    {"start_id":"P0002-U01","end_id":"P0002-U01","track_id":"track-a"},
                    {"start_id":"P0003-U01","end_id":"P0003-U01","track_id":"track-b"}
                  ]
                }
            """.trimIndent(),
            options = AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01", "P0002-U01", "P0003-U01"),
                validVoiceIds = emptyList(),
                validTrackIds = listOf("track-a", "track-b"),
                includeVoiceCast = false,
                includeSceneMusic = true,
            ),
        )

        assertEquals(2, result.musicCues.size)
        assertEquals("P0001-U01", result.musicCues[0].startUnitId)
        assertEquals("P0002-U01", result.musicCues[0].endUnitId)
        assertEquals("track-a", result.musicCues[0].trackId)
        assertEquals("track-b", result.musicCues[1].trackId)
        assertTrue(result.musicSceneError.isBlank())
    }
}
