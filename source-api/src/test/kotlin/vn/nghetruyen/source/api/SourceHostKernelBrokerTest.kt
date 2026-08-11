package vn.nghetruyen.source.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHostKernelBrokerTest {
    @Test
    fun unavailableBrokerFailsAtHostBoundaryWithoutPlatformObject() {
        SourceHostKernelBus.clear()
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
    fun lifecycleBusActivatesExistingBrokerReferenceAfterHostInstall() {
        SourceHostKernelBus.clear()
        val existingReference = SourceHostKernelBroker.UNAVAILABLE
        val dispatcher = SourceHostKernelDispatcher()
            .register("tts", "play") { _, _, _ -> SourcePlatformResult.Success(JsonValue.Str("played")) }
        SourceHostKernelBus.install(dispatcher)
        try {
            val result = existingReference.execute(
                sourceId = "vn.nghetruyen.sources.test",
                command = SourceHostKernelContract.command("tts", "play"),
                traceId = "trace-live-host",
            )
            assertTrue(result is SourcePlatformResult.Success)
            result as SourcePlatformResult.Success
            assertEquals("played", (result.value as JsonValue.Str).value)
        } finally {
            SourceHostKernelBus.clear()
        }
    }

    @Test
    fun dispatcherRoutesOnlyRegisteredHostCommands() {
        val dispatcher = SourceHostKernelDispatcher()
            .register("reader", "nextChapter") { sourceId, _, traceId ->
                SourcePlatformResult.Success(JsonValue.Obj(linkedMapOf(
                    "sourceId" to JsonValue.Str(sourceId),
                    "traceId" to JsonValue.Str(traceId),
                )))
            }
        val result = dispatcher.execute(
            sourceId = "vn.nghetruyen.sources.test",
            command = SourceHostKernelContract.command("reader", "nextChapter"),
            traceId = "trace-next",
        )
        assertTrue(result is SourcePlatformResult.Success)
        result as SourcePlatformResult.Success
        val value = result.value as JsonValue.Obj
        assertEquals("vn.nghetruyen.sources.test", (value.values["sourceId"] as JsonValue.Str).value)

        val missing = dispatcher.execute(
            sourceId = "vn.nghetruyen.sources.test",
            command = SourceHostKernelContract.command("tts", "play"),
            traceId = "trace-missing",
        )
        assertTrue(missing is SourcePlatformResult.Failure)
        missing as SourcePlatformResult.Failure
        assertTrue(missing.error.message.contains("SOURCE_HOST_COMMAND_HANDLER_UNAVAILABLE:tts:play"))
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

    @Test
    fun eventBusRoutesLiveSinkThenFallsBackToNameScopedPolling() {
        val sourceId = "vn.nghetruyen.sources.events"
        SourceHostEventBus.unregister(sourceId)
        SourceHostEventBus.drain(sourceId)
        val firstEvents = mutableListOf<String>()
        val secondEvents = mutableListOf<String>()
        val first = SourceHostEventSink { _, event, traceId -> firstEvents += "${event.name}:$traceId" }
        val second = SourceHostEventSink { _, event, traceId -> secondEvents += "${event.name}:$traceId" }

        SourceHostEventBus.register(sourceId, first)
        SourceHostEventBus.emit(sourceId, SourceHostKernelContract.event("reader.enter"), "trace-first")
        SourceHostEventBus.register(sourceId, second)
        SourceHostEventBus.emit(sourceId, SourceHostKernelContract.event("playback.changed"), "trace-second")
        SourceHostEventBus.unregister(sourceId, second)
        SourceHostEventBus.emit(
            sourceId,
            SourceHostKernelContract.event("library.changed", JsonValue.Obj(linkedMapOf("bookmarks" to JsonValue.Num(2.0, "2")))),
            "trace-queued-library",
        )
        SourceHostEventBus.emit(sourceId, SourceHostKernelContract.event("app.resume"), "trace-queued-resume")

        assertEquals(listOf("reader.enter:trace-first"), firstEvents)
        assertEquals(listOf("playback.changed:trace-second"), secondEvents)
        val library = SourceHostEventBus.drain(sourceId, "library.changed")
        assertEquals(1, library.size)
        assertEquals("library.changed", library.single().name)
        assertEquals("2", ((library.single().payload.values["bookmarks"] as JsonValue.Num).raw))
        assertEquals(listOf("app.resume"), SourceHostEventBus.drain(sourceId).map(SourceHostEvent::name))
    }
}
