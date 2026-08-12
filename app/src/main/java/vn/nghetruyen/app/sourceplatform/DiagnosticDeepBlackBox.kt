package vn.nghetruyen.app.sourceplatform

import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceStats
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticRecorderStats
import vn.nghetruyen.source.diagnostics.DiagnosticRedactor
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.time.Instant
import java.util.Locale

data class DiagnosticDeepBlackBoxReport(
    val operationsJson: String,
    val flowsJson: String,
    val browserSessionsJson: String,
    val dataLossJson: String,
    val flowLogs: Map<String, String>,
)

/**
 * Forensic reconstruction inspired by the Lua/XPK advanced black box.
 *
 * The UI stays intentionally simple. This object turns the unified event stream into the richer
 * developer-facing views that Lua exposed internally: operation/deadline state, latest state per
 * flow, browser session counters/probes, and explicit data-loss accounting.
 */
object DiagnosticDeepBlackBox {
    fun analyze(
        events: List<DiagnosticEvent>,
        nowMs: Long,
        recorderStats: DiagnosticRecorderStats,
        evidenceStats: DiagnosticEvidenceStats,
    ): DiagnosticDeepBlackBoxReport {
        val sorted = events.sortedBy(DiagnosticEvent::timestampEpochMs)
        val operations = reconstructOperations(sorted, nowMs)
        return DiagnosticDeepBlackBoxReport(
            operationsJson = operationsJson(operations),
            flowsJson = flowsJson(sorted, operations),
            browserSessionsJson = browserSessionsJson(sorted),
            dataLossJson = dataLossJson(sorted, recorderStats, evidenceStats),
            flowLogs = flowLogs(sorted),
        )
    }

    private data class OperationState(
        val key: String,
        val traceId: String,
        val sourceId: String,
        val flow: String,
        var kind: String,
        var startedAt: Long,
        var lastAt: Long,
        var lastEvent: String,
        var stage: String,
        var detail: String,
        var requestId: String,
        var method: String,
        var timeoutMs: Long? = null,
        var deadlineEpochMs: Long? = null,
        var eventRemainingMs: Long? = null,
        var pollCount: Long? = null,
        var pollElapsedMs: Long? = null,
        var hadStart: Boolean = false,
        var terminal: Boolean = false,
        var errorCount: Int = 0,
        var warningCount: Int = 0,
        var eventCount: Int = 0,
    )

    private fun reconstructOperations(events: List<DiagnosticEvent>, nowMs: Long): List<OperationState> {
        val output = linkedMapOf<String, OperationState>()
        events.forEach { event ->
            val attrs = event.attributes
            val flow = flow(event)
            val explicitId = first(attrs, "operationId", "operation_id", "requestId", "request_id")
            val key = listOf(event.traceId.ifBlank { "no-trace" }, flow, explicitId.orEmpty()).joinToString("|")
            val current = output.getOrPut(key) {
                OperationState(
                    key = key,
                    traceId = event.traceId,
                    sourceId = event.sourceId,
                    flow = flow,
                    kind = first(attrs, "operation", "action", "method", "kind") ?: event.name,
                    startedAt = event.timestampEpochMs,
                    lastAt = event.timestampEpochMs,
                    lastEvent = event.name,
                    stage = first(attrs, "stage", "phase", "step") ?: event.name,
                    detail = detail(event),
                    requestId = explicitId ?: event.traceId,
                    method = first(attrs, "method", "action").orEmpty(),
                )
            }
            current.eventCount += 1
            current.lastAt = event.timestampEpochMs
            current.lastEvent = event.name
            current.kind = first(attrs, "operation", "action", "kind") ?: current.kind
            current.stage = first(attrs, "stage", "phase", "step") ?: event.name
            detail(event).takeIf(String::isNotBlank)?.let { current.detail = it }
            first(attrs, "requestId", "request_id", "operationId", "operation_id")?.let { current.requestId = it }
            first(attrs, "method", "action")?.let { current.method = it }
            long(attrs, "timeoutMs", "timeout_ms")?.let { current.timeoutMs = it }
            long(attrs, "deadlineEpochMs", "deadlineAt", "deadline_ms")?.let { current.deadlineEpochMs = it }
            long(attrs, "remainingMs", "deadlineRemainingMs", "pollRemainingMs")?.let { current.eventRemainingMs = it }
            long(attrs, "polls", "pollCount", "poll_count")?.let { current.pollCount = it }
            long(attrs, "elapsedMs", "pollElapsedMs", "poll_elapsed_ms")?.let { current.pollElapsedMs = it }
            if (isStart(event.name)) current.hadStart = true
            if (isTerminal(event.name)) current.terminal = true
            if (event.severity == DiagnosticSeverity.ERROR) current.errorCount += 1
            if (event.severity == DiagnosticSeverity.WARN) current.warningCount += 1
        }
        output.values.forEach { state ->
            if (state.deadlineEpochMs == null && state.timeoutMs != null) {
                state.deadlineEpochMs = state.startedAt + state.timeoutMs!!
            }
            if (!state.terminal && state.deadlineEpochMs != null) {
                state.eventRemainingMs = (state.deadlineEpochMs!! - nowMs).coerceAtLeast(0L)
            }
        }
        return output.values.sortedBy { it.startedAt }
    }

