package vn.nghetruyen.app.playback

import java.util.Base64
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary

/**
 * Lightweight bridge from pagination code into the app diagnostics runtime.
 *
 * The playback package deliberately does not own an Android Context. The Application installs a
 * reporter once, and pagination can then emit exact stage/failure events without reaching through
 * global singletons or changing the public merge API.
 */
object ChapterPaginationDiagnostics {
    @Volatile
    private var reporter: ((String, Boolean, Map<String, String>) -> Unit)? = null

    fun install(reporter: (String, Boolean, Map<String, String>) -> Unit) {
        this.reporter = reporter
    }

    internal fun mark(
        name: String,
        error: Boolean = false,
        attributes: Map<String, String> = emptyMap(),
    ) {
        runCatching { reporter?.invoke(name, error, attributes) }
        if (error) {
            System.err.println(
                buildString {
                    append(name)
                    if (attributes.isNotEmpty()) {
                        append(' ')
                        append(attributes.entries.joinToString(" ") { (key, value) -> "$key=$value" })
                    }
                },
            )
        }
    }
}

data class PersistedChapterPageCursor(
    val url: String,
    val startIndex: Int,
    val nextChapterUrl: String? = null,
)


object ChapterPageCursorCodec {
    private const val PREFIX = "nghetruyen:toc-page:v1:"

    fun encode(url: String, startIndex: Int, nextChapterUrl: String? = null): String {
        val normalized = url.trim()
        require(normalized.isNotBlank()) { "Catalog page URL must not be blank." }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedUrl = encoder
            .encodeToString(normalized.toByteArray(Charsets.UTF_8))
        val encodedChapter = nextChapterUrl?.trim()?.takeIf(String::isNotBlank)
            ?.let { encoder.encodeToString(it.toByteArray(Charsets.UTF_8)) }
        return "$PREFIX${startIndex.coerceAtLeast(0)}:$encodedUrl" +
            encodedChapter?.let { ":$it" }.orEmpty()
    }

    fun decode(value: String?): PersistedChapterPageCursor? {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith(PREFIX)) return null
        return runCatching {
            val payload = raw.removePrefix(PREFIX)
            val parts = payload.split(':', limit = 3)
            require(parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
            val startIndex = parts[0].toInt().coerceAtLeast(0)
            val decoder = Base64.getUrlDecoder()
            val url = decoder.decode(parts[1])
                .toString(Charsets.UTF_8)
                .trim()
            require(url.isNotBlank())
            val nextChapterUrl = parts.getOrNull(2)?.takeIf(String::isNotBlank)
                ?.let(decoder::decode)
                ?.toString(Charsets.UTF_8)
                ?.trim()
                ?.takeIf(String::isNotBlank)
            PersistedChapterPageCursor(url, startIndex, nextChapterUrl)
        }.getOrNull()
    }

    fun isEncoded(value: String?): Boolean = value?.trim()?.startsWith(PREFIX) == true
}

data class ChapterCatalogMergeResult(
    val chapters: List<ChapterSummary>,
    val nextPageUrl: String?,
    val addedCount: Int,
    val repeatedCursor: Boolean,
)


object ChapterCatalogMerger {
    fun merge(
        existing: List<ChapterSummary>,
        requestedPageUrl: String,
        page: ChapterPage,
        previouslyRequestedPages: Set<String> = emptySet(),
    ): ChapterCatalogMergeResult {
        val storyId = existing.firstOrNull()?.storyId
            ?.takeIf(String::isNotBlank)
            ?: page.chapters.firstOrNull()?.storyId.orEmpty()
        ChapterPaginationDiagnostics.mark(
            name = "CHAPTER_PAGE_MERGE_STARTED",
            attributes = mapOf(
                "storyId" to storyId.take(160),
                "existingCount" to existing.size.toString(),
                "incomingCount" to page.chapters.size.toString(),
                "requestedPage" to pageIdentity(requestedPageUrl).takeLast(500),
            ),
        )

        return try {
            mergeGuarded(existing, requestedPageUrl, page, previouslyRequestedPages, storyId)
        } catch (error: Exception) {
            ChapterPaginationDiagnostics.mark(
                name = "CHAPTER_PAGE_MERGE_FAILED",
                error = true,
                attributes = mapOf(
                    "storyId" to storyId.take(160),
                    "existingCount" to existing.size.toString(),
                    "incomingCount" to page.chapters.size.toString(),
                    "exception" to error::class.java.name.take(240),
                    "message" to error.message.orEmpty().take(500),
                ),
            )
            ChapterCatalogMergeResult(
                chapters = existing,
                nextPageUrl = null,
                addedCount = 0,
                repeatedCursor = true,
            )
        }
    }

