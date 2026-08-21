from pathlib import Path


def patch(path: str, old: str, new: str, marker: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if marker in text:
        print(f"SKIP {path}: {marker}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 occurrence, found {count}; marker={marker!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"PATCH {path}: {marker}")


source = "app/src/main/java/vn/nghetruyen/app/playback/SceneAmbienceController.kt"

patch(
    source,
    '''import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.sqrt''',
    '''import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt''',
    "import kotlin.math.cos",
)

patch(
    source,
    '''/**
 * Voice-first ambience bus with any number of logical layers selected by the AI.''',
    '''internal object AmbienceLoopTransitionPolicy {
    private const val FULL_SHORT_ADAPT_MS = 10_000
    private const val ADAPT_END_MS = 20_000
    private const val MAX_OVERLAP_MS = 4_000
    private const val MAX_START_OFFSET_MS = 1_800

    /**
     * Short ambience files need substantially more overlap because many source clips already contain
     * their own fade-in/fade-out. The requested settings remain authoritative for long files, while
     * clips around ten seconds automatically use roughly 26-36% overlap to hide the source envelope.
     */
    fun overlapRange(durationMillis: Int, requestedMinMillis: Int, requestedMaxMillis: Int): IntRange {
        if (durationMillis <= 0) return 200..200
        val weight = shortClipWeight(durationMillis)
        val requestedMin = requestedMinMillis.coerceAtLeast(200)
        val requestedMax = requestedMaxMillis.coerceAtLeast(requestedMin)
        val shortTargetMin = (durationMillis * 0.26f).roundToInt()
        val shortTargetMax = (durationMillis * 0.36f).roundToInt()
        val hardMax = minOf(MAX_OVERLAP_MS, (durationMillis * 0.42f).roundToInt()).coerceAtLeast(200)
        val min = blend(requestedMin, shortTargetMin, weight).coerceIn(200, hardMax)
        val max = blend(requestedMax, shortTargetMax, weight).coerceIn(min, hardMax)
        return min..max
    }

    /**
     * For short loops, start the incoming player beyond the quiet intro instead of repeatedly
     * replaying a baked-in fade-in. Long files retain the old small random jitter behaviour.
     */
    fun startOffsetRange(durationMillis: Int): IntRange {
        if (durationMillis < 2_500) return 0..0
        val weight = shortClipWeight(durationMillis)
        val legacyMax = minOf(durationMillis / 12, 450).coerceAtLeast(0)
        val shortTargetMin = (durationMillis * 0.10f).roundToInt()
        val shortTargetMax = (durationMillis * 0.16f).roundToInt()
        val hardMax = minOf(MAX_START_OFFSET_MS, durationMillis / 5).coerceAtLeast(0)
        val min = blend(0, shortTargetMin, weight).coerceIn(0, hardMax)
        val max = blend(legacyMax, shortTargetMax, weight).coerceIn(min, hardMax)
        return min..max
    }

    /** Equal-power rather than linear crossfade, preventing the perceived dip around the midpoint. */
    fun crossfadeGains(fraction: Float): Pair<Float, Float> {
        val t = fraction.coerceIn(0f, 1f)
        val angle = t.toDouble() * (PI / 2.0)
        return cos(angle).toFloat() to sin(angle).toFloat()
    }

    private fun shortClipWeight(durationMillis: Int): Float = when {
        durationMillis <= FULL_SHORT_ADAPT_MS -> 1f
        durationMillis >= ADAPT_END_MS -> 0f
        else -> (ADAPT_END_MS - durationMillis).toFloat() / (ADAPT_END_MS - FULL_SHORT_ADAPT_MS).toFloat()
    }

    private fun blend(base: Int, target: Int, weight: Float): Int =
        (base + (target - base) * weight.coerceIn(0f, 1f)).roundToInt()
}

/**
 * Voice-first ambience bus with any number of logical layers selected by the AI.''',
    "internal object AmbienceLoopTransitionPolicy",
)

patch(
    source,
    '''        val maximumUsefulOverlap = (duration / 3).coerceAtLeast(250)
        val minOverlap = overlapMinMillis.coerceAtMost(maximumUsefulOverlap).coerceAtLeast(200)
        val maxOverlap = overlapMaxMillis.coerceAtMost(maximumUsefulOverlap).coerceAtLeast(minOverlap)
        val overlap = if (maxOverlap == minOverlap) minOverlap else Random.nextInt(minOverlap, maxOverlap + 1)''',
    '''        val overlapRange = AmbienceLoopTransitionPolicy.overlapRange(
            durationMillis = duration,
            requestedMinMillis = overlapMinMillis,
            requestedMaxMillis = overlapMaxMillis,
        )
        val overlap = if (overlapRange.first == overlapRange.last) {
            overlapRange.first
        } else {
            Random.nextInt(overlapRange.first, overlapRange.last + 1)
        }''',
    "AmbienceLoopTransitionPolicy.overlapRange(",
)

patch(
    source,
    '''            val fraction = index.toFloat() / steps.toFloat()
            old.fade = 1f - fraction
            next.fade = fraction
            applyLevel(layer, old)''',
    '''            val fraction = index.toFloat() / steps.toFloat()
            val (oldGain, nextGain) = AmbienceLoopTransitionPolicy.crossfadeGains(fraction)
            old.fade = oldGain
            next.fade = nextGain
            applyLevel(layer, old)''',
    "AmbienceLoopTransitionPolicy.crossfadeGains(fraction)",
)

patch(
    source,
    '''    private fun loopStartJitterMillis(duration: Int): Int {
        if (duration < 2_000) return 0
        val ceiling = minOf(duration / 12, 450).coerceAtLeast(1)
        return Random.nextInt(0, ceiling + 1)
    }''',
    '''    private fun loopStartJitterMillis(duration: Int): Int {
        val range = AmbienceLoopTransitionPolicy.startOffsetRange(duration)
        return if (range.first == range.last) range.first else Random.nextInt(range.first, range.last + 1)
    }''',
    "AmbienceLoopTransitionPolicy.startOffsetRange(duration)",
)


test_path = Path("app/src/test/java/vn/nghetruyen/app/playback/AmbienceLoopTransitionPolicyTest.kt")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(
    '''package vn.nghetruyen.app.playback

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
''',
    encoding="utf-8",
)
print(f"WRITE {test_path}")
