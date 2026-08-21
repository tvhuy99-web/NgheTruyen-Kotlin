package vn.nghetruyen.app.playback

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbienceLoopTransitionPolicyTest {
    @Test
    fun tenAndFifteenSecondFilesBothAdaptFromTheirOwnDuration() {
        val ten = AmbienceLoopTransitionPolicy.overlapRange(10_000, 900, 2_200)
        val fifteen = AmbienceLoopTransitionPolicy.overlapRange(15_000, 900, 2_200)
        assertTrue(ten.first >= 2_400)
        assertTrue(ten.last >= 3_400)
        assertTrue(fifteen.first >= 2_600)
        assertTrue(fifteen.last >= 3_100)
        assertTrue(ten.last <= 4_000)
        assertTrue(fifteen.last <= 4_000)
    }

    @Test
    fun nearbyDurationsDoNotHitAHardSecondBoundary() {
        val before = AmbienceLoopTransitionPolicy.overlapRange(9_999, 900, 2_200)
        val exact = AmbienceLoopTransitionPolicy.overlapRange(10_000, 900, 2_200)
        val after = AmbienceLoopTransitionPolicy.overlapRange(10_001, 900, 2_200)
        assertTrue(abs(before.first - exact.first) <= 4)
        assertTrue(abs(after.first - exact.first) <= 4)
        assertTrue(abs(before.last - exact.last) <= 4)
        assertTrue(abs(after.last - exact.last) <= 4)
    }

    @Test
    fun longFilesReceiveOnlyGentleContinuousAdjustment() {
        val range = AmbienceLoopTransitionPolicy.overlapRange(60_000, 900, 2_200)
        assertTrue(range.first in 1_250..1_500)
        assertTrue(range.last in 2_350..2_550)
    }

    @Test
    fun loopStartOffsetAlsoDependsOnTheActualFile() {
        val ten = AmbienceLoopTransitionPolicy.startOffsetRange(10_000, 2_200)
        val fifteen = AmbienceLoopTransitionPolicy.startOffsetRange(15_000, 2_200)
        assertTrue(ten.first > 0)
        assertTrue(ten.last > ten.first)
        assertTrue(fifteen.first > 0)
        assertTrue(fifteen.last > fifteen.first)
        assertTrue(ten.last <= 1_800)
        assertTrue(fifteen.last <= 1_800)
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
