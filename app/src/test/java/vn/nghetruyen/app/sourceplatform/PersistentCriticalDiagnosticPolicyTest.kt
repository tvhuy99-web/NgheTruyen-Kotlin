package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

class PersistentCriticalDiagnosticPolicyTest {
    @Test
    fun keepsInstallAndPackageFailuresButNotOrdinaryNoise() {
        assertTrue(PersistentCriticalDiagnosticPolicy.shouldPersist(event(
            name = "SOURCE_EXTENSION_INSTALL_FAILED",
            category = DiagnosticCategory.PACKAGE,
            severity = DiagnosticSeverity.ERROR,
        )))
        assertTrue(PersistentCriticalDiagnosticPolicy.shouldPersist(event(
            name = "REPOSITORY_CACHE_REJECTED",
            category = DiagnosticCategory.STORE,
            severity = DiagnosticSeverity.WARN,
        )))
        assertFalse(PersistentCriticalDiagnosticPolicy.shouldPersist(event(
            name = "SOURCE_ACTION_COMPLETED",
            category = DiagnosticCategory.RUNTIME,
            severity = DiagnosticSeverity.INFO,
        )))
        assertFalse(PersistentCriticalDiagnosticPolicy.shouldPersist(event(
            name = "SOURCE_EXTENSION_FORMAT_PROBE_REJECTED",
            category = DiagnosticCategory.PACKAGE,
            severity = DiagnosticSeverity.INFO,
        )))
        assertFalse(PersistentCriticalDiagnosticPolicy.shouldPersist(event(
            name = "BROWSER_CONSOLE",
            category = DiagnosticCategory.BROWSER,
            severity = DiagnosticSeverity.ERROR,
        )))
    }

    private fun event(name: String, category: DiagnosticCategory, severity: DiagnosticSeverity) = DiagnosticEvent(
        timestampEpochMs = 1,
        traceId = "trace",
        sourceId = "source",
        category = category,
        name = name,
        severity = severity,
    )
}
