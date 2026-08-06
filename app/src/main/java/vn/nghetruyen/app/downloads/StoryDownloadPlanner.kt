package vn.nghetruyen.app.downloads

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.sources.StorySource

class StoryDownloadPlanner(
    private val maxChapterPages: Int = 250,
    private val maxChapters: Int = 20_000,
) {
    suspend fun collectChapters(
        source: StorySource,
        detail: StoryDetail,
        checkCancelled: () -> Unit = {},
    ): AppResult<List<ChapterSummary>> {
        val chapters = detail.chapters.toMutableList()
        val knownChapterUrls = chapters.mapTo(linkedSetOf()) { it.url.ifBlank { it.id } }
        val visitedPages = linkedSetOf<String>()
        var nextPageUrl = detail.nextChapterPageUrl
        var pageCount = if (chapters.isEmpty()) 0 else 1

        while (nextPageUrl != null) {
            checkCancelled()
            if (!visitedPages.add(nextPageUrl)) {
                return AppResult.Failure(
                    code = "DOWNLOAD_CHAPTER_PAGE_CYCLE",
                    message = "Nguồn trả về vòng lặp phân trang mục lục.",
                )
            }
            if (pageCount >= maxChapterPages) {
                return AppResult.Failure(
                    code = "DOWNLOAD_CHAPTER_PAGE_LIMIT",
                    message = "Mục lục vượt giới hạn $maxChapterPages trang an toàn.",
                )
            }

            val page = when (
                val result = source.chapterPage(
                    storyId = detail.story.id,
                    url = nextPageUrl,
                    startIndex = chapters.size,
                )
            ) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result
            }

            page.chapters.forEach { chapter ->
                val key = chapter.url.ifBlank { chapter.id }
                if (knownChapterUrls.add(key)) {
                    chapters += chapter.copy(storyId = detail.story.id)
                }
            }
            if (chapters.size > maxChapters) {
                return AppResult.Failure(
                    code = "DOWNLOAD_CHAPTER_LIMIT",
                    message = "Truyện vượt giới hạn $maxChapters chương an toàn.",
                )
            }
            nextPageUrl = page.nextPageUrl
            pageCount += 1
        }

        return AppResult.Success(
            chapters.mapIndexed { index, chapter ->
                chapter.copy(storyId = detail.story.id, index = index)
            },
        )
    }
}
