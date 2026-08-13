package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

class DiagnosticActivityTrackerTest {
    @Test
    fun screenStartedMarkerIsNotAnActiveOperation() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("DIAGNOSTIC_SCREEN_STARTED", "screen-trace"))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun realOperationStillTracksAndCompletes() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("SOURCE_CHECK_STARTED", "source-check"))
        assertEquals(1, tracker.snapshot().size)
        tracker.emit(event("SOURCE_CHECK_COMPLETED", "source-check"))
        assertTrue(tracker.snapshot().isEmpty())
    }

    private fun event(name: String, traceId: String) = DiagnosticEvent(
        timestampEpochMs = 1L,
        traceId = traceId,
        sourceId = "source:test",
        category = DiagnosticCategory.RUNTIME,
        name = name,
        severity = DiagnosticSeverity.INFO,
        attributes = emptyMap(),
    )
}
