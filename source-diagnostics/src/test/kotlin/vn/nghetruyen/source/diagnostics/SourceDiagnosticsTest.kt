package vn.nghetruyen.source.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test fun diagnosticsOffRetainsNothing() {
        val recorder = BoundedDiagnosticRecorder(20, DiagnosticLevel.OFF)
        recorder.emit(DiagnosticEvent(1, "a", "source", category = DiagnosticCategory.RUNTIME, name = "normal", severity = DiagnosticSeverity.INFO))
        recorder.emit(DiagnosticEvent(2, "b", "source", category = DiagnosticCategory.RUNTIME, name = "runtime_warn", severity = DiagnosticSeverity.WARN))
        recorder.emit(DiagnosticEvent(3, "c", "source", category = DiagnosticCategory.PACKAGE, name = "install_warn", severity = DiagnosticSeverity.WARN))
        recorder.emit(DiagnosticEvent(4, "d", "source", category = DiagnosticCategory.RUNTIME, name = "fatal", severity = DiagnosticSeverity.ERROR))
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test fun diagnosticsOffCanMirrorOnlyToAnExplicitDurablePolicySink() {
        val mirrored = mutableListOf<DiagnosticEvent>()
        val recorder = BoundedDiagnosticRecorder(
            maxEvents = 20,
            level = DiagnosticLevel.OFF,
            alwaysMirror = DiagnosticSink(mirrored::add),
        )
        recorder.emit(DiagnosticEvent(
            1, "install", "source", category = DiagnosticCategory.PACKAGE,
            name = "SOURCE_EXTENSION_INSTALL_FAILED", severity = DiagnosticSeverity.ERROR,
            attributes = mapOf("password" to "must-not-leak"),
        ))

        assertTrue(recorder.snapshot().isEmpty())
        assertEquals(1, mirrored.size)
        assertTrue(mirrored.single().attributes.getValue("password").startsWith("<redacted:"))
    }

    @Test fun explicitOperationContractRoundTripsWithoutEventNameGuessing() {
        val event = DiagnosticEvent(
            1, "trace", "source", category = DiagnosticCategory.RUNTIME, name = "ARBITRARY_NAME",
            attributes = DiagnosticOperationContract.attributes(
                id = "operation-1",
                kind = "SEARCH",
                flow = "native",
                state = DiagnosticOperationState.TIMEOUT,
                stage = "WAIT_SELECTOR",
                timeoutMs = 5_000,
                deadlineEpochMs = 6_000,
            ),
        )
        assertEquals("operation-1", DiagnosticOperationContract.id(event))
        assertEquals(DiagnosticOperationState.TIMEOUT, DiagnosticOperationContract.state(event))
    }

    @Test fun basicDropsDebugWhileVerboseKeepsIt() {
        val basic = BoundedDiagnosticRecorder(20, DiagnosticLevel.BASIC)
        val verbose = BoundedDiagnosticRecorder(20, DiagnosticLevel.VERBOSE)
        val debug = DiagnosticEvent(1, "a", "source", category = DiagnosticCategory.RUNTIME, name = "debug", severity = DiagnosticSeverity.DEBUG)
        basic.emit(debug)
        verbose.emit(debug)
        assertTrue(basic.snapshot().isEmpty())
        assertTrue(verbose.snapshot().single().name == "debug")
    }
}
