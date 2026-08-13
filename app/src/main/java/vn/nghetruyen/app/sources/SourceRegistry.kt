package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime

class SourceRegistry(
    sources: List<StorySource>? = null,
    sessionStore: SourceSessionStore = InMemorySourceSessionStore(),
    sourcePackSources: List<StorySource> = emptyList(),
    diagnostics: SourceDiagnosticRuntime? = null,
) {
    private val diagnosticRuntime = diagnostics
    private val legacySources = (sources ?: defaultSources(sessionStore)).distinctBy { it.descriptor.id }
    @Volatile
    private var byId: Map<String, StorySource> = merge(sourcePackSources)

    fun descriptors(): List<SourceDescriptor> = byId.values.map { it.descriptor }
    fun get(id: String): StorySource? = byId[id]
    fun searchableSources(): List<StorySource> = byId.values.filter {
        it.descriptor.health == SourceHealth.READY || it.descriptor.health == SourceHealth.DEGRADED
    }

    /**
     * Compatibility refresh used by the legacy SourcePack manager.
     *
     * Older UI code only passes SourcePack candidates here. Preserve already registered vBook
     * sources so that opening/refreshing the legacy source-management screen cannot silently make
     * an ACTIVE vBook artifact disappear until process restart. The AppContainer full refresh may
     * still replace a vBook candidate by passing the same stable id with a newer implementation.
     */
    @Synchronized
    fun refreshSourcePacks(sourcePackSources: List<StorySource>) {
        val incomingIds = sourcePackSources.mapTo(linkedSetOf()) { it.descriptor.id }
        val preservedVBook = byId.values.filter { source ->
            source.descriptor.implementationKind == SourceImplementationKind.VBOOK &&
                source.descriptor.id !in incomingIds
        }
        byId = merge(sourcePackSources + preservedVBook)
    }

    /** Full external-runtime refresh. Callers supply every active external ecosystem. */
    @Synchronized
    fun replaceExternalSources(externalSources: List<StorySource>) {
        byId = merge(externalSources)
    }

    private fun merge(sourcePackSources: List<StorySource>): Map<String, StorySource> {
        val selected = linkedMapOf<String, StorySource>()
        val builtInsById = legacySources.associateBy { it.descriptor.id }

        // Built-in adapters are considered first. A certified SourcePack may attach
        // itself to the matching adapter, but only when source-info.json names the
        // same stable legacy id. This gives the pack full website fidelity without
        // letting an arbitrary package redirect to a different built-in source.
        legacySources.forEach { selected[it.descriptor.id] = it }
        sourcePackSources.distinctBy { it.descriptor.id }.forEach { rawCandidate ->
            val candidate = if (rawCandidate is BuiltInSourcePackBridge) {
                val delegateId = rawCandidate.builtInDelegateId
                val delegate = delegateId?.let(builtInsById::get)
                if (delegate != null && delegate.descriptor.id == rawCandidate.descriptor.id) {
                    rawCandidate.attachBuiltInDelegate(delegate)
                } else {
                    rawCandidate
                }
            } else rawCandidate

            val id = candidate.descriptor.id
            val current = selected[id]
            if (current == null || candidate.selectionPriority > current.selectionPriority) {
                selected[id] = candidate
            }
        }
        return selected.mapValues { (_, source) ->
            source.withExecutionAndDiagnostics(diagnosticRuntime)
        }
    }

    companion object {
        private fun defaultSources(sessionStore: SourceSessionStore): List<StorySource> = listOf(
            DemoStorySource(),
            TruyenFullSource(),
            TruyenCvSource(),
            TruyenComSource(),
            TruyenYySource(),
            WikidichSource(),
            SangTacVietSource(sessionStore),
            NotPortedSource("wattpad", "Wattpad / vBook", "https://www.wattpad.com"),
        )
    }
}

private fun StorySource.withExecutionAndDiagnostics(diagnostics: SourceDiagnosticRuntime?): StorySource {
    if (this is DiagnosticStorySource) return this
    return withVBookExecutionBoundary().withDiagnostics(diagnostics)
}

/**
 * vBook compatibility scripts expose synchronous Http/fetch helpers to JavaScript. Their host
 * implementation is intentionally blocking, so the complete source call must leave the Android
 * main thread before script execution begins. Keeping the boundary here protects every UI caller
 * while preserving the vBook JavaScript contract and lets browser/host brokers do their own
 * thread-hops internally when they need Android's main looper.
 */
private fun StorySource.withVBookExecutionBoundary(): StorySource = when {
    descriptor.implementationKind != SourceImplementationKind.VBOOK -> this
    this is IoBoundVBookStorySource -> this
    else -> IoBoundVBookStorySource(this)
}

