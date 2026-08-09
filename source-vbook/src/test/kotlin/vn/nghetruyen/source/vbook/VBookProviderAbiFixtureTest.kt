package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookProviderAbiFixtureTest {
    @Test
    fun comicPageReceivesUrlAsSingleStringArgument() {
        val runtime = VBookCompatibilityRuntime()
        val result = runtime.executeDeclared(
            sourceManifest = manifest("fixture.vbook.comic", SourceContentType.COMIC),
            resources = resources("reference-fixtures/current-providers/comic"),
            role = VBookScriptRole.PAGE,
            input = "https://example.invalid/comic/a/1",
        ) as SourcePlatformResult.Success
        val pages = result.value.data as JsonValue.Arr
        assertEquals(2, pages.values.size)
        assertEquals(
            "https://example.invalid/comic/a/1/001.jpg",
            (pages.values[0] as JsonValue.Str).value,
        )
    }

    @Test
    fun mediaChapAndTrackReceiveOpaqueStringArguments() {
        val runtime = VBookCompatibilityRuntime()
        val videoSources = runtime.executeDeclared(
            sourceManifest = manifest("fixture.vbook.video", SourceContentType.MIXED),
            resources = resources("reference-fixtures/current-providers/video"),
            role = VBookScriptRole.CHAP,
            input = "https://example.invalid/video/a/episode-1",
        ) as SourcePlatformResult.Success
        val source = (videoSources.value.data as JsonValue.Arr).values.single() as JsonValue.Obj
        assertEquals("video-token:https://example.invalid/video/a/episode-1", source.string("data"))

        val videoTrack = runtime.executeDeclared(
            sourceManifest = manifest("fixture.vbook.video", SourceContentType.MIXED),
            resources = resources("reference-fixtures/current-providers/video"),
            role = VBookScriptRole.TRACK,
            input = "video-token:fixture",
        ) as SourcePlatformResult.Success
        assertEquals(
            "https://media.example.invalid/video.m3u8?token=video-token:fixture",
            (videoTrack.value.data as JsonValue.Obj).string("data"),
        )

        val audioTrack = runtime.executeDeclared(
            sourceManifest = manifest("fixture.vbook.audio", SourceContentType.AUDIO),
            resources = resources("reference-fixtures/current-providers/audio"),
            role = VBookScriptRole.TRACK,
            input = "https://example.invalid/audio/a/chapter-1",
        ) as SourcePlatformResult.Success
        assertEquals(
            "https://media.example.invalid/audio.mp3?source=https://example.invalid/audio/a/chapter-1",
            (audioTrack.value.data as JsonValue.Obj).string("data"),
        )
    }

    @Test
    fun ttsReceivesTextAndVoiceId() {
        val runtime = VBookCompatibilityRuntime()
        val voices = runtime.executeDeclared(
            manifest("fixture.vbook.tts", SourceContentType.MIXED),
            resources("reference-fixtures/current-providers/tts"),
            VBookScriptRole.VOICE,
        ) as SourcePlatformResult.Success
        assertEquals(2, (voices.value.data as JsonValue.Arr).values.size)

        val result = runtime.executeDeclared(
            sourceManifest = manifest("fixture.vbook.tts", SourceContentType.MIXED),
            resources = resources("reference-fixtures/current-providers/tts"),
            role = VBookScriptRole.TTS,
            text = "xin chào",
            voiceId = "voice-b",
        ) as SourcePlatformResult.Success
        val obj = result.value.data as JsonValue.Obj
        assertEquals("xin chào", obj.string("text"))
        assertEquals("voice-b", obj.string("voiceId"))
    }

    @Test
    fun translateReceivesTextFromToAndSource() {
        val runtime = VBookCompatibilityRuntime()
        val languages = runtime.executeDeclared(
            manifest("fixture.vbook.translate", SourceContentType.MIXED),
            resources("reference-fixtures/current-providers/translate"),
            VBookScriptRole.LANGUAGE,
        ) as SourcePlatformResult.Success
        assertTrue((languages.value.data as JsonValue.Arr).values.isNotEmpty())

        val result = runtime.executeDeclared(
            sourceManifest = manifest("fixture.vbook.translate", SourceContentType.MIXED),
            resources = resources("reference-fixtures/current-providers/translate"),
            role = VBookScriptRole.TRANSLATE,
            text = "你好",
            from = "zh",
            to = "vi",
            source = "fixture-source",
        ) as SourcePlatformResult.Success
        val obj = result.value.data as JsonValue.Obj
        assertEquals("你好", obj.string("text"))
        assertEquals("zh", obj.string("from"))
        assertEquals("vi", obj.string("to"))
        assertEquals("fixture-source", obj.string("source"))
    }

    private fun manifest(id: String, contentType: SourceContentType) = SourceManifest(
        schemaVersion = 2,
        id = id,
        name = id,
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = contentType,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://example.invalid"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources(root: String): SourceResourceProvider = object : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? {
            val stream = javaClass.classLoader?.getResourceAsStream("$root/$path") ?: return null
            return stream.use { input -> input.readBytes().takeIf { it.size <= maxBytes } }
        }
    }
}
