package vn.nghetruyen.app.playback


object ReaderVolumeKeyPolicy {
    enum class Key { VOLUME_UP, VOLUME_DOWN, OTHER }

    fun paragraphDelta(
        readerVisible: Boolean,
        navigationEnabled: Boolean,
        actionDown: Boolean,
        repeatCount: Int,
        key: Key,
    ): Int? {
        if (!readerVisible || !navigationEnabled || !actionDown || repeatCount != 0) return null
        return when (key) {
            Key.VOLUME_UP -> -1
            Key.VOLUME_DOWN -> 1
            Key.OTHER -> null
        }
    }
}
