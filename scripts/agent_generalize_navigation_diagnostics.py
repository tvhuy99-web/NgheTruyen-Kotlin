from pathlib import Path
import re

root = Path('.')
vm_path = root / 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt'
helper_path = root / 'app/src/main/java/vn/nghetruyen/app/sourceplatform/CausalNavigationDiagnostics.kt'
test_path = root / 'app/src/test/java/vn/nghetruyen/app/sourceplatform/CausalNavigationDiagnosticsTest.kt'
vm = vm_path.read_text()

import_anchor = 'import vn.nghetruyen.app.sourceplatform.SourceDiagnosticUi\n'
new_import = 'import vn.nghetruyen.app.sourceplatform.navigateWithCausalHandoff\n'
assert import_anchor in vm
if new_import not in vm:
    vm = vm.replace(import_anchor, import_anchor + new_import, 1)

story_old = '''            val storyLoadStartedAt = System.currentTimeMillis()
            val storyOriginGeneration = container.sourceDiagnostics.recorder.currentScreenGeneration()
            val storyDiagnosticTraceId = "story-open:${UUID.randomUUID()}"
            val result = withContext(DiagnosticCausalTrace(storyDiagnosticTraceId)) {
                source.story(story.url.ifBlank { story.id })
            }
            when (result) {
                is AppResult.Success -> {
                    val destinationStoryKey = "story:${result.value.story.id}"
                    container.sourceDiagnostics.onScreenChanged(
                        destinationStoryKey,
                        handoffTraceIds = setOf(storyDiagnosticTraceId),
                    )
                    container.sourceDiagnostics.mark(
                        name = "STORY_SCREEN_READY",
                        category = vn.nghetruyen.source.diagnostics.DiagnosticCategory.RUNTIME,
                        severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
                        sourceId = result.value.story.sourceId,
                        traceId = storyDiagnosticTraceId,
                        durationMs = (System.currentTimeMillis() - storyLoadStartedAt).coerceAtLeast(0L),
                        attributes = mapOf(
                            "action" to "STORY",
                            "status" to "success",
                            "chapterCount" to result.value.chapters.size.toString(),
                            "hasNextChapterPage" to (!result.value.nextChapterPageUrl.isNullOrBlank()).toString(),
                            "originScreenGeneration" to storyOriginGeneration.toString(),
                            "diagnosticRootTraceId" to storyDiagnosticTraceId,
                            "handoff" to "selective-causal-source-action-to-story-screen",
                        ),
                    )
'''
story_new = '''            val result = container.sourceDiagnostics.navigateWithCausalHandoff(
                traceKind = "story-open",
                action = "STORY",
                readyEventName = "STORY_SCREEN_READY",
                destinationScreenKey = { detail -> "story:${detail.story.id}" },
                sourceId = { detail -> detail.story.sourceId },
                readyAttributes = { detail ->
                    mapOf(
                        "chapterCount" to detail.chapters.size.toString(),
                        "hasNextChapterPage" to (!detail.nextChapterPageUrl.isNullOrBlank()).toString(),
                    )
                },
            ) {
                source.story(story.url.ifBlank { story.id })
            }
            when (result) {
                is AppResult.Success -> {
'''
assert vm.count(story_old) == 1, f'story block match count={vm.count(story_old)}'
vm = vm.replace(story_old, story_new, 1)

