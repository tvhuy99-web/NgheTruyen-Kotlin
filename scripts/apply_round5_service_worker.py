from pathlib import Path

PATH = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''    private val recoveredSources = linkedSetOf<String>()
    private val serviceWorkerClientInstalled = AtomicBoolean(false)
''',
    '''    private val recoveredSources = linkedSetOf<String>()
''',
    "remove per-broker service worker install flag",
)

replace_once(
    '''                require(request.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_BROWSER_OUTPUT_LIMIT_INVALID" }
                requireCapability(manifest, request.action)
                val session = ensureSession(manifest, request.url)
                resumeSession(session)
                if (request.action == SourceBrowserAction.NAVIGATE || request.action == SourceBrowserAction.LOAD_HTML) {
''',
    '''                require(request.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_BROWSER_OUTPUT_LIMIT_INVALID" }
                requireCapability(manifest, request.action)
                if (request.action == SourceBrowserAction.CLOSE_SESSION || request.action == SourceBrowserAction.CLEAR_SESSION) {
                    val existing = active?.takeIf { it.manifest.id == manifest.id }
                    val metadata = existing?.metadata?.toList().orEmpty()
                    val dialogs = existing?.dialogs?.toList().orEmpty()
                    val finalUrl = existing?.logicalPageUrl
                    diagnostics.emit(event(manifest, request, "BROWSER_ACTION_STARTED", attributes = mapOf(
                        "action" to request.action.name,
                        "flow" to "browser",
                        "stage" to "action_start",
                        "timeoutMs" to request.timeoutMs.toString(),
                        "deadlineEpochMs" to (started + request.timeoutMs).toString(),
                        "requestId" to requestId,
                        "sessionId" to existing?.sessionId.orEmpty(),
                        "navigationGeneration" to (existing?.navigationGeneration ?: 0L).toString(),
                        "url" to diagnosticUrl(request.url.orEmpty()),
                    )))
                    if (existing != null) {
                        destroyActive(clearCookies = request.action == SourceBrowserAction.CLEAR_SESSION)
                    } else if (request.action == SourceBrowserAction.CLEAR_SESSION) {
                        cookiePartition.clear(manifest.id)
                    }
                    if (request.action == SourceBrowserAction.CLEAR_SESSION) clearWebViewCookies()
                    return@runCatching SourceBrowserResponse(
                        finalUrl = finalUrl,
                        title = null,
                        value = null,
                        requestMetadata = metadata,
                        dialogs = dialogs,
                        degradedIsolation = true,
                        rendererRecovered = recoveredSources.remove(manifest.id),
                        traceId = request.traceId,
                    )
                }
                val session = ensureSession(manifest, request.url)
                serviceWorkerOwner.set(this)
                resumeSession(session)
                if (request.action == SourceBrowserAction.NAVIGATE || request.action == SourceBrowserAction.LOAD_HTML) {
''',
    "close-clear fast path and service worker ownership",
)

replace_once(
    '''                    SourceBrowserAction.CLEAR_COOKIES -> clearCookies(session, manifest, request)
                    SourceBrowserAction.CLOSE_SESSION -> {
                        destroyActive(clearCookies = false)
                        null
                    }
                    SourceBrowserAction.CLEAR_SESSION -> {
                        destroyActive(clearCookies = true)
                        clearWebViewCookies()
                        null
                    }
''',
    '''                    SourceBrowserAction.CLEAR_COOKIES -> clearCookies(session, manifest, request)
                    SourceBrowserAction.CLOSE_SESSION, SourceBrowserAction.CLEAR_SESSION ->
                        error("SOURCE_BROWSER_SESSION_ACTION_FAST_PATH_MISSED")
''',
    "make close-clear main action branch unreachable",
)

old_service_worker = '''    private fun ensureServiceWorkerClientInstalled() {
        if (!serviceWorkerClientInstalled.compareAndSet(false, true)) return
        val installed = runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    val session = active ?: return blockedResponse()
                    return when (val decision = evaluateWithBackgroundDns(session, request.url.toString())) {
                        is BrowserNavigationPolicy.Decision.Allowed -> {
                            session.takeIf { it.manifest.capabilities.browser.serviceWorkerCapture }
                                ?.record(request, resourceType = "service-worker")
                            null
                        }
                        else -> {
                            session.record(request, resourceType = "service-worker-blocked")
                            emitUrlPolicyDecision(session, "service_worker", decision, DiagnosticSeverity.WARN)
                            blockedResponse()
                        }
                    }
                }
            })
        }.isSuccess
        if (!installed) serviceWorkerClientInstalled.set(false)
    }
