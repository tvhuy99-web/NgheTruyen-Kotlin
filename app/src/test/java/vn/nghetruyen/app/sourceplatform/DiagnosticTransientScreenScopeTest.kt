package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

class DiagnosticTransientScreenScopeTest {
    @Test
    fun latestScreenKeyComesFromNewestStampedEvent() {
        val events = listOf(
            event(timestamp = 1L, screen = "personal:extensions_diagnostics"),
            event(timestamp = 2L, screen = "source-login:wattpad"),
        )

        assertEquals("source-login:wattpad", latestDiagnosticScreenKey(events))
    }

    @Test
    fun latestScreenKeyIsBlankWithoutRecordedEvents() {
        assertEquals("", latestDiagnosticScreenKey(emptyList()))
    }

    private fun event(timestamp: Long, screen: String) = DiagnosticEvent(
        timestampEpochMs = timestamp,
        traceId = "trace-$timestamp",
        sourceId = "app",
        category = DiagnosticCategory.RUNTIME,
        name = "DIAGNOSTIC_SCREEN_STARTED",
        severity = DiagnosticSeverity.INFO,
        attributes = mapOf("screen" to screen),
    )
}