chapter_pattern = re.compile(r'''            val content: AppResult<ChapterContent> = when \{\n                cached != null -> AppResult\.Success\(cached\)\n                sourceId == "offline" -> AppResult\.Failure\(\n                    "NOT_FOUND",\n                    "Không tìm thấy nội dung chương ngoại tuyến\."\,\n                \)\n                else -> \{\n                    val source = container\.sourceRegistry\.get\(sourceId\)\n                    source\?\.chapter\(chapter\.url\.ifBlank \{ chapter\.id \}\)\n                        \?: AppResult\.Failure\("NO_SOURCE", "Không tìm thấy nguồn chương\."\)\n                \}\n            \}\n            when \(content\) \{\n                is AppResult\.Success -> \{\n                    val enriched = enrichNavigation\(ReaderDocumentNormalizer\.normalize\(content\.value\)\)\n                    if \(enriched\.paragraphs\.isEmpty\(\)\) \{\n                        mutableState\.update \{\n                            it\.copy\(loading = false, message = "Chương không có nội dung có thể đọc\."\)\n                        \}\n                        return@launch\n                    \}\n''')
chapter_match = chapter_pattern.search(vm)
assert chapter_match, 'chapter block not found'
chapter_new = '''            val content = container.sourceDiagnostics.navigateWithCausalHandoff(
                traceKind = "chapter-open",
                action = "CONTENT",
                readyEventName = "READER_SCREEN_READY",
                destinationScreenKey = { value -> "reader:${value.chapter.id}" },
                sourceId = { _ -> sourceId.ifBlank { "offline" } },
                readyAttributes = { value ->
                    mapOf(
                        "chapterId" to value.chapter.id,
                        "storyId" to value.chapter.storyId,
                        "paragraphCount" to value.paragraphs.size.toString(),
                        "fromCache" to (cached != null).toString(),
                    )
                },
            ) {
                val rawContent: AppResult<ChapterContent> = when {
                    cached != null -> AppResult.Success(cached)
                    sourceId == "offline" -> AppResult.Failure(
                        "NOT_FOUND",
                        "Không tìm thấy nội dung chương ngoại tuyến.",
                    )
                    else -> {
                        val source = container.sourceRegistry.get(sourceId)
                        source?.chapter(chapter.url.ifBlank { chapter.id })
                            ?: AppResult.Failure("NO_SOURCE", "Không tìm thấy nguồn chương.")
                    }
                }
                when (rawContent) {
                    is AppResult.Success -> {
                        val normalized = enrichNavigation(ReaderDocumentNormalizer.normalize(rawContent.value))
                        if (normalized.paragraphs.isEmpty()) {
                            AppResult.Failure("EMPTY_CHAPTER_CONTENT", "Chương không có nội dung có thể đọc.")
                        } else {
                            AppResult.Success(normalized)
                        }
                    }
                    is AppResult.Failure -> rawContent
                }
            }
            when (content) {
                is AppResult.Success -> {
                    val enriched = content.value
'''
vm = vm[:chapter_match.start()] + chapter_new + vm[chapter_match.end():]

# The old direct Story-specific causal machinery must be gone from the navigation routes.
assert 'storyDiagnosticTraceId' not in vm
assert 'selective-causal-source-action-to-story-screen' not in vm
assert vm.count('navigateWithCausalHandoff(') >= 2
vm_path.write_text(vm)

