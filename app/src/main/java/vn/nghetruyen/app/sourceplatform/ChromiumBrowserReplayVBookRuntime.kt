package vn.nghetruyen.app.sourceplatform

import android.content.Context
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookActionRuntime
import vn.nghetruyen.source.vbook.VBookRawNetworkBroker
import java.security.MessageDigest

/**
 * Runs Chromium vBook actions with cooperative Browser replay.
 *
 * A synchronous JavaScript prompt cannot safely drive a second Android WebView while the action
 * WebView is paused waiting for that prompt result. Browser calls are therefore deferred: the
 * first Chromium pass records the Browser request and exits, the real Browser broker runs only
 * after that action WebView has been released, and the script is replayed with the Browser result
 * cached. Network results are memoized by call order for the lifetime of the action so requests
 * that happened before a deferred Browser call are not repeated on replay.
 *
 * All replay rounds share one original action deadline and are bounded. Ordinary Chromium failures
 * are never replayed unless a Browser request was actually deferred.
 */
class ChromiumBrowserReplayVBookRuntime(
    context: Context,
    brokers: SourceCapabilityBrokers,
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : VBookActionRuntime, AutoCloseable {
    private val actionLock = Any()
    private val network = ReplayNetworkBroker(brokers.network)
    private val browser = ReplayBrowserBroker(brokers.browser)
    private val chromium = AndroidChromiumVBookRuntime(
        context = context,
        brokers = brokers.copy(network = network, browser = browser),
        diagnostics = diagnostics,
        clockMs = clockMs,
    )
    private val delegate: VBookActionRuntime = ChromiumVBookDispatcherParityRuntime(chromium)

    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> = synchronized(actionLock) {
        val action = manifest.actions[request.action] ?: return@synchronized delegate.execute(manifest, resources, request)
        val originalTimeoutMs = (action.timeoutMs ?: manifest.runtime.actionTimeoutMs).coerceIn(100L, MAX_ACTION_TIMEOUT_MS)
        val deadlineMs = clockMs() + originalTimeoutMs

        network.beginAction()
        browser.beginAction()
        try {
            repeat(MAX_BROWSER_REPLAY_ROUNDS + 1) { round ->
                val remainingMs = deadlineMs - clockMs()
                if (remainingMs < MIN_ACTION_TIMEOUT_MS) {
                    return@synchronized replayFailure("CHROMIUM_BROWSER_REPLAY_DEADLINE", request)
                }

                network.beginRound()
                browser.beginRound()
                val roundAction = action.copy(timeoutMs = remainingMs.coerceIn(MIN_ACTION_TIMEOUT_MS, MAX_ACTION_TIMEOUT_MS))
                val roundManifest = manifest.copy(actions = manifest.actions + (request.action to roundAction))
                val result = delegate.execute(roundManifest, resources, request)
                val pending = browser.takePending()
                    ?: return@synchronized result

                if (round >= MAX_BROWSER_REPLAY_ROUNDS) {
                    return@synchronized replayFailure("CHROMIUM_BROWSER_REPLAY_LIMIT", request)
                }
                val browserBudgetMs = deadlineMs - clockMs()
                if (browserBudgetMs < MIN_ACTION_TIMEOUT_MS) {
                    return@synchronized replayFailure("CHROMIUM_BROWSER_REPLAY_DEADLINE", request)
                }
                browser.resolve(pending, manifest, browserBudgetMs)
            }
            replayFailure("CHROMIUM_BROWSER_REPLAY_LIMIT", request)
        } finally {
            browser.endAction()
            network.endAction()
        }
    }

    override fun close() {
        chromium.close()
    }

    private fun replayFailure(message: String, request: SourceActionRequest): SourcePlatformResult.Failure =
        SourcePlatformResult.Failure(SourcePlatformFailure(
            SourceErrorCode.RUNTIME_BUDGET_EXCEEDED,
            message,
            request.traceId,
        ))

    private class ReplayBrowserBroker(
        private val delegate: SourceBrowserBroker,
    ) : SourceBrowserBroker {
        private val cache = linkedMapOf<String, SourcePlatformResult<SourceBrowserResponse>>()
        private var sequence = 0
        private var pending: Pending? = null

        fun beginAction() {
            cache.clear()
            beginRound()
        }

        fun beginRound() {
            sequence = 0
            pending = null
        }

        fun takePending(): Pending? = pending

        fun resolve(value: Pending, manifest: SourceManifest, budgetMs: Long) {
            if (value.key in cache) return
            require(cache.size < MAX_REPLAY_CACHE_ENTRIES) { "CHROMIUM_BROWSER_REPLAY_CACHE_LIMIT" }
            val bounded = value.request.copy(
                timeoutMs = minOf(value.request.timeoutMs, budgetMs).coerceIn(MIN_ACTION_TIMEOUT_MS, MAX_ACTION_TIMEOUT_MS),
            )
            cache[value.key] = delegate.execute(manifest, bounded)
        }

        fun endAction() {
            cache.clear()
            pending = null
            sequence = 0
        }

        override fun execute(
            manifest: SourceManifest,
            request: SourceBrowserRequest,
        ): SourcePlatformResult<SourceBrowserResponse> {
            val key = browserKey(sequence++, request)
            cache[key]?.let { return it }
            if (pending == null) pending = Pending(key, request)
            return SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.BROWSER_UNAVAILABLE,
                "CHROMIUM_BROWSER_REPLAY_REQUIRED:$key",
                request.traceId,
            ))
        }

        data class Pending(val key: String, val request: SourceBrowserRequest)
    }

    private class ReplayNetworkBroker(
        private val delegate: SourceNetworkBroker,
    ) : SourceNetworkBroker {
        private val cache = linkedMapOf<String, SourcePlatformResult<SourceNetworkResponse>>()
        private var sequence = 0

        fun beginAction() {
            cache.clear()
            beginRound()
        }

        fun beginRound() {
            sequence = 0
        }

        fun endAction() {
            cache.clear()
            sequence = 0
        }

        override fun execute(
            manifest: SourceManifest,
            request: SourceNetworkRequest,
        ): SourcePlatformResult<SourceNetworkResponse> {
            val key = networkKey(sequence++, request)
            cache[key]?.let { return it }
            require(cache.size < MAX_REPLAY_CACHE_ENTRIES) { "CHROMIUM_NETWORK_REPLAY_CACHE_LIMIT" }
            return delegate.execute(manifest, request).also { cache[key] = it }
        }
    }

    companion object {
        private const val MIN_ACTION_TIMEOUT_MS = 100L
        private const val MAX_ACTION_TIMEOUT_MS = 120_000L
        private const val MAX_BROWSER_REPLAY_ROUNDS = 16
        private const val MAX_REPLAY_CACHE_ENTRIES = 256

        private fun browserKey(sequence: Int, request: SourceBrowserRequest): String = stableKey(
            "browser",
            sequence.toString(),
            request.action.name,
            request.url.orEmpty(),
            request.selector.orEmpty(),
            request.value.orEmpty(),
            request.script.orEmpty(),
            request.values.joinToString("\u0001"),
            request.options.toSortedMap().entries.joinToString("\u0001") { "${it.key}=${it.value}" },
        )

        private fun networkKey(sequence: Int, request: SourceNetworkRequest): String = stableKey(
            "network",
            sequence.toString(),
            request.method.uppercase(),
            request.url,
            externalNetworkHeaders(request),
            controlHeader(request, VBookRawNetworkBroker.INTERNAL_OPERATION)?.lowercase().orEmpty(),
            controlHeader(request, VBookRawNetworkBroker.INTERNAL_DECODE_CHARSET).orEmpty(),
            request.contentType.orEmpty(),
            request.responseMode.name,
            request.allowHttpError.toString(),
            sha256(request.body),
        )

        private fun externalNetworkHeaders(request: SourceNetworkRequest): String = request.headers.entries
            .filterNot { (name, _) -> name.startsWith(VBookRawNetworkBroker.INTERNAL_PREFIX, ignoreCase = true) }
            .sortedBy { (name, _) -> name.lowercase() }
            .joinToString("\u0001") { (name, value) -> "$name=$value" }

        private fun controlHeader(request: SourceNetworkRequest, name: String): String? =
            request.headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

        private fun stableKey(vararg parts: String): String = sha256(
            parts.joinToString("\u0000").toByteArray(Charsets.UTF_8),
        )

        private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
