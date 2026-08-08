package vn.nghetruyen.app.audio

/** One-shot filename override used by the reference Reader export dialog. */
object ReferenceAudioExportRuntime {
    @Volatile
    private var nextFileName: String? = null

    fun setNextFileName(value: String?) {
        nextFileName = value?.trim()?.takeIf(String::isNotBlank)?.take(180)
    }

    fun consumeNextFileName(): String? {
        val value = nextFileName
        nextFileName = null
        return value
    }
}
