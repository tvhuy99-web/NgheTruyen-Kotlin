package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.sources.currentDiagnosticCausalTraceId

// Shared regression contract for every source-backed navigation boundary.
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
