package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sources.BuiltInSourcePackBridge
import vn.nghetruyen.app.sources.SourceCommentCapability
import vn.nghetruyen.app.sources.SourceDescriptor
import vn.nghetruyen.app.sources.SourceImplementationKind
import vn.nghetruyen.app.sources.SourceUiActionDescriptor
import vn.nghetruyen.app.sources.SourceUiActionResult
import vn.nghetruyen.app.sources.SourceUiSurface
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import vn.nghetruyen.source.runtime.MapSourceResourceProvider
import java.net.URI
import java.util.Base64

class SourcePackStorySource(
    private val pack: VerifiedSourcePack,
    private val executor: SourcePackActionExecutor,
    private val genericCommentLoader: GenericStoryCommentLoader? = null,
    private val builtInDelegate: StorySource? = null,
) : StorySource, BuiltInSourcePackBridge {
    private val resources = MapSourceResourceProvider(pack.entries)
    private val metadata = readSourceMetadata()
    private val sourceAlias = metadata.legacyId
    private val sourceId = sourceAlias ?: pack.manifest.id
    private val categories = readCategories()
    private val fullParityCertified = isFullParityCertified()
    private val bridgeActive = builtInDelegate != null && metadata.delegateBuiltInId == sourceId

    override val builtInDelegateId: String? get() = metadata.delegateBuiltInId

    override fun attachBuiltInDelegate(delegate: StorySource): StorySource {
        require(metadata.delegateBuiltInId == delegate.descriptor.id) { "SOURCE_PACK_DELEGATE_ID_MISMATCH" }
        require(sourceId == delegate.descriptor.id) { "SOURCE_PACK_LEGACY_ID_MISMATCH" }
        return SourcePackStorySource(pack, executor, genericCommentLoader, delegate)
    }

    override val selectionPriority: Int = metadata.selectionPriority?.let { requested ->
        if (requested > BUILT_IN_SOURCE_PRIORITY && !(fullParityCertified || bridgeActive)) {
            SOURCE_PACK_UNCERTIFIED_MAX_PRIORITY
        } else {
            requested
        }
    } ?: if (metadata.preferSourcePack && (fullParityCertified || bridgeActive)) {
        SOURCE_PACK_FULL_PARITY_PRIORITY
    } else {
        SOURCE_PACK_COMPATIBILITY_PRIORITY
    }

    private val packAllowedHosts: Set<String> = (pack.manifest.origins + pack.manifest.redirectOrigins).mapNotNull { origin ->
        runCatching { URI(origin.replace("https://*.", "https://")).host }.getOrNull()
    }.toSet()

    override val descriptor: SourceDescriptor = SourceDescriptor(
        id = sourceId,
        displayName = pack.manifest.name,
        baseUrl = builtInDelegate?.descriptor?.baseUrl
            ?: pack.manifest.origins.firstOrNull()
            ?: "sourcepack://${pack.manifest.id}",
        health = builtInDelegate?.descriptor?.health ?: SourceHealth.READY,
        categories = (builtInDelegate?.descriptor?.categories.orEmpty() + categories).distinct(),
        loginUrl = builtInDelegate?.descriptor?.loginUrl,
        privacyNote = pack.manifest.privacy.note.ifBlank { builtInDelegate?.descriptor?.privacyNote },
        allowedHosts = builtInDelegate?.descriptor?.allowedHosts.orEmpty() + packAllowedHosts,
        supportsComments = builtInDelegate?.descriptor?.supportsComments == true ||
            SourceActionName.COMMENTS in pack.manifest.actions || genericCommentLoader != null,
        commentCapability = when {
            SourceActionName.COMMENTS in pack.manifest.actions && pack.manifest.capabilities.browser.navigate -> SourceCommentCapability.DYNAMIC_BROWSER
            SourceActionName.COMMENTS in pack.manifest.actions -> SourceCommentCapability.PAGED
            builtInDelegate?.descriptor?.supportsComments == true -> builtInDelegate.descriptor.commentCapability
            genericCommentLoader != null && pack.manifest.capabilities.browser.navigate -> SourceCommentCapability.DYNAMIC_BROWSER
            genericCommentLoader != null -> SourceCommentCapability.EMBEDDED
            else -> SourceCommentCapability.NONE
        },
        supportsHome = builtInDelegate?.descriptor?.supportsHome == true ||
            SourceActionName.HOME in pack.manifest.actions || categories.isNotEmpty() || SourceActionName.SEARCH in pack.manifest.actions,
        supportsSuggestions = builtInDelegate?.descriptor?.supportsSuggestions == true ||
            SourceActionName.SUGGESTIONS in pack.manifest.actions,
        implementationKind = if (bridgeActive) {
            SourceImplementationKind.HYBRID_PACK
        } else when (pack.manifest.runtime.mode) {
            SourceRuntimeMode.DECLARATIVE -> SourceImplementationKind.SOURCE_PACK
            SourceRuntimeMode.VBOOK_JS_COMPAT -> SourceImplementationKind.VBOOK
            SourceRuntimeMode.NATIVE_LUA_COMPAT -> SourceImplementationKind.NATIVE_LUA
        },
        uiActions = (builtInDelegate?.descriptor?.uiActions.orEmpty() + pack.manifest.uiActions.map { action ->
            SourceUiActionDescriptor(
                id = action.id,
                label = action.label,
                surfaces = action.contexts.map { SourceUiSurface.valueOf(it.name) }.toSet(),
                group = action.group,
                order = action.order,
            )
        }).distinctBy { it.id },
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> {
        builtInDelegate?.let { return it.home(page) }
        return guarded {
        val result = execute(
            SourceActionName.HOME,
            JsonValue.Obj(linkedMapOf(
                "page" to JsonValue.Num(page.toDouble(), page.toString()),
                "category" to JsonValue.Str(categories.firstOrNull().orEmpty()),
                "input" to JsonValue.Str(""),
            )),
        )
        if (result != null) return@guarded AppResult.Success(storyItems(result))

        val fallback = categories.firstOrNull()?.let { category(it, page) } ?: search("", page)
        when (fallback) {
            is AppResult.Success -> AppResult.Success(fallback.value)
            is AppResult.Failure -> fallback
        }
    }
    }

    override suspend fun suggestions(query: String): AppResult<List<String>> {
        if (SourceActionName.SUGGESTIONS !in pack.manifest.actions) {
            builtInDelegate?.let { return it.suggestions(query) }
        }
        return guarded {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2 || SourceActionName.SUGGESTIONS !in pack.manifest.actions) {
            return@guarded AppResult.Success(emptyList())
        }
        val result = execute(
            SourceActionName.SUGGESTIONS,
            JsonValue.Obj(linkedMapOf(
                "query" to JsonValue.Str(cleanQuery),
                "url" to JsonValue.Str(cleanQuery),
                "page" to JsonValue.Num(1.0, "1"),
            )),
        ) ?: return@guarded AppResult.Success(emptyList())
        AppResult.Success(suggestionItems(result).take(MAX_SUGGESTIONS))
    }
    }

    override suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String?,
        storyId: String?,
        chapterId: String?,
    ): AppResult<SourceUiActionResult> = guarded {
        val action = descriptor.uiActions.firstOrNull { it.id == actionId && surface in it.surfaces }
            ?: return@guarded AppResult.Failure("SOURCE_UI_ACTION_NOT_FOUND", "Action giao diện không tồn tại ở màn hình này.")
        val value = execute(
            SourceActionName.UI_ACTION,
            JsonValue.Obj(linkedMapOf(
                "id" to JsonValue.Str(action.id),
                "context" to JsonValue.Str(surface.name),
                "url" to JsonValue.Str(currentUrl.orEmpty()),
                "storyId" to JsonValue.Str(storyId.orEmpty()),
                "chapterId" to JsonValue.Str(chapterId.orEmpty()),
            )),
        ) ?: return@guarded AppResult.Failure("SOURCE_UI_ACTION_HANDLER_MISSING", "Gói nguồn thiếu handler uiAction.")
        val outcome = when (value) {
            is JsonValue.Str -> SourceUiActionResult(message = value.value.take(1000))
            is JsonValue.Obj -> SourceUiActionResult(
                message = value.string("message").orEmpty().take(1000),
                openUrl = value.string("openUrl")?.take(4096),
                refresh = (value.values["refresh"] as? JsonValue.Bool)?.value ?: false,
            )
            JsonValue.Null -> SourceUiActionResult()
            else -> return@guarded typeFailure("Kết quả uiAction phải là string, object hoặc null.")
        }
        AppResult.Success(outcome)
    }

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> {
        builtInDelegate?.let { return it.search(query, page) }
        return guarded {
        val result = execute(
            SourceActionName.SEARCH,
            JsonValue.Obj(linkedMapOf(
                "query" to JsonValue.Str(query),
                "page" to JsonValue.Num(page.toDouble(), page.toString()),
            )),
        ) ?: return@guarded AppResult.Failure("SOURCE_ACTION_MISSING", "Gói nguồn không hỗ trợ tìm kiếm.")
        AppResult.Success(storyItems(result))
    }
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> {
        builtInDelegate?.let { return it.category(category, page) }
        return guarded {
        val result = execute(
            SourceActionName.GENRE,
            JsonValue.Obj(linkedMapOf(
                "category" to JsonValue.Str(category),
                "page" to JsonValue.Num(page.toDouble(), page.toString()),
            )),
        ) ?: return@guarded search("", page)
        AppResult.Success(storyItems(result))
    }
    }

    override suspend fun story(url: String): AppResult<StoryDetail> {
        builtInDelegate?.let { return it.story(url) }
        return guarded {
        val value = execute(
            SourceActionName.DETAIL,
            JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(url))),
        ) ?: return@guarded AppResult.Failure("SOURCE_ACTION_MISSING", "Gói nguồn thiếu action detail.")
        if (value == JsonValue.Null) return@guarded AppResult.Failure("NOT_FOUND", "Không tìm thấy truyện trong gói nguồn.")
        val obj = value as? JsonValue.Obj
            ?: return@guarded typeFailure("Kết quả chi tiết không phải object.")
        val summary = storySummary(obj)
            ?: return@guarded typeFailure("Chi tiết truyện thiếu id hoặc title.")
        val embedded = obj.array("chapters")?.values.orEmpty().mapNotNull(::chapterSummary)
        val embeddedNext = extractNextPageUrl(obj, url)
        val tocPage = if (embedded.isNotEmpty() || SourceActionName.TOC !in pack.manifest.actions) {
            ChapterPage(normalizeChapterIndices(summary.id, embedded, 0), embeddedNext)
        } else {
            val toc = execute(SourceActionName.TOC, JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(url))))
            parseChapterPage(toc, summary.id, startIndex = 0, currentUrl = url)
        }
        AppResult.Success(
            StoryDetail(
                story = summary,
                genres = obj.stringArray("genres"),
                status = obj.string("status").orEmpty(),
                chapters = tocPage.chapters,
                nextChapterPageUrl = tocPage.nextPageUrl,
                commentsUrl = obj.string("commentsUrl"),
                comments = StoryCommentPayloadParser.parse(obj.array("comments") ?: JsonValue.Arr(emptyList())),
            ),
        )
    }
    }

    override suspend fun comments(url: String): AppResult<List<StoryComment>> = when (val result = commentsPage(url)) {
        is AppResult.Success -> AppResult.Success(result.value.comments)
        is AppResult.Failure -> result
    }

    override suspend fun commentsPage(url: String): AppResult<StoryCommentPage> = guarded {
        val value = execute(
            SourceActionName.COMMENTS,
            JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(url))),
        )
        if (value != null) return@guarded AppResult.Success(StoryCommentPayloadParser.parsePage(value))
        val delegateComments = builtInDelegate?.commentsPage(url)
        if (delegateComments is AppResult.Success && delegateComments.value.comments.isNotEmpty()) {
            return@guarded delegateComments
        }
        val detail = story(url)
        if (detail is AppResult.Success && detail.value.comments.isNotEmpty()) {
            return@guarded AppResult.Success(StoryCommentPage(detail.value.comments, detail.value.commentsUrl))
        }
        val fallback = genericCommentLoader?.load(pack.manifest, url)
            ?: return@guarded AppResult.Failure("COMMENTS_UNSUPPORTED", "Nguồn chưa hỗ trợ bình luận.")
        AppResult.Success(fallback)
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> {
        builtInDelegate?.let { return it.chapter(url) }
        return guarded {
        val value = execute(
            SourceActionName.CHAPTER,
            JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(url))),
        ) ?: return@guarded AppResult.Failure("SOURCE_ACTION_MISSING", "Gói nguồn thiếu action chapter.")
        if (value == JsonValue.Null) return@guarded AppResult.Failure("NOT_FOUND", "Không tìm thấy chương trong gói nguồn.")
        val obj = value as? JsonValue.Obj
            ?: return@guarded typeFailure("Kết quả chương không phải object.")
        val summary = chapterSummary(obj)
            ?: return@guarded typeFailure("Chương thiếu id hoặc title.")
        val paragraphs = obj.stringArray("paragraphs")
        if (paragraphs.isEmpty()) return@guarded AppResult.Failure("EMPTY_CHAPTER", "Chương không có nội dung.")
        AppResult.Success(
            ChapterContent(
                chapter = summary,
                paragraphs = paragraphs,
                previousChapterUrl = obj.string("previousChapterUrl"),
                nextChapterUrl = obj.string("nextChapterUrl"),
            ),
        )
    }
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> {
        builtInDelegate?.let { return it.latestChapter(url) }
        val explicit = guarded {
            val value = execute(
                SourceActionName.LATEST_CHAPTER,
                JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(url))),
            ) ?: return@guarded AppResult.Success(null)
            val candidate = when (value) {
                is JsonValue.Obj -> chapterSummary(value)
                is JsonValue.Arr -> value.values.mapNotNull(::chapterSummary).maxByOrNull(ChapterSummary::index)
                else -> null
            }
            AppResult.Success(candidate)
        }
        if (SourceActionName.LATEST_CHAPTER in pack.manifest.actions) return explicit
        return when (val detail = story(url)) {
        is AppResult.Success -> AppResult.Success(detail.value.chapters.maxByOrNull(ChapterSummary::index))
        is AppResult.Failure -> detail
    }
    }

    override suspend fun chapterPage(storyId: String, url: String, startIndex: Int): AppResult<ChapterPage> {
        builtInDelegate?.let { return it.chapterPage(storyId, url, startIndex) }
        return guarded {
        val continuation = decodeContinuation(url)
        val action = when {
            SourceActionName.TOC_PAGES in pack.manifest.actions -> SourceActionName.TOC_PAGES
            SourceActionName.TOC in pack.manifest.actions -> SourceActionName.TOC
            else -> return@guarded AppResult.Failure(
                code = "CHAPTER_PAGING_UNSUPPORTED",
                message = "Gói nguồn không có action TOC/TOC_PAGES.",
            )
        }
        val page = (startIndex / DEFAULT_TOC_PAGE_SIZE) + 1
        val actionUrl = when {
            continuation == null -> url
            action == SourceActionName.TOC_PAGES -> continuation.storyUrl
            else -> continuation.token
        }
        val value = execute(
            action,
            JsonValue.Obj(linkedMapOf(
                "storyId" to JsonValue.Str(storyId),
                "url" to JsonValue.Str(actionUrl),
                "pageToken" to JsonValue.Str(continuation?.token.orEmpty()),
                "page" to JsonValue.Num(page.toDouble(), page.toString()),
                "startIndex" to JsonValue.Num(startIndex.toDouble(), startIndex.toString()),
            )),
        ) ?: return@guarded AppResult.Failure("SOURCE_ACTION_MISSING", "Gói nguồn thiếu action mục lục.")
        AppResult.Success(parseChapterPage(value, storyId, startIndex, continuation?.storyUrl ?: url))
    }
    }

    private fun execute(action: SourceActionName, input: JsonValue.Obj): JsonValue? {
        if (action !in pack.manifest.actions) return null
        return when (val result = executor.execute(pack, resources, SourceActionRequest(pack.manifest.id, action, input))) {
            is SourcePlatformResult.Success -> result.value.value
            is SourcePlatformResult.Failure -> throw SourcePackRuntimeFailure(result.error.code.name, result.error.message)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> AppResult<T>): AppResult<T> = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: SourcePackRuntimeFailure) {
            AppResult.Failure(error.code, error.message ?: "Runtime gói nguồn thất bại.")
        } catch (error: Exception) {
            AppResult.Failure("SOURCE_PACK_RUNTIME_FAILED", error.message ?: "Runtime gói nguồn thất bại.")
        }
    }

    private fun readSourceMetadata(): SourcePackMetadata {
        val raw = pack.entries["data/source-info.json"] ?: return SourcePackMetadata()
        return runCatching {
            val root = vn.nghetruyen.source.api.JsonCodec.parse(raw.toString(Charsets.UTF_8)) as? JsonValue.Obj
                ?: return@runCatching SourcePackMetadata()
            SourcePackMetadata(
                legacyId = root.string("legacyId")?.takeIf { it.matches(Regex("^[a-z][a-z0-9_-]{1,63}$")) },
                preferSourcePack = root.bool("preferSourcePack") == true ||
                    root.string("compatibilityTier")?.startsWith("FULL", ignoreCase = true) == true,
                selectionPriority = root.int("selectionPriority")?.coerceIn(MIN_SELECTION_PRIORITY, MAX_SELECTION_PRIORITY),
                delegateBuiltInId = root.string("delegateBuiltInId")?.takeIf { it.matches(Regex("^[a-z][a-z0-9_-]{1,63}$")) },
            )
        }.getOrDefault(SourcePackMetadata())
    }

    private fun isFullParityCertified(): Boolean {
        if (!metadata.preferSourcePack || metadata.delegateBuiltInId != null) return false
        if (!pack.manifest.actions.keys.containsAll(FULL_PARITY_REQUIRED_ACTIONS)) return false
        val fixtureActions = pack.manifest.fixtures.mapTo(mutableSetOf()) { it.action }
        return fixtureActions.containsAll(FULL_PARITY_REQUIRED_FIXTURES)
    }

    private fun readCategories(): List<String> {
        val infoCategories = pack.entries["data/source-info.json"]?.let { raw ->
            runCatching {
                val root = vn.nghetruyen.source.api.JsonCodec.parse(raw.toString(Charsets.UTF_8)) as? JsonValue.Obj
                root?.stringArray("categories").orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty()
        if (infoCategories.isNotEmpty()) return infoCategories.distinct()
        val raw = pack.entries["data/catalog.json"] ?: return emptyList()
        return runCatching {
            val root = vn.nghetruyen.source.api.JsonCodec.parse(raw.toString(Charsets.UTF_8)) as JsonValue.Obj
            root.array("stories")?.values.orEmpty()
                .flatMap { (it as? JsonValue.Obj)?.stringArray("genres").orEmpty() }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    private fun storySummary(value: JsonValue): StorySummary? {
        val obj = value as? JsonValue.Obj ?: return null
        val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return null
        val title = obj.string("title")?.takeIf(String::isNotBlank) ?: return null
        return StorySummary(
            id = id,
            sourceId = sourceId,
            title = title,
            author = obj.string("author").orEmpty(),
            coverUrl = obj.string("coverUrl"),
            description = obj.string("description").orEmpty(),
            url = obj.string("url") ?: id,
        )
    }

    private fun storyItems(value: JsonValue): List<StorySummary> {
        val candidates = when (value) {
            is JsonValue.Arr -> value.values
            is JsonValue.Obj -> value.array("items")?.values
                ?: value.array("stories")?.values
                ?: value.array("results")?.values
                ?: value.array("data")?.values
                ?: value.array("sections")?.values.orEmpty().flatMap { section ->
                    (section as? JsonValue.Obj)?.array("items")?.values.orEmpty()
                }
            else -> emptyList()
        }
        return candidates.mapNotNull(::storySummary).distinctBy { it.url.ifBlank { it.id } }.take(MAX_STORY_ITEMS)
    }

    private fun suggestionItems(value: JsonValue): List<String> {
        val candidates = when (value) {
            is JsonValue.Arr -> value.values
            is JsonValue.Obj -> value.array("items")?.values
                ?: value.array("suggestions")?.values
                ?: value.array("results")?.values
                ?: value.array("data")?.values
                ?: emptyList()
            else -> emptyList()
        }
        return candidates.mapNotNull { candidate ->
            when (candidate) {
                is JsonValue.Str -> candidate.value
                is JsonValue.Obj -> candidate.string("query")
                    ?: candidate.string("title")
                    ?: candidate.string("name")
                    ?: candidate.string("text")
                    ?: candidate.string("input")
                else -> null
            }?.trim()?.takeIf(String::isNotBlank)
        }.distinctBy(String::lowercase)
    }

    private fun parseChapterPage(
        value: JsonValue?,
        storyId: String,
        startIndex: Int,
        currentUrl: String,
    ): ChapterPage {
        val candidates = when (value) {
            is JsonValue.Arr -> value.values
            is JsonValue.Obj -> value.array("chapters")?.values
                ?: value.array("items")?.values
                ?: value.array("data")?.values
                ?: emptyList()
            else -> emptyList()
        }
        val chapters = normalizeChapterIndices(storyId, candidates.mapNotNull(::chapterSummary), startIndex)
        val next = (value as? JsonValue.Obj)?.let { extractNextPageUrl(it, currentUrl) }
        return ChapterPage(chapters = chapters, nextPageUrl = next)
    }

    private fun normalizeChapterIndices(
        storyId: String,
        chapters: List<ChapterSummary>,
        startIndex: Int,
    ): List<ChapterSummary> {
        if (chapters.isEmpty()) return emptyList()
        val shouldRebase = startIndex > 0 && chapters.maxOf(ChapterSummary::index) < startIndex
        return chapters.mapIndexed { offset, chapter ->
            chapter.copy(
                storyId = chapter.storyId.ifBlank { storyId },
                index = if (shouldRebase) startIndex + offset else chapter.index,
            )
        }
    }

    private fun extractNextPageUrl(obj: JsonValue.Obj, currentUrl: String): String? {
        val raw = NEXT_PAGE_KEYS.asSequence().mapNotNull { key -> scalarString(obj[key]) }.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("NO_NEXT", ignoreCase = true) && it != "0" && it != "null" }
            ?: return null
        val resolved = when {
            raw.startsWith(CONTINUATION_PREFIX) -> raw
            isResolvablePageReference(raw) -> runCatching { URI(currentUrl).resolve(raw).toString() }.getOrDefault(raw)
            else -> encodeContinuation(currentUrl, raw)
        }
        return resolved.takeUnless { normalizePageIdentity(it) == normalizePageIdentity(currentUrl) }
    }

    private fun isResolvablePageReference(raw: String): Boolean =
        raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith('/') || raw.startsWith("./") || raw.startsWith("../") ||
            raw.startsWith('?') || raw.startsWith('#') || '/' in raw

    private fun encodeContinuation(storyUrl: String, token: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return CONTINUATION_PREFIX +
            encoder.encodeToString(storyUrl.toByteArray(Charsets.UTF_8)) + ":" +
            encoder.encodeToString(token.toByteArray(Charsets.UTF_8))
    }

    private fun decodeContinuation(raw: String): PageContinuation? {
        if (!raw.startsWith(CONTINUATION_PREFIX)) return null
        val payload = raw.removePrefix(CONTINUATION_PREFIX)
        val parts = payload.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val decoder = Base64.getUrlDecoder()
            PageContinuation(
                storyUrl = decoder.decode(parts[0]).toString(Charsets.UTF_8),
                token = decoder.decode(parts[1]).toString(Charsets.UTF_8),
            )
        }.getOrNull()
    }

    private fun scalarString(value: JsonValue?): String? = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        else -> null
    }

    private fun normalizePageIdentity(url: String): String = url.trim().trimEnd('/')

    private fun chapterSummary(value: JsonValue): ChapterSummary? {
        val obj = value as? JsonValue.Obj ?: return null
        val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return null
        val title = obj.string("title")?.takeIf(String::isNotBlank) ?: return null
        return ChapterSummary(
            id = id,
            storyId = obj.string("storyId").orEmpty(),
            index = obj.int("index") ?: 0,
            title = title,
            url = obj.string("url") ?: id,
        )
    }

    private fun JsonValue.Obj.stringArray(name: String): List<String> = array(name)?.values.orEmpty().mapNotNull {
        (it as? JsonValue.Str)?.value
    }

    private fun <T> typeFailure(message: String): AppResult<T> = AppResult.Failure("SOURCE_OUTPUT_INVALID", message)

    private data class SourcePackMetadata(
        val legacyId: String? = null,
        val preferSourcePack: Boolean = false,
        val selectionPriority: Int? = null,
        val delegateBuiltInId: String? = null,
    )

    private data class PageContinuation(val storyUrl: String, val token: String)

    companion object {
        private const val SOURCE_PACK_COMPATIBILITY_PRIORITY = 50
        private const val BUILT_IN_SOURCE_PRIORITY = 100
        private const val SOURCE_PACK_UNCERTIFIED_MAX_PRIORITY = 99
        private const val SOURCE_PACK_FULL_PARITY_PRIORITY = 200
        private const val MIN_SELECTION_PRIORITY = 0
        private const val MAX_SELECTION_PRIORITY = 1_000
        private const val DEFAULT_TOC_PAGE_SIZE = 50
        private const val MAX_STORY_ITEMS = 500
        private const val MAX_SUGGESTIONS = 12
        private const val CONTINUATION_PREFIX = "sourcepack-page:"
        private val NEXT_PAGE_KEYS = listOf("nextPageUrl", "nextUrl", "nextPage", "next", "data2", "cursor")
        private val FULL_PARITY_REQUIRED_ACTIONS = setOf(
            SourceActionName.HOME,
            SourceActionName.GENRE,
            SourceActionName.SEARCH,
            SourceActionName.SUGGESTIONS,
            SourceActionName.DETAIL,
            SourceActionName.LATEST_CHAPTER,
            SourceActionName.TOC,
            SourceActionName.TOC_PAGES,
            SourceActionName.CHAPTER,
        )
        private val FULL_PARITY_REQUIRED_FIXTURES = FULL_PARITY_REQUIRED_ACTIONS
    }
}

private class SourcePackRuntimeFailure(val code: String, message: String) : RuntimeException(message)
