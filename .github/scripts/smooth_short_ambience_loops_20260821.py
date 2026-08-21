from pathlib import Path


source = Path("app/src/main/java/vn/nghetruyen/app/playback/SceneAmbienceController.kt")
text = source.read_text(encoding="utf-8")

new_policy = '''internal object AmbienceLoopTransitionPolicy {
    private const val MIN_OVERLAP_MS = 200
    private const val MAX_OVERLAP_MS = 4_000
    private const val MAX_OVERLAP_FRACTION = 0.42f
    private const val TARGET_OVERLAP_RATIO = 0.25f
    private const val FILE_OVERLAP_MIN_FRACTION = 0.28f
    private const val FILE_OVERLAP_MAX_FRACTION = 0.38f
    private const val MAX_START_OFFSET_MS = 1_800
    private const val START_OFFSET_MIN_FRACTION = 0.08f
    private const val START_OFFSET_MAX_FRACTION = 0.16f

    /**
     * Loop smoothing is derived continuously from this file's own duration. There are no special
     * 10s/15s/20s buckets: nearby durations produce nearby overlap ranges. The configured overlap
     * remains the baseline, while relatively short files receive a stronger proportional overlap
     * because their baked-in fade-in/fade-out occupies more of the whole clip.
     */
    fun overlapRange(durationMillis: Int, requestedMinMillis: Int, requestedMaxMillis: Int): IntRange {
        if (durationMillis <= 0) return MIN_OVERLAP_MS..MIN_OVERLAP_MS
        val hardMax = minOf(
            MAX_OVERLAP_MS,
            (durationMillis * MAX_OVERLAP_FRACTION).roundToInt(),
        ).coerceAtLeast(MIN_OVERLAP_MS)
        val requestedMin = requestedMinMillis.coerceIn(MIN_OVERLAP_MS, hardMax)
        val requestedMax = requestedMaxMillis.coerceIn(requestedMin, hardMax)
        val weight = adaptiveWeight(durationMillis, requestedMax)
        val fileTargetMin = (durationMillis * FILE_OVERLAP_MIN_FRACTION)
            .roundToInt().coerceIn(MIN_OVERLAP_MS, hardMax)
        val fileTargetMax = (durationMillis * FILE_OVERLAP_MAX_FRACTION)
            .roundToInt().coerceIn(fileTargetMin, hardMax)
        val min = blend(requestedMin, fileTargetMin, weight).coerceIn(MIN_OVERLAP_MS, hardMax)
        val max = blend(requestedMax, fileTargetMax, weight).coerceIn(min, hardMax)
        return min..max
    }

    /**
     * Incoming loops skip part of their own quiet intro in proportion to the same file-relative
     * smoothing pressure. This is continuous for every duration instead of switching at a fixed
     * number of seconds. Absolute caps exist only to keep seeks safe on unusual source files.
     */
    fun startOffsetRange(durationMillis: Int, requestedOverlapMaxMillis: Int): IntRange {
        if (durationMillis <= 0) return 0..0
        val hardMax = minOf(
            MAX_START_OFFSET_MS,
            (durationMillis * 0.20f).roundToInt(),
        ).coerceAtLeast(0)
        if (hardMax == 0) return 0..0
        val legacyMax = minOf(durationMillis / 12, 450).coerceIn(0, hardMax)
        val weight = adaptiveWeight(durationMillis, requestedOverlapMaxMillis.coerceAtLeast(MIN_OVERLAP_MS))
        val targetMin = (durationMillis * START_OFFSET_MIN_FRACTION).roundToInt().coerceIn(0, hardMax)
        val targetMax = (durationMillis * START_OFFSET_MAX_FRACTION).roundToInt().coerceIn(targetMin, hardMax)
        val min = blend(0, targetMin, weight).coerceIn(0, hardMax)
        val max = blend(legacyMax, targetMax, weight).coerceIn(min, hardMax)
        return min..max
    }

    /** Equal-power rather than linear crossfade, preventing the perceived dip around the midpoint. */
    fun crossfadeGains(fraction: Float): Pair<Float, Float> {
        val t = fraction.coerceIn(0f, 1f)
        val angle = t.toDouble() * (PI / 2.0)
        return cos(angle).toFloat() to sin(angle).toFloat()
    }

    private fun adaptiveWeight(durationMillis: Int, requestedOverlapMaxMillis: Int): Float {
        if (durationMillis <= 0) return 0f
        val currentRatio = requestedOverlapMaxMillis.toFloat() / durationMillis.toFloat()
        return (currentRatio / TARGET_OVERLAP_RATIO).coerceIn(0f, 1f)
    }

    private fun blend(base: Int, target: Int, weight: Float): Int =
        (base + (target - base) * weight.coerceIn(0f, 1f)).roundToInt()
}
'''

if "private const val FULL_SHORT_ADAPT_MS" in text:
    start = text.index("internal object AmbienceLoopTransitionPolicy {")
    end = text.index("/**\n * Voice-first ambience bus", start)
    text = text[:start] + new_policy + "\n" + text[end:]
    print("PATCH SceneAmbienceController: remove fixed-duration buckets")
elif "private const val TARGET_OVERLAP_RATIO" in text:
    print("SKIP SceneAmbienceController: file-adaptive policy already present")
