package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.util.concurrent.atomic.AtomicReference

fun interface VBookActionRuntimeFactory {
    fun create(
        brokers: SourceCapabilityBrokers,
        diagnostics: DiagnosticSink,
    ): VBookActionRuntime
}









object VBookActionRuntimeRegistry {
    private val platformFactory = AtomicReference<VBookActionRuntimeFactory?>(null)

    fun install(factory: VBookActionRuntimeFactory) {
        platformFactory.set(factory)
    }

    fun clear() {
        platformFactory.set(null)
    }

    internal fun platformRuntime(
        compatibleBrokers: SourceCapabilityBrokers,
        diagnostics: DiagnosticSink,
    ): VBookActionRuntime? = platformFactory.get()?.create(compatibleBrokers, diagnostics)
}
