package vn.nghetruyen.source.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiagnosticRotationTest {
    @Test
    fun activeOperationKeepsWholeTraceAcrossScreenRotation() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(operationEvent(1, "active", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(stageEvent(2, "active", "BROWSER_PAGE_STARTED"))
        recorder.emit(operationEvent(3, "done", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(operationEvent(4, "done", "SOURCE_ACTION_COMPLETED", DiagnosticOperationState.COMPLETED))

        val carried = recorder.retainActiveOperationTraces()
        val retained = recorder.snapshot()

        assertEquals(setOf("active"), carried)
        assertEquals(listOf("SOURCE_ACTION_STARTED", "BROWSER_PAGE_STARTED"), retained.map { it.name })
        assertTrue(retained.all { it.traceId == "active" })
    }

    @Test
    fun terminalOperationIsDiscardedAtNextScreenBoundary() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(operationEvent(1, "trace", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(stageEvent(2, "trace", "WAIT_SELECTOR"))
        recorder.emit(operationEvent(3, "trace", "SOURCE_ACTION_FAILED", DiagnosticOperationState.FAILED))

        val carried = recorder.retainActiveOperationTraces()

        assertTrue(carried.isEmpty())
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test
    fun diagnosticScreenStartedMarkerIsNeverTreatedAsActiveOperation() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(DiagnosticEvent(
            timestampEpochMs = 1,
            traceId = "screen-marker",
            sourceId = "app",
            category = DiagnosticCategory.RUNTIME,
            name = "DIAGNOSTIC_SCREEN_STARTED",
        ))

        val carried = recorder.retainActiveOperationTraces()

        assertTrue(carried.isEmpty())
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test
    fun legacyStartAndFailureAreReconstructedWithoutExplicitContract() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(DiagnosticEvent(1, "legacy", "source", category = DiagnosticCategory.RUNTIME, name = "FETCH_STARTED"))
        recorder.emit(DiagnosticEvent(2, "legacy", "source", category = DiagnosticCategory.NETWORK, name = "FETCH_STAGE"))

        assertEquals(setOf("legacy"), recorder.retainActiveOperationTraces())
        assertEquals(2, recorder.snapshot().size)

        recorder.emit(DiagnosticEvent(3, "legacy", "source", category = DiagnosticCategory.RUNTIME, name = "FETCH_FAILED"))
        assertTrue(recorder.retainActiveOperationTraces().isEmpty())
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test
    fun evidenceRotationFollowsOnlyCarriedTraceIds() {
        val evidence = BoundedDiagnosticEvidenceRecorder(
            maxBytes = 1024,
            maxItems = 10,
            maxItemBytes = 512,
        ).apply { enabled = true }
        evidence.capture(evidence(1, "active", "active.html", "keep"))
        evidence.capture(evidence(2, "old", "old.html", "drop"))

        evidence.retainTraces(setOf("active"))

        val retained = evidence.snapshot()
        assertEquals(1, retained.size)
        assertEquals("active", retained.single().traceId)
        assertEquals("keep", retained.single().data.toString(Charsets.UTF_8))
        assertEquals(4L, evidence.stats().retainedBytes)
        assertFalse(evidence.stats().itemCount == 0)
    }

    private fun operationEvent(
        time: Long,
        traceId: String,
        name: String,
        state: DiagnosticOperationState,
    ) = DiagnosticEvent(
        timestampEpochMs = time,
        traceId = traceId,
        sourceId = "source",
        category = DiagnosticCategory.RUNTIME,
        name = name,
        attributes = DiagnosticOperationContract.attributes(
            id = "operation:$traceId",
            kind = "HOME",
            flow = "runtime",
            state = state,
            stage = name,
        ),
    )

    private fun stageEvent(time: Long, traceId: String, name: String) = DiagnosticEvent(
        timestampEpochMs = time,
        traceId = traceId,
        sourceId = "source",
        category = DiagnosticCategory.BROWSER,
        name = name,
        attributes = DiagnosticOperationContract.attributes(
            id = "browser:$traceId",
            kind = "BROWSER",
            flow = "browser",
            state = DiagnosticOperationState.STAGE,
            stage = name,
        ),
    )

    private fun evidence(time: Long, traceId: String, name: String, body: String) = DiagnosticEvidence(
        timestampEpochMs = time,
        traceId = traceId,
        sourceId = "source",
        category = DiagnosticCategory.BROWSER,
        name = name,
        contentType = "text/html",
        data = body.toByteArray(),
    )
}
