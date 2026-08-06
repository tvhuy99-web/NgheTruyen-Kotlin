package vn.nghetruyen.app.sources

import kotlinx.coroutines.withTimeout
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StorySummary

enum class SourceCheckStatus { PASS, FAIL, SKIPPED }

data class SourceCheckStep(
    val name: String,
    val status: SourceCheckStatus,
    val detail: String,
    val elapsedMillis: Long,
)

data class SourceCheckReport(
    val sourceId: String,
    val sourceName: String,
    val resolvedHealth: SourceHealth,
    val checkedAtEpochMillis: Long,
    val steps: List<SourceCheckStep>,
) {
    val passedSteps: Int get() = steps.count { it.status == SourceCheckStatus.PASS }
    val totalSteps: Int get() = steps.count { it.status != SourceCheckStatus.SKIPPED }
}

class SourceHealthChecker(
    private val registry: SourceRegistry,
) {
    suspend fun check(sourceId: String): SourceCheckReport {
        val source = registry.get(sourceId)
            ?: return SourceCheckReport(
                sourceId = sourceId,
                sourceName = sourceId,
                resolvedHealth = SourceHealth.DISABLED,
                checkedAtEpochMillis = System.currentTimeMillis(),
                steps = listOf(SourceCheckStep("Khởi tạo", SourceCheckStatus.FAIL, "Không tìm thấy nguồn.", 0)),
            )
        val steps = mutableListOf<SourceCheckStep>()
        var loginRequired = false

        suspend fun <T> runStep(name: String, block: suspend () -> AppResult<T>): T? {
            val started = System.nanoTime()
            val result = runCatching { withTimeout(STEP_TIMEOUT_MILLIS) { block() } }
                .getOrElse { AppResult.Failure("SOURCE_CHECK_EXCEPTION", it.message ?: "Lỗi không xác định.", it) }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            return when (result) {
                is AppResult.Success -> {
                    steps += SourceCheckStep(name, SourceCheckStatus.PASS, "Hoạt động", elapsed)
                    result.value
                }
                is AppResult.Failure -> {
                    if (result.code.contains("LOGIN", ignoreCase = true) || result.code.contains("SESSION", ignoreCase = true)) {
                        loginRequired = true
                    }
                    steps += SourceCheckStep(name, SourceCheckStatus.FAIL, "${result.code}: ${result.message}", elapsed)
                    null
                }
            }
        }

        suspend fun probeStories(): AppResult<List<StorySummary>> {
            val homeResult = source.home(1)
            if (homeResult is AppResult.Success && homeResult.value.isNotEmpty()) return homeResult
            val category = source.descriptor.categories.firstOrNull() ?: return homeResult
            val categoryResult = source.category(category, 1)
            return if (categoryResult is AppResult.Success && categoryResult.value.isNotEmpty()) categoryResult else homeResult
        }

        val stories = runStep("Trang chủ / danh sách", { probeStories() })
        val firstStory = stories?.firstOrNull()
        if (source.descriptor.supportsSuggestions && firstStory != null) {
            runStep("Gợi ý tìm kiếm", { source.suggestions(firstStory.title.take(24)) })
        } else {
            steps += SourceCheckStep("Gợi ý tìm kiếm", SourceCheckStatus.SKIPPED, "Nguồn không khai báo suggestions.", 0)
        }
        if (firstStory == null) {
            steps += SourceCheckStep("Chi tiết", SourceCheckStatus.SKIPPED, "Không có truyện mẫu để kiểm tra.", 0)
            steps += SourceCheckStep("Nội dung", SourceCheckStatus.SKIPPED, "Không có chương mẫu để kiểm tra.", 0)
        } else {
            val detail = runStep("Chi tiết và mục lục", { source.story(firstStory.url) })
            val firstChapter = detail?.chapters?.firstOrNull()
                ?: detail?.nextChapterPageUrl?.let { next ->
                    runStep("Trang mục lục", {
                        source.chapterPage(
                            storyId = detail.story.id,
                            url = next,
                            startIndex = detail.chapters.size,
                        )
                    })?.chapters?.firstOrNull()
                }
            if (firstChapter == null) {
                steps += SourceCheckStep("Nội dung", SourceCheckStatus.SKIPPED, "Không có chương mẫu để kiểm tra.", 0)
            } else {
                runStep("Nội dung chương", { source.chapter(firstChapter.url) })
            }
            if (source.descriptor.supportsComments) {
                val commentsUrl = detail?.commentsUrl ?: firstStory.url
                runStep("Bình luận nguồn", { source.commentsPage(commentsUrl) })
            } else {
                steps += SourceCheckStep("Bình luận nguồn", SourceCheckStatus.SKIPPED, "Nguồn không khai báo capability bình luận.", 0)
            }
        }

        val failures = steps.count { it.status == SourceCheckStatus.FAIL }
        val resolved = when {
            loginRequired -> SourceHealth.NEEDS_LOGIN
            failures == 0 && steps.any { it.name == "Nội dung chương" && it.status == SourceCheckStatus.PASS } -> SourceHealth.READY
            steps.firstOrNull()?.status == SourceCheckStatus.PASS -> SourceHealth.DEGRADED
            else -> SourceHealth.DISABLED
        }
        return SourceCheckReport(
            sourceId = sourceId,
            sourceName = source.descriptor.displayName,
            resolvedHealth = resolved,
            checkedAtEpochMillis = System.currentTimeMillis(),
            steps = steps,
        )
    }

    companion object {
        private const val STEP_TIMEOUT_MILLIS = 45_000L
    }
}
