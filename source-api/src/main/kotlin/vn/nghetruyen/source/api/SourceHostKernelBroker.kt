package vn.nghetruyen.source.api

import java.lang.ref.WeakReference
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
        internal val FAIL_CLOSED = SourceHostKernelBroker { _, command, traceId ->
            SourceHostKernelContract.validate(command)
            SourcePlatformResult.Failure(
                SourcePlatformFailure(
                    code = SourceErrorCode.INTERNAL_ERROR,
                    message = "SOURCE_HOST_KERNEL_UNAVAILABLE:${command.domain}:${command.action}",
                    traceId = traceId,
                ),
            )
        }

        /**
         * Compatibility name retained for existing broker construction. The returned object is a
         * lifecycle-stable bus that behaves exactly like FAIL_CLOSED until the NgheTruyen host
         * installs a dispatcher. Existing runtimes therefore do not need to be reconstructed.
         */
        val UNAVAILABLE: SourceHostKernelBroker
            get() = SourceHostKernelBus
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
    private val delegate = AtomicReference(SourceHostKernelBroker.FAIL_CLOSED)

    fun install(host: SourceHostKernelBroker) {
        require(host !== this) { "SOURCE_HOST_KERNEL_RECURSIVE_INSTALL" }
        delegate.set(host)
    }

    fun clear() {
        delegate.set(SourceHostKernelBroker.FAIL_CLOSED)
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

/**
 * Process-stable host-to-extension event router and bounded fallback inbox.
 *
 * A live runtime may register a weak sink and receive events immediately. If there is no live sink,
 * the event is kept in a small per-source FIFO until the extension polls it through the host command
 * boundary. This lets short-lived Rhino scopes receive lifecycle events without keeping a JS scope,
 * Activity or runtime alive between actions.
 */
object SourceHostEventBus : SourceHostEventSink {
    private val sinks = ConcurrentHashMap<String, WeakReference<SourceHostEventSink>>()
    private val pending = ConcurrentHashMap<String, ArrayDeque<SourceHostEvent>>()

    fun register(sourceId: String, sink: SourceHostEventSink) {
        validateSourceId(sourceId)
        require(sink !== this) { "SOURCE_HOST_EVENT_RECURSIVE_SINK" }
        sinks[sourceId] = WeakReference(sink)
    }

    fun unregister(sourceId: String, sink: SourceHostEventSink? = null) {
        if (sink == null) {
            sinks.remove(sourceId)
            return
        }
        val current = sinks[sourceId]?.get()
        if (current === sink || current == null) sinks.remove(sourceId)
    }

    fun drain(sourceId: String, eventName: String? = null): List<SourceHostEvent> {
        validateSourceId(sourceId)
        val normalizedName = eventName?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedName != null) {
            require(normalizedName in SourceHostKernelContract.lifecycleEvents) {
                "SOURCE_HOST_EVENT_INVALID:$normalizedName"
            }
        }
        val queue = pending[sourceId] ?: return emptyList()
        val drained = mutableListOf<SourceHostEvent>()
        synchronized(queue) {
            if (normalizedName == null) {
                while (queue.isNotEmpty()) drained += queue.removeFirst()
            } else {
                val keep = ArrayDeque<SourceHostEvent>()
                while (queue.isNotEmpty()) {
                    val event = queue.removeFirst()
                    if (event.name == normalizedName) drained += event else keep.addLast(event)
                }
                while (keep.isNotEmpty()) queue.addLast(keep.removeFirst())
            }
            if (queue.isEmpty()) pending.remove(sourceId, queue)
        }
        return drained
    }

    override fun emit(sourceId: String, event: SourceHostEvent, traceId: String) {
        validateSourceId(sourceId)
        SourceHostKernelContract.validate(event)
        val reference = sinks[sourceId]
        val sink = reference?.get()
        if (sink != null) {
            sink.emit(sourceId, event, traceId)
            return
        }
        if (reference != null) sinks.remove(sourceId, reference)
        enqueue(sourceId, event)
    }

    private fun enqueue(sourceId: String, event: SourceHostEvent) {
        if (!pending.containsKey(sourceId) && pending.size >= MAX_SOURCE_QUEUES) {
            pending.keys.firstOrNull()?.let(pending::remove)
        }
        val queue = pending.computeIfAbsent(sourceId) { ArrayDeque() }
        synchronized(queue) {
            while (queue.size >= MAX_EVENTS_PER_SOURCE) queue.removeFirst()
            queue.addLast(event)
        }
    }

    private fun validateSourceId(sourceId: String) {
        require(sourceId.isNotBlank() && sourceId.length <= MAX_SOURCE_ID_CHARS) {
            "SOURCE_HOST_EVENT_SOURCE_ID_INVALID"
        }
    }

    private const val MAX_SOURCE_ID_CHARS = 512
    private const val MAX_SOURCE_QUEUES = 256
    private const val MAX_EVENTS_PER_SOURCE = 64
}
