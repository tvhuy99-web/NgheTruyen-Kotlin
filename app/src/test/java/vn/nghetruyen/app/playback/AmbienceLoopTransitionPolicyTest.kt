package vn.nghetruyen.app.playback

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbienceLoopTransitionPolicyTest {
    @Test
    fun tenSecondClipGetsWideAdaptiveOverlap() {
        val range = AmbienceLoopTransitionPolicy.overlapRange(
            durationMillis = 10_000,
            requestedMinMillis = 900,
            requestedMaxMillis = 2_200,
        )
        assertTrue(range.first >= 2_500)
        assertTrue(range.last >= 3_500)
        assertTrue(range.last <= 4_000)
    }

    @Test
    fun longClipKeepsRequestedOverlap() {
        val range = AmbienceLoopTransitionPolicy.overlapRange(
            durationMillis = 60_000,
            requestedMinMillis = 900,
            requestedMaxMillis = 2_200,
        )
        assertEquals(900, range.first)
        assertEquals(2_200, range.last)
    }

    @Test
    fun shortClipSkipsQuietIntroOnSubsequentLoops() {
        val range = AmbienceLoopTransitionPolicy.startOffsetRange(10_000)
        assertTrue(range.first >= 900)
        assertTrue(range.last >= 1_500)
        assertTrue(range.last <= 1_800)
    }

    @Test
    fun equalPowerCrossfadeKeepsEnergyFlat() {
        for (step in 0..20) {
            val fraction = step / 20f
            val (oldGain, nextGain) = AmbienceLoopTransitionPolicy.crossfadeGains(fraction)
            val power = oldGain * oldGain + nextGain * nextGain
            assertTrue(abs(power - 1f) < 0.002f)
        }
    }
}
