package vn.nghetruyen.source.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHostKernelContractTest {
    @Test
    fun exposesStableFullInAppHostDomains() {
        assertEquals(2, SourceHostKernelContract.API_VERSION)
        assertEquals(
            setOf("ui", "reader", "library", "tts", "hooks"),
            SourceHostKernelContract.commandDomains,
        )
        assertTrue("nextChapter" in SourceHostKernelContract.commandActions.getValue("reader"))
        assertTrue("follow" in SourceHostKernelContract.commandActions.getValue("library"))
        assertTrue("play" in SourceHostKernelContract.commandActions.getValue("tts"))
        assertTrue("reader.chapterChanged" in SourceHostKernelContract.lifecycleEvents)
    }

    @Test
    fun validatesCommandsWithoutExposingPlatformObjects() {
        val command = SourceHostKernelContract.command(
            domain = "reader",
            action = "moveParagraph",
            payload = JsonValue.Obj(linkedMapOf("delta" to JsonValue.Num(1.0, "1"))),
        )
        assertEquals("reader", command.domain)
        assertEquals("moveParagraph", command.action)

        val failure = runCatching {
            SourceHostKernelContract.command("android", "getContext")
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SOURCE_HOST_COMMAND_DOMAIN_INVALID"))
    }

    @Test
    fun commandWireFormatRoundTrips() {
        val original = SourceHostKernelContract.command(
            domain = "tts",
            action = "setRate",
            payload = JsonValue.Obj(linkedMapOf("rate" to JsonValue.Num(1.15, "1.15"))),
        )
        val encoded = SourceHostKernelContract.encode(original)
        val decoded = SourceHostKernelContract.parseCommand(encoded)
        assertEquals(original, decoded)
        assertEquals(
            SourceHostKernelContract.COMMAND_KIND,
            (encoded.values["kind"] as JsonValue.Str).value,
        )
    }

    @Test
    fun rejectsPresentNonObjectPayloadInsteadOfSilentlyDroppingIt() {
        val malformed = JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str(SourceHostKernelContract.COMMAND_KIND),
            "version" to JsonValue.Num(2.0, "2"),
            "domain" to JsonValue.Str("tts"),
            "action" to JsonValue.Str("play"),
            "payload" to JsonValue.Str("not-an-object"),
        ))
        val failure = runCatching { SourceHostKernelContract.parseCommand(malformed) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SOURCE_HOST_COMMAND_PAYLOAD_OBJECT_REQUIRED"))
    }

    @Test
    fun validatesLifecycleEventsAndWireFormat() {
        val event = SourceHostKernelContract.event(
            "reader.enter",
            JsonValue.Obj(linkedMapOf("chapterId" to JsonValue.Str("chapter-1"))),
        )
        assertEquals(event, SourceHostKernelContract.parseEvent(SourceHostKernelContract.encode(event)))
        val failure = runCatching { SourceHostKernelContract.event("android.activity") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SOURCE_HOST_EVENT_INVALID"))
    }
}
