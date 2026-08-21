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
        val parts = buildList {
            val downloaded = downloadedAssets.coerceAtLeast(0)
            val reused = reusedAssets.coerceAtLeast(0)
            add("$downloaded tải mới")
            add("$reused bộ nhớ tạm")
            if (!resultPresent) add("chưa có kế hoạch âm thanh")
            else if (retryRequired) add("còn thiếu")
        }
        return if (parts.isEmpty()) "" else " • ${parts.joinToString(" • ")}"
    }
}
