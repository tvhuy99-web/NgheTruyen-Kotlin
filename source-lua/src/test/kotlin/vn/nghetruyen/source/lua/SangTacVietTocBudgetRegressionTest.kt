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

        // New imports get explicit Native-Lua headroom. Runtime also applies the same floor to old
        // installed manifests, which may still contain the historical 32 MiB value.
        assertEquals(64 * 1024 * 1024, pack.manifest.runtime.memoryBudgetBytes)
        val oldInstalledManifest = pack.manifest.copy(
            runtime = pack.manifest.runtime.copy(memoryBudgetBytes = 32 * 1024 * 1024),
        )

        val responseBody = realisticChapterApiResponse()
        val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
        assertTrue("fixture should resemble the 55-69 KiB device response: ${responseBytes.size}", responseBytes.size in 50 * 1024..90 * 1024)

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

        val result = runtime.execute(oldInstalledManifest, MapSourceResourceProvider(pack.entries), request)
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
        assertTrue("expected last chapter in normalized output", encoded.contains("Chương 100"))
        assertTrue("expected chapter URL rooted at the story", encoded.contains(storyUrl))
    }

    private fun realisticChapterApiResponse(): String {
        val padding = "x".repeat(250)
        val records = (1..100).joinToString("-//-") { index ->
            "$padding-/-$index-/-Chương $index-/-$padding"
        }
        return JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "data" to JsonValue.Str(records),
        )))
    }
}
