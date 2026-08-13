package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.util.Locale

internal data class ParsedVBookDiagnosticLog(
    val name: String,
    val severity: DiagnosticSeverity,
    val attributes: Map<String, String>,
)

/** Turns Lua/Native Log.log arguments into searchable fields instead of one lossy text blob. */
internal object VBookDiagnosticLogParser {
    fun parse(
        rawArguments: List<String>,
        requestedSeverity: DiagnosticSeverity,
        traceId: String,
        action: SourceActionName,
    ): ParsedVBookDiagnosticLog {
        val arguments = rawArguments.take(MAX_ARGUMENTS + 2).map { it.take(MAX_ARGUMENT_CHARS) }
        val native = arguments.firstOrNull().equals("NATIVE_V2", ignoreCase = true)
        val typeIndex = if (native) 1 else 0
        val rawType = arguments.getOrNull(typeIndex).orEmpty().ifBlank { "MESSAGE" }
        val type = rawType.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "MESSAGE" }
        val values = arguments.drop(typeIndex + 1)
        val severity = semanticSeverity(type, requestedSeverity)
        val flow = if (native) "native" else "executor"
        val attributes = linkedMapOf<String, String>(
            "flow" to flow,
            "nativeRuntime" to if (native) "NATIVE_V2" else "VBOOK",
            "nativeEvent" to type,
            "message" to arguments.joinToString(" ").take(MAX_MESSAGE_CHARS),
            "logArgumentCount" to rawArguments.size.toString(),
            "logArgumentsCaptured" to arguments.size.toString(),
            "logArgumentsDropped" to (rawArguments.size - arguments.size).coerceAtLeast(0).toString(),
        )

        values.take(MAX_ARGUMENTS).forEachIndexed { index, value ->
            attributes["logArg${index + 1}"] = value
            val split = value.indexOf('=')
            if (split in 1 until value.lastIndex) {
                val key = value.substring(0, split)
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    .take(80)
                if (key.isNotBlank()) attributes[key] = value.substring(split + 1)
            }
        }

        val requestId = values.getOrNull(0).orEmpty().take(300)
        when (type) {
            "REQUEST" -> {
                attributes["requestId"] = requestId
                attributes["transport"] = values.getOrNull(1).orEmpty().take(80)
                attributes["method"] = values.getOrNull(2).orEmpty().take(40)
                attributes["url"] = values.getOrNull(3).orEmpty().take(MAX_ARGUMENT_CHARS)
            }
            "RETRY" -> {
                attributes["requestId"] = requestId
                attributes["attempt"] = namedValue(values.getOrNull(1), "attempt")
                attributes["status"] = namedValue(values.getOrNull(2), "status")
            }
            "RESPONSE" -> {
                attributes["requestId"] = requestId
                attributes["status"] = namedValue(values.getOrNull(1), "status")
                attributes["url"] = namedValue(values.getOrNull(2), "url")
            }
            "WARNING", "WARN" -> {
                attributes["warningStage"] = values.getOrNull(0).orEmpty().take(300)
                attributes["warningMessage"] = values.getOrNull(1).orEmpty().take(MAX_ARGUMENT_CHARS)
                attributes["warningDetail"] = values.getOrNull(2).orEmpty().take(MAX_ARGUMENT_CHARS)
            }
            "BROWSER_OPEN" -> attributes["sessionId"] = values.getOrNull(0).orEmpty().take(300)
            "BROWSER" -> {
                attributes["browserOperation"] = values.getOrNull(0).orEmpty().take(120)
                attributes["sessionId"] = values.getOrNull(1).orEmpty().take(300)
            }
            "BROWSER_CLOSE_ERROR" -> {
                attributes["sessionId"] = values.getOrNull(0).orEmpty().take(300)
                attributes["error"] = values.drop(1).joinToString(" ").take(MAX_MESSAGE_CHARS)
            }
            "ACTION_START", "ACTION_DONE", "ERROR" -> {
                attributes["nativeAction"] = values.getOrNull(0).orEmpty().take(160)
            }
        }

        val operationId = when {
            native && type in REQUEST_EVENTS && requestId.isNotBlank() -> "native-request:$traceId:$requestId"
            native && type in ACTION_EVENTS -> "native-action:$traceId:${values.getOrNull(0).orEmpty().ifBlank { action.name }}"
            else -> "vbook:$traceId:${action.name}"
        }
        val operationState = when (type) {
            "REQUEST", "ACTION_START" -> DiagnosticOperationState.STARTED
            "RESPONSE", "ACTION_DONE" -> DiagnosticOperationState.COMPLETED
            "REQUEST_ERROR", "BROWSER_CLOSE_ERROR", "ERROR", "FAILED" -> DiagnosticOperationState.FAILED
            else -> DiagnosticOperationState.STAGE
        }
        attributes += DiagnosticOperationContract.attributes(
            id = operationId,
            kind = when {
                type in REQUEST_EVENTS -> "NATIVE_REQUEST"
                type in ACTION_EVENTS -> "NATIVE_ACTION"
                else -> action.name
            },
            flow = flow,
            state = operationState,
            stage = type,
        )

        return ParsedVBookDiagnosticLog(
            name = if (native) "NATIVE_V2_$type" else "VBOOK_LOG_$type",
            severity = severity,
            attributes = attributes,
        )
    }

    private fun semanticSeverity(type: String, fallback: DiagnosticSeverity): DiagnosticSeverity = when {
        type == "ERROR_POLICY" -> DiagnosticSeverity.WARN
        type.contains("ERROR") || type.endsWith("FAILED") -> DiagnosticSeverity.ERROR
        type.contains("WARN") || type == "RETRY" || type.contains("PARTIAL") -> DiagnosticSeverity.WARN
        else -> fallback
    }

    private fun namedValue(raw: String?, name: String): String = raw.orEmpty()
        .removePrefix("$name=")
        .take(MAX_ARGUMENT_CHARS)

    private val REQUEST_EVENTS = setOf("REQUEST", "RETRY", "RESPONSE", "REQUEST_ERROR")
    private val ACTION_EVENTS = setOf("ACTION_START", "ACTION_DONE", "ERROR")
    private const val MAX_ARGUMENTS = 24
    private const val MAX_ARGUMENT_CHARS = 8_000
    private const val MAX_MESSAGE_CHARS = 32_000
}
