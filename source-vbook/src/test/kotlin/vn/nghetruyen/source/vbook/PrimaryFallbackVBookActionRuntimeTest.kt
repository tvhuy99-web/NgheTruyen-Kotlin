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
import vn.nghetruyen.source.api.SourceActionSpec
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
    fun routesBrowserHostActionToPortableRuntimeBeforePrimaryRuns() {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = VBookActionRuntime { _, _, _ ->
            primaryCalls += 1
            SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("chromium"), "trace", 1))
        }
        val expected = SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("rhino"), "trace", 7))
        val fallback = VBookActionRuntime { _, _, _ -> fallbackCalls += 1; expected }
        val manifest = manifest(mapOf(SourceActionName.UI_ACTION to SourceActionSpec("src/action.js")))
        val resources = resources(mapOf(
            "src/action.js" to "function execute(){ return Browser.navigate('https://example.com'); }",
        ))

        val actual = PrimaryFallbackVBookActionRuntime(primary, fallback).execute(manifest, resources, request())

        assertSame(expected, actual)
        assertEquals(0, primaryCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun routesBrowserHostActionFoundThroughLiteralLoadGraph() {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = VBookActionRuntime { _, _, _ ->
            primaryCalls += 1
            SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("chromium"), "trace", 1))
        }
        val expected = SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("rhino"), "trace", 7))
        val fallback = VBookActionRuntime { _, _, _ -> fallbackCalls += 1; expected }
        val manifest = manifest(mapOf(SourceActionName.UI_ACTION to SourceActionSpec("src/action.js")))
        val resources = resources(mapOf(
            "src/action.js" to "load('helper.js'); function execute(){ return helper(); }",
            "src/helper.js" to "function helper(){ return Browser['navigate']('https://example.com'); }",
        ))

        val actual = PrimaryFallbackVBookActionRuntime(primary, fallback).execute(manifest, resources, request())

        assertSame(expected, actual)
        assertEquals(0, primaryCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun routesDynamicBrowserScriptFromDispatcherRequestInput() {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = VBookActionRuntime { _, _, _ ->
            primaryCalls += 1
            SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("chromium"), "trace", 1))
        }
        val expected = SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("rhino"), "trace", 7))
        val fallback = VBookActionRuntime { _, _, _ -> fallbackCalls += 1; expected }
        val manifest = manifest(mapOf(SourceActionName.UI_ACTION to SourceActionSpec("src/__nghe_vbook_dispatch.js")))
        val resources = resources(mapOf(
            "src/__nghe_vbook_dispatch.js" to "function execute(input){ return load(input.script); }",
            "src/gen0.js" to "function execute(){ return Browser.navigate('https://my.qidian.com'); }",
        ))
        val input = JsonValue.Obj(linkedMapOf(
            "script" to JsonValue.Str("gen0.js"),
            "args" to JsonValue.Arr(emptyList()),
        ))

        val actual = PrimaryFallbackVBookActionRuntime(primary, fallback).execute(manifest, resources, request(input))

        assertSame(expected, actual)
        assertEquals(0, primaryCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun ignoresBrowserHostNamesInsideCommentsAndStrings() {
        var primaryCalls = 0
        var fallbackCalls = 0
        val expected = SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("chromium"), "trace", 1))
        val primary = VBookActionRuntime { _, _, _ -> primaryCalls += 1; expected }
        val fallback = VBookActionRuntime { _, _, _ ->
            fallbackCalls += 1
            SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("rhino"), "trace", 7))
        }
        val manifest = manifest(mapOf(SourceActionName.UI_ACTION to SourceActionSpec("src/action.js")))
        val resources = resources(mapOf(
            "src/action.js" to "// Browser.navigate('ignored')\nfunction execute(){ var text='Browser.navigate'; return text; }",
        ))

        val actual = PrimaryFallbackVBookActionRuntime(primary, fallback).execute(manifest, resources, request())

        assertSame(expected, actual)
        assertEquals(1, primaryCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun fallsBackOnlyForWhitelistedPreExecutionUnavailableState() {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = VBookActionRuntime { _, _, request ->
            primaryCalls += 1
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE,
                "CHROMIUM_WEBVIEW_UNAVAILABLE:provider-missing",
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

    @Test
    fun doesNotReplayUnrecognizedUnavailableFailureSuchAsRendererCrash() {
        var fallbackCalls = 0
        val expected = SourcePlatformResult.Failure(SourcePlatformFailure(
            SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE,
            "CHROMIUM_RENDERER_GONE:true",
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

    private fun request(input: JsonValue.Obj = JsonValue.Obj()) = SourceActionRequest(
        sourceId = "vn.nghetruyen.sources.chromiumtest",
        action = SourceActionName.UI_ACTION,
        input = input,
        traceId = "trace",
    )

    private fun manifest(actions: Map<SourceActionName, SourceActionSpec> = emptyMap()) = SourceManifest(
        schemaVersion = SOURCE_PACK_SCHEMA_VERSION,
        id = "vn.nghetruyen.sources.chromiumtest",
        name = "Chromium Test",
        version = SemanticVersion(1, 0, 0),
        apiVersion = SOURCE_API_VERSION,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://example.com"),
        capabilities = SourceCapabilities(),
        actions = actions,
    )

    private fun resources(values: Map<String, String> = emptyMap()) = object : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? = values[path]
            ?.toByteArray(Charsets.UTF_8)
            ?.takeIf { it.size <= maxBytes }
    }
}