private class IoBoundVBookStorySource(
    private val delegate: StorySource,
) : StorySource {
    override val descriptor: SourceDescriptor get() = delegate.descriptor
    override val selectionPriority: Int get() = delegate.selectionPriority

    override suspend fun search(query: String, page: Int) = onIo { delegate.search(query, page) }
    override suspend fun category(category: String, page: Int) = onIo { delegate.category(category, page) }
    override suspend fun story(url: String) = onIo { delegate.story(url) }
    override suspend fun chapter(url: String) = onIo { delegate.chapter(url) }
    override suspend fun home(page: Int) = onIo { delegate.home(page) }
    override suspend fun suggestions(query: String) = onIo { delegate.suggestions(query) }
    override suspend fun comments(url: String) = onIo { delegate.comments(url) }
    override suspend fun commentsPage(url: String) = onIo { delegate.commentsPage(url) }
    override suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String?,
        storyId: String?,
        chapterId: String?,
    ) = onIo { delegate.runUiAction(actionId, surface, currentUrl, storyId, chapterId) }

    override suspend fun latestChapter(url: String) = onIo { delegate.latestChapter(url) }
    override suspend fun chapterPage(storyId: String, url: String, startIndex: Int) =
        onIo { delegate.chapterPage(storyId, url, startIndex) }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

private class NotPortedSource(
    id: String,
    name: String,
    baseUrl: String,
) : StorySource {
    override val selectionPriority: Int = 0

    override val descriptor = SourceDescriptor(
        id = id,
        displayName = name,
        baseUrl = baseUrl,
        health = SourceHealth.NOT_PORTED,
        supportsHome = false,
        implementationKind = SourceImplementationKind.PLACEHOLDER,
    )

    private fun <T> pending(): AppResult<T> = AppResult.Failure(
        code = "SOURCE_NOT_PORTED",
        message = "Nguồn ${descriptor.displayName} chưa được viết adapter Kotlin.",
    )

    override suspend fun search(query: String, page: Int) = pending<List<StorySummary>>()
    override suspend fun category(category: String, page: Int) = pending<List<StorySummary>>()
    override suspend fun story(url: String) = pending<StoryDetail>()
    override suspend fun chapter(url: String) = pending<ChapterContent>()
}

private class DemoStorySource : StorySource {
    override val descriptor = SourceDescriptor(
        id = "vn.nghetruyen.sources.demo",
        displayName = "Bản mẫu Kotlin",
        baseUrl = "local://demo",
        health = SourceHealth.READY,
        categories = listOf("Tiên hiệp", "Kiếm hiệp", "Ngôn tình", "Khoa huyễn"),
    )

    private val stories = listOf(
        StorySummary(
            id = "demo-1",
            sourceId = descriptor.id,
            title = "Hành Trình Qua Miền Gió",
            author = "Bản mẫu",
            description = "Dữ liệu minh họa để kiểm tra giao diện và lõi Kotlin mới.",
            url = "local://demo/story/1",
        ),
        StorySummary(
            id = "demo-2",
            sourceId = descriptor.id,
            title = "Thư Viện Cuối Ánh Trăng",
            author = "Bản mẫu",
            description = "Một truyện ngắn dùng để thử tìm kiếm, đọc và TTS nền.",
            url = "local://demo/story/2",
        ),
    )

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> {
        val needle = query.trim().lowercase()
        return AppResult.Success(
            if (needle.isBlank()) stories
            else stories.filter { it.title.lowercase().contains(needle) || it.author.lowercase().contains(needle) },
        )
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> =
        AppResult.Success(stories)

    override suspend fun story(url: String): AppResult<StoryDetail> {
        val story = stories.firstOrNull { it.url == url || it.id == url }
            ?: return AppResult.Failure("NOT_FOUND", "Không tìm thấy truyện mẫu.")
        val chapters = (1..5).map { index ->
            ChapterSummary(
                id = "${story.id}-chapter-$index",
                storyId = story.id,
                index = index - 1,
                title = "Chương $index",
                url = "local://demo/${story.id}/chapter/$index",
            )
        }
        return AppResult.Success(
            StoryDetail(
                story = story,
                genres = listOf("Phiêu lưu", "Đời thường"),
                status = "Đang viết",
                chapters = chapters,
            ),
        )
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> {
        val chapterNumber = url.substringAfterLast('/').toIntOrNull() ?: 1
        val storyId = if (url.contains("demo-2")) "demo-2" else "demo-1"
        val chapter = ChapterSummary(
            id = "$storyId-chapter-$chapterNumber",
            storyId = storyId,
            index = chapterNumber - 1,
            title = "Chương $chapterNumber",
            url = url,
        )
        return AppResult.Success(
            ChapterContent(
                chapter = chapter,
                paragraphs = listOf(
                    "Đây là nội dung minh họa của chương $chapterNumber trong lõi Kotlin mới.",
                    "Mỗi đoạn văn được quản lý bằng dữ liệu có kiểu rõ ràng, không còn trạng thái toàn cục khó kiểm soát.",
                    "Trình đọc có thể tiếp tục từ đúng đoạn, phát TTS nền và nhận lệnh từ thông báo hệ thống.",
                    "Các nguồn truyện thật sẽ được viết thành adapter riêng, có kiểm thử parser và giới hạn miền truy cập.",
                ),
                previousChapterUrl = if (chapterNumber > 1) "local://demo/$storyId/chapter/${chapterNumber - 1}" else null,
                nextChapterUrl = if (chapterNumber < 5) "local://demo/$storyId/chapter/${chapterNumber + 1}" else null,
            ),
        )
    }
}
