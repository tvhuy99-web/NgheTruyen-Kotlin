package vn.nghetruyen.source.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHostKernelWireExecutorTest {
    @Test
    fun parsesValidatesDispatchesAndSerializesWithoutPlatformObjects() {
        val dispatcher = SourceHostKernelDispatcher()
            .register("reader", "moveParagraph") { sourceId, payload, traceId ->
                SourcePlatformResult.Success(JsonValue.Obj(linkedMapOf(
                    "sourceId" to JsonValue.Str(sourceId),
                    "delta" to (payload.values["delta"] ?: JsonValue.Null),
                    "traceId" to JsonValue.Str(traceId),
                )))
            }
        val command = SourceHostKernelContract.command(
            "reader",
            "moveParagraph",
            JsonValue.Obj(linkedMapOf("delta" to JsonValue.Num(2.0, "2"))),
        )
        val raw = JsonCodec.stringify(SourceHostKernelContract.encode(command))
        val result = SourceHostKernelWireExecutor.execute(
            dispatcher,
            "vn.nghetruyen.sources.test",
            raw,
            "trace-wire",
        )
        assertTrue(result is SourcePlatformResult.Success)
        result as SourcePlatformResult.Success
        val decoded = JsonCodec.parse(result.value) as JsonValue.Obj
        assertEquals("vn.nghetruyen.sources.test", (decoded.values["sourceId"] as JsonValue.Str).value)
        assertEquals("2", (decoded.values["delta"] as JsonValue.Num).raw)
        assertEquals("trace-wire", (decoded.values["traceId"] as JsonValue.Str).value)
    }

    @Test
    fun rejectsNonHostJsonBeforeDispatcher() {
        val result = SourceHostKernelWireExecutor.execute(
            SourceHostKernelDispatcher(),
            "vn.nghetruyen.sources.test",
            "{\"kind\":\"android.intent\",\"version\":2}",
            "trace-invalid",
        )
        assertTrue(result is SourcePlatformResult.Failure)
        result as SourcePlatformResult.Failure
        assertTrue(result.error.message.contains("SOURCE_HOST_COMMAND_KIND_INVALID"))
    }
}
