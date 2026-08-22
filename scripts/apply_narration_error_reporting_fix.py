from pathlib import Path

root = Path(__file__).resolve().parents[1]

formatter = root / "app/src/main/java/vn/nghetruyen/app/playback/NarrationAutomationStatusFormatter.kt"
formatter.write_text('''package vn.nghetruyen.app.playback

import java.util.Locale

internal object NarrationAutomationStatusFormatter {
    private const val MAX_ERROR_DETAIL_CHARS = 280

    fun normalizeIssues(messages: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val normalized = messages
            .asSequence()
            .map { it.replace(Regex("\\\\s+"), " ").trim().trimEnd('.') }
            .filter(String::isNotBlank)
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .toList()
        if (normalized.isEmpty()) return emptyList()

        val specific = normalized.filterNot(::isSummaryOnlyIssue)
        return if (specific.isNotEmpty()) specific else normalized
    }

    fun errorReport(messages: List<String>): String {
        val errors = normalizeIssues(messages)
        if (errors.isEmpty()) return " • 0 lỗi"
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

    private fun isSummaryOnlyIssue(message: String): Boolean {
        val value = message.lowercase(Locale.ROOT)
        return value.startsWith("một số âm thanh freesound còn thiếu sau") ||
            value.startsWith("freesound chưa tải được asset nào sau") ||
            value.startsWith("freesound không tạo được kế hoạch âm thanh hợp lệ sau") ||
            value.startsWith("freesound còn thiếu sau")
    }
}
''', encoding='utf-8')

service = root / "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
text = service.read_text(encoding='utf-8')

old = '''                        val warning = warnings.firstOrNull()?.takeIf(String::isNotBlank)
                        val transferSummary = PlaybackQueueStore.consumeFreesoundTransferSummary(
'''
new = '''                        val issueMessages = NarrationAutomationStatusFormatter.normalizeIssues(warnings)
                        val firstIssue = issueMessages.firstOrNull()
                        val transferSummary = PlaybackQueueStore.consumeFreesoundTransferSummary(
'''
assert old in text, 'current ready warning declaration not found'
text = text.replace(old, new, 1)

old = '''                                beginPlayback = true,
                                warning = warning,
                            ),
'''
new = '''                                beginPlayback = true,
                                issues = issueMessages,
                            ),
'''
assert old in text, 'current ready formatter args not found'
text = text.replace(old, new, 1)

old = '''                                if (planResult?.freesoundRetryRequired == true || warning != null) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                                mapOf(
                                    "resolvedAssets" to (planResult?.freesoundResolvedAssets ?: 0).toString(),
                                    "retryRequired" to (planResult?.freesoundRetryRequired ?: false).toString(),
                                    "musicApplied" to musicApplied.toString(),
                                    "audioPlanCreated" to (planResult?.audioPlanCreated ?: false).toString(),
                                    "warning" to warning.orEmpty().take(180),
                                ),
'''
new = '''                                if (planResult?.freesoundRetryRequired == true || issueMessages.isNotEmpty()) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                                mapOf(
                                    "resolvedAssets" to (planResult?.freesoundResolvedAssets ?: 0).toString(),
                                    "retryRequired" to (planResult?.freesoundRetryRequired ?: false).toString(),
                                    "musicApplied" to musicApplied.toString(),
                                    "audioPlanCreated" to (planResult?.audioPlanCreated ?: false).toString(),
                                    "issueCount" to issueMessages.size.toString(),
                                    "warning" to firstIssue.orEmpty().take(180),
                                ),
'''
assert old in text, 'current diagnostic warning block not found'
text = text.replace(old, new, 1)

old = '''                        transitionMessage = warning?.take(180)
'''
new = '''                        transitionMessage = firstIssue?.take(180)
'''
assert old in text, 'current transition warning not found'
text = text.replace(old, new, 1)

old = '''                val warningSuffix = warnings.firstOrNull()?.takeIf(String::isNotBlank)
                    ?.let { " ${it.take(120)}" }
                    .orEmpty()
'''
new = '''                val errorSuffix = NarrationAutomationStatusFormatter.errorReport(warnings)
'''
assert old in text, 'failure warning suffix not found'
text = text.replace(old, new, 1)
text = text.replace('''"Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần.$warningSuffix"''', '''"Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần$errorSuffix"''', 1)
text = text.replace('''"Chưa chuẩn bị xong. Sẽ thử lại sau 5 giây (lần ${attempt + 1}/$MAX_NARRATION_ATTEMPTS).$warningSuffix"''', '''"Chưa chuẩn bị xong. Sẽ thử lại sau 5 giây (lần ${attempt + 1}/$MAX_NARRATION_ATTEMPTS)$errorSuffix"''', 1)

