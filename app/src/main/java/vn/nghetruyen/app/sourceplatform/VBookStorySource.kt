package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sources.SourceCommentCapability
import vn.nghetruyen.app.sources.SourceDescriptor
import vn.nghetruyen.app.sources.SourceImplementationKind
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookCompatibilityRuntime
import vn.nghetruyen.source.vbook.VBookConfigReader
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
import java.util.Base64
import java.util.UUID

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
    private val configReader: VBookConfigReader = VBookConfigReader { emptyMap() },
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) : StorySource {
    private val pkg: VBookPackage = VBookPackageReader.read(packageBytes)
    private val resources = PackageResources(pkg)
    private val plugin: VBookExtensionManifest = vn.nghetruyen.source.vbook.VBookManifestParser.parse(pkg.pluginJson())
    private val hostManifest = VBookHostManifestFactory.create(artifact.identity.canonicalKey(), plugin, resources)
    private val runtime = VBookCompatibilityRuntime(brokers, diagnostics)
    private val configKey = artifact.identity.canonicalKey()
    private val pageCache = BoundedLruCache<PageKey, VBookCompatibilityRuntime.ExecutionResult>(MAX_PAGE_CACHE_ENTRIES)
    private val chapterByUrl = BoundedLruCache<String, ChapterSummary>(MAX_CHAPTER_CACHE_ENTRIES)

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
        supportsComments = plugin.script(VBookScriptRole.COMMENT) != null,
        commentCapability = if (plugin.script(VBookScriptRole.COMMENT) != null) SourceCommentCapability.PAGED else SourceCommentCapability.NONE,
        supportsHome = plugin.script(VBookScriptRole.HOME) != null || plugin.script(VBookScriptRole.EXPLORE) != null,
        supportsSuggestions = plugin.script(VBookScriptRole.SUGGEST) != null,
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

    override suspend fun suggestions(query: String): AppResult<List<String>> {
        if (plugin.script(VBookScriptRole.SUGGEST) == null) return AppResult.Success(emptyList())
        val clean = query.trim()
        if (clean.isBlank()) return AppResult.Success(emptyList())
        return when (val result = executeDeclared(VBookScriptRole.SUGGEST, clean)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(suggestionStrings(result.value.data))
        }
    }

    override suspend fun commentsPage(url: String): AppResult<StoryCommentPage> {
        if (plugin.script(VBookScriptRole.COMMENT) == null) {
            return AppResult.Failure("COMMENTS_UNSUPPORTED", "Tiện ích vBook này không khai báo comment.js.")
        }
        val request = runCatching { decodeCommentTarget(url) }
            .getOrElse { return AppResult.Failure("VBOOK_COMMENT_CURSOR_INVALID", it.message.orEmpty(), it) }
        return when (val result = executeDeclared(VBookScriptRole.COMMENT, request.first, request.second)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val parsed = StoryCommentPayloadParser.parsePage(result.value.data)
                val next = result.value.continuation.token.takeIf(String::isNotEmpty)?.let { token ->
                    encodeCommentTarget(request.first, token)
                }
                AppResult.Success(parsed.copy(nextPageUrl = next))
            }
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
        val direct = if (role == VBookScriptRole.EXPLORE) {
            VBookStoryNormalizer.exploreStories(raw.data, plugin.metadata.source)
        } else {
            VBookStoryNormalizer.stories(raw.data, plugin.metadata.source)
        }
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
                commentsUrl = detail.story.url.takeIf { plugin.script(VBookScriptRole.COMMENT) != null },
            ),
        )
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> {
        val result = executeDeclared(VBookScriptRole.CHAP, url)
        if (result is AppResult.Failure) return result
        val executed = (result as AppResult.Success).value
        val body = VBookStoryNormalizer.chapterBody(executed.data)
        val resolvedTitle = body.title
            .ifBlank { executed.continuation.token }
            .ifBlank { chapterByUrl[url]?.title.orEmpty() }
            .ifBlank { "Chương" }
        val known = chapterByUrl[url]
        val chapter = known?.copy(title = resolvedTitle) ?: ChapterSummary(
            id = VBookStoryNormalizer.stableId(url),
            storyId = "",
            index = 0,
            title = resolvedTitle,
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
        AppResult.Failure("VBOOK_TOC_PAGING_UNCERTIFIED", "vBook current toc(url) returns one chapter list; no reference contract for TOC pagination is claimed.")

    private fun executeDeclared(
        role: VBookScriptRole,
        input: String,
        continuation: VBookContinuation = VBookContinuation(),
    ): AppResult<VBookCompatibilityRuntime.ExecutionResult> {
        val traceId = UUID.randomUUID().toString()
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            traceId = traceId,
            sourceId = hostManifest.id,
            sourceVersion = artifact.version,
            category = DiagnosticCategory.RUNTIME,
            name = "VBOOK_ROLE_STARTED",
            attributes = mapOf("role" to role.manifestKey, "inputChars" to input.length.toString()),
        ))
        return when (val result = runtime.executeDeclared(
            sourceManifest = hostManifest,
            resources = resources,
            role = role,
            input = input,
            continuation = continuation,
            persistedConfig = configReader.read(configKey),
            traceId = traceId,
        )) {
            is SourcePlatformResult.Success -> {
                evidence.capture(DiagnosticEvidence(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = hostManifest.id,
                    category = DiagnosticCategory.RUNTIME,
                    name = "vbook-${role.manifestKey}-raw-envelope.json",
                    contentType = "application/json",
                    data = JsonCodec.stringify(result.value.rawEnvelope).toByteArray(Charsets.UTF_8),
                    attributes = mapOf("profile" to result.value.profile.name, "instructions" to result.value.instructionCount.toString()),
                ))
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = hostManifest.id,
                    sourceVersion = artifact.version,
                    category = DiagnosticCategory.RUNTIME,
                    name = "VBOOK_ROLE_COMPLETED",
                    attributes = mapOf("role" to role.manifestKey, "instructions" to result.value.instructionCount.toString()),
                ))
                AppResult.Success(result.value)
            }
            is SourcePlatformResult.Failure -> {
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = hostManifest.id,
                    sourceVersion = artifact.version,
                    category = DiagnosticCategory.RUNTIME,
                    name = "VBOOK_ROLE_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                    attributes = mapOf("role" to role.manifestKey, "code" to result.error.code.name, "error" to result.error.message),
                ))
                AppResult.Failure(result.error.code.name, result.error.message, result.error.cause)
            }
        }
    }

    private fun executeDynamic(
        action: VBookDynamicAction,
        continuation: VBookContinuation,
    ): AppResult<VBookCompatibilityRuntime.ExecutionResult> {
        val traceId = UUID.randomUUID().toString()
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            traceId = traceId,
            sourceId = hostManifest.id,
            sourceVersion = artifact.version,
            category = DiagnosticCategory.RUNTIME,
            name = "VBOOK_DYNAMIC_STARTED",
            attributes = mapOf("script" to action.scriptPath),
        ))
        return when (val result = runtime.executeDynamic(
            sourceManifest = hostManifest,
            resources = resources,
            scriptPath = action.scriptPath,
            args = action.invocation(continuation).args,
            persistedConfig = configReader.read(configKey),
            traceId = traceId,
        )) {
            is SourcePlatformResult.Success -> {
                evidence.capture(DiagnosticEvidence(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = hostManifest.id,
                    category = DiagnosticCategory.RUNTIME,
                    name = "vbook-dynamic-raw-envelope.json",
                    contentType = "application/json",
                    data = JsonCodec.stringify(result.value.rawEnvelope).toByteArray(Charsets.UTF_8),
                    attributes = mapOf("script" to action.scriptPath, "profile" to result.value.profile.name),
                ))
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = hostManifest.id,
                    sourceVersion = artifact.version,
                    category = DiagnosticCategory.RUNTIME,
                    name = "VBOOK_DYNAMIC_COMPLETED",
                    attributes = mapOf("script" to action.scriptPath, "instructions" to result.value.instructionCount.toString()),
                ))
                AppResult.Success(result.value)
            }
            is SourcePlatformResult.Failure -> {
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = hostManifest.id,
                    sourceVersion = artifact.version,
                    category = DiagnosticCategory.RUNTIME,
                    name = "VBOOK_DYNAMIC_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                    attributes = mapOf("script" to action.scriptPath, "code" to result.error.code.name, "error" to result.error.message),
                ))
                AppResult.Failure(result.error.code.name, result.error.message, result.error.cause)
            }
        }
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

    private fun suggestionStrings(data: JsonValue): List<String> {
        val values = when (data) {
            is JsonValue.Arr -> data.values
            is JsonValue.Obj -> data.array("items")?.values ?: data.array("data")?.values ?: listOf(data)
            is JsonValue.Str -> listOf(data)
            else -> emptyList()
        }
        return values.asSequence().mapNotNull { value ->
            when (value) {
                is JsonValue.Str -> value.value
                is JsonValue.Obj -> value.string("name") ?: value.string("title")
                else -> null
            }?.trim()?.takeIf(String::isNotBlank)
        }.distinct().take(MAX_SUGGESTIONS).toList()
    }

    private fun encodeCommentTarget(input: String, token: String): String {
        require(input.length + token.length <= MAX_COMMENT_CURSOR_CHARS) { "VBOOK_COMMENT_CURSOR_TOO_LARGE" }
        val payload = "${input.length}:$input$token".toByteArray(Charsets.UTF_8)
        return COMMENT_CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    private fun decodeCommentTarget(target: String): Pair<String, VBookContinuation> {
        if (!target.startsWith(COMMENT_CURSOR_PREFIX)) return target to VBookContinuation()
        val encoded = target.removePrefix(COMMENT_CURSOR_PREFIX)
        require(encoded.length <= MAX_COMMENT_CURSOR_CHARS * 2) { "VBOOK_COMMENT_CURSOR_TOO_LARGE" }
        val raw = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
        val separator = raw.indexOf(':')
        require(separator in 1..10) { "VBOOK_COMMENT_CURSOR_INVALID" }
        val inputLength = raw.substring(0, separator).toIntOrNull() ?: error("VBOOK_COMMENT_CURSOR_INVALID")
        val start = separator + 1
        require(inputLength in 0..MAX_COMMENT_CURSOR_CHARS && start + inputLength <= raw.length) {
            "VBOOK_COMMENT_CURSOR_INVALID"
        }
        val input = raw.substring(start, start + inputLength)
        val token = raw.substring(start + inputLength)
        require(input.length + token.length <= MAX_COMMENT_CURSOR_CHARS) { "VBOOK_COMMENT_CURSOR_TOO_LARGE" }
        return input to VBookContinuation(token)
    }

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
        override fun read(path: String, maxBytes: Int): ByteArray? {
            val bytes = when (path) {
                "plugin.json" -> pkg.pluginJsonBytes
                "icon.png" -> pkg.iconBytes
                else -> pkg.scripts[path]
            } ?: return null
            return bytes.takeIf { it.size <= maxBytes }?.copyOf()
        }
    }

    companion object {
        private const val COMMENT_CURSOR_PREFIX = "vbook-comment:"
        private const val MAX_COMMENT_CURSOR_CHARS = 64 * 1024
        private const val MAX_SUGGESTIONS = 30
        private const val MAX_PAGE_CACHE_ENTRIES = 128
        private const val MAX_CHAPTER_CACHE_ENTRIES = 4_096
    }
}
