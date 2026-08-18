package vn.nghetruyen.app.sources

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary

enum class SourceCommentCapability(val label: String) {
    NONE("Không hỗ trợ"),
    EMBEDDED("Bình luận nhúng trong chi tiết truyện"),
    PAGED("Bình luận phân trang trong ứng dụng"),
    DYNAMIC_BROWSER("Bình luận động qua WebView sandbox"),
}

enum class SourceImplementationKind {
    BUILT_IN,
    HYBRID_PACK,
    SOURCE_PACK,
    VBOOK,
    NATIVE_LUA,
    PLACEHOLDER,
}

enum class SourceUiSurface { EXPLORE, STORY, READER }

data class SourceUiActionDescriptor(
    val id: String,
    val label: String,
    val surfaces: Set<SourceUiSurface>,
    val group: String = "",
    val order: Int = 0,
)

data class SourceUiActionResult(
    val message: String = "",
    val openUrl: String? = null,
    val refresh: Boolean = false,
)

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val health: SourceHealth,
    val categories: List<String> = emptyList(),
    val loginUrl: String? = null,
    val privacyNote: String? = null,
    val allowedHosts: Set<String> = emptySet(),
    val supportsComments: Boolean = false,
    val commentCapability: SourceCommentCapability = if (supportsComments) SourceCommentCapability.PAGED else SourceCommentCapability.NONE,
    val supportsHome: Boolean = true,
    val supportsGenre: Boolean = false,
    val supportsSuggestions: Boolean = false,
    val implementationKind: SourceImplementationKind = SourceImplementationKind.BUILT_IN,
    val uiActions: List<SourceUiActionDescriptor> = emptyList(),
)


/**
 * A SourcePack can opt into a certified bridge to a richer built-in adapter.
 * The bridge lets the installed pack own metadata, permissions and update state
 * while the website-specific parser remains the same tested implementation used
 * by the application. It is deliberately explicit so an untrusted pack cannot
 * silently capture an arbitrary built-in source.
 */
interface BuiltInSourcePackBridge {
    val builtInDelegateId: String?
    fun attachBuiltInDelegate(delegate: StorySource): StorySource
}

interface StorySource {
    val descriptor: SourceDescriptor

    /**
     * Used only when more than one implementation exposes the same stable source id.
     * Built-in adapters deliberately outrank compatibility packs until a pack explicitly
     * declares that it has reached full parity. This prevents a small selector pack from
     * silently hiding a richer website-specific adapter.
     */
    val selectionPriority: Int get() = 100

    suspend fun search(query: String, page: Int = 1): AppResult<List<StorySummary>>
    suspend fun category(category: String, page: Int = 1): AppResult<List<StorySummary>>
    suspend fun story(url: String): AppResult<StoryDetail>
    suspend fun chapter(url: String): AppResult<ChapterContent>

    suspend fun home(page: Int = 1): AppResult<List<StorySummary>> = search("", page)

    /** Top-level browse menu. Static sources fall back to descriptor categories. */
    suspend fun genreMenu(): AppResult<List<String>> = AppResult.Success(descriptor.categories)

    suspend fun suggestions(query: String): AppResult<List<String>> = AppResult.Success(emptyList())

    suspend fun comments(url: String): AppResult<List<StoryComment>> = when (val result = commentsPage(url)) {
        is AppResult.Success -> AppResult.Success(result.value.comments)
        is AppResult.Failure -> result
    }

    suspend fun commentsPage(url: String): AppResult<StoryCommentPage> = AppResult.Failure(
        code = "COMMENTS_UNSUPPORTED",
        message = "Nguồn này chưa hỗ trợ lấy bình luận trong ứng dụng.",
    )

    suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String? = null,
        storyId: String? = null,
        chapterId: String? = null,
    ): AppResult<SourceUiActionResult> = AppResult.Failure(
        code = "SOURCE_UI_ACTION_UNSUPPORTED",
        message = "Nguồn này không có action giao diện tương ứng.",
    )

    suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = when (val result = story(url)) {
        is AppResult.Success -> AppResult.Success(result.value.chapters.lastOrNull())
        is AppResult.Failure -> result
    }

    suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = AppResult.Failure(
        code = "CHAPTER_PAGING_UNSUPPORTED",
        message = "Nguồn này không hỗ trợ tải thêm trang chương.",
    )
}
