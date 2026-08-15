package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookActionRuntime

class ChromiumVBookBrowserReplayRuntimeTest {
    @Test
    fun expectedReplayYieldIsDebugAndDoesNotKeepFailureAttributes() {
        val captured = mutableListOf<DiagnosticEvent>()
        val sink = replayAwareChromiumDiagnostics(DiagnosticSink { captured += it })

        sink.emit(DiagnosticEvent(
            timestampEpochMs = 1L,
            traceId = "trace",
            sourceId = "source",
            category = DiagnosticCategory.RUNTIME,
            name = "CHROMIUM_ACTION_FAILED",
            severity = DiagnosticSeverity.ERROR,
            attributes = mapOf(
                "code" to "VBOOK_SCRIPT_ERROR",
                "error" to "CHROMIUM_EVAL_ERROR:$CHROMIUM_BROWSER_REPLAY_REQUIRED:2\nstack",
                "bridgeCalls" to "12",
            ),
        ))

        val event = captured.single()
        assertEquals("CHROMIUM_BROWSER_REPLAY_YIELDED", event.name)
        assertEquals(DiagnosticSeverity.DEBUG, event.severity)
        assertEquals("2", event.attributes["replayIndex"])
        assertEquals("12", event.attributes["bridgeCalls"])
        assertFalse(event.attributes.containsKey("code"))
        assertFalse(event.attributes.containsKey("error"))
    }

    @Test
    fun browserActionsRunAfterDelegateYieldsAndEarlierNetworkCallIsCached() {
        var now = 1_000L
        var delegateCalls = 0
        var networkCalls = 0
        var browserCalls = 0
        var insideDelegate = false

        val networkDelegate = SourceNetworkBroker { _, request ->
            assertTrue(insideDelegate)
            networkCalls += 1
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = emptyMap(),
                body = "network-ok".toByteArray(),
                timing = SourceNetworkTiming(now, now + 1),
                traceId = request.traceId,
            ))
        }
        val browserDelegate = SourceBrowserBroker { _, request ->
            assertFalse(insideDelegate)
            browserCalls += 1
            val value = when (request.action) {
                SourceBrowserAction.NAVIGATE -> "navigated"
                SourceBrowserAction.DOM_SNAPSHOT -> "<a class=\"item\">ready</a>"
                else -> error("UNEXPECTED_BROWSER_ACTION:${request.action}")
            }
            SourcePlatformResult.Success(SourceBrowserResponse(
                finalUrl = request.url ?: "https://example.com/page",
                value = value,
                traceId = request.traceId,
            ))
        }
        val replay = ChromiumVBookReplayCoordinator(browserDelegate, networkDelegate) { now }
        val delegate = VBookActionRuntime { manifest, _, request ->
            delegateCalls += 1
            insideDelegate = true
            try {
                when (val network = replay.networkBroker.execute(manifest, SourceNetworkRequest(
                    sourceId = manifest.id,
                    url = "https://example.com/bootstrap",
                    traceId = request.traceId,
                ))) {
                    is SourcePlatformResult.Failure -> network
                    is SourcePlatformResult.Success -> when (val navigate = replay.browserBroker.execute(manifest, SourceBrowserRequest(
                        sourceId = manifest.id,
                        action = SourceBrowserAction.NAVIGATE,
                        url = "https://example.com/page",
                        traceId = request.traceId,
                    ))) {
                        is SourcePlatformResult.Failure -> navigate
                        is SourcePlatformResult.Success -> when (val snapshot = replay.browserBroker.execute(manifest, SourceBrowserRequest(
                            sourceId = manifest.id,
                            action = SourceBrowserAction.DOM_SNAPSHOT,
                            traceId = request.traceId,
                        ))) {
                            is SourcePlatformResult.Failure -> snapshot
                            is SourcePlatformResult.Success -> SourcePlatformResult.Success(SourceActionResponse(
                                JsonValue.Str(snapshot.value.value.orEmpty()),
                                request.traceId,
                                0,
                            ))
                        }
                    }
                }
            } finally {
                insideDelegate = false
            }
        }
        val runtime = ChromiumVBookBrowserReplayRuntime(
            delegate = delegate,
            replay = replay,
            clockMs = { now },
        )

        val result = runtime.execute(manifest(), resources(), request())

        val success = result as SourcePlatformResult.Success
        assertEquals("<a class=\"item\">ready</a>", (success.value.value as JsonValue.Str).value)
        assertEquals(3, delegateCalls)
        assertEquals(1, networkCalls)
        assertEquals(2, browserCalls)
    }

    private fun request() = SourceActionRequest(
        sourceId = "vn.nghetruyen.sources.replaytest",
        action = SourceActionName.UI_ACTION,
        traceId = "replay-test-trace",
    )

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "vn.nghetruyen.sources.replaytest",
        name = "Replay Test",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT, actionTimeoutMs = 5_000),
        origins = setOf("https://example.com"),
        capabilities = SourceCapabilities(),
        actions = mapOf(SourceActionName.UI_ACTION to SourceActionSpec("src/action.js", timeoutMs = 5_000)),
    )

    private fun resources() = object : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? = null
    }
}
