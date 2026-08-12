package vn.nghetruyen.source.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticRetentionStatsTest {
    @Test
    fun recorderReportsEvictedEvents() {
        val recorder = BoundedDiagnosticRecorder(maxEvents = 2, level = DiagnosticLevel.VERBOSE)
        repeat(3) { index ->
            recorder.emit(DiagnosticEvent(
                timestampEpochMs = index.toLong(),
                traceId = "trace",
                sourceId = "source",
                category = DiagnosticCategory.RUNTIME,
                name = "EVENT_$index",
            ))
        }
        assertEquals(2, recorder.stats().itemCount)
        assertEquals(1L, recorder.stats().evictedEvents)
    }

    @Test
    fun evidenceReportsTruncation() {
        val evidence = BoundedDiagnosticEvidenceRecorder(
            maxBytes = 64,
            maxItems = 4,
            maxItemBytes = 4,
        ).apply { enabled = true }
        evidence.capture(DiagnosticEvidence(
            timestampEpochMs = 1L,
            traceId = "trace",
            sourceId = "source",
            category = DiagnosticCategory.BROWSER,
            name = "large.txt",
            contentType = "text/plain",
            data = "12345678".toByteArray(),
        ))
        assertEquals(4L, evidence.stats().retainedBytes)
        assertEquals(1L, evidence.stats().truncatedItems)
    }
}
