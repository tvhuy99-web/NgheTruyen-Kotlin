package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.withContext
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.sources.DiagnosticCausalTrace
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.util.Locale
import java.util.UUID

internal data class CausalNavigationReadyEvent(
    val name: String,
    val action: String,
    val sourceId: String,
    val traceId: String,
    val navigationKind: String,
    val originScreenGeneration: Long,
    val durationMs: Long,
    val attributes: Map<String, String>,
)

internal interface CausalNavigationRuntime {
    fun currentScreenGeneration(): Long
    fun handoff(destinationScreenKey: String, traceId: String)
    fun emitReady(event: CausalNavigationReadyEvent)
}

/**
 * One navigation contract for every source-backed screen transition.
 *
 * The source action runs inside one causal CoroutineContext. A destination generation is created
 * only after the block returns Success, and only that root trace is handed off. Failures therefore
 * stay on the origin screen with their complete timeline/evidence, while unrelated origin events
 * can never leak into the destination.
 */
internal suspend fun <T> runCausalNavigation(
    runtime: CausalNavigationRuntime,
    traceKind: String,
    action: String,
    readyEventName: String,
    destinationScreenKey: (T) -> String,
    sourceId: (T) -> String,
    readyAttributes: (T) -> Map<String, String> = { emptyMap() },
    traceIdFactory: (String) -> String = { kind -> "$kind:${UUID.randomUUID()}" },
    clockMs: () -> Long = System::currentTimeMillis,
    block: suspend () -> AppResult<T>,
): AppResult<T> {
    val normalizedKind = traceKind.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .take(80)
        .ifBlank { "navigation" }
    val traceId = traceIdFactory(normalizedKind).trim().take(240).ifBlank {
        "$normalizedKind:${UUID.randomUUID()}"
    }
    val originGeneration = runtime.currentScreenGeneration()
    val startedAt = clockMs()
    val result = withContext(DiagnosticCausalTrace(traceId)) { block() }

    if (result is AppResult.Success) {
        val destination = destinationScreenKey(result.value).trim().take(500).ifBlank { "unknown" }
        runtime.handoff(destination, traceId)
        runtime.emitReady(
            CausalNavigationReadyEvent(
                name = readyEventName,
                action = action,
                sourceId = sourceId(result.value),
                traceId = traceId,
                navigationKind = normalizedKind,
                originScreenGeneration = originGeneration,
                durationMs = (clockMs() - startedAt).coerceAtLeast(0L),
                attributes = readyAttributes(result.value),
            ),
        )
    }
    return result
}

internal suspend fun <T> SourceDiagnosticRuntime.navigateWithCausalHandoff(
    traceKind: String,
    action: String,
    readyEventName: String,
    destinationScreenKey: (T) -> String,
    sourceId: (T) -> String,
    readyAttributes: (T) -> Map<String, String> = { emptyMap() },
    block: suspend () -> AppResult<T>,
): AppResult<T> = runCausalNavigation(
    runtime = object : CausalNavigationRuntime {
        override fun currentScreenGeneration(): Long = recorder.currentScreenGeneration()

        override fun handoff(destinationScreenKey: String, traceId: String) {
            onScreenChanged(destinationScreenKey, handoffTraceIds = setOf(traceId))
        }

        override fun emitReady(event: CausalNavigationReadyEvent) {
            mark(
                name = event.name,
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                sourceId = event.sourceId,
                traceId = event.traceId,
                durationMs = event.durationMs,
                attributes = mapOf(
                    "action" to event.action,
                    "status" to "success",
                    "originScreenGeneration" to event.originScreenGeneration.toString(),
                    "diagnosticRootTraceId" to event.traceId,
                    "navigationKind" to event.navigationKind,
                    "handoff" to "selective-causal-navigation",
                ) + event.attributes,
            )
        }
    },
    traceKind = traceKind,
    action = action,
    readyEventName = readyEventName,
    destinationScreenKey = destinationScreenKey,
    sourceId = sourceId,
    readyAttributes = readyAttributes,
    block = block,
)
