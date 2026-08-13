package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

class DiagnosticHumanFormatterTest {
    @Test
    fun failedSuffixDoesNotMasqueradeAsAi() {
        val report = DiagnosticHumanFormatter.formatEvents(
            events = listOf(
                event(
                    name = "BUILTIN_SOURCEPACK_BOOTSTRAP_FAILED",
                    category = DiagnosticCategory.STORE,
                    attributes = mapOf(
                        "asset" to "demo.ntsource",
                        "error" to "SOURCE_FIXTURE_FAILED: Tìm truyện mẫu=RUNTIME_INVALID_PROGRAM:RUNTIME_FAILED",
                    ),
                ),
            ),
            mode = "basic",
        )

        assertTrue(report.contains("Khởi tạo nguồn tích hợp"))
        assertTrue(report.contains("SOURCE_FIXTURE_FAILED"))
        assertTrue(report.contains("RUNTIME_INVALID_PROGRAM"))
        assertFalse(report.contains("AI / chuyển ngữ"))
        assertFalse(report.contains("nhà cung cấp, model"))
    }

    @Test
    fun realAiCodeStillUsesAiClassification() {
        val report = DiagnosticHumanFormatter.formatEvents(
            events = listOf(event("AI_TRANSLATION_FAILED", DiagnosticCategory.RUNTIME)),
            mode = "basic",
        )

        assertTrue(report.contains("AI / chuyển ngữ"))
        assertTrue(report.contains("nhà cung cấp, model"))
    }

    @Test
    fun structuredInstallStageWinsOverGenericLabel() {
        val report = DiagnosticHumanFormatter.formatEvents(
            events = listOf(
                event(
                    name = "SOURCE_EXTENSION_IMPORT_FAILED",
                    category = DiagnosticCategory.PACKAGE,
                    attributes = mapOf(
                        "code" to "PACKAGE_INVALID",
                        "installStage" to "native_lua_prepare_import",
                        "error" to "PACKAGE_EMPTY",
                    ),
                ),
            ),
            mode = "basic",
        )

        assertTrue(report.contains("Giai đoạn: native_lua_prepare_import"))
        assertTrue(report.contains("PACKAGE_EMPTY"))
    }

    @Test
    fun builtinConflictIsExplainedAsPreservationNotFailure() {
        val report = DiagnosticHumanFormatter.formatEvents(
            events = listOf(
                event(
                    name = "BUILTIN_LUA_SOURCE_CONFLICT_PRESERVED",
                    category = DiagnosticCategory.STORE,
                    severity = DiagnosticSeverity.WARN,
                    attributes = mapOf(
                        "expectedSha256" to "aaa",
                        "activeSha256" to "bbb",
                        "activeSigner" to "trusted-key",
                    ),
                ),
            ),
            mode = "basic",
        )

        assertTrue(report.contains("Đồng bộ nguồn tích hợp"))
        assertTrue(report.contains("chủ động giữ bản hiện tại"))
        assertTrue(report.contains("cảnh báo bảo toàn"))
        assertFalse(report.contains("Thao tác kết thúc bất thường"))
        assertFalse(report.contains("AI / chuyển ngữ"))
    }

    private fun event(
        name: String,
        category: DiagnosticCategory,
        severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        timestampEpochMs = 1L,
        traceId = "trace-1",
        sourceId = "builtin:test",
        category = category,
        name = name,
        severity = severity,
        attributes = attributes,
    )
}
