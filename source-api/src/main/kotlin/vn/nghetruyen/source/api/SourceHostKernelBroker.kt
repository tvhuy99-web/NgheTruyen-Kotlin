package vn.nghetruyen.source.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime-neutral execution boundary for NgheTruyen host commands.
 *
 * Implementations live in the app/host layer. Extension runtimes only see serializable commands
 * defined by [SourceHostKernelContract], never Android Context, Activity, Service or JVM reflection.
 */
fun interface SourceHostKernelBroker {
    fun execute(
        sourceId: String,
        command: SourceHostCommand,
        traceId: String,
    ): SourcePlatformResult<JsonValue>

    companion object {
        val UNAVAILABLE = SourceHostKernelBroker { _, command, traceId ->
            SourceHostKernelContract.validate(command)
            unavailable(command, traceId)
        }

        private fun unavailable(command: SourceHostCommand, traceId: String): SourcePlatformResult.Failure =
            SourcePlatformResult.Failure(
                SourcePlatformFailure(
                    code = SourceErrorCode.INTERNAL_ERROR,
                    message = "SOURCE_HOST_KERNEL_UNAVAILABLE:${command.domain}:${command.action}",
                    traceId = traceId,
                ),
            )
    }
}

/**
 * Process-stable indirection owned by the host.
 *
 * Source runtimes may be constructed before an Activity/ViewModel exists. They keep this broker
 * reference for their lifetime while the Android host is free to attach a new dispatcher whenever
 * a UI session appears. This avoids rebuilding runtimes and avoids storing Android objects here.
 */
object SourceHostKernelBus : SourceHostKernelBroker {
    private val delegate = AtomicReference<SourceHostKernelBroker>(SourceHostKernelBroker.UNAVAILABLE)

    fun install(host: SourceHostKernelBroker) {
        require(host !== this) { "SOURCE_HOST_KERNEL_RECURSIVE_INSTALL" }
        delegate.set(host)
    }

    fun clear() {
        delegate.set(SourceHostKernelBroker.UNAVAILABLE)
    }

    override fun execute(
        sourceId: String,
        command: SourceHostCommand,
        traceId: String,
    ): SourcePlatformResult<JsonValue> = delegate.get().execute(sourceId, command, traceId)
}

fun interface SourceHostCommandHandler {
    fun execute(
        sourceId: String,
        payload: JsonValue.Obj,
        traceId: String,
    ): SourcePlatformResult<JsonValue>
}

/**
 * Small deterministic router used by the NgheTruyen host to bind commands to app-owned handlers.
 * Registering handlers is host wiring, not extension permission negotiation.
 */
class SourceHostKernelDispatcher(
    handlers: Map<Pair<String, String>, SourceHostCommandHandler> = emptyMap(),
) : SourceHostKernelBroker {
    private val handlers = ConcurrentHashMap(handlers)

    fun register(domain: String, action: String, handler: SourceHostCommandHandler): SourceHostKernelDispatcher {
        SourceHostKernelContract.validate(SourceHostCommand(domain, action))
        handlers[domain to action] = handler
        return this
    }

    override fun execute(
        sourceId: String,
        command: SourceHostCommand,
        traceId: String,
    ): SourcePlatformResult<JsonValue> {
        SourceHostKernelContract.validate(command)
        val handler = handlers[command.domain to command.action]
            ?: return SourcePlatformResult.Failure(
                SourcePlatformFailure(
                    code = SourceErrorCode.INTERNAL_ERROR,
                    message = "SOURCE_HOST_COMMAND_HANDLER_UNAVAILABLE:${command.domain}:${command.action}",
                    traceId = traceId,
                ),
            )
        return handler.execute(sourceId, command.payload, traceId)
    }
}

/** Host-to-extension event delivery abstraction. Runtimes may implement this as an event queue. */
fun interface SourceHostEventSink {
    fun emit(
        sourceId: String,
        event: SourceHostEvent,
        traceId: String,
    )

    companion object {
        val NONE = SourceHostEventSink { _, event, _ -> SourceHostKernelContract.validate(event) }
    }
}
