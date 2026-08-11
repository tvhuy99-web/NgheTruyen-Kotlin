package vn.nghetruyen.source.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiagnosticsTest {
    @Test fun redactsSecretsAndBoundsEvents() {
        val recorder = BoundedDiagnosticRecorder(2, DiagnosticLevel.VERBOSE)
        repeat(3) { index -> recorder.emit(DiagnosticEvent(index.toLong(), "t", "s", category = DiagnosticCategory.NETWORK, name = "request", attributes = mapOf("Authorization" to "Bearer secret-$index"))) }
        val events = recorder.snapshot()
        assertTrue(events.size == 2)
        assertTrue(events.all { it.attributes["Authorization"]!!.startsWith("<redacted:") })
        assertFalse(DiagnosticJsonExporter.export(events).toString(Charsets.UTF_8).contains("secret-"))
    }

    @Test fun diagnosticsOffRetainsOnlyCriticalBreadcrumbs() {
        val recorder = BoundedDiagnosticRecorder(20, DiagnosticLevel.OFF)
        recorder.emit(DiagnosticEvent(1, "a", "source", category = DiagnosticCategory.RUNTIME, name = "normal", severity = DiagnosticSeverity.INFO))
        recorder.emit(DiagnosticEvent(2, "b", "source", category = DiagnosticCategory.RUNTIME, name = "runtime_warn", severity = DiagnosticSeverity.WARN))
        recorder.emit(DiagnosticEvent(3, "c", "source", category = DiagnosticCategory.PACKAGE, name = "install_warn", severity = DiagnosticSeverity.WARN))
        recorder.emit(DiagnosticEvent(4, "d", "source", category = DiagnosticCategory.RUNTIME, name = "fatal", severity = DiagnosticSeverity.ERROR))
        val names = recorder.snapshot().map(DiagnosticEvent::name)
        assertTrue(names == listOf("install_warn", "fatal"))
    }
}
