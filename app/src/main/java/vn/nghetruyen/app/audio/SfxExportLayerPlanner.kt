package vn.nghetruyen.app.audio

/** One non-looping source placement on the exported narration timeline. */
data class SfxExportPlacement(
    val startFrame: Long,
    val endFrameExclusive: Long,
)

/**
 * Expands one logical SFX cue into ordinary non-looping mixer layers.
 *
 * Keeping every placement non-looping preserves foreground-SFX ducking in [Pcm16SceneMixer]. An
 * ACTION LOOP is therefore represented by consecutive source placements until the story boundary;
 * a source longer than the action is simply clipped by [endFrameExclusive].
 */
object SfxExportLayerPlanner {
    private const val MAX_ACTION_LOOP_PLACEMENTS = 4_096

    fun placements(
        cue: SoundEffectCue,
        actionStartFrame: Long,
        actionEndFrameExclusive: Long,
        sourceFrames: Long,
        sampleRate: Long,
    ): List<SfxExportPlacement> {
        if (actionEndFrameExclusive <= actionStartFrame || sourceFrames <= 0L || sampleRate <= 0L) return emptyList()

        if (cue.loopUntilStop) {
            val out = ArrayList<SfxExportPlacement>()
            var start = actionStartFrame
            var count = 0
            while (start < actionEndFrameExclusive && count < MAX_ACTION_LOOP_PLACEMENTS) {
                out += SfxExportPlacement(start, actionEndFrameExclusive)
                start += sourceFrames
                count++
            }
            return out
        }

        val repeatCount = cue.repeatCount.coerceIn(1, AudioDirectionLimits.MAX_SFX_REPEAT_COUNT)
        val cadenceFrames = (sampleRate * cue.cadence.intervalMillis / 1_000L).coerceAtLeast(1L)
        return buildList {
            repeat(repeatCount) { repeatIndex ->
                val start = actionStartFrame + cadenceFrames * repeatIndex
                if (start < actionEndFrameExclusive) add(SfxExportPlacement(start, actionEndFrameExclusive))
            }
        }
    }
}
