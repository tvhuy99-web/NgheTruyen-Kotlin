package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookActionRuntime
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val CHROMIUM_BROWSER_REPLAY_REQUIRED = "SOURCE_BROWSER_REPLAY_REQUIRED"
internal const val CHROMIUM_BROWSER_NETWORK_REPLAY_REQUIRED = "SOURCE_BROWSER_NETWORK_REPLAY_REQUIRED"

/**
 * Runs Browser host calls and browser-session network calls between Chromium evaluation passes.
 *
 * The action WebView owns a synchronous prompt rendezvous on the main thread. Browser work also
 * needs the main thread, so both Browser.* and browser-context fetches must yield, run after the
 * action WebView has been destroyed, and then replay the same extension script. Earlier network
 * calls are cached by call position so replay never repeats their upstream side effects.
 */
internal class ChromiumVBookBrowserReplayRuntime(
    private val delegate: VBookActionRuntime,
    private val replay: ChromiumVBookReplayCoordinator,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : VBookActionRuntime {
    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> {
        val action = manifest.actions[request.action] ?: return delegate.execute(manifest, resources, request)
        val timeoutMs = (action.timeoutMs ?: manifest.runtime.actionTimeoutMs).coerceIn(MIN_PASS_BUDGET_MS, 120_000L)
        val deadlineMs = clockMs() + timeoutMs
        val originalTraceId = request.traceId
        val effectiveTraceId = originalTraceId.ifBlank { "chromium-replay:${UUID.randomUUID()}" }
        val effectiveRequest = if (effectiveTraceId == originalTraceId) request else request.copy(traceId = effectiveTraceId)

        if (!replay.open(effectiveTraceId, deadlineMs)) {
            return SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.INTERNAL_ERROR,
                "CHROMIUM_BROWSER_REPLAY_TRACE_COLLISION",
                originalTraceId,
            ))
        }

        var resolvedBrowserActions = 0
        var resolvedBrowserNetworkRequests = 0
        return try {
            while (true) {
                val remainingMs = deadlineMs - clockMs()
                if (remainingMs < MIN_PASS_BUDGET_MS) {
                    return restoreTrace(
                        SourcePlatformResult.Failure(SourcePlatformFailure(
                            SourceErrorCode.RUNTIME_BUDGET_EXCEEDED,
                            "CHROMIUM_BROWSER_REPLAY_TIMEOUT",
                            effectiveTraceId,
                        )),
                        originalTraceId,
                        effectiveTraceId,
                    )
                }
                if (resolvedBrowserActions + resolvedBrowserNetworkRequests >= MAX_REPLAYS) {
                    return restoreTrace(
                        SourcePlatformResult.Failure(SourcePlatformFailure(
                            SourceErrorCode.RUNTIME_BUDGET_EXCEEDED,
                            "CHROMIUM_BROWSER_REPLAY_LIMIT:$MAX_REPLAYS",
                            effectiveTraceId,
                        )),
                        originalTraceId,
                        effectiveTraceId,
                    )
                }

                replay.beginPass(effectiveTraceId)
                val passManifest = manifest.copy(actions = manifest.actions + (
                    request.action to action.copy(timeoutMs = remainingMs.coerceAtMost(120_000L))
                ))
                val result = delegate.execute(passManifest, resources, effectiveRequest)

                when {
                    replay.hasPendingBrowser(effectiveTraceId) -> {
                        when (val resolved = replay.resolvePendingBrowser(effectiveTraceId)) {
                            is SourcePlatformResult.Success -> resolvedBrowserActions += 1
                            is SourcePlatformResult.Failure -> return restoreTrace(
                                resolved,
                                originalTraceId,
                                effectiveTraceId,
                            )
                        }
                    }
                    replay.hasPendingBrowserNetwork(effectiveTraceId) -> {
                        when (val resolved = replay.resolvePendingBrowserNetwork(effectiveTraceId)) {
                            is SourcePlatformResult.Success -> resolvedBrowserNetworkRequests += 1
                            is SourcePlatformResult.Failure -> return restoreTrace(
                                resolved,
                                originalTraceId,
                                effectiveTraceId,
                            )
                        }
                    }
                    else -> {
                        if (resolvedBrowserActions > 0 || resolvedBrowserNetworkRequests > 0) {
                            diagnostics.emit(DiagnosticEvent(
                                timestampEpochMs = clockMs(),
                                traceId = effectiveTraceId,
                                sourceId = manifest.id,
                                sourceVersion = manifest.version.toString(),
                                category = DiagnosticCategory.REPLAY,
                                name = "CHROMIUM_BROWSER_REPLAY_COMPLETED",
                                severity = DiagnosticSeverity.INFO,
                                attributes = mapOf(
                                    "browserActions" to resolvedBrowserActions.toString(),
                                    "browserNetworkRequests" to resolvedBrowserNetworkRequests.toString(),
                                    "networkReplayHits" to replay.networkReplayHits(effectiveTraceId).toString(),
                                ),
                            ))
                        }
                        return restoreTrace(result, originalTraceId, effectiveTraceId)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.INTERNAL_ERROR,
                "CHROMIUM_BROWSER_REPLAY_UNREACHABLE",
                effectiveTraceId,
            ))
        } finally {
            replay.close(effectiveTraceId)
        }
    }

    private fun <T> restoreTrace(
        result: SourcePlatformResult<T>,
        originalTraceId: String,
        effectiveTraceId: String,
    ): SourcePlatformResult<T> {
        if (originalTraceId == effectiveTraceId) return result
        @Suppress("UNCHECKED_CAST")
        return when (result) {
            is SourcePlatformResult.Success<*> -> {
                val value = result.value
                if (value is SourceActionResponse) {
                    SourcePlatformResult.Success(value.copy(traceId = originalTraceId)) as SourcePlatformResult<T>
                } else result as SourcePlatformResult<T>
            }
            is SourcePlatformResult.Failure -> SourcePlatformResult.Failure(
                result.error.copy(traceId = originalTraceId),
            )
        }
    }

    companion object {
        private const val MIN_PASS_BUDGET_MS = 500L
        private const val MAX_REPLAYS = 64
    }
}


