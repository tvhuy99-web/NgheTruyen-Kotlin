package vn.nghetruyen.app.sources

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext






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
