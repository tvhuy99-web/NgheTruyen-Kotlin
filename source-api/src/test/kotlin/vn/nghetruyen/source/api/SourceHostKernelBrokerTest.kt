package vn.nghetruyen.source.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHostKernelBrokerTest {
    @Test
    fun unavailableBrokerFailsAtHostBoundaryWithoutPlatformObject() {
        val result = SourceHostKernelBroker.UNAVAILABLE.execute(
            sourceId = "vn.nghetruyen.sources.test",
            command = SourceHostKernelContract.command("tts", "play"),
            traceId = "trace-host-kernel",
        )
        assertTrue(result is SourcePlatformResult.Failure)
        result as SourcePlatformResult.Failure
        assertEquals(SourceErrorCode.INTERNAL_ERROR, result.error.code)
        assertTrue(result.error.message.contains("SOURCE_HOST_KERNEL_UNAVAILABLE:tts:play"))
        assertEquals("trace-host-kernel", result.error.traceId)
    }

    @Test
    fun eventSinkValidatesOnlySerializableHostEvents() {
        SourceHostEventSink.NONE.emit(
            "vn.nghetruyen.sources.test",
            SourceHostKernelContract.event("app.resume"),
            "trace-event",
        )
        val failure = runCatching {
            SourceHostEventSink.NONE.emit(
                "vn.nghetruyen.sources.test",
                SourceHostEvent("android.context"),
                "trace-event",
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SOURCE_HOST_EVENT_INVALID"))
    }
}
