package vn.nghetruyen.source.api








object SourceHostKernelWireExecutor {
    fun execute(
        broker: SourceHostKernelBroker,
        sourceId: String,
        rawCommandJson: String,
        traceId: String,
    ): SourcePlatformResult<String> {
        val bytes = rawCommandJson.toByteArray(Charsets.UTF_8)
        if (bytes.size > SourceHostKernelContract.MAX_PAYLOAD_BYTES + ENVELOPE_OVERHEAD_BYTES) {
            return failure(traceId, "SOURCE_HOST_COMMAND_ENVELOPE_TOO_LARGE")
        }
        val command = runCatching {
            SourceHostKernelContract.parseCommand(
                JsonCodec.parse(rawCommandJson, maxDepth = 48, maxNodes = 20_000),
            )
        }.getOrElse { error ->
            return failure(traceId, error.message ?: "SOURCE_HOST_COMMAND_PARSE_FAILED", error)
        }
        return when (val result = broker.execute(sourceId, command, traceId)) {
            is SourcePlatformResult.Success -> SourcePlatformResult.Success(JsonCodec.stringify(result.value))
            is SourcePlatformResult.Failure -> result
        }
    }

    private fun failure(
        traceId: String,
        message: String,
        cause: Throwable? = null,
    ): SourcePlatformResult.Failure = SourcePlatformResult.Failure(
        SourcePlatformFailure(
            code = SourceErrorCode.INTERNAL_ERROR,
            message = message,
            traceId = traceId,
            cause = cause,
        ),
    )

    private const val ENVELOPE_OVERHEAD_BYTES = 4 * 1024
}
