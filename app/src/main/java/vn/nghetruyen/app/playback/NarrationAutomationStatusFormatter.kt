package vn.nghetruyen.app.playback

import java.util.Locale

internal object NarrationAutomationStatusFormatter {
    private const val MAX_ERROR_DETAIL_CHARS = 280

    fun normalizeIssues(messages: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val normalized = messages
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim().trimEnd('.') }
            .filter(String::isNotBlank)
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .toList()
        if (normalized.isEmpty()) return emptyList()

        // Retry attempts are diagnostic history, not separate final errors. Once the coordinator
        // emits a final Freesound summary, collapse every Freesound retry/detail warning into that
        // one final state while preserving unrelated real issues (TTS, AI, persistence, etc.).
        val finalFreesoundSummary = normalized.lastOrNull(::isSummaryOnlyIssue)
        if (finalFreesoundSummary != null) {
            return normalized
                .filterNot(::isFreesoundIssue)
                .plus(finalFreesoundSummary)
                .distinctBy { it.lowercase(Locale.ROOT) }
        }

        return normalized.filterNot(::isSummaryOnlyIssue)
    }

    fun errorReport(messages: List<String>): String {
        val errors = normalizeIssues(messages)
        if (errors.isEmpty()) return ""
        return buildString {
            append(" • ").append(errors.size).append(" lỗi: ")
            errors.forEachIndexed { index, error ->
                if (index > 0) append("; ")
                append(index + 1).append(") ").append(error.take(MAX_ERROR_DETAIL_CHARS))
            }
        }
    }

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
        issues: List<String> = emptyList(),
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
        append(errorReport(issues + listOfNotNull(warning)))
        if (beginPlayback) append(". Đang bắt đầu phát")
        append('.')
    }

    private fun isFreesoundIssue(message: String): Boolean =
        message.lowercase(Locale.ROOT).contains("freesound")

    private fun isSummaryOnlyIssue(message: String): Boolean {
        val value = message.lowercase(Locale.ROOT)
        return value.startsWith("một số âm thanh freesound còn thiếu sau") ||
            value.startsWith("freesound chưa tải được asset nào sau") ||
            value.startsWith("freesound không tạo được kế hoạch âm thanh hợp lệ sau") ||
            value.startsWith("freesound còn thiếu sau")
    }
}