old = '''                    val warning = result?.warnings?.firstOrNull()?.takeIf(String::isNotBlank)
                        ?: attempt.exceptionOrNull()?.message
                    val mode3 = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)
                    val baseMessage = when {
                        result == null -> "Không chuẩn bị AI trước được chương tiếp theo: ${chapter.chapter.title}." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        !planVoice -> "Đã tải xong chương tiếp theo: ${chapter.chapter.title}. Đã chuẩn bị âm thanh AI." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        assignmentCount <= 0 -> "Phân vai trước chưa tạo được mục giọng hợp lệ: ${chapter.chapter.title}." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
'''
new = '''                    val issueMessages = NarrationAutomationStatusFormatter.normalizeIssues(
                        result?.warnings.orEmpty() + listOfNotNull(attempt.exceptionOrNull()?.message),
                    )
                    val mode3 = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)
                    val issueReport = NarrationAutomationStatusFormatter.errorReport(issueMessages)
                    val baseMessage = when {
                        result == null -> "Không chuẩn bị AI trước được chương tiếp theo: ${chapter.chapter.title}$issueReport."
                        !planVoice -> "Đã tải xong chương tiếp theo: ${chapter.chapter.title}. Đã chuẩn bị âm thanh AI$issueReport."
                        assignmentCount <= 0 -> "Phân vai trước chưa tạo được mục giọng hợp lệ: ${chapter.chapter.title}$issueReport."
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}$issueReport."
'''
assert old in text, 'prefetch warning/base block not found'
text = text.replace(old, new, 1)

old = '''                            beginPlayback = false,
                            warning = warning,
                        )
'''
new = '''                            beginPlayback = false,
                            issues = issueMessages,
                        )
'''
assert text.count(old) >= 2, f'expected at least 2 prefetch formatter warning calls, got {text.count(old)}'
text = text.replace(old, new, 2)

service.write_text(text, encoding='utf-8')

freesound_test = root / "app/src/test/java/vn/nghetruyen/app/playback/FreesoundPlaybackStatusFormatterTest.kt"
freesound_test.write_text('''package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class FreesoundPlaybackStatusFormatterTest {
    @Test
    fun compactCountsOnly() {
        assertEquals(
            " • 5 tải mới • 3 bộ nhớ tạm",
            FreesoundPlaybackStatusFormatter.format(true, 5, 3, false, true),
        )
    }

    @Test
    fun zeroCountsRemainExplicit() {
        assertEquals(
            " • 0 tải mới • 0 bộ nhớ tạm",
            FreesoundPlaybackStatusFormatter.format(true, 0, 0, false, true),
        )
    }

    @Test
    fun abnormalStateRemainsVisible() {
        assertEquals(
            " • 1 tải mới • 0 bộ nhớ tạm • còn thiếu",
            FreesoundPlaybackStatusFormatter.format(true, 1, 0, true, true),
        )
    }

    @Test
    fun disabledLayersStaySilent() {
        assertEquals("", FreesoundPlaybackStatusFormatter.format(false, 2, 4, true, false))
    }
}
''', encoding='utf-8')

narration_test = root / "app/src/test/java/vn/nghetruyen/app/playback/NarrationAutomationStatusFormatterTest.kt"
narration_test.write_text('''package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationAutomationStatusFormatterTest {
    @Test
    fun automaticAndManualShareSameBodyAndAlwaysReportErrorCount() {
        assertEquals(
            "Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm • 0 lỗi. Đang bắt đầu phát.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 12,
                resultPresent = true,
                downloadedAssets = 5,
                reusedAssets = 3,
                retryRequired = false,
                audioLayersEnabled = true,
                beginPlayback = true,
            ),
        )
    }

    @Test
    fun prefetchOnlyAddsNextChapterPrefix() {
        assertEquals(
            "Đã tải xong chương tiếp theo: Chương 2. Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm • 0 lỗi.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 12,
                resultPresent = true,
                downloadedAssets = 5,
                reusedAssets = 3,
                retryRequired = false,
                audioLayersEnabled = true,
                prefix = "Đã tải xong chương tiếp theo: Chương 2",
            ),
        )
    }

    @Test
    fun reportsAllDistinctSpecificErrorsInline() {
        assertEquals(
            "Đã phân vai xong 20 mục • 3 tải mới • 5 bộ nhớ tạm • 2 lỗi: 1) Freesound ‘light rain drops’: Preview quá thời gian; 2) Âm thanh quan trọng ‘night wind’ chưa tìm thấy kết quả. Đang bắt đầu phát.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 20,
                resultPresent = true,
                downloadedAssets = 3,
                reusedAssets = 5,
                retryRequired = false,
                audioLayersEnabled = true,
                beginPlayback = true,
                issues = listOf(
                    "Freesound ‘light rain drops’: Preview quá thời gian.",
                    "Freesound ‘light rain drops’: Preview quá thời gian.",
                    "Một số âm thanh Freesound còn thiếu sau 3 lần; ứng dụng sẽ phát phần đã tải hợp lệ thay vì làm câm cả chương.",
                    "Âm thanh quan trọng ‘night wind’ chưa tìm thấy kết quả.",
                ),
            ),
        )
    }

    @Test
    fun reportsErrorsEvenWhenAudioLayersAreDisabled() {
        assertEquals(
            "Đã phân vai xong 8 mục • 1 lỗi: 1) Không áp dụng được giọng nhân vật.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 8,
                resultPresent = true,
                downloadedAssets = 0,
                reusedAssets = 0,
                retryRequired = false,
                audioLayersEnabled = false,
                issues = listOf("Không áp dụng được giọng nhân vật."),
            ),
        )
    }
}
''', encoding='utf-8')

print('NARRATION_ERROR_REPORTING_PATCH_APPLIED')
