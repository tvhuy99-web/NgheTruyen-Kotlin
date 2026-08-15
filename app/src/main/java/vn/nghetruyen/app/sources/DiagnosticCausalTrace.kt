package vn.nghetruyen.app.sources

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Correlates one user-facing source action with every nested runtime action/evidence item.
 * CoroutineContext is used instead of global or ThreadLocal state so concurrent source calls cannot
 * inherit each other's trace and the value survives dispatcher changes.
 */
internal class DiagnosticCausalTrace(
    val traceId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticCausalTrace>
}

internal suspend fun currentDiagnosticCausalTraceId(): String? =
    coroutineContext[DiagnosticCausalTrace]
        ?.traceId
        ?.trim()
        ?.takeIf(String::isNotBlank)
