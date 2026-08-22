package vn.nghetruyen.app.playback

internal object FreesoundPlaybackStatusFormatter {
    fun format(
        resultPresent: Boolean,
        downloadedAssets: Int,
        reusedAssets: Int,
        retryRequired: Boolean,
        audioLayersEnabled: Boolean,
    ): String {
        if (!audioLayersEnabled) return ""
        val downloaded = downloadedAssets.coerceAtLeast(0)
        val reused = reusedAssets.coerceAtLeast(0)
        val parts = buildList {
            // Normal zero activity is not useful status information. Only surface transfer counters
            // when something was actually downloaded/reused, or surface an abnormal plan state.
            if (downloaded > 0) add("$downloaded tải mới")
            if (reused > 0) add("$reused bộ nhớ tạm")
            if (!resultPresent) add("chưa có kế hoạch âm thanh")
            else if (retryRequired) add("còn thiếu")
        }
        return if (parts.isEmpty()) "" else " • ${parts.joinToString(" • ")}"
    }
}
