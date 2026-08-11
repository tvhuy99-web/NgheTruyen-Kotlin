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

/**
 * Process-wide platform engine policy for compatibility vBook actions.
 *
 * Pure JVM consumers never install a platform factory and continue to execute Rhino directly.
 * Android installs a Chromium factory from Application.onCreate. The brokers passed here have
 * already been wrapped by [VBookCompatibilityRuntime], so primary and fallback engines observe the
 * exact same vBook network, translation and WebSocket semantics.
 */
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
