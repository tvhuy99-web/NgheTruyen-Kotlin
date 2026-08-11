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
    fun validatesLifecycleEvents() {
        val event = SourceHostKernelContract.event("reader.enter")
        assertEquals("reader.enter", event.name)
        val failure = runCatching { SourceHostKernelContract.event("android.activity") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SOURCE_HOST_EVENT_INVALID"))
    }
}
