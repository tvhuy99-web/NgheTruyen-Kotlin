package vn.nghetruyen.app.playback

object SleepTimerPolicy {
    const val MAX_MINUTES = 180

    fun deadlineFromMinutes(nowMillis: Long, minutes: Int): Long? {
        if (minutes <= 0) return null
        return nowMillis + minutes.coerceIn(1, MAX_MINUTES) * 60_000L
    }

    fun remainingMillis(deadlineMillis: Long?, nowMillis: Long): Long? = deadlineMillis
        ?.minus(nowMillis)
        ?.takeIf { it > 0L }

    fun hasExpired(deadlineMillis: Long?, nowMillis: Long): Boolean =
        deadlineMillis != null && deadlineMillis <= nowMillis
}
