package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SOURCE_API_VERSION
import vn.nghetruyen.source.api.SOURCE_PACK_SCHEMA_VERSION
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.runtime.SourceResourceProvider

class PrimaryFallbackVBookActionRuntimeTest {
    @Test
    fun fallsBackOnlyWhenPrimaryIsUnavailableBeforeExecution() {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = VBookActionRuntime { _, _, request ->
            primaryCalls += 1
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE,
                "CHROMIUM_NOT_AVAILABLE",
                request.traceId,
            ))
        }
        val expected = SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("rhino"), "trace", 7))
        val fallback = VBookActionRuntime { _, _, _ -> fallbackCalls += 1; expected }

        val actual = PrimaryFallbackVBookActionRuntime(primary, fallback).execute(manifest(), resources(), request())

        assertSame(expected, actual)
        assertEquals(1, primaryCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun doesNotReplayOrdinaryPrimaryFailureThroughFallback() {
        var fallbackCalls = 0
        val expected = SourcePlatformResult.Failure(SourcePlatformFailure(
            SourceErrorCode.VBOOK_SCRIPT_ERROR,
            "SCRIPT_ALREADY_RAN_AND_FAILED",
            "trace",
        ))
        val primary = VBookActionRuntime { _, _, _ -> expected }
        val fallback = VBookActionRuntime { _, _, _ ->
            fallbackCalls += 1
            SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("must-not-run"), "trace", 0))
        }

        val actual = PrimaryFallbackVBookActionRuntime(primary, fallback).execute(manifest(), resources(), request())

        assertSame(expected, actual)
        assertEquals(0, fallbackCalls)
    }

    private fun request() = SourceActionRequest(
        sourceId = "vn.nghetruyen.sources.chromiumtest",
        action = SourceActionName.UI_ACTION,
        traceId = "trace",
    )

    private fun manifest() = SourceManifest(
        schemaVersion = SOURCE_PACK_SCHEMA_VERSION,
        id = "vn.nghetruyen.sources.chromiumtest",
        name = "Chromium Test",
        version = SemanticVersion(1, 0, 0),
        apiVersion = SOURCE_API_VERSION,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://example.com"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private fun resources() = object : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? = null
    }
}
