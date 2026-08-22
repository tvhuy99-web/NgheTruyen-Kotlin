package vn.nghetruyen.app.sources

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary

/**
 * Keep the stable app-facing ids used by the Kotlin-era database/UI while the implementation
 * underneath is the exact Lua/XPK source. Native API2 ids are package identities, not user-data
 * identities, so exposing them directly would strand stories/history/following saved under the
 * short ids and would also let the older Kotlin adapter keep winning SourceRegistry selection.
 */
internal fun StorySource.withStableDefaultLuaId(): StorySource {
    val stableId = DEFAULT_LUA_STABLE_IDS[descriptor.id] ?: return this
    if (descriptor.id == stableId) return this
    val aliased = StableDefaultLuaSourceAlias(this, stableId)
    return if (stableId == "truyenfull") aliased.withChapterCatalogSafety() else aliased
}

private class StableDefaultLuaSourceAlias(
    private val delegate: StorySource,
    private val stableId: String,
) : StorySource {
    override val descriptor: SourceDescriptor = delegate.descriptor.copy(id = stableId)

    // Exact bundled Lua/XPK implementations are the production implementation for these stable ids.
    // This must outrank the legacy Kotlin adapters (priority 100) without changing third-party packs.
    override val selectionPriority: Int = 250

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> =
        delegate.search(query, page).rewriteStories()

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> =
        delegate.category(category, page).rewriteStories()

    override suspend fun home(page: Int): AppResult<List<StorySummary>> =
        delegate.home(page).rewriteStories()

    override suspend fun genreMenu(): AppResult<List<SourceBrowseEntry>> = delegate.genreMenu()

    override suspend fun story(url: String): AppResult<StoryDetail> = when (val result = delegate.story(url)) {
        is AppResult.Success -> AppResult.Success(
            result.value.copy(story = result.value.story.copy(sourceId = stableId)),
        )
        is AppResult.Failure -> result
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = delegate.chapter(url)

    override suspend fun suggestions(query: String): AppResult<List<String>> = delegate.suggestions(query)

    override suspend fun comments(url: String): AppResult<List<StoryComment>> = delegate.comments(url)

    override suspend fun commentsPage(url: String): AppResult<StoryCommentPage> = delegate.commentsPage(url)

    override suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String?,
        storyId: String?,
        chapterId: String?,
    ): AppResult<SourceUiActionResult> = delegate.runUiAction(actionId, surface, currentUrl, storyId, chapterId)

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = delegate.latestChapter(url)

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = delegate.chapterPage(storyId, url, startIndex)

    private fun AppResult<List<StorySummary>>.rewriteStories(): AppResult<List<StorySummary>> = when (this) {
        is AppResult.Success -> AppResult.Success(value.map { it.copy(sourceId = stableId) })
        is AppResult.Failure -> this
    }
}

internal val DEFAULT_LUA_STABLE_IDS: Map<String, String> = mapOf(
    "vn.nghetruyen.native.truyenfull-native" to "truyenfull",
    "vn.nghetruyen.native.truyencv-io-default-native" to "truyencv",
    "vn.nghetruyen.native.truyencom-default-native" to "truyencom",
    "vn.nghetruyen.native.truyenyy-co-native" to "truyenyy",
    "vn.nghetruyen.native.wikidich-default-native-v9-complete-scroll" to "wikidich",
    "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50" to "sangtacviet",
    "vn.nghetruyen.vbook.wattpad-default-vbook" to "wattpad",
)
