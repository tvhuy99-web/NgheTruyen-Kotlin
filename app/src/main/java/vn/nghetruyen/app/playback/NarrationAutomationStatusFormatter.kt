package vn.nghetruyen.app.playback

internal object NarrationAutomationStatusFormatter {
    fun ready(
        assignmentCount: Int,
        resultPresent: Boolean,
        downloadedAssets: Int,
        reusedAssets: Int,
        retryRequired: Boolean,
        audioLayersEnabled: Boolean,
        prefix: String? = null,
        beginPlayback: Boolean = false,
        warning: String? = null,
    ): String = buildString {
        prefix?.trim()?.trimEnd('.')?.takeIf(String::isNotBlank)?.let {
            append(it).append(". ")
        }
        append("Đã phân vai xong ").append(assignmentCount.coerceAtLeast(0)).append(" mục")
        append(
            FreesoundPlaybackStatusFormatter.format(
                resultPresent = resultPresent,
                downloadedAssets = downloadedAssets,
                reusedAssets = reusedAssets,
                retryRequired = retryRequired,
                audioLayersEnabled = audioLayersEnabled,
            ),
        )
        if (beginPlayback) append(". Đang bắt đầu phát")
        warning?.trim()?.takeIf(String::isNotBlank)?.let {
            append(" • Cảnh báo: ").append(it.take(140))
        }
        append('.')
    }
}
