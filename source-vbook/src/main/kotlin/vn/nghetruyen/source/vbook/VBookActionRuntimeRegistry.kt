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
 * Process-wide engine policy for compatibility vBook actions.
 *
 * Pure JVM consumers never install a platform factory and continue to receive Rhino. Android installs
 * a factory from Application.onCreate, using only application-lifetime state. The compatibility
 * broker wrappers are centralized here so Chromium and Rhino see exactly the same vBook network,
 * translation and WebSocket semantics.
 */
object VBookActionRuntimeRegistry {
    private val platformFactory = AtomicReference<VBookActionRuntimeFactory?>(null)

    fun install(factory: VBookActionRuntimeFactory) {
        platformFactory.set(factory)
    }

    fun clear() {
        platformFactory.set(null)
    }

    fun create(
        brokers: SourceCapabilityBrokers,
        diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    ): VBookActionRuntime {
        val compatible = compatibleBrokers(brokers)
        return platformFactory.get()?.create(compatible, diagnostics)
            ?: RhinoVBookActionRuntime(compatible, diagnostics)
    }

    private fun compatibleBrokers(brokers: SourceCapabilityBrokers): SourceCapabilityBrokers = brokers.copy(
        network = VBookRawNetworkBroker(brokers.network),
        translation = VBookTranslationBrokerRouter(brokers.translation, brokers.quickTranslation),
        websocket = VBookWebSocketBroker(brokers.websocket),
    )
}
