package vn.nghetruyen.app.audio

/** Runtime-only knobs used by the reference TTS dialogs without leaking story overrides globally. */
object ReferenceSonicRuntime {
    @Volatile
    var accurateMode: Boolean = false

    @Volatile
    var outputGain: Float = 1f
}