helper_path.write_text(r'''package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.withContext
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.sources.DiagnosticCausalTrace
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.util.Locale
import java.util.UUID

internal data class CausalNavigationReadyEvent(
    val name: String,
    val action: String,
    val sourceId: String,
    val traceId: String,
    val navigationKind: String,
    val originScreenGeneration: Long,
    val durationMs: Long,
    val attributes: Map<String, String>,
)

internal interface CausalNavigationRuntime {
    fun currentScreenGeneration(): Long
    fun handoff(destinationScreenKey: String, traceId: String)
    fun emitReady(event: CausalNavigationReadyEvent)
}

/**
 * One navigation contract for every source-backed screen transition.
 *
 * The source action runs inside one causal CoroutineContext. A destination generation is created
 * only after the block returns Success, and only that root trace is handed off. Failures therefore
 * stay on the origin screen with their complete timeline/evidence, while unrelated origin events
 * can never leak into the destination.
 */
internal suspend fun <T> runCausalNavigation(
    runtime: CausalNavigationRuntime,
    traceKind: String,
    action: String,
    readyEventName: String,
    destinationScreenKey: (T) -> String,
    sourceId: (T) -> String,
    readyAttributes: (T) -> Map<String, String> = { emptyMap() },
    traceIdFactory: (String) -> String = { kind -> "$kind:${UUID.randomUUID()}" },
    clockMs: () -> Long = System::currentTimeMillis,
    block: suspend () -> AppResult<T>,
): AppResult<T> {
    val normalizedKind = traceKind.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .take(80)
        .ifBlank { "navigation" }
    val traceId = traceIdFactory(normalizedKind).trim().take(240).ifBlank {
        "$normalizedKind:${UUID.randomUUID()}"
    }
    val originGeneration = runtime.currentScreenGeneration()
    val startedAt = clockMs()
    val result = withContext(DiagnosticCausalTrace(traceId)) { block() }

    if (result is AppResult.Success) {
        val destination = destinationScreenKey(result.value).trim().take(500).ifBlank { "unknown" }
        runtime.handoff(destination, traceId)
        runtime.emitReady(
            CausalNavigationReadyEvent(
                name = readyEventName,
                action = action,
                sourceId = sourceId(result.value),
                traceId = traceId,
                navigationKind = normalizedKind,
                originScreenGeneration = originGeneration,
                durationMs = (clockMs() - startedAt).coerceAtLeast(0L),
                attributes = readyAttributes(result.value),
            ),
        )
    }
    return result
}

internal suspend fun <T> SourceDiagnosticRuntime.navigateWithCausalHandoff(
    traceKind: String,
    action: String,
    readyEventName: String,
    destinationScreenKey: (T) -> String,
    sourceId: (T) -> String,
    readyAttributes: (T) -> Map<String, String> = { emptyMap() },
    block: suspend () -> AppResult<T>,
): AppResult<T> = runCausalNavigation(
    runtime = object : CausalNavigationRuntime {
        override fun currentScreenGeneration(): Long = recorder.currentScreenGeneration()

        override fun handoff(destinationScreenKey: String, traceId: String) {
            onScreenChanged(destinationScreenKey, handoffTraceIds = setOf(traceId))
        }

        override fun emitReady(event: CausalNavigationReadyEvent) {
            mark(
                name = event.name,
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                sourceId = event.sourceId,
                traceId = event.traceId,
                durationMs = event.durationMs,
                attributes = mapOf(
                    "action" to event.action,
                    "status" to "success",
                    "originScreenGeneration" to event.originScreenGeneration.toString(),
                    "diagnosticRootTraceId" to event.traceId,
                    "navigationKind" to event.navigationKind,
                    "handoff" to "selective-causal-navigation",
                ) + event.attributes,
            )
        }
    },
    traceKind = traceKind,
    action = action,
    readyEventName = readyEventName,
    destinationScreenKey = destinationScreenKey,
    sourceId = sourceId,
    readyAttributes = readyAttributes,
    block = block,
)
''')