    private fun mergeGuarded(
        existing: List<ChapterSummary>,
        requestedPageUrl: String,
        page: ChapterPage,
        previouslyRequestedPages: Set<String>,
        storyId: String,
    ): ChapterCatalogMergeResult {
        val identitiesById = linkedMapOf<String, String>()
        existing.forEach { chapter ->
            val id = chapter.id.trim()
            if (id.isNotBlank()) identitiesById.putIfAbsent(id, chapterIdentity(chapter))
        }

        val conflictingIds = linkedSetOf<String>()
        var blankIdCount = 0
        page.chapters.forEach { chapter ->
            val id = chapter.id.trim()
            if (id.isBlank()) {
                blankIdCount += 1
                return@forEach
            }
            val identity = chapterIdentity(chapter)
            val previousIdentity = identitiesById[id]
            if (previousIdentity != null && previousIdentity != identity) {
                conflictingIds += id
            } else {
                identitiesById.putIfAbsent(id, identity)
            }
        }

        if (blankIdCount > 0 || conflictingIds.isNotEmpty()) {
            ChapterPaginationDiagnostics.mark(
                name = "CHAPTER_PAGE_UNSAFE_ID_BLOCKED",
                error = true,
                attributes = mapOf(
                    "storyId" to storyId.take(160),
                    "blankIdCount" to blankIdCount.toString(),
                    "conflictingIdCount" to conflictingIds.size.toString(),
                    "conflictingIds" to conflictingIds.joinToString(",").take(700),
                    "existingCount" to existing.size.toString(),
                    "incomingCount" to page.chapters.size.toString(),
                    "reason" to "compose-key-safety",
                ),
            )
            return ChapterCatalogMergeResult(
                chapters = existing,
                nextPageUrl = null,
                addedCount = 0,
                repeatedCursor = true,
            )
        }

        val seen = existing.mapTo(linkedSetOf(), ::chapterIdentity)
        val merged = existing.toMutableList()
        page.chapters.forEach { chapter ->
            if (seen.add(chapterIdentity(chapter))) merged += chapter
        }

        val addedCount = merged.size - existing.size
        val requestedIdentity = pageIdentity(requestedPageUrl)
        val visitedIdentities = previouslyRequestedPages.mapTo(hashSetOf(), ::pageIdentity)
        visitedIdentities += requestedIdentity
        val candidate = page.nextPageUrl?.trim()?.takeIf(String::isNotBlank)
        val repeated = candidate != null && pageIdentity(candidate) in visitedIdentities
        val noProgress = addedCount == 0

        if (noProgress) {
            ChapterPaginationDiagnostics.mark(
                name = "CHAPTER_PAGE_NO_PROGRESS_BLOCKED",
                error = true,
                attributes = mapOf(
                    "storyId" to storyId.take(160),
                    "existingCount" to existing.size.toString(),
                    "incomingCount" to page.chapters.size.toString(),
                    "candidateNextPage" to candidate.orEmpty().takeLast(500),
                    "reason" to "zero-new-chapters",
                ),
            )
        }

        val stopPaging = repeated || noProgress
        val result = ChapterCatalogMergeResult(
            chapters = merged,
            nextPageUrl = candidate?.takeUnless { stopPaging },
            addedCount = addedCount,
            repeatedCursor = stopPaging,
        )
        ChapterPaginationDiagnostics.mark(
            name = "CHAPTER_PAGE_MERGE_COMPLETED",
            attributes = mapOf(
                "storyId" to storyId.take(160),
                "addedCount" to result.addedCount.toString(),
                "totalCount" to result.chapters.size.toString(),
                "hasNextPage" to (!result.nextPageUrl.isNullOrBlank()).toString(),
                "stoppedSafely" to result.repeatedCursor.toString(),
            ),
        )
        return result
    }

