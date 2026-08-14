package vn.nghetruyen.source.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiagnosticRotationTest {
    @Test
    fun screenRotationNeverCarriesOldTraceIntoNewTimeline() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(operationEvent(1, "active", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(stageEvent(2, "active", "BROWSER_PAGE_STARTED"))
        recorder.emit(operationEvent(3, "done", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(operationEvent(4, "done", "SOURCE_ACTION_COMPLETED", DiagnosticOperationState.COMPLETED))

        val carried = recorder.retainActiveOperationTraces()

        assertTrue(carried.isEmpty())
        assertTrue(recorder.snapshot().isEmpty())
        assertEquals(1L, recorder.currentScreenGeneration())
    }

    @Test
    fun lateCallbackFromOldScreenIsMirroredButNeverStoredOnNewScreen() {
        val mirrored = mutableListOf<DiagnosticEvent>()
        val recorder = BoundedDiagnosticRecorder(
            maxEvents = 50,
            level = DiagnosticLevel.VERBOSE,
            mirror = DiagnosticSink { mirrored += it },
        )
        recorder.emit(operationEvent(1, "old", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.retainActiveOperationTraces()

        recorder.emit(stageEvent(2, "old", "BROWSER_PAGE_FINISHED"))
        recorder.emit(operationEvent(3, "old", "SOURCE_ACTION_COMPLETED", DiagnosticOperationState.COMPLETED))

        assertTrue(recorder.snapshot().isEmpty())
        assertTrue(mirrored.any { it.name == "SOURCE_ACTION_COMPLETED" })
        assertTrue(mirrored.last().attributes["diagnosticScreenDisposition"] == "stale")
        assertEquals(2L, recorder.stats().staleScreenEventsDropped)
    }

    @Test
    fun currentScreenOperationIsStoredAfterOldCallbackWasDropped() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(operationEvent(1, "old", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.retainActiveOperationTraces()
        recorder.emit(operationEvent(2, "old", "SOURCE_ACTION_COMPLETED", DiagnosticOperationState.COMPLETED))

        recorder.emit(operationEvent(3, "new", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(stageEvent(4, "new", "BROWSER_PAGE_STARTED"))

        assertEquals(listOf("SOURCE_ACTION_STARTED", "BROWSER_PAGE_STARTED"), recorder.snapshot().map { it.name })
        assertTrue(recorder.snapshot().all { it.attributes["diagnosticScreenDisposition"] == "current" })
    }

    @Test
    fun legacyPackageVerifiedCallbackKeepsOldOriginAndSameTraceCanStartFreshLater() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(DiagnosticEvent(1, "package-trace", "source", category = DiagnosticCategory.PACKAGE, name = "PACKAGE_VERIFY_STARTED"))
        recorder.retainActiveOperationTraces()

        recorder.emit(DiagnosticEvent(2, "package-trace", "source", category = DiagnosticCategory.TRUST, name = "PACKAGE_VERIFIED"))
        assertTrue(recorder.snapshot().isEmpty())
        assertEquals(1L, recorder.stats().staleScreenEventsDropped)

        recorder.emit(DiagnosticEvent(3, "package-trace", "source", category = DiagnosticCategory.PACKAGE, name = "PACKAGE_VERIFY_STARTED"))
        assertEquals(listOf("PACKAGE_VERIFY_STARTED"), recorder.snapshot().map { it.name })
        assertEquals("current", recorder.snapshot().single().attributes["diagnosticScreenDisposition"])
    }

    @Test
    fun diagnosticScreenStartedMarkerIsNeverInheritedFromPreviousGeneration() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(DiagnosticEvent(
            timestampEpochMs = 1,
            traceId = "screen-marker-a",
            sourceId = "app",
            category = DiagnosticCategory.RUNTIME,
            name = "DIAGNOSTIC_SCREEN_STARTED",
        ))

        recorder.retainActiveOperationTraces()
        recorder.emit(DiagnosticEvent(
            timestampEpochMs = 2,
            traceId = "screen-marker-b",
            sourceId = "app",
            category = DiagnosticCategory.RUNTIME,
            name = "DIAGNOSTIC_SCREEN_STARTED",
        ))

        assertEquals(1, recorder.snapshot().size)
        assertEquals("screen-marker-b", recorder.snapshot().single().traceId)
    }

    @Test
    fun evidenceFromOldTraceIsDroppedAfterScreenRotation() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        val evidence = BoundedDiagnosticEvidenceRecorder(
            maxBytes = 1024,
            maxItems = 10,
            maxItemBytes = 512,
        ).apply { enabled = true }
        val scopedEvidence = ScreenScopedDiagnosticEvidenceSink(recorder, evidence)

        recorder.emit(operationEvent(1, "old", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        scopedEvidence.capture(evidence(1, "old", "old-before.html", "before"))
        assertEquals(1, evidence.snapshot().size)

        recorder.retainActiveOperationTraces()
        evidence.retainTraces(emptySet())
        scopedEvidence.capture(evidence(2, "old", "old-late.html", "must-not-leak"))

        recorder.emit(operationEvent(3, "new", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        scopedEvidence.capture(evidence(3, "new", "new.html", "keep"))

        val retained = evidence.snapshot()
        assertEquals(1, retained.size)
        assertEquals("new", retained.single().traceId)
        assertEquals("keep", retained.single().data.toString(Charsets.UTF_8))
        assertEquals(1L, evidence.stats().staleScreenItemsDropped)
        assertFalse(retained.any { it.traceId == "old" })
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
            id = "operation:$traceId",
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
