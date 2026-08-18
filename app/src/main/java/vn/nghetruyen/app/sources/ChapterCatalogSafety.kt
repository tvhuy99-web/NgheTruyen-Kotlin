package vn.nghetruyen.app.sources

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.StoryDetail
import java.net.URI

/**
 * Prevent malformed source catalog entries from reaching keyed Compose lists.
 *
 * TruyenFull currently exposes its chapter pagination controls through the same selector used by
 * the bundled Lua source for chapter anchors. Those controls look like chapters ("2", "Trang tiếp",
 * "Cuối »") and can even share the same URL/id, which makes LazyColumn reject duplicate keys.
 * Keep the source package untouched here and repair the boundary before data reaches app state.
 */
internal fun StorySource.withChapterCatalogSafety(): StorySource =
    if (this is ChapterCatalogSafetyStorySource) this else ChapterCatalogSafetyStorySource(this)

private class ChapterCatalogSafetyStorySource(
    private val delegate: StorySource,
) : StorySource by delegate {
    override suspend fun story(url: String): AppResult<StoryDetail> = when (val result = delegate.story(url)) {
        is AppResult.Success -> {
            val safe = sanitizeChapterCatalog(
                currentPageUrl = url,
                chapters = result.value.chapters,
                nextPageUrl = result.value.nextChapterPageUrl,
            )
            AppResult.Success(
                result.value.copy(
                    chapters = safe.chapters,
                    nextChapterPageUrl = safe.nextPageUrl,
                ),
            )
        }
        is AppResult.Failure -> result
    }

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = when (val result = delegate.chapterPage(storyId, url, startIndex)) {
        is AppResult.Success -> {
            val safe = sanitizeChapterCatalog(
                currentPageUrl = url,
                chapters = result.value.chapters,
                nextPageUrl = result.value.nextPageUrl,
            )
            AppResult.Success(ChapterPage(safe.chapters, safe.nextPageUrl))
        }
        is AppResult.Failure -> result
    }
}

internal data class SanitizedChapterCatalog(
    val chapters: List<ChapterSummary>,
    val nextPageUrl: String?,
)

internal fun sanitizeChapterCatalog(
    currentPageUrl: String,
    chapters: List<ChapterSummary>,
    nextPageUrl: String?,
): SanitizedChapterCatalog {
    val pagerEntries = chapters.mapNotNull { chapter ->
        truyenFullPagerPage(chapter.url)?.let { page -> PagerEntry(page, chapter.url) }
    }
    val realChapters = chapters.filter { truyenFullPagerPage(it.url) == null }

    // Compose keyed lists require unique ids. Preserve the first source-order occurrence so a bad
    // parser can never terminate the process even if another source defect slips through.
    val seenIds = HashSet<String>(realChapters.size)
    val uniqueChapters = realChapters.filter { chapter ->
        chapter.id.isNotBlank() && seenIds.add(chapter.id)
    }

    val recoveredNext = (
        nextPageUrl
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: recoverNextPageUrl(currentPageUrl, pagerEntries)
        )?.withoutFragment()

    return SanitizedChapterCatalog(uniqueChapters, recoveredNext)
}

private data class PagerEntry(val page: Int, val url: String)

private fun recoverNextPageUrl(currentPageUrl: String, entries: List<PagerEntry>): String? {
    if (entries.isEmpty()) return null
    val currentPage = truyenFullPageNumber(currentPageUrl) ?: 1
    return entries
        .asSequence()
        .filter { it.page > currentPage }
        .minByOrNull(PagerEntry::page)
        ?.url
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

private fun String.withoutFragment(): String = substringBefore('#').trim().ifEmpty { this }

private fun truyenFullPagerPage(url: String): Int? {
    if (!url.contains("#list-chapter", ignoreCase = true)) return null
    // TruyenFull links page 1 back to the story root instead of /trang-1/.
    return truyenFullPageNumber(url) ?: 1
}

private fun truyenFullPageNumber(url: String): Int? {
    val path = runCatching { URI(url).path }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: url.substringBefore('#').substringBefore('?')
    return TRUYENFULL_PAGE_REGEX.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private val TRUYENFULL_PAGE_REGEX = Regex("/trang-(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
