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

    @Test
    fun packageVerifiedClosesVerificationOperation() {
        val events = listOf(
            DiagnosticEvent(
                timestampEpochMs = 1_000L,
                traceId = "package:verify:stv",
                sourceId = "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50",
                category = DiagnosticCategory.PACKAGE,
                name = "PACKAGE_VERIFY_STARTED",
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf(
                    "operationId" to "package-verify:stv",
                    "operation" to "PACKAGE_VERIFY",
                ),
            ),
            DiagnosticEvent(
                timestampEpochMs = 1_050L,
                traceId = "package:verify:stv",
                sourceId = "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50",
                category = DiagnosticCategory.PACKAGE,
                name = "PACKAGE_VERIFIED",
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf(
                    "operationId" to "package-verify:stv",
                    "operation" to "PACKAGE_VERIFY",
                ),
            ),
        )
        val report = DiagnosticDeepBlackBox.analyze(
            events = events,
            nowMs = 2_000L,
            recorderStats = DiagnosticRecorderStats(itemCount = events.size, evictedEvents = 0L),
            evidenceStats = DiagnosticEvidenceStats(
                itemCount = 0,
                retainedBytes = 0L,
                evictedItems = 0L,
                truncatedItems = 0L,
            ),
        )

        assertTrue(report.operationsJson.contains("\"lastEvent\": \"PACKAGE_VERIFIED\""))
        assertTrue(report.operationsJson.contains("\"active\": false"))
        assertTrue(report.operationsJson.contains("\"terminal\": true"))
        assertTrue(report.flowsJson.contains("\"activeOperationCount\": 0"))
    }
}
