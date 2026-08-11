package vn.nghetruyen.source.api

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
            SourcePlatformResult.Failure(
                SourcePlatformFailure(
                    code = SourceErrorCode.INTERNAL_ERROR,
                    message = "SOURCE_HOST_KERNEL_UNAVAILABLE:${command.domain}:${command.action}",
                    traceId = traceId,
                ),
            )
        }
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