test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(r'''package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.sources.currentDiagnosticCausalTraceId

class CausalNavigationDiagnosticsTest {
    private class FakeRuntime(
        private val generation: Long = 41L,
    ) : CausalNavigationRuntime {
        val handoffs = mutableListOf<Pair<String, String>>()
        val ready = mutableListOf<CausalNavigationReadyEvent>()
        override fun currentScreenGeneration(): Long = generation
        override fun handoff(destinationScreenKey: String, traceId: String) {
            handoffs += destinationScreenKey to traceId
        }
        override fun emitReady(event: CausalNavigationReadyEvent) {
            ready += event
        }
    }

    @Test
    fun successKeepsOneTraceAcrossDispatcherAndHandsOffExactlyThatTrace() = runTest {
        val runtime = FakeRuntime()
        var observedTrace: String? = null
        val times = ArrayDeque(listOf(1_000L, 1_075L))

        val result = runCausalNavigation(
            runtime = runtime,
            traceKind = "chapter-open",
            action = "CONTENT",
            readyEventName = "READER_SCREEN_READY",
            destinationScreenKey = { value: String -> "reader:$value" },
            sourceId = { "sangtacviet" },
            readyAttributes = { mapOf("paragraphCount" to "321") },
            traceIdFactory = { "chapter-open:test-root" },
            clockMs = { times.removeFirst() },
        ) {
            observedTrace = withContext(Dispatchers.Default) { currentDiagnosticCausalTraceId() }
            AppResult.Success("chapter-7")
        }

        assertTrue(result is AppResult.Success)
        assertEquals("chapter-open:test-root", observedTrace)
        assertEquals(listOf("reader:chapter-7" to "chapter-open:test-root"), runtime.handoffs)
        assertEquals(1, runtime.ready.size)
        assertEquals("chapter-open:test-root", runtime.ready.single().traceId)
        assertEquals("chapter-open", runtime.ready.single().navigationKind)
        assertEquals(41L, runtime.ready.single().originScreenGeneration)
        assertEquals(75L, runtime.ready.single().durationMs)
        assertEquals("321", runtime.ready.single().attributes["paragraphCount"])
    }

    @Test
    fun failureStaysOnOriginAndNeverCreatesDestinationBoundary() = runTest {
        val runtime = FakeRuntime()
        var observedTrace: String? = null
        val result = runCausalNavigation(
            runtime = runtime,
            traceKind = "chapter-open",
            action = "CONTENT",
            readyEventName = "READER_SCREEN_READY",
            destinationScreenKey = { _: String -> "reader:should-not-exist" },
            sourceId = { "sangtacviet" },
            traceIdFactory = { "chapter-open:failed-root" },
        ) {
            observedTrace = currentDiagnosticCausalTraceId()
            AppResult.Failure("HTTP_500", "failed")
        }

        assertTrue(result is AppResult.Failure)
        assertEquals("chapter-open:failed-root", observedTrace)
        assertTrue(runtime.handoffs.isEmpty())
        assertTrue(runtime.ready.isEmpty())
        assertNull(currentDiagnosticCausalTraceId())
    }

    @Test
    fun storyAndReaderUseTheSameNavigationContractNotRouteSpecificRetention() = runTest {
        val runtime = FakeRuntime(9L)
        val traces = ArrayDeque(listOf("story-open:root", "chapter-open:root"))

        runCausalNavigation(
            runtime = runtime,
            traceKind = "story-open",
            action = "STORY",
            readyEventName = "STORY_SCREEN_READY",
            destinationScreenKey = { id: String -> "story:$id" },
            sourceId = { "sangtacviet" },
            traceIdFactory = { traces.removeFirst() },
        ) { AppResult.Success("story-1") }

        runCausalNavigation(
            runtime = runtime,
            traceKind = "chapter-open",
            action = "CONTENT",
            readyEventName = "READER_SCREEN_READY",
            destinationScreenKey = { id: String -> "reader:$id" },
            sourceId = { "sangtacviet" },
            traceIdFactory = { traces.removeFirst() },
        ) { AppResult.Success("chapter-1") }

        assertEquals(
            listOf(
                "story:story-1" to "story-open:root",
                "reader:chapter-1" to "chapter-open:root",
            ),
            runtime.handoffs,
        )
        assertEquals(listOf("STORY_SCREEN_READY", "READER_SCREEN_READY"), runtime.ready.map { it.name })
    }
}
''')

# Static wiring guard: both source-backed navigation routes must use the same coordinator.
open_story_start = vm.index('    private fun openRemoteStory(')
open_story_end = vm.index('    fun openLibraryStory(', open_story_start)
open_chapter_start = vm.index('    private fun openChapterAt(')
open_chapter_end = vm.index('    fun loadMoreChapters()', open_chapter_start)
assert 'navigateWithCausalHandoff(' in vm[open_story_start:open_story_end]
assert 'navigateWithCausalHandoff(' in vm[open_chapter_start:open_chapter_end]
assert 'source?.chapter(' in vm[open_chapter_start:open_chapter_end]
print('generic navigation diagnostics patch applied')
