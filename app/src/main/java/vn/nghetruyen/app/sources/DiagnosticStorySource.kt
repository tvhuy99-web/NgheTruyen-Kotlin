package vn.nghetruyen.app.sources

import kotlinx.coroutines.CancellationException
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticRedactor
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.util.UUID

/**
 * Top-level diagnostic boundary for every StorySource implementation.
 *
 * Lower runtimes (native Lua, vBook, built-in HTTP/parser code) may emit richer events, but the UI
 * must never become diagnostically silent just because a particular adapter does not. This wrapper
 * guarantees one start and one terminal event for every user-facing source call while preserving the
 * original AppResult and exception semantics.
 */
internal class DiagnosticStorySource(
    private val delegate: StorySource,
    private val diagnostics: SourceDiagnosticRuntime,
) : StorySource {
    override val descriptor: SourceDescriptor get() = delegate.descriptor
    override val selectionPriority: Int get() = delegate.selectionPriority

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = trace(
        action = "SEARCH",
        attributes = mapOf("page" to page.toString(), "queryLength" to query.length.toString()),
    ) { delegate.search(query, page) }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = trace(
        action = "CATEGORY",
        attributes = mapOf(
            "page" to page.toString(),
            "category" to safeText(category, 160),
        ),
    ) { delegate.category(category, page) }

    override suspend fun story(url: String): AppResult<StoryDetail> = trace(
        action = "STORY",
        attributes = safeLocatorMetadata(url),
    ) { delegate.story(url) }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = trace(
        action = "CHAPTER",
        attributes = safeLocatorMetadata(url),
    ) { delegate.chapter(url) }

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = trace(
        action = "HOME",
        attributes = mapOf("page" to page.toString()),
    ) { delegate.home(page) }

    override suspend fun suggestions(query: String): AppResult<List<String>> = trace(
        action = "SUGGESTIONS",
        attributes = mapOf("queryLength" to query.length.toString()),
    ) { delegate.suggestions(query) }

    override suspend fun comments(url: String): AppResult<List<StoryComment>> = trace(
        action = "COMMENTS",
        attributes = safeLocatorMetadata(url),
    ) { delegate.comments(url) }

    override suspend fun commentsPage(url: String): AppResult<StoryCommentPage> = trace(
        action = "COMMENTS_PAGE",
        attributes = safeLocatorMetadata(url),
    ) { delegate.commentsPage(url) }

    override suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String?,
        storyId: String?,
        chapterId: String?,
    ): AppResult<SourceUiActionResult> = trace(
        action = "UI_ACTION",
        attributes = mapOf(
            "actionId" to safeText(actionId, 160),
            "surface" to surface.name,
            "hasCurrentUrl" to (!currentUrl.isNullOrBlank()).toString(),
            "hasStoryId" to (!storyId.isNullOrBlank()).toString(),
            "hasChapterId" to (!chapterId.isNullOrBlank()).toString(),
        ),
    ) { delegate.runUiAction(actionId, surface, currentUrl, storyId, chapterId) }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = trace(
        action = "LATEST_CHAPTER",
        attributes = safeLocatorMetadata(url),
    ) { delegate.latestChapter(url) }

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = trace(
        action = "CHAPTER_PAGE",
        attributes = safeLocatorMetadata(url) + mapOf(
            "hasStoryId" to storyId.isNotBlank().toString(),
            "startIndex" to startIndex.toString(),
        ),
    ) { delegate.chapterPage(storyId, url, startIndex) }

    private suspend fun <T> trace(
        action: String,
        attributes: Map<String, String> = emptyMap(),
        block: suspend () -> AppResult<T>,
    ): AppResult<T> {
        if (diagnostics.mode == SourceDiagnosticRuntime.MODE_OFF) return block()

        val operationId = "source-action:${descriptor.id.take(120)}:${action.lowercase()}:${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val base = baseAttributes(action) + attributes
        diagnostics.mark(
            name = "SOURCE_ACTION_STARTED",
            category = DiagnosticCategory.RUNTIME,
            severity = DiagnosticSeverity.INFO,
            sourceId = descriptor.id,
            traceId = operationId,
            attributes = base + operationAttributes(operationId, action, DiagnosticOperationState.STARTED),
        )

        return try {
            when (val result = block()) {
                is AppResult.Success -> {
                    val duration = elapsedSince(startedAt)
                    diagnostics.mark(
                        name = "SOURCE_ACTION_COMPLETED",
                        category = DiagnosticCategory.RUNTIME,
                        severity = DiagnosticSeverity.INFO,
                        sourceId = descriptor.id,
                        traceId = operationId,
                        durationMs = duration,
                        attributes = base + successMetadata(result.value) +
                            operationAttributes(operationId, action, DiagnosticOperationState.COMPLETED) +
                            mapOf("status" to "success"),
                    )
                    result
                }
                is AppResult.Failure -> {
                    val duration = elapsedSince(startedAt)
                    diagnostics.mark(
                        name = "SOURCE_ACTION_FAILED",
                        category = DiagnosticCategory.RUNTIME,
                        severity = DiagnosticSeverity.ERROR,
                        sourceId = descriptor.id,
                        traceId = operationId,
                        durationMs = duration,
                        attributes = base + operationAttributes(operationId, action, DiagnosticOperationState.FAILED) + mapOf(
                            "status" to "failed",
                            "code" to safeText(result.code, 240),
                            "errorCode" to safeText(result.code, 240),
                            "message" to safeText(result.message, 2_000),
                            "error" to safeText(result.message, 2_000),
                            "causeType" to result.cause?.javaClass?.name.orEmpty(),
                            "causeChain" to causeChain(result.cause),
                        ),
                    )
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            diagnostics.mark(
                name = "SOURCE_ACTION_CANCELLED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                sourceId = descriptor.id,
                traceId = operationId,
                durationMs = elapsedSince(startedAt),
                attributes = base + operationAttributes(operationId, action, DiagnosticOperationState.CANCELLED) + mapOf(
                    "status" to "cancelled",
                    "causeType" to cancelled.javaClass.name,
                    "message" to safeText(cancelled.message.orEmpty(), 1_000),
                ),
            )
            throw cancelled
        } catch (error: Throwable) {
            diagnostics.mark(
                name = "SOURCE_ACTION_FAILED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.ERROR,
                sourceId = descriptor.id,
                traceId = operationId,
                durationMs = elapsedSince(startedAt),
                attributes = base + operationAttributes(operationId, action, DiagnosticOperationState.FAILED) + mapOf(
                    "status" to "failed",
                    "code" to "SOURCE_ACTION_UNCAUGHT",
                    "errorCode" to "SOURCE_ACTION_UNCAUGHT",
                    "message" to safeText(error.message.orEmpty().ifBlank { error.javaClass.simpleName }, 2_000),
                    "error" to safeText(error.message.orEmpty().ifBlank { error.javaClass.simpleName }, 2_000),
                    "causeType" to error.javaClass.name,
                    "causeChain" to causeChain(error),
                ),
            )
            throw error
        }
    }

    private fun operationAttributes(
        operationId: String,
        action: String,
        state: DiagnosticOperationState,
    ): Map<String, String> = DiagnosticOperationContract.attributes(
        id = operationId,
        kind = "SOURCE_ACTION",
        flow = "source-action",
        state = state,
        stage = action,
    ) + mapOf("operation" to action)

    private fun baseAttributes(action: String): Map<String, String> = mapOf(
        "action" to action,
        "sourceDisplayName" to safeText(descriptor.displayName, 240),
        "implementationKind" to descriptor.implementationKind.name,
        "sourceHealth" to descriptor.health.name,
    )

    private fun successMetadata(value: Any?): Map<String, String> = when (value) {
        is Collection<*> -> mapOf("resultCount" to value.size.toString())
        null -> mapOf("resultPresent" to "false")
        else -> mapOf("resultPresent" to "true", "resultType" to value.javaClass.simpleName.take(120))
    }

    private fun safeLocatorMetadata(value: String): Map<String, String> = mapOf(
        "locatorPresent" to value.isNotBlank().toString(),
        "locatorLength" to value.length.toString(),
    )

    private fun causeChain(error: Throwable?): String {
        if (error == null) return ""
        val raw = generateSequence(error) { it.cause }
            .take(8)
            .joinToString(" <- ") { cause ->
                val message = cause.message?.trim().orEmpty()
                if (message.isBlank()) cause.javaClass.name else "${cause.javaClass.name}: $message"
            }
        return safeText(raw, 4_000)
    }

    private fun safeText(value: String, maxLength: Int): String =
        DiagnosticRedactor.redactLongText(value, maxLength).take(maxLength)

    private fun elapsedSince(startedAt: Long): Long =
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
}

internal fun StorySource.withDiagnostics(diagnostics: SourceDiagnosticRuntime?): StorySource = when {
    diagnostics == null -> this
    this is DiagnosticStorySource -> this
    else -> DiagnosticStorySource(this, diagnostics)
}