internal class ChromiumVBookReplayCoordinator(
    private val browserDelegate: SourceBrowserBroker,
    private val networkDelegate: SourceNetworkBroker,
    private val browserNetworkDelegate: SourceNetworkBroker? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val states = ConcurrentHashMap<String, ReplayState>()

    val browserBroker: SourceBrowserBroker = SourceBrowserBroker { manifest, request ->
        states[request.traceId]?.browser(manifest, request)
            ?: browserDelegate.execute(manifest, request)
    }

    val networkBroker: SourceNetworkBroker = SourceNetworkBroker { manifest, request ->
        states[request.traceId]?.network(manifest, request)
            ?: networkDelegate.execute(manifest, request)
    }

    fun open(traceId: String, deadlineMs: Long): Boolean =
        states.putIfAbsent(traceId, ReplayState(deadlineMs)) == null

    fun beginPass(traceId: String) {
        states[traceId]?.beginPass() ?: error("CHROMIUM_BROWSER_REPLAY_STATE_MISSING")
    }

    fun hasPendingBrowser(traceId: String): Boolean = states[traceId]?.hasPendingBrowser() == true

    fun hasPendingBrowserNetwork(traceId: String): Boolean = states[traceId]?.hasPendingBrowserNetwork() == true

    fun resolvePendingBrowser(traceId: String): SourcePlatformResult<Unit> =
        states[traceId]?.resolvePendingBrowser()
            ?: missingState(traceId)

    fun resolvePendingBrowserNetwork(traceId: String): SourcePlatformResult<Unit> =
        states[traceId]?.resolvePendingBrowserNetwork()
            ?: missingState(traceId)

    fun networkReplayHits(traceId: String): Int = states[traceId]?.networkReplayHits ?: 0

    fun close(traceId: String) {
        states.remove(traceId)
    }

    private fun missingState(traceId: String) = SourcePlatformResult.Failure(SourcePlatformFailure(
        SourceErrorCode.INTERNAL_ERROR,
        "CHROMIUM_BROWSER_REPLAY_STATE_MISSING",
        traceId,
    ))

    private inner class ReplayState(
        private val deadlineMs: Long,
    ) {
        private val browserCache = mutableListOf<BrowserRecord>()
        private val networkCache = mutableListOf<NetworkRecord>()
        private var browserCursor = 0
        private var networkCursor = 0
        private var pendingBrowser: PendingBrowser? = null
        private var pendingBrowserNetwork: PendingBrowserNetwork? = null
        private var browserContextReady = false
        private var browserNetworkFloor: Int? = null
        var networkReplayHits: Int = 0
            private set

        @Synchronized
        fun beginPass() {
            browserCursor = 0
            networkCursor = 0
            pendingBrowser = null
            pendingBrowserNetwork = null
        }

        @Synchronized
        fun hasPendingBrowser(): Boolean = pendingBrowser != null

        @Synchronized
        fun hasPendingBrowserNetwork(): Boolean = pendingBrowserNetwork != null

        @Synchronized
        fun browser(
            manifest: SourceManifest,
            request: SourceBrowserRequest,
        ): SourcePlatformResult<SourceBrowserResponse> {
            pendingBrowser?.let { return browserReplayRequired(request, it.index) }
            pendingBrowserNetwork?.let { return browserReplayRequired(request, it.index) }

            val index = browserCursor++
            val key = BrowserKey.from(request)
            if (index < browserCache.size) {
                val cached = browserCache[index]
                if (cached.key == key) return cached.result
                truncateBrowserCache(index)
            }

            if (browserNetworkFloor == null) browserNetworkFloor = networkCursor
            pendingBrowser = PendingBrowser(index, key, manifest, request)
            return browserReplayRequired(request, index)
        }

        @Synchronized
        fun network(
            manifest: SourceManifest,
            request: SourceNetworkRequest,
        ): SourcePlatformResult<SourceNetworkResponse> {
            pendingBrowser?.let { pending ->
                return networkFailure(request, CHROMIUM_BROWSER_REPLAY_REQUIRED, pending.index)
            }
            pendingBrowserNetwork?.let { pending ->
                return networkFailure(request, CHROMIUM_BROWSER_NETWORK_REPLAY_REQUIRED, pending.index)
            }

            val index = networkCursor++
            val key = NetworkKey.from(request)
            if (index < networkCache.size) {
                val cached = networkCache[index]
                if (cached.key == key) {
                    networkReplayHits += 1
                    return cached.result.asReplay()
                }
                truncateNetworkCache(index)
            }

            if (shouldUseBrowserNetwork(manifest, index)) {
                pendingBrowserNetwork = PendingBrowserNetwork(index, key, manifest, request)
                return networkFailure(request, CHROMIUM_BROWSER_NETWORK_REPLAY_REQUIRED, index)
            }

            val remainingMs = deadlineMs - clockMs()
            if (remainingMs < MIN_BROKER_BUDGET_MS) {
                return SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.NETWORK_TIMEOUT,
                    "CHROMIUM_REPLAY_NETWORK_TIMEOUT",
                    request.traceId,
                ))
            }
            val bounded = request.copy(timeoutMs = minOf(request.timeoutMs, remainingMs.coerceAtMost(120_000L)))
            val result = runCatching { networkDelegate.execute(manifest, bounded) }
                .getOrElse { error -> SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.NETWORK_IO_ERROR,
                    error.message ?: "CHROMIUM_REPLAY_NETWORK_FAILED",
                    request.traceId,
                    error,
                )) }
            networkCache += NetworkRecord(key, result)
            return result
        }

        @Synchronized
        fun resolvePendingBrowser(): SourcePlatformResult<Unit> {
            val pending = pendingBrowser ?: return SourcePlatformResult.Success(Unit)
            val remainingMs = deadlineMs - clockMs()
            if (remainingMs < MIN_BROKER_BUDGET_MS) {
                return SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.RUNTIME_BUDGET_EXCEEDED,
                    "CHROMIUM_BROWSER_REPLAY_TIMEOUT",
                    pending.request.traceId,
                ))
            }
            val bounded = pending.request.copy(
                timeoutMs = minOf(pending.request.timeoutMs, remainingMs.coerceAtMost(120_000L)),
            )
            val result = runCatching { browserDelegate.execute(pending.manifest, bounded) }
                .getOrElse { error -> SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.BROWSER_UNAVAILABLE,
                    error.message ?: "CHROMIUM_REPLAY_BROWSER_FAILED",
                    pending.request.traceId,
                    error,
                )) }
            truncateBrowserCache(pending.index)
            browserCache += BrowserRecord(pending.key, result)
            if (result is SourcePlatformResult.Success) browserContextReady = true
            pendingBrowser = null
            return SourcePlatformResult.Success(Unit)
        }

        @Synchronized
        fun resolvePendingBrowserNetwork(): SourcePlatformResult<Unit> {
            val pending = pendingBrowserNetwork ?: return SourcePlatformResult.Success(Unit)
            val delegate = browserNetworkDelegate ?: return SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.NETWORK_UNAVAILABLE,
                "CHROMIUM_BROWSER_NETWORK_DELEGATE_MISSING",
                pending.request.traceId,
            ))
            val remainingMs = deadlineMs - clockMs()
            if (remainingMs < MIN_BROKER_BUDGET_MS) {
                return SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.NETWORK_TIMEOUT,
                    "CHROMIUM_BROWSER_NETWORK_REPLAY_TIMEOUT",
                    pending.request.traceId,
                ))
            }
            val bounded = pending.request.copy(
                timeoutMs = minOf(pending.request.timeoutMs, remainingMs.coerceAtMost(120_000L)),
            )
            val result = runCatching { delegate.execute(pending.manifest, bounded) }
                .getOrElse { error -> SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.NETWORK_IO_ERROR,
                    error.message ?: "CHROMIUM_BROWSER_NETWORK_FAILED",
                    pending.request.traceId,
                    error,
                )) }
            truncateNetworkCache(pending.index)
            networkCache += NetworkRecord(pending.key, result)
            pendingBrowserNetwork = null
            return SourcePlatformResult.Success(Unit)
        }

        private fun shouldUseBrowserNetwork(manifest: SourceManifest, index: Int): Boolean {
            val floor = browserNetworkFloor ?: return false
            return browserNetworkDelegate != null &&
                browserContextReady &&
                index >= floor &&
                manifest.capabilities.cookies == SourceCookieMode.BROWSER_SHARED
        }

        private fun browserReplayRequired(
            request: SourceBrowserRequest,
            index: Int,
        ): SourcePlatformResult.Failure = SourcePlatformResult.Failure(SourcePlatformFailure(
            SourceErrorCode.BROWSER_UNAVAILABLE,
            "$CHROMIUM_BROWSER_REPLAY_REQUIRED:$index",
            request.traceId,
        ))

        private fun networkFailure(
            request: SourceNetworkRequest,
            marker: String,
            index: Int,
        ): SourcePlatformResult.Failure = SourcePlatformResult.Failure(SourcePlatformFailure(
            SourceErrorCode.NETWORK_UNAVAILABLE,
            "$marker:$index",
            request.traceId,
        ))

        private fun truncateBrowserCache(index: Int) {
            while (browserCache.size > index) browserCache.removeAt(browserCache.lastIndex)
        }

        private fun truncateNetworkCache(index: Int) {
            while (networkCache.size > index) networkCache.removeAt(networkCache.lastIndex)
        }
    }

    private data class PendingBrowser(
        val index: Int,
        val key: BrowserKey,
        val manifest: SourceManifest,
        val request: SourceBrowserRequest,
    )

    private data class PendingBrowserNetwork(
        val index: Int,
        val key: NetworkKey,
        val manifest: SourceManifest,
        val request: SourceNetworkRequest,
    )

    private data class BrowserRecord(
        val key: BrowserKey,
        val result: SourcePlatformResult<SourceBrowserResponse>,
    )

    private data class NetworkRecord(
        val key: NetworkKey,
        val result: SourcePlatformResult<SourceNetworkResponse>,
    )

    private data class BrowserKey(
        val sourceId: String,
        val action: String,
        val url: String?,
        val selector: String?,
        val value: String?,
        val script: String?,
        val values: List<String>,
        val options: Map<String, String>,
        val maxOutputBytes: Int,
    ) {
        companion object {
            fun from(request: SourceBrowserRequest) = BrowserKey(
                sourceId = request.sourceId,
                action = request.action.name,
                url = request.url,
                selector = request.selector,
                value = request.value,
                script = request.script,
                values = request.values.toList(),
                options = request.options.toMap(),
                maxOutputBytes = request.maxOutputBytes,
            )
        }
    }

    private data class NetworkKey(
        val sourceId: String,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val bodySha256: String,
        val contentType: String?,
        val responseMode: String,
        val allowHttpError: Boolean,
    ) {
        companion object {
            fun from(request: SourceNetworkRequest) = NetworkKey(
                sourceId = request.sourceId,
                url = request.url,
                method = request.method.uppercase(Locale.ROOT),
                headers = request.headers.entries
                    .filterNot { (name, _) -> name.equals(VBOOK_REQUEST_KEY_HEADER, ignoreCase = true) }
                    .associate { (name, value) -> name.lowercase(Locale.ROOT) to value },
                bodySha256 = request.body.sha256(),
                contentType = request.contentType,
                responseMode = request.responseMode.name,
                allowHttpError = request.allowHttpError,
            )
        }
    }

    companion object {
        private const val MIN_BROKER_BUDGET_MS = 100L
        private const val VBOOK_REQUEST_KEY_HEADER = "X-Nghe-VBook-Request-Key"
    }
}

