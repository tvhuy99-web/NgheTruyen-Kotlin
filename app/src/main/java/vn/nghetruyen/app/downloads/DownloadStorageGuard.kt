package vn.nghetruyen.app.downloads

import android.content.Context
import android.os.StatFs


object DownloadStorageGuard {
    data class Estimate(
        val chapterCount: Int,
        val estimatedChapterBytes: Long,
        val requiredBytes: Long,
        val availableBytes: Long,
        val reserveBytes: Long,
    ) {
        val hasEnoughSpace: Boolean get() = availableBytes - reserveBytes >= requiredBytes
        val shortfallBytes: Long get() = (requiredBytes + reserveBytes - availableBytes).coerceAtLeast(0L)
    }

    fun availableBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun estimate(
        availableBytes: Long,
        chapterCount: Int,
        knownDownloadedBytes: Long = 0L,
        knownDownloadedChapters: Int = 0,
        reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    ): Estimate {
        val safeCount = chapterCount.coerceAtLeast(1)
        val observedAverage = if (knownDownloadedBytes > 0 && knownDownloadedChapters > 0) {
            knownDownloadedBytes / knownDownloadedChapters
        } else DEFAULT_CHAPTER_BYTES
        val perChapter = observedAverage.coerceIn(MIN_CHAPTER_BYTES, MAX_CHAPTER_BYTES)
        val required = multiplyCapped(perChapter, safeCount.toLong(), MAX_ESTIMATE_BYTES)
        return Estimate(
            chapterCount = safeCount,
            estimatedChapterBytes = perChapter,
            requiredBytes = required,
            availableBytes = availableBytes.coerceAtLeast(0L),
            reserveBytes = reserveBytes.coerceAtLeast(0L),
        )
    }

    private fun multiplyCapped(left: Long, right: Long, cap: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        if (left > cap / right) return cap
        return (left * right).coerceAtMost(cap)
    }

    const val DEFAULT_CHAPTER_BYTES: Long = 256L * 1024L
    const val MIN_CHAPTER_BYTES: Long = 32L * 1024L
    const val MAX_CHAPTER_BYTES: Long = 2L * 1024L * 1024L
    const val DEFAULT_RESERVE_BYTES: Long = 64L * 1024L * 1024L
    const val MAX_ESTIMATE_BYTES: Long = 16L * 1024L * 1024L * 1024L
}
