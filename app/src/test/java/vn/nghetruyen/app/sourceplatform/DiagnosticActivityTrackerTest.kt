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

    @Test
    fun packageVerifiedClosesPackageVerifyAcrossCategoryChange() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("PACKAGE_VERIFY_STARTED", "package-verify", DiagnosticCategory.PACKAGE))
        assertEquals(1, tracker.snapshot().size)

        tracker.emit(event("PACKAGE_VERIFIED", "package-verify", DiagnosticCategory.TRUST))

        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun timeoutSuffixClosesLegacyOperation() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("BROWSER_WAIT_STARTED", "browser-wait", DiagnosticCategory.BROWSER))
        tracker.emit(event("BROWSER_WAIT_TIMEOUT", "browser-wait", DiagnosticCategory.BROWSER))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun okSuffixClosesChromiumMicroStageOperation() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("CHROMIUM_DECODE_JSON_START", "decode-json", generation = 7L))
        assertEquals(1, tracker.snapshot(7L).size)
        tracker.emit(event("CHROMIUM_DECODE_JSON_OK", "decode-json", generation = 7L))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun activeSnapshotCanBeScopedToCurrentScreenGeneration() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("CHROMIUM_PROCESS_DATA_START", "old-process", generation = 8L))
        tracker.emit(event("SOURCE_CHECK_STARTED", "current-check", generation = 9L))

        val current = tracker.snapshot(9L)
        assertEquals(1, current.size)
        assertEquals("current-check", current.single().traceId)
        assertEquals(9L, current.single().screenGeneration)
        assertEquals(2, tracker.snapshot().size)
    }

    private fun event(
        name: String,
        traceId: String,
        category: DiagnosticCategory = DiagnosticCategory.RUNTIME,
        generation: Long? = null,
    ) = DiagnosticEvent(
        timestampEpochMs = 1L,
        traceId = traceId,
        sourceId = "source:test",
        category = category,
        name = name,
        severity = DiagnosticSeverity.INFO,
        attributes = generation?.let { mapOf("diagnosticScreenGeneration" to it.toString()) }.orEmpty(),
    )
}