    private fun operationsJson(items: List<OperationState>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("operationKey", item.key)
                put("traceId", item.traceId)
                put("sourceId", item.sourceId)
                put("flow", item.flow)
                put("kind", item.kind)
                put("requestId", item.requestId)
                put("method", item.method)
                put("startedAtEpochMs", item.startedAt)
                put("lastEventAtEpochMs", item.lastAt)
                put("elapsedMs", (item.lastAt - item.startedAt).coerceAtLeast(0L))
                put("timeoutMs", item.timeoutMs ?: JSONObject.NULL)
                put("deadlineEpochMs", item.deadlineEpochMs ?: JSONObject.NULL)
                put("remainingMs", item.eventRemainingMs ?: JSONObject.NULL)
                put("stage", item.stage)
                put("detail", item.detail)
                put("lastEvent", item.lastEvent)
                put("pollCount", item.pollCount ?: JSONObject.NULL)
                put("pollElapsedMs", item.pollElapsedMs ?: JSONObject.NULL)
                put("eventCount", item.eventCount)
                put("errorCount", item.errorCount)
                put("warningCount", item.warningCount)
                put("active", item.hadStart && !item.terminal)
                put("terminal", item.terminal)
            })
        }
    }.toString(2)

    private fun flowsJson(events: List<DiagnosticEvent>, operations: List<OperationState>): String {
        val root = JSONObject()
        events.groupBy(::flow).toSortedMap().forEach { (flow, flowEvents) ->
            val sorted = flowEvents.sortedBy(DiagnosticEvent::timestampEpochMs)
            val last = sorted.last()
            val relatedOps = operations.filter { it.flow == flow }
            root.put(flow, JSONObject().apply {
                put("eventCount", sorted.size)
                put("errorCount", sorted.count { it.severity == DiagnosticSeverity.ERROR })
                put("warningCount", sorted.count { it.severity == DiagnosticSeverity.WARN })
                put("traceCount", sorted.map(DiagnosticEvent::traceId).filter(String::isNotBlank).toSet().size)
                put("startedAtEpochMs", sorted.first().timestampEpochMs)
                put("lastEventAtEpochMs", last.timestampEpochMs)
                put("latestStage", first(last.attributes, "stage", "phase", "step") ?: last.name)
                put("latestDetail", detail(last))
                put("latestTraceId", last.traceId)
                put("latestSourceId", last.sourceId)
                put("activeOperationCount", relatedOps.count { it.hadStart && !it.terminal })
            })
        }
        return root.toString(2)
    }

    private fun browserSessionsJson(events: List<DiagnosticEvent>): String = JSONArray().apply {
        events.filter { flow(it) == "browser" }
            .groupBy { "${it.sourceId}|${it.traceId.ifBlank { "browser-session" }}" }
            .toSortedMap()
            .forEach { (_, sessionEvents) ->
                val sorted = sessionEvents.sortedBy(DiagnosticEvent::timestampEpochMs)
                val last = sorted.last()
                val environment = sorted.lastOrNull { it.name == "BROWSER_ENVIRONMENT_SNAPSHOT" }?.attributes.orEmpty()
                val lastError = sorted.lastOrNull {
                    it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.WARN
                }
                put(JSONObject().apply {
                    put("traceId", last.traceId)
                    put("sourceId", last.sourceId)
                    put("sessionId", latest(sorted, "sessionId"))
                    put("navigationGeneration", latest(sorted, "navigationGeneration"))
                    put("startedAtEpochMs", sorted.first().timestampEpochMs)
                    put("lastEventAtEpochMs", last.timestampEpochMs)
                    put("currentUrl", latest(sorted, "url", "documentUrl", "pageUrl"))
                    put("title", latest(sorted, "title"))
                    put("readyState", latest(sorted, "readyState"))
                    put("progress", latest(sorted, "progress"))
                    put("pageStartedCount", sorted.count { it.name == "BROWSER_PAGE_STARTED" })
                    put("pageFinishedCount", sorted.count { it.name == "BROWSER_PAGE_FINISHED" })
                    put("requestCount", maxLong(sorted, "requests", "requestCount"))
                    put("mainFrameRequestCount", maxLong(sorted, "mainFrameRequests", "mainFrameRequestCount"))
                    put("blockedCount", sorted.count { it.name.contains("BLOCKED", true) } + maxLong(sorted, "blockedRequests").toInt())
                    put("lateCallbackCount", maxLong(sorted, "lateCallbacks") + sorted.count { it.attributes["late"].equals("true", true) })
                    put("fallbackCount", sorted.count { it.name.contains("FALLBACK", true) || it.attributes.containsKey("fallback") })
                    put("consoleCount", sorted.count { it.name.contains("CONSOLE", true) })
                    put("selectorProbeCount", sorted.count { it.name.contains("SELECTOR_PROBE", true) })
                    put("evaluationCount", sorted.count { it.name == "BROWSER_EVAL_STARTED" })
                    put("evaluationTimeoutCount", sorted.count { it.name == "BROWSER_EVAL_TIMEOUT" })
                    put("httpErrorCount", sorted.count { it.name == "BROWSER_HTTP_ERROR" })
                    put("dialogCount", sorted.count { it.name == "BROWSER_JS_DIALOG" })
                    put("progress", latest(sorted, "progress"))
                    put("rendererGone", sorted.any { it.name.contains("RENDERER_GONE", true) })
                    put("urlHistory", JSONArray(sorted.mapNotNull { it.attributes["url"] ?: it.attributes["currentUrl"] }.filter { it.isNotBlank() && it != "[redacted-url]" }.distinct().takeLast(50)))
                    put("lastError", lastError?.let(::detail).orEmpty())
                    put("environment", JSONObject(DiagnosticRedactor.redact(environment)))
                    val pageForensics = sorted.lastOrNull { it.name == "BROWSER_PAGE_FORENSICS" }?.attributes.orEmpty()
                    put("pageForensics", JSONObject(DiagnosticRedactor.redact(pageForensics)))
                })
            }
    }.toString(2)

    private fun dataLossJson(
        events: List<DiagnosticEvent>,
        recorderStats: DiagnosticRecorderStats,
        evidenceStats: DiagnosticEvidenceStats,
    ): String = JSONObject().apply {
        put("ramEventItems", recorderStats.itemCount)
        put("ramEventEvicted", recorderStats.evictedEvents)
        put("ramEvidenceItems", evidenceStats.itemCount)
        put("ramEvidenceBytes", evidenceStats.retainedBytes)
        put("ramEvidenceEvictedItems", evidenceStats.evictedItems)
        put("ramEvidenceTruncatedItems", evidenceStats.truncatedItems)
        put("eventsMarkedTruncated", events.count { event ->
            event.attributes.any { (key, value) ->
                key.contains("truncat", true) && value.equals("true", true)
            }
        })
        put("eventsReportingDroppedData", events.count { event ->
            event.attributes.keys.any { key -> key.contains("drop", true) || key.contains("evict", true) || key.contains("reject", true) }
        })
        put("lossVisible", recorderStats.evictedEvents > 0 || evidenceStats.evictedItems > 0 || evidenceStats.truncatedItems > 0)
    }.toString(2)

    private fun flowLogs(events: List<DiagnosticEvent>): Map<String, String> = events
        .groupBy(::flow)
        .mapValues { (_, flowEvents) ->
            flowEvents.sortedBy(DiagnosticEvent::timestampEpochMs).joinToString("\n") { event ->
                val attrs = DiagnosticRedactor.redact(event.attributes)
                    .toSortedMap()
                    .entries
                    .joinToString(" | ") { (key, value) -> "$key=${value.take(2_000)}" }
                buildString {
                    append(Instant.ofEpochMilli(event.timestampEpochMs))
                    append(" | ")
                    append(event.severity.name)
                    append(" | source=")
                    append(event.sourceId)
                    append(" | trace=")
                    append(event.traceId)
                    append(" | ")
                    append(event.name)
                    if (attrs.isNotBlank()) append(" | $attrs")
                }
            }
        }
        .toSortedMap()

    private fun flow(event: DiagnosticEvent): String {
        first(event.attributes, "flow", "diagnosticFlow")?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let { return it }
        val name = event.name.uppercase(Locale.ROOT)
        return when {
            "BROWSER" in name || "WEBVIEW" in name || event.category == DiagnosticCategory.BROWSER -> "browser"
            "BRIDGE" in name -> "bridge"
            "NATIVE" in name -> "native"
            "EXECUTOR" in name || "SANDBOX" in name || name.startsWith("VBOOK_STAGE_") || "RESOURCE_LOADED" in name || name.startsWith("CHROMIUM_ACTION_") -> "executor"
            "NETWORK" in name || "HTTP" in name || "FETCH" in name || event.category == DiagnosticCategory.NETWORK -> "network"
            "DOWNLOAD" in name -> "download"
            "PREFETCH" in name -> "prefetch"
            "TTS" in name || "VOICE" in name -> "tts"
            name.startsWith("AI_") || "TRANSLAT" in name -> "ai"
            "BACKUP" in name || "RESTORE" in name -> "backup"
            "AUDIO_EXPORT" in name -> "audio"
            event.category == DiagnosticCategory.PARSER -> "parser"
            event.category == DiagnosticCategory.PACKAGE -> "package"
            event.category == DiagnosticCategory.TRUST -> "trust"
            event.category == DiagnosticCategory.SECURITY -> "security"
            event.category == DiagnosticCategory.STORE -> "store"
            event.category == DiagnosticCategory.REPLAY -> "replay"
            else -> "runtime"
        }
    }

    private fun detail(event: DiagnosticEvent): String = first(
        event.attributes,
        "detail", "message", "error", "reason", "status", "url", "selector", "result",
    ).orEmpty().take(4_000)

    private fun first(attributes: Map<String, String>, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        attributes.entries.firstOrNull { it.key.equals(key, true) }?.value?.takeIf(String::isNotBlank)
    }

    private fun long(attributes: Map<String, String>, vararg keys: String): Long? =
        first(attributes, *keys)?.toLongOrNull()

    private fun latest(events: List<DiagnosticEvent>, vararg keys: String): String =
        events.asReversed().firstNotNullOfOrNull { event -> first(event.attributes, *keys) }.orEmpty()

    private fun maxLong(events: List<DiagnosticEvent>, vararg keys: String): Long =
        events.mapNotNull { event -> long(event.attributes, *keys) }.maxOrNull() ?: 0L

    private fun isStart(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return upper.endsWith("_START") || upper.endsWith("_STARTED")
    }

    private fun isTerminal(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return listOf("_COMPLETED", "_FAILED", "_ERROR", "_DONE", "_CANCELLED", "_STOPPED", "_TIMEOUT").any(upper::endsWith)
    }
}
