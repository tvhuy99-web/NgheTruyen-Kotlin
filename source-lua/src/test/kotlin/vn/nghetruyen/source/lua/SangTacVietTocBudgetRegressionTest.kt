package vn.nghetruyen.source.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.MapSourceResourceProvider
import vn.nghetruyen.source.vbook.VBookJsRuntime
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class SangTacVietTocBudgetRegressionTest {
    @Test
    fun realisticTocPayloadCompletesThroughExactNativeSourceFixture() {
        val sourceBytes = requireNotNull(javaClass.getResourceAsStream("/xpk-defaults/nguon_sangtacviet_native.lua.gz"))
            .use { compressed -> GZIPInputStream(compressed).use { it.readBytes() } }
        val (pack, _) = NativeLuaArchiveImporter.import(ByteArrayInputStream(sourceBytes))

        
        
        
        assertEquals(64 * 1024 * 1024, pack.manifest.runtime.memoryBudgetBytes)
        val oldInstalledManifest = pack.manifest.copy(
            runtime = pack.manifest.runtime.copy(memoryBudgetBytes = 32 * 1024 * 1024),
        )
        val currentCore = requireNotNull(pack.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)
        assertTrue(currentCore.contains(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER))
        val stalePack = pack.copy(entries = LinkedHashMap(pack.entries).apply {
            put(
                "src/native_v2_core.js",
                currentCore.replace(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER, "STALE_NATIVE_V2_HOST_RUNTIME")
                    .toByteArray(Charsets.UTF_8),
            )
        })
        val overlay = NativeLuaRuntimeOverlay.refresh(stalePack)
        assertTrue("old installed NativeV2 core must be refreshed", overlay.refreshed)
        assertTrue(
            requireNotNull(overlay.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)
                .contains(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER),
        )

        val responseBody = realisticChapterApiResponse()
        val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
        assertTrue(
            "fixture should resemble the 167,907-byte live device response: ${responseBytes.size}",
            responseBytes.size in 155 * 1024..180 * 1024,
        )

        val network = SourceNetworkBroker { _, request ->
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("content-type" to listOf("application/json; charset=utf-8")),
                body = responseBytes,
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(0L, 1L),
                traceId = request.traceId,
                requestUrl = request.url,
            ))
        }
        val runtime = VBookJsRuntime(
            brokers = SourceCapabilityBrokers(
                network = network,
                nativeHooks = LuaNativeHookBroker(),
            ),
        )
        val storyUrl = "https://sangtacviet.com/truyen/fanqie/1/7636340618855189528/"
        val request = SourceActionRequest(
            sourceId = oldInstalledManifest.id,
            action = SourceActionName.TOC,
            input = JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(storyUrl))),
            traceId = "stv-toc-budget-regression",
        )

        val result = runtime.execute(oldInstalledManifest, MapSourceResourceProvider(overlay.entries), request)
        assertTrue(
            when (result) {
                is SourcePlatformResult.Success -> "success"
                is SourcePlatformResult.Failure -> "${result.error.code}: ${result.error.message}"
            },
            result is SourcePlatformResult.Success,
        )
        val response = (result as SourcePlatformResult.Success).value
        val encoded = JsonCodec.stringify(response.value)
        
        
        assertTrue("expected first chapter in normalized output", encoded.contains("Chương 1"))
        assertTrue("expected last chapter on page 1", encoded.contains("Chương 100"))
        assertTrue("page 1 must not eagerly materialize chapter 101", !encoded.contains("Chương 101"))
        assertTrue("expected next page token", encoded.contains("__vbook_stv_toc=2"))
        assertTrue("expected chapter URL rooted at the story", encoded.contains(storyUrl))
    }

    private fun realisticChapterApiResponse(): String {
        val padding = "x".repeat(70)
        val records = (1..1_000).joinToString("-//-") { index ->
            "$padding-/-$index-/-Chương $index-/-$padding"
        }
        return JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "data" to JsonValue.Str(records),
        )))
    }
}