    fun sameChapter(left: ChapterSummary, right: ChapterSummary): Boolean =
        left.id == right.id || (
            left.url.isNotBlank() && right.url.isNotBlank() &&
                pageIdentity(left.url) == pageIdentity(right.url)
            )

    private fun chapterIdentity(chapter: ChapterSummary): String =
        chapter.url.trim().takeIf(String::isNotBlank)
            ?.let(::pageIdentity)
            ?: chapter.id.trim()

    private fun pageIdentity(value: String): String = value.trim().trimEnd('/')
}

private data class ChapterNavigationHint(
    val chapter: ChapterSummary,
    val previousChapterUrl: String?,
    val nextChapterUrl: String?,
    val nextChapterPageUrl: String?,
    val nextChapterPageStartIndex: Int?,
)





class ChapterPageNavigationCache {
    private val hints = LinkedHashMap<String, ChapterNavigationHint>()
    private var activeStoryId: String = ""

    @Synchronized
    fun registerPage(
        storyId: String,
        chapters: List<ChapterSummary>,
        previousChapterUrl: String?,
        nextPageUrl: String?,
        nextPageStartIndex: Int,
    ) {
        if (storyId != activeStoryId) {
            hints.clear()
            activeStoryId = storyId
        }
        val unique = chapters.distinctBy { chapterKey(storyId, it.url.ifBlank { it.id }) }
        unique.forEachIndexed { index, chapter ->
            val hint = ChapterNavigationHint(
                chapter = chapter,
                previousChapterUrl = unique.getOrNull(index - 1)?.url?.takeIf(String::isNotBlank)
                    ?: previousChapterUrl?.takeIf(String::isNotBlank),
                nextChapterUrl = unique.getOrNull(index + 1)?.url?.takeIf(String::isNotBlank),
                nextChapterPageUrl = nextPageUrl?.takeIf(String::isNotBlank)
                    ?.takeIf { index == unique.lastIndex },
                nextChapterPageStartIndex = nextPageStartIndex.takeIf {
                    index == unique.lastIndex && !nextPageUrl.isNullOrBlank()
                },
            )
            hints[chapterKey(storyId, chapter.id)] = hint
            chapter.url.takeIf(String::isNotBlank)?.let { hints[chapterKey(storyId, it)] = hint }
        }
        while (hints.size > MAX_HINT_KEYS) {
            hints.remove(hints.entries.first().key)
        }
    }

    @Synchronized
    fun enrich(content: ChapterContent): ChapterContent {
        if (content.chapter.storyId != activeStoryId) return content
        val hint = hints[chapterKey(activeStoryId, content.chapter.id)]
            ?: content.chapter.url.takeIf(String::isNotBlank)
                ?.let { hints[chapterKey(activeStoryId, it)] }
            ?: return content
        val fetchedNext = content.nextChapterUrl?.trim()?.takeIf(String::isNotBlank)
            ?.takeUnless { next ->
                !hint.nextChapterPageUrl.isNullOrBlank() &&
                    next.trim().trimEnd('/') == hint.nextChapterPageUrl.trim().trimEnd('/')
            }
        return content.copy(
            chapter = content.chapter.copy(
                storyId = hint.chapter.storyId,
                index = hint.chapter.index,
                title = content.chapter.title.ifBlank { hint.chapter.title },
                url = content.chapter.url.ifBlank { hint.chapter.url },
            ),
            previousChapterUrl = hint.previousChapterUrl
                ?: content.previousChapterUrl?.takeIf(String::isNotBlank),
            nextChapterUrl = hint.nextChapterUrl ?: fetchedNext,
            nextChapterPageUrl = content.nextChapterPageUrl?.takeIf(String::isNotBlank)
                ?: hint.nextChapterPageUrl,
            nextChapterPageStartIndex = content.nextChapterPageStartIndex
                ?: hint.nextChapterPageStartIndex,
        )
    }

    @Synchronized
    fun clear() {
        activeStoryId = ""
        hints.clear()
    }

    private fun chapterKey(storyId: String, value: String): String =
        "$storyId|${value.trim().trimEnd('/')}"

    private companion object {
        const val MAX_HINT_KEYS = 1_024
    }
}