'''
new_service_worker = '''    private fun interceptServiceWorkerRequest(request: WebResourceRequest): WebResourceResponse? {
        val session = active ?: return blockedResponse()
        return when (val decision = evaluateWithBackgroundDns(session, request.url.toString())) {
            is BrowserNavigationPolicy.Decision.Allowed -> {
                session.takeIf { it.manifest.capabilities.browser.serviceWorkerCapture }
                    ?.record(request, resourceType = "service-worker")
                null
            }
            else -> {
                session.record(request, resourceType = "service-worker-blocked")
                emitUrlPolicyDecision(session, "service_worker", decision, DiagnosticSeverity.WARN)
                blockedResponse()
            }
        }
    }
'''
replace_once(old_service_worker, new_service_worker, "replace per-broker service worker client")

replace_once(
    '''    @SuppressLint("SetJavaScriptEnabled")
    private fun createSession(manifest: SourceManifest): Session {
        ensureServiceWorkerClientInstalled()
        val sessionRef = AtomicReference<Session?>()
''',
    '''    @SuppressLint("SetJavaScriptEnabled")
    private fun createSession(manifest: SourceManifest): Session {
        ensureProcessServiceWorkerClientInstalled()
        val sessionRef = AtomicReference<Session?>()
''',
    "install process service worker client lazily",
)

replace_once(
    '''        active = null
        if (clearCookies) cookiePartition.clear(session.manifest.id)
    }
''',
    '''        active = null
        serviceWorkerOwner.compareAndSet(this, null)
        if (clearCookies) cookiePartition.clear(session.manifest.id)
    }
''',
    "release service worker ownership when session dies",
)

replace_once(
    '''        const val INTERNAL_DIAGNOSTIC_REQUEST_ID = "__nghetruyenDiagnosticRequestId"
        const val INTERNAL_DIAGNOSTIC_OPERATION_ID = "__nghetruyenDiagnosticOperationId"
        const val SESSION_SUSPEND_AFTER_MS = 30_000L
''',
    '''        private val serviceWorkerClientInstalled = AtomicBoolean(false)
        private val serviceWorkerOwner = AtomicReference<AndroidSourceBrowserBroker?>(null)

        private fun ensureProcessServiceWorkerClientInstalled() {
            if (!serviceWorkerClientInstalled.compareAndSet(false, true)) return
            val installed = runCatching {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        serviceWorkerOwner.get()?.interceptServiceWorkerRequest(request)
                            ?: blockedServiceWorkerResponse()
                })
            }.isSuccess
            if (!installed) serviceWorkerClientInstalled.set(false)
        }

        private fun blockedServiceWorkerResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0)),
        )

        const val INTERNAL_DIAGNOSTIC_REQUEST_ID = "__nghetruyenDiagnosticRequestId"
        const val INTERNAL_DIAGNOSTIC_OPERATION_ID = "__nghetruyenDiagnosticOperationId"
        const val SESSION_SUSPEND_AFTER_MS = 30_000L
''',
    "add process-wide service worker router",
)

PATH.write_text(text, encoding="utf-8")

final = PATH.read_text(encoding="utf-8")
assert "private val serviceWorkerOwner = AtomicReference<AndroidSourceBrowserBroker?>(null)" in final
assert final.count("setServiceWorkerClient(object : ServiceWorkerClient()") == 1
assert "private fun interceptServiceWorkerRequest" in final
assert "serviceWorkerOwner.set(this)" in final
assert "serviceWorkerOwner.compareAndSet(this, null)" in final
assert "SOURCE_BROWSER_SESSION_ACTION_FAST_PATH_MISSED" in final
assert "val existing = active?.takeIf { it.manifest.id == manifest.id }" in final
assert "ensureServiceWorkerClientInstalled()" not in final
assert "private val serviceWorkerClientInstalled = AtomicBoolean(false)" in final
print("Round 5 process ServiceWorker + close/clear fast-path patch applied.")
