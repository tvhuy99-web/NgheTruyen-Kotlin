package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourcePlatformResult
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VBookProviderSessionTest {
    @Test
    fun comicUsesPageScriptAndSupportsRawUrlFallback() {
        val withPage = session(
            type = "comic",
            scripts = linkedMapOf(
                "search" to "search.js", "detail" to "detail.js", "toc" to "toc.js", "page" to "page.js",
            ),
            sources = mapOf(
                "search.js" to success("[]"),
                "detail.js" to success("{}"),
                "toc.js" to success("[]"),
                "page.js" to "function execute(url){return Response.success(['https://img/1.jpg','https://img/2.jpg']);}",
            ),
        )
        val pages = withPage.comicPages("https://comic/chapter") as SourcePlatformResult.Success
        assertEquals(listOf("https://img/1.jpg", "https://img/2.jpg"), pages.value)

        val fallback = session(
            type = "comic",
            scripts = linkedMapOf("search" to "search.js", "detail" to "detail.js", "toc" to "toc.js"),
            sources = mapOf("search.js" to success("[]"), "detail.js" to success("{}"), "toc.js" to success("[]")),
        )
        val raw = fallback.comicPages("https://comic/raw.jpg") as SourcePlatformResult.Success
        assertEquals(listOf("https://comic/raw.jpg"), raw.value)
    }

    @Test
    fun mediaPreservesChapToTrackContract() {
        val provider = session(
            type = "video",
            scripts = linkedMapOf(
                "search" to "search.js", "detail" to "detail.js", "toc" to "toc.js",
                "chap" to "chap.js", "track" to "track.js",
            ),
            sources = mapOf(
                "search.js" to success("[]"),
                "detail.js" to success("{}"),
                "toc.js" to success("[]"),
                "chap.js" to "function execute(url){return Response.success([{title:'VIP',data:'embed-1'}]);}",
                "track.js" to "function execute(data){return Response.success({type:'native',data:'https://cdn/video.m3u8',host:'https://site',mimeType:'application/x-mpegURL',headers:{Referer:'https://site'},timeSkip:[]});}",
            ),
        )
        val sources = provider.mediaSources("episode") as SourcePlatformResult.Success
        assertEquals("embed-1", sources.value.single().data)
        val track = provider.resolveTrack(sources.value.single().data) as SourcePlatformResult.Success
        assertEquals("native", track.value.type)
        assertEquals("https://cdn/video.m3u8", track.value.data)
        assertEquals("https://site", track.value.headers["Referer"])
    }

    @Test
    fun ttsReturnsVoicesAndValidatedBase64Audio() {
        val audio = "voice-audio".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(audio)
        val provider = session(
            type = "tts",
            scripts = linkedMapOf("voice" to "voice.js", "tts" to "tts.js"),
            sources = mapOf(
                "voice.js" to "function execute(){return Response.success([{id:'v1',name:'Voice 1',language:'vi-VN'}]);}",
                "tts.js" to "function execute(text,voiceId){return Response.success('$encoded');}",
            ),
        )
        val voices = provider.voices() as SourcePlatformResult.Success
        assertEquals("v1", voices.value.single().id)
        val synthesized = provider.synthesize("xin chao", "v1") as SourcePlatformResult.Success
        assertTrue(synthesized.value.bytes().contentEquals(audio))
    }

    @Test
    fun translatePreservesLanguageDirectionAndSegmentOffsets() {
        val provider = session(
            type = "translate",
            scripts = linkedMapOf("language" to "language.js", "translate" to "translate.js"),
            sources = mapOf(
                "language.js" to "function execute(){return Response.success([{id:'zh',name:'Chinese',type:'from'},{id:'vi',name:'Vietnamese',type:'to'}]);}",
                "translate.js" to "function execute(text,from,to,source){return Response.success({translateText:'Xin chao',segments:[{srcStart:0,srcLen:2,transStart:0,transLen:8,type:1}]});}",
            ),
        )
        val languages = provider.languages() as SourcePlatformResult.Success
        assertEquals(listOf("from", "to"), languages.value.map { it.type })
        val translated = provider.translate("你好", "zh", "vi", "fixture") as SourcePlatformResult.Success
        assertEquals("Xin chao", translated.value.translateText)
        assertEquals(VBookTranslationSegment(0, 2, 0, 8, 1), translated.value.segments.single())
    }

    private fun session(
        type: String,
        scripts: LinkedHashMap<String, String>,
        sources: Map<String, String>,
    ): VBookProviderSession {
        val plugin = buildString {
            append("{\"metadata\":{\"name\":\"fixture\",\"author\":\"test\",\"version\":1,\"source\":\"https://site.example\",\"description\":\"\",\"locale\":\"vi\",\"regexp\":\"site\",\"type\":\"")
            append(type)
            append("\",\"nsfw\":false},\"script\":{")
            append(scripts.entries.joinToString(",") { (role, file) -> "\"$role\":\"$file\"" })
            append("},\"config\":{}}")
        }
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                fun put(path: String, data: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(data.toByteArray())
                    zip.closeEntry()
                }
                put("plugin.json", plugin)
                sources.forEach { (name, source) -> put("src/$name", source) }
            }
        }.toByteArray()
        return VBookProviderSession("fixture.$type", bytes, SourceCapabilityBrokers())
    }

    private fun success(data: String): String = "function execute(){return Response.success($data);}" 
}
