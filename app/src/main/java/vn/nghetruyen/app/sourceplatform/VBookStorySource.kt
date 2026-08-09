package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sources.SourceCommentCapability
import vn.nghetruyen.app.sources.SourceDescriptor
import vn.nghetruyen.app.sources.SourceImplementationKind
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookContentType
import vn.nghetruyen.source.vbook.VBookContinuation
import vn.nghetruyen.source.vbook.VBookDynamicAction
import vn.nghetruyen.source.vbook.VBookExtensionManifest
import vn.nghetruyen.source.vbook.VBookHostManifestFactory
import vn.nghetruyen.source.vbook.VBookPackage
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookScriptRole
import vn.nghetruyen.source.vbook.VBookStoryNormalizer
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Story adapter for an immutable active vBook artifact.
 *
 * It never translates an extension into NgheTruyen rules. plugin.json/src stay authoritative and
 * are executed through [VBookCompatibilityRuntime]. Only the returned data is normalized.
 */
class VBookStorySource(
    private val artifact: SourceArtifactDescriptor,
    packageBytes: ByteArray,
    brokers: SourceCapabilityBrokers,
) : StorySource {
    private val pkg: VBookPackage = VBookPackageReader.read(packageBytes)
    private val resources = PackageResources(pkg)
    private val plugin: VBookExtensionManifest = vn.nghetruyen.source.vbook.VBookManifestParser.parse(pkg.pluginJson())
    private val hostManifest = VBookHostManifestFactory.create(artifact.identity.canonicalKey(), plugin, resources)
    private val runtime = VBookCompatibilityRuntime(brokers)
    private val pageCache = ConcurrentHashMap<PageKey, VBookCompatibilityRuntime.ExecutionResult>()
    private val chapterByUrl = ConcurrentHashMap<String, ChapterSummary>()

    init {
        require(plugin.metadata.type in setOf(VBookContentType.NOVEL, VBookContentType.CHINESE_NOVEL)) {
            "VBOOK_STORY_SOURCE_CONTENT_TYPE_UNSUPPORTED:${plugin.metadata.type}"
        }
    }

    override val descriptor: SourceDescriptor = SourceDescriptor(
        id = hostManifest.id,
        displayName = plugin.metadata.name.ifBlank { artifact.identity.remoteIdentity },
        baseUrl = plugin.metadata.source,
        health = SourceHealth.READY,
        categories = emptyList(),
        loginUrl = plugin.metadata.source.takeIf(String::isNotBlank),
        privacyNote = "Tiện ích vBook chạy trong sandbox; Internet công khai được phép nhưng localhost/LAN bị chặn.",
        allowedHosts = setOfNotNull(runCatching { URI(plugin.metadata.source).host }.getOrNull()),
        supportsComments = false,
        commentCapability = SourceCommentCapability.NONE,
        supportsHome = plugin.script(VBookScriptRole.HOME) != null || plugin.script(VBookScriptRole.EXPLORE) != null,
        supportsSuggestions = false,
        implementationKind = SourceImplementationKind.VBOOK,
    )

    override val selectionPriority: Int = 120

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> {
        if (plugin.script(VBookScriptRole.SEARCH) == null) return failure("VBOOK_SEARCH_UNSUPPORTED")
        return when (val result = declaredPage(VBookScriptRole.SEARCH, query, page)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(
                result.value?.let { VBookStoryNormalizer.stories(it.data, plugin.metadata.source).map(::storySummary) }.orEmpty(),
            )
        }
    }

    override suspend fun home(page: Int): AppResult<List<StorySummary>> {
        val role = when {
            plugin.script(VBookScriptRole.HOME) != null -> VBookScriptRole.HOME
            plugin.script(VBookScriptRole.EXPLORE) != null -> VBookScriptRole.EXPLORE
            else -> return search("", page)
        }
        val menu = executeDeclared(role, input = "")
        if (menu is AppResult.Failure) return menu
        val raw = (menu as AppResult.Success).value
        val direct = VBookStoryNormalizer.stories(raw.data, plugin.metadata.source)
        if (direct.isNotEmpty()) return AppResult.Success(direct.map(::storySummary))
        val action = chooseListAction(VBookStoryNormalizer.dynamicActions(raw.data)) ?: return search("", page)
        return when (val result = dynamicPage(action, page)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(
                result.value?.let { VBookStoryNormalizer.stories(it.data, plugin.metadata.source).map(::storySummary) }.orEmpty(),
            )
        }
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> {
        val role = when {
            plugin.script(VBookScriptRole.GENRE) != null -> VBookScriptRole.GENRE
            plugin.script(VBookScriptRole.HOME) != null -> VBookScriptRole.HOME
            else -> return failure("VBOOK_GENRE_UNSUPPORTED")
        }
        val menu = executeDeclared(role, input = "")
        if (menu is AppResult.Failure) return menu
        val actions = VBookStoryNormalizer.dynamicActions((menu as AppResult.Success).value.data)
        val action = actions.firstOrNull {
            it.title.equals(category, ignoreCase = true) || it.input == category
        } ?: return failure("VBOOK_CATEGORY_NOT_FOUND:$category")
        return when (val result = dynamicPage(action, page)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(
                result.value?.let { VBookStoryNormalizer.stories(it.data, plugin.metadata.source).map(::storySummary) }.orEmpty(),
            )
        }
    }

    override suspend fun story(url: String): AppResult<StoryDetail> {
        val detailResult = executeDeclared(VBookScriptRole.DETAIL, url)
        if (detailResult is AppResult.Failure) return detailResult
        val detail = VBookStoryNormalizer.detail(
            (detailResult as AppResult.Success).value.data,
            inputUrl = url,
            fallbackHost = plugin.metadata.source,
        ) ?: return failure("VBOOK_DETAIL_INVALID")

        val tocResult = executeDeclared(VBookScriptRole.TOC, url)
        val chapters = when (tocResult) {
            is AppResult.Failure -> return tocResult
            is AppResult.Success -> VBookStoryNormalizer.chapters(
                tocResult.value.data,
                storyUrl = detail.story.url,
                fallbackHost = plugin.metadata.source,
            ).map { chapter ->
                ChapterSummary(chapter.id, chapter.storyId, chapter.index, chapter.title, chapter.url).also {
                    chapterByUrl[it.url] = it
                }
            }
        }
        return AppResult.Success(
            StoryDetail(
                story = storySummary(detail.story),
                genres = detail.genres,
                status = detail.status,
                chapters = chapters,
            ),
        )
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> {
        val result = executeDeclared(VBookScriptRole.CHAP, url)
        if (result is AppResult.Failure) return result
        val body = VBookStoryNormalizer.chapterBody((result as AppResult.Success).value.data)
        val known = chapterByUrl[url]
        val chapter = known ?: ChapterSummary(
            id = VBookStoryNormalizer.stableId(url),
            storyId = "",
            index = 0,
            title = body.title.ifBlank { "Chương" },
            url = url,
        )
        return AppResult.Success(
            ChapterContent(
                chapter = chapter,
                paragraphs = body.paragraphs,
                nextChapterUrl = VBookStoryNormalizer.resolveUrl(plugin.metadata.source, body.nextUrl),
                previousChapterUrl = VBookStoryNormalizer.resolveUrl(plugin.metadata.source, body.previousUrl),
            ),
        )
    }

    override suspend fun chapterPage(storyId: String, url: String, startIndex: Int): AppResult<ChapterPage> =
        AppResult.Failure("VBOOK_TOC_PAGING_UNCERTIFIED", "Nguồn vBook này chưa chứng nhận phân trang mục lục.")

    private fun executeDeclared(
        role: VBookScriptRole,
        input: String,
        continuation: VBookContinuation = VBookContinuation(),
    ): AppResult<VBookCompatibilityRuntime.ExecutionResult> = when (val result = runtime.executeDeclared(
        sourceManifest = hostManifest,
        resources = resources,
        role = role,
        input = input,
        continuation = continuation,
    )) {
        is SourcePlatformResult.Success -> AppResult.Success(result.value)
        is SourcePlatformResult.Failure -> AppResult.Failure(result.error.code.name, result.error.message, result.error.cause)
    }

    private fun executeDynamic(
        action: VBookDynamicAction,
        continuation: VBookContinuation,
    ): AppResult<VBookCompatibilityRuntime.ExecutionResult> = when (val result = runtime.executeDynamic(
        sourceManifest = hostManifest,
        resources = resources,
        scriptPath = action.scriptPath,
        args = action.invocation(continuation).args,
    )) {
        is SourcePlatformResult.Success -> AppResult.Success(result.value)
        is SourcePlatformResult.Failure -> AppResult.Failure(result.error.code.name, result.error.message, result.error.cause)
    }

    private fun declaredPage(
        role: VBookScriptRole,
        input: String,
        page: Int,
    ): AppResult<VBookCompatibilityRuntime.ExecutionResult?> {
        require(page >= 1) { "VBOOK_PAGE_INVALID" }
        var previous: VBookCompatibilityRuntime.ExecutionResult? = null
        for (index in 1..page) {
            val key = PageKey("declared:${role.manifestKey}", input, "", index)
            val cached = pageCache[key]
            if (cached != null) {
                previous = cached
                continue
            }
            val continuation = if (index == 1) VBookContinuation() else previous?.continuation ?: return AppResult.Success(null)
            if (index > 1 && !continuation.hasNext()) return AppResult.Success(null)
            when (val executed = executeDeclared(role, input, continuation)) {
                is AppResult.Failure -> return executed
                is AppResult.Success -> {
                    pageCache[key] = executed.value
                    previous = executed.value
                }
            }
        }
        return AppResult.Success(previous)
    }

    private fun dynamicPage(
        action: VBookDynamicAction,
        page: Int,
    ): AppResult<VBookCompatibilityRuntime.ExecutionResult?> {
        require(page >= 1) { "VBOOK_PAGE_INVALID" }
        var previous: VBookCompatibilityRuntime.ExecutionResult? = null
        for (index in 1..page) {
            val key = PageKey("dynamic:${action.scriptPath}", action.input, action.data, index)
            val cached = pageCache[key]
            if (cached != null) {
                previous = cached
                continue
            }
            val continuation = if (index == 1) VBookContinuation() else previous?.continuation ?: return AppResult.Success(null)
            if (index > 1 && !continuation.hasNext()) return AppResult.Success(null)
            when (val executed = executeDynamic(action, continuation)) {
                is AppResult.Failure -> return executed
                is AppResult.Success -> {
                    pageCache[key] = executed.value
                    previous = executed.value
                }
            }
        }
        return AppResult.Success(previous)
    }

    private fun chooseListAction(actions: List<VBookDynamicAction>): VBookDynamicAction? =
        actions.firstOrNull { it.type.equals("list", ignoreCase = true) } ?: actions.firstOrNull()

    private fun storySummary(record: vn.nghetruyen.source.vbook.VBookStoryRecord): StorySummary = StorySummary(
        id = record.id,
        sourceId = descriptor.id,
        title = record.title,
        author = record.author,
        coverUrl = record.coverUrl,
        description = record.description,
        url = record.url,
    )

    private fun failure(message: String): AppResult.Failure = AppResult.Failure("VBOOK_SOURCE_ERROR", message)

    private data class PageKey(
        val kind: String,
        val input: String,
        val data: String,
        val page: Int,
    )

    private class PackageResources(private val pkg: VBookPackage) : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? =
            pkg.entries[path]?.takeIf { it.size <= maxBytes }?.copyOf()
    }
}
