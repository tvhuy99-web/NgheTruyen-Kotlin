package vn.nghetruyen.app.playback

data class MediaKeyEvent(
    val keyCode: Int,
    val action: Int,
    val downTime: Long,
    val eventTime: Long,
    val repeatCount: Int = 0,
    val longPress: Boolean = false,
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val HEADSET_HOOK = 79
        const val MEDIA_PLAY_PAUSE = 85
        const val MEDIA_STOP = 86
        const val MEDIA_NEXT = 87
        const val MEDIA_PREVIOUS = 88
        const val MEDIA_REWIND = 89
        const val MEDIA_FAST_FORWARD = 90
        const val MEDIA_PLAY = 126
        const val MEDIA_PAUSE = 127
    }
}

enum class MediaButtonCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS,
    FORWARD,
    REWIND,
    STOP,
}


data class MediaButtonMapping(
    val singleClick: MediaButtonCommand = MediaButtonCommand.TOGGLE,
    val doubleClick: MediaButtonCommand = MediaButtonCommand.NEXT,
    val tripleClick: MediaButtonCommand = MediaButtonCommand.PREVIOUS,
    val longPress: MediaButtonCommand = MediaButtonCommand.STOP,
) {
    fun sanitized(): MediaButtonMapping = copy(
        singleClick = singleClick.allowedForGesture(),
        doubleClick = doubleClick.allowedForGesture(),
        tripleClick = tripleClick.allowedForGesture(),
        longPress = longPress.allowedForGesture(),
    )

    companion object {
        val DEFAULT = MediaButtonMapping()

        fun fromNames(single: String?, double: String?, triple: String?, long: String?): MediaButtonMapping =
            MediaButtonMapping(
                singleClick = single.toCommand(MediaButtonCommand.TOGGLE),
                doubleClick = double.toCommand(MediaButtonCommand.NEXT),
                tripleClick = triple.toCommand(MediaButtonCommand.PREVIOUS),
                longPress = long.toCommand(MediaButtonCommand.STOP),
            ).sanitized()

        private fun String?.toCommand(fallback: MediaButtonCommand): MediaButtonCommand =
            runCatching { MediaButtonCommand.valueOf(this.orEmpty()) }.getOrDefault(fallback)
    }
}

private fun MediaButtonCommand.allowedForGesture(): MediaButtonCommand = when (this) {
    MediaButtonCommand.PLAY, MediaButtonCommand.PAUSE, MediaButtonCommand.TOGGLE,
    MediaButtonCommand.NEXT, MediaButtonCommand.PREVIOUS, MediaButtonCommand.FORWARD,
    MediaButtonCommand.REWIND, MediaButtonCommand.STOP -> this
}

/**
 * Converts headset/media key events into deterministic app commands.
 *
 * A headset-hook or play/pause key uses a short multi-click window:
 * one click toggles, two clicks advance, three clicks go back. A long
 * press stops playback. Dedicated transport keys are handled immediately.
 */
class MediaButtonGestureInterpreter(
    private val multiClickWindowMillis: Long = 360L,
) {
    private var clickCount = 0
    private var lastClickAt = Long.MIN_VALUE
    private var longPressConsumed = false

    data class Result(
        val immediate: MediaButtonCommand? = null,
        val flushAtMillis: Long? = null,
        val cancelPendingFlush: Boolean = false,
    )

    fun onKeyEvent(
        event: MediaKeyEvent,
        multiClickEnabled: Boolean,
        mapping: MediaButtonMapping = MediaButtonMapping.DEFAULT,
    ): Result {
        val keyCode = event.keyCode
        when (keyCode) {
            MediaKeyEvent.MEDIA_PLAY -> return onDedicated(event, MediaButtonCommand.PLAY)
            MediaKeyEvent.MEDIA_PAUSE -> return onDedicated(event, MediaButtonCommand.PAUSE)
            MediaKeyEvent.MEDIA_NEXT -> return onDedicated(event, MediaButtonCommand.NEXT)
            MediaKeyEvent.MEDIA_PREVIOUS -> return onDedicated(event, MediaButtonCommand.PREVIOUS)
            MediaKeyEvent.MEDIA_FAST_FORWARD -> return onDedicated(event, MediaButtonCommand.FORWARD)
            MediaKeyEvent.MEDIA_REWIND -> return onDedicated(event, MediaButtonCommand.REWIND)
            MediaKeyEvent.MEDIA_STOP -> return onDedicated(event, MediaButtonCommand.STOP)
            MediaKeyEvent.HEADSET_HOOK,
            MediaKeyEvent.MEDIA_PLAY_PAUSE,
            -> Unit
            else -> return Result()
        }

        if (event.action == MediaKeyEvent.ACTION_DOWN) {
            if (event.longPress || event.repeatCount > 0) {
                longPressConsumed = true
                clickCount = 0
                return Result(
                    immediate = mapping.longPress,
                    cancelPendingFlush = true,
                )
            }
            return Result()
        }
        if (event.action != MediaKeyEvent.ACTION_UP) return Result()
        if (longPressConsumed) {
            longPressConsumed = false
            return Result(cancelPendingFlush = true)
        }
        if (!multiClickEnabled) {
            clickCount = 0
            return Result(immediate = mapping.singleClick, cancelPendingFlush = true)
        }
        clickCount = if (lastClickAt != Long.MIN_VALUE && event.eventTime - lastClickAt <= multiClickWindowMillis) {
            (clickCount + 1).coerceAtMost(3)
        } else {
            1
        }
        lastClickAt = event.eventTime
        return Result(
            flushAtMillis = event.eventTime + multiClickWindowMillis,
            cancelPendingFlush = true,
        )
    }

    fun flush(
        nowMillis: Long,
        mapping: MediaButtonMapping = MediaButtonMapping.DEFAULT,
    ): MediaButtonCommand? {
        if (clickCount == 0 || nowMillis < lastClickAt + multiClickWindowMillis) return null
        val safeMapping = mapping.sanitized()
        val command = when (clickCount) {
            1 -> safeMapping.singleClick
            2 -> safeMapping.doubleClick
            else -> safeMapping.tripleClick
        }
        clickCount = 0
        lastClickAt = Long.MIN_VALUE
        return command
    }

    fun reset() {
        clickCount = 0
        lastClickAt = Long.MIN_VALUE
        longPressConsumed = false
    }

    private fun onDedicated(event: MediaKeyEvent, command: MediaButtonCommand): Result =
        if (event.action == MediaKeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            reset()
            Result(immediate = command, cancelPendingFlush = true)
        } else {
            Result()
        }
}

/** Suppresses the same physical KeyEvent when both a receiver and MediaSession deliver it. */
class MediaButtonEventDeduplicator(
    private val retentionMillis: Long = 1_500L,
) {
    private data class Signature(
        val keyCode: Int,
        val action: Int,
        val downTime: Long,
        val eventTime: Long,
        val repeatCount: Int,
    )

    private val seen = LinkedHashMap<Signature, Long>()

    @Synchronized
    fun accept(event: MediaKeyEvent, nowMillis: Long): Boolean {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().value > retentionMillis) iterator.remove()
        }
        val signature = Signature(event.keyCode, event.action, event.downTime, event.eventTime, event.repeatCount)
        if (seen.containsKey(signature)) return false
        seen[signature] = nowMillis
        return true
    }
}