internal fun replayAwareChromiumDiagnostics(delegate: DiagnosticSink): DiagnosticSink = DiagnosticSink { event ->
    val error = event.attributes["error"].orEmpty()
    val marker = when {
        CHROMIUM_BROWSER_NETWORK_REPLAY_REQUIRED in error -> CHROMIUM_BROWSER_NETWORK_REPLAY_REQUIRED
        CHROMIUM_BROWSER_REPLAY_REQUIRED in error -> CHROMIUM_BROWSER_REPLAY_REQUIRED
        else -> null
    }
    val isReplayYield = event.name == "CHROMIUM_ACTION_FAILED" && marker != null
    if (isReplayYield) {
        val replayIndex = error
            .substringAfter("$marker:", "")
            .substringBefore('\n')
            .trim()
        delegate.emit(event.copy(
            category = DiagnosticCategory.REPLAY,
            name = "CHROMIUM_BROWSER_REPLAY_YIELDED",
            severity = DiagnosticSeverity.DEBUG,
            attributes = event.attributes
                .filterKeys { it !in setOf("code", "error", "cause", "stack") } + mapOf(
                "replayIndex" to replayIndex,
                "yieldReason" to if (marker == CHROMIUM_BROWSER_NETWORK_REPLAY_REQUIRED) "browser-network" else "browser-action",
            ),
        ))
    } else {
        delegate.emit(event)
    }
}

private fun SourcePlatformResult<SourceNetworkResponse>.asReplay(): SourcePlatformResult<SourceNetworkResponse> = when (this) {
    is SourcePlatformResult.Success -> SourcePlatformResult.Success(value.copy(fromReplay = true))
    is SourcePlatformResult.Failure -> this
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
