package vn.nghetruyen.app.playback

internal object FreesoundPlaybackStatusFormatter {
    fun format(
        resultPresent: Boolean,
        downloadedAssets: Int,
        reusedAssets: Int,
        retryRequired: Boolean,
        audioLayersEnabled: Boolean,
    ): String {
        if (!audioLayersEnabled) return " • các lớp âm thanh Mode 3 đang tắt"
        if (!resultPresent) return " • Mode 3 chưa tạo được kế hoạch âm thanh"
        val downloaded = downloadedAssets.coerceAtLeast(0)
        val reused = reusedAssets.coerceAtLeast(0)
        val base = " • Freesound: tải mới $downloaded tệp, dùng lại $reused tệp đã có"
        return if (retryRequired) "$base; đang thử lại phần còn thiếu" else base
    }
}
