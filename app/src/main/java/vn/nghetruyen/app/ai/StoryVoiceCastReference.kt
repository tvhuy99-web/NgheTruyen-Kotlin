package vn.nghetruyen.app.ai

/**
 * The legacy Room entity has no dedicated story voice-cast mode columns. Keep the reference-only
 * mode metadata in a reserved first line of voiceCastNote, and strip it before text is sent to AI.
 */
enum class StoryVoiceCastMode { GLOBAL, PRIVATE, OFF }

data class StoryVoiceCastReferenceSettings(
    val mode: StoryVoiceCastMode = StoryVoiceCastMode.GLOBAL,
    val autoRunOnOpenTts: Boolean = false,
    val note: String = "",
)

object StoryVoiceCastReferenceCodec {
    private const val PREFIX = "@NGHETRUYEN_VOICE_CAST|"

    fun decode(raw: String): StoryVoiceCastReferenceSettings {
        val first = raw.lineSequence().firstOrNull().orEmpty()
        if (!first.startsWith(PREFIX)) return StoryVoiceCastReferenceSettings(note = raw)
        val tokens = first.removePrefix(PREFIX).split('|')
        val mode = runCatching { StoryVoiceCastMode.valueOf(tokens.getOrNull(0).orEmpty()) }
            .getOrDefault(StoryVoiceCastMode.GLOBAL)
        val auto = tokens.getOrNull(1) == "AUTO=1"
        val body = raw.substringAfter('\n', "")
        return StoryVoiceCastReferenceSettings(mode = mode, autoRunOnOpenTts = auto, note = body)
    }

    fun encode(mode: StoryVoiceCastMode, autoRunOnOpenTts: Boolean, note: String): String = buildString {
        append(PREFIX)
        append(mode.name)
        append('|')
        append(if (autoRunOnOpenTts) "AUTO=1" else "AUTO=0")
        val clean = note.trim()
        if (clean.isNotBlank()) append('\n').append(clean)
    }

    fun userNote(raw: String): String = decode(raw).note
}