else:
    raise SystemExit("SceneAmbienceController: cannot locate ambience loop policy")

old_call = "val jitter = loopStartJitterMillis(nextPlayer.duration)"
new_call = "val jitter = loopStartJitterMillis(nextPlayer.duration, layer.overlapMaxMillis)"
if old_call in text:
    text = text.replace(old_call, new_call, 1)
elif new_call not in text:
    raise SystemExit("SceneAmbienceController: loop jitter call not found")

old_fun = '''    private fun loopStartJitterMillis(duration: Int): Int {
        val range = AmbienceLoopTransitionPolicy.startOffsetRange(duration)
        return if (range.first == range.last) range.first else Random.nextInt(range.first, range.last + 1)
    }'''
new_fun = '''    private fun loopStartJitterMillis(duration: Int, requestedOverlapMaxMillis: Int): Int {
        val range = AmbienceLoopTransitionPolicy.startOffsetRange(duration, requestedOverlapMaxMillis)
        return if (range.first == range.last) range.first else Random.nextInt(range.first, range.last + 1)
    }'''
if old_fun in text:
    text = text.replace(old_fun, new_fun, 1)
elif new_fun not in text:
    raise SystemExit("SceneAmbienceController: loop jitter function not found")
source.write_text(text, encoding="utf-8")


test_path = Path("app/src/test/java/vn/nghetruyen/app/playback/AmbienceLoopTransitionPolicyTest.kt")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(
    '''package vn.nghetruyen.app.playback

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
''',
    encoding="utf-8",
)
print(f"WRITE {test_path}")


prompt_path = Path("app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt")
prompt = prompt_path.read_text(encoding="utf-8")
mode3_anchor = '- Không có độ dài tối thiểu cho AMBIENCE. Một lớp có thể chỉ tồn tại một UNIT hoặc kéo dài nhiều cảnh, miễn ranh giới bắt đầu/dừng đúng với nguồn âm thực tế trong truyện.'
mode3_extra = '- Độ dài vật lý của file ambience KHÔNG quyết định ranh giới scene. File ngắn vẫn phải phủ toàn bộ khoảng mà nguồn âm còn tồn tại; runtime tự lặp và làm mượt dựa trên chính file, AI không được cắt scene, đổi query hoặc dừng ambience chỉ vì file ngắn.'
if mode3_extra not in prompt:
    if mode3_anchor not in prompt:
        raise SystemExit("XpkUnifiedNarrationPrompt: Mode3 ambience anchor not found")
    prompt = prompt.replace(mode3_anchor, mode3_anchor + '\\n                appendLine("' + mode3_extra + '")', 1)
    # The replacement above entered Kotlin source inside appendLine text; normalize the leading source syntax.
    prompt = prompt.replace('appendLine("' + mode3_anchor + '\\n                appendLine("' + mode3_extra + '")")', 'appendLine("' + mode3_anchor + '")\\n                appendLine("' + mode3_extra + '")', 1)

local_anchor = '5. Không có độ dài tối thiểu cho một ambience_scene. Một UNIT cũng hợp lệ nếu nguồn âm thực sự chỉ tồn tại ở đó; ngược lại phải kéo dài scene qua mọi UNIT mà nguồn âm còn tồn tại dù văn bản không nhắc lại.'
local_extra = ' Độ dài vật lý của file không quyết định ranh giới scene: file ngắn vẫn phải phủ toàn bộ khoảng nguồn âm còn tồn tại; runtime tự lặp/làm mượt theo chính file, không được đổi hoặc dừng asset chỉ vì file ngắn.'
if local_extra.strip() not in prompt:
    if local_anchor not in prompt:
        raise SystemExit("XpkUnifiedNarrationPrompt: local ambience anchor not found")
    prompt = prompt.replace(local_anchor, local_anchor + local_extra, 1)
prompt_path.write_text(prompt, encoding="utf-8")
print("PATCH XpkUnifiedNarrationPrompt: file duration is runtime concern")


quality_test = Path("app/src/test/java/vn/nghetruyen/app/ai/XpkAudioPromptQualityTest.kt")
q = quality_test.read_text(encoding="utf-8")
local_assert_anchor = '        assertTrue(prompt.contains("Nếu các UNIT sau không nhắc lại nguồn âm"))\n'
local_assert = '        assertTrue(prompt.contains("Độ dài vật lý của file không quyết định ranh giới scene"))\n'
if local_assert not in q:
    if local_assert_anchor not in q:
        raise SystemExit("XpkAudioPromptQualityTest: local assertion anchor not found")
    q = q.replace(local_assert_anchor, local_assert_anchor + local_assert, 1)
mode3_assert_anchor = '        assertTrue(prompt.contains("Không có độ dài tối thiểu cho AMBIENCE"))\n'
mode3_assert = '        assertTrue(prompt.contains("Độ dài vật lý của file ambience KHÔNG quyết định ranh giới scene"))\n'
if mode3_assert not in q:
    if mode3_assert_anchor not in q:
        raise SystemExit("XpkAudioPromptQualityTest: Mode3 assertion anchor not found")
    q = q.replace(mode3_assert_anchor, mode3_assert_anchor + mode3_assert, 1)
quality_test.write_text(q, encoding="utf-8")
print("PATCH XpkAudioPromptQualityTest: verify file-duration independence")
