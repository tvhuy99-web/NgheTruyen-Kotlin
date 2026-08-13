package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

class VBookDiagnosticLogParserTest {
    @Test
    fun nativeRequestIsStructuredAndStartsAnExplicitSubOperation() {
        val parsed = VBookDiagnosticLogParser.parse(
            listOf("NATIVE_V2", "REQUEST", "chapter-7", "browser", "POST", "https://example.test/chapter/7"),
            DiagnosticSeverity.INFO,
            traceId = "trace-1",
            action = SourceActionName.CHAPTER,
        )

        assertEquals("NATIVE_V2_REQUEST", parsed.name)
        assertEquals("chapter-7", parsed.attributes["requestId"])
        assertEquals("browser", parsed.attributes["transport"])
        assertEquals("POST", parsed.attributes["method"])
        assertEquals("https://example.test/chapter/7", parsed.attributes["url"])
        assertEquals(DiagnosticOperationState.STARTED.name, parsed.attributes["operationState"])
        assertEquals("native-request:trace-1:chapter-7", parsed.attributes["operationId"])
    }

    @Test
    fun nativeResponseCompletesTheSameSubOperationAndPreservesKeyValueFields() {
        val parsed = VBookDiagnosticLogParser.parse(
            listOf("NATIVE_V2", "RESPONSE", "chapter-7", "status=200", "url=https://example.test/final"),
            DiagnosticSeverity.INFO,
            traceId = "trace-1",
            action = SourceActionName.CHAPTER,
        )

        assertEquals("200", parsed.attributes["status"])
        assertEquals("https://example.test/final", parsed.attributes["url"])
        assertEquals(DiagnosticOperationState.COMPLETED.name, parsed.attributes["operationState"])
        assertEquals("native-request:trace-1:chapter-7", parsed.attributes["operationId"])
    }

    @Test
    fun semanticWarningsAreNotHiddenAsInformationalVbookLogs() {
        val parsed = VBookDiagnosticLogParser.parse(
            listOf("NATIVE_V2", "WARNING", "parse.items", "selector returned no rows", ".story-card"),
            DiagnosticSeverity.INFO,
            traceId = "trace-2",
            action = SourceActionName.SEARCH,
        )

        assertEquals(DiagnosticSeverity.WARN, parsed.severity)
        assertEquals("parse.items", parsed.attributes["warningStage"])
        assertTrue(parsed.attributes.getValue("message").contains("selector returned no rows"))
    }

    @Test
    fun nativeActionLifecycleHasItsOwnOperationInsteadOfClosingTheOuterVbookAction() {
        val started = VBookDiagnosticLogParser.parse(
            listOf("NATIVE_V2", "ACTION_START", "search", "input=abc", "page="),
            DiagnosticSeverity.INFO,
            traceId = "trace-3",
            action = SourceActionName.SEARCH,
        )
        val completed = VBookDiagnosticLogParser.parse(
            listOf("NATIVE_V2", "ACTION_DONE", "search", "items=20", "next=cursor"),
            DiagnosticSeverity.INFO,
            traceId = "trace-3",
            action = SourceActionName.SEARCH,
        )

        assertEquals("native-action:trace-3:search", started.attributes["operationId"])
        assertEquals(started.attributes["operationId"], completed.attributes["operationId"])
        assertEquals(DiagnosticOperationState.STARTED.name, started.attributes["operationState"])
        assertEquals(DiagnosticOperationState.COMPLETED.name, completed.attributes["operationState"])
    }

    @Test
    fun hostileLogCallsAreBoundedAndReportExactlyWhatWasDropped() {
        val parsed = VBookDiagnosticLogParser.parse(
            listOf("NATIVE_V2", "WARNING") + List(100) { "argument-$it" },
            DiagnosticSeverity.INFO,
            traceId = "trace-4",
            action = SourceActionName.SEARCH,
        )

        assertEquals("102", parsed.attributes["logArgumentCount"])
        assertEquals("26", parsed.attributes["logArgumentsCaptured"])
        assertEquals("76", parsed.attributes["logArgumentsDropped"])
        assertTrue(parsed.attributes.getValue("message").length <= 32_000)
    }
}
