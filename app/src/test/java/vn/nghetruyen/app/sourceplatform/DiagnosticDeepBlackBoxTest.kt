package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceStats
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticRecorderStats
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

class DiagnosticDeepBlackBoxTest {
    @Test
    fun screenBoundaryIsNotReconstructedAsActiveOperation() {
        val report = DiagnosticDeepBlackBox.analyze(
            events = listOf(
                DiagnosticEvent(
                    timestampEpochMs = 1_000L,
                    traceId = "app:screen",
                    sourceId = "app",
                    category = DiagnosticCategory.RUNTIME,
                    name = "DIAGNOSTIC_SCREEN_STARTED",
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf(
                        "screen" to "explore:sangtacviet:HOME:",
                        "previousScreen" to "explore:truyenfull:HOME:",
                    ),
                ),
            ),
            nowMs = 2_000L,
            recorderStats = DiagnosticRecorderStats(itemCount = 1, evictedEvents = 0L),
            evidenceStats = DiagnosticEvidenceStats(
                itemCount = 0,
                retainedBytes = 0L,
                evictedItems = 0L,
                truncatedItems = 0L,
            ),
        )

        assertEquals("[]", report.operationsJson.trim())
        assertTrue(report.flowsJson.contains("\"activeOperationCount\": 0"))
    }
}
