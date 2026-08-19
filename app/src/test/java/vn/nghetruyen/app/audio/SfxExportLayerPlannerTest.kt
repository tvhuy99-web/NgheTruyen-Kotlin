package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfxExportLayerPlannerTest {
    @Test
    fun actionLoopRepeatsShortSourceAndClipsAtStoryBoundary() {
        val cue = SoundEffectCue(
            unitId = "P1",
            effectId = "gallop",
            stopUnitId = "P4",
            loopUntilStop = true,
        )
        val placements = SfxExportLayerPlanner.placements(
            cue = cue,
            actionStartFrame = 1_000L,
            actionEndFrameExclusive = 3_500L,
            sourceFrames = 1_000L,
            sampleRate = 1_000L,
        )

        assertEquals(listOf(1_000L, 2_000L, 3_000L), placements.map { it.startFrame })
        assertTrue(placements.all { it.endFrameExclusive == 3_500L })
    }

    @Test
    fun actionLoopDoesNotForceLongSourceToFinish() {
        val cue = SoundEffectCue("P1", "gallop", "P2", loopUntilStop = true)
        val placements = SfxExportLayerPlanner.placements(cue, 500L, 1_500L, 10_000L, 1_000L)

        assertEquals(listOf(SfxExportPlacement(500L, 1_500L)), placements)
    }

    @Test
    fun countedRepeatUsesRequestedCadence() {
        val cue = SoundEffectCue(
            unitId = "P1",
            effectId = "hammer",
            repeatCount = 5,
            cadence = SfxCadence.FAST,
        )
        val placements = SfxExportLayerPlanner.placements(cue, 100L, 3_000L, 100L, 1_000L)

        assertEquals(listOf(100L, 420L, 740L, 1_060L, 1_380L), placements.map { it.startFrame })
    }
}
