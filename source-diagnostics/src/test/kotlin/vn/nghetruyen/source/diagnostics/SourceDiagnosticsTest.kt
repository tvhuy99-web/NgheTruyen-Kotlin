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
}
