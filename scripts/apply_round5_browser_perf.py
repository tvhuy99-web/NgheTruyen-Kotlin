from pathlib import Path

PATH = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import java.util.concurrent.TimeUnit\nimport java.util.concurrent.atomic.AtomicReference",
    "import java.util.concurrent.TimeUnit\nimport java.util.concurrent.ScheduledFuture\nimport java.util.concurrent.atomic.AtomicReference",
    "scheduled future import",
)

replace_once(
    '''    private val dnsExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "source-browser-dns").apply { isDaemon = true }
    }
    @Volatile private var active: Session? = null
    private val recoveredSources = linkedSetOf<String>()

    init {
        runCatching {
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
        }
    }
''',
    '''    private val dnsExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "source-browser-dns").apply { isDaemon = true }
    }
    private val idleExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "source-browser-idle").apply { isDaemon = true }
    }
    @Volatile private var active: Session? = null
    @Volatile private var suspendFuture: ScheduledFuture<*>? = null
    @Volatile private var destroyFuture: ScheduledFuture<*>? = null
    private val recoveredSources = linkedSetOf<String>()
    private val serviceWorkerClientInstalled = AtomicBoolean(false)
''',
    "lazy service worker fields",
)

replace_once(
    '''        return synchronized(operationLock) {
            val request = trackedRequest
            runCatching {''',
    '''        return synchronized(operationLock) {
            cancelIdleCleanup()
            val request = trackedRequest
            runCatching {''',
    "cancel idle work before action",
)

replace_once(
    '''                val session = ensureSession(manifest, request.url)
                if (request.action == SourceBrowserAction.NAVIGATE || request.action == SourceBrowserAction.LOAD_HTML) {''',
    '''                val session = ensureSession(manifest, request.url)
                resumeSession(session)
                if (request.action == SourceBrowserAction.NAVIGATE || request.action == SourceBrowserAction.LOAD_HTML) {''',
    "resume session before action",
)

replace_once(
    '''                    SourceBrowserAction.CLEAR_SESSION -> {
                        destroyActive(clearCookies = true)
                        null
                    }''',
    '''                    SourceBrowserAction.CLEAR_SESSION -> {
                        destroyActive(clearCookies = true)
                        clearWebViewCookies()
                        null
                    }''',
    "clear global browser state for explicit clear session",
)

replace_once(
    '''                active?.let { session -> captureBrowserEvidence(session, request, "completed-${request.action.name.lowercase()}") }
                SourcePlatformResult.Success(it)
''',
    '''                active?.let { session -> captureBrowserEvidence(session, request, "completed-${request.action.name.lowercase()}") }
                scheduleIdleCleanup()
                SourcePlatformResult.Success(it)
''',
    "schedule idle cleanup on success",
)

replace_once(
    '''                active?.let { session -> captureBrowserEvidence(session, request, "failed-${request.action.name.lowercase()}") }
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_BROWSER_FAILED", request.traceId, error))
''',
    '''                active?.let { session -> captureBrowserEvidence(session, request, "failed-${request.action.name.lowercase()}") }
                scheduleIdleCleanup()
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_BROWSER_FAILED", request.traceId, error))
''',
    "schedule idle cleanup on failure",
)

replace_once(
    '''    private fun ensureSession(manifest: SourceManifest, initialUrl: String?): Session {
        active?.takeIf { it.manifest.id == manifest.id && !it.rendererGone }?.let { return it }
        destroyActive(clearCookies = false)
        clearWebViewCookies()
        val session = runOnMain(20_000) { createSession(manifest) }
        active = session
        initialUrl?.let { importCookiesIntoWebView(manifest, it) }
        return session
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createSession(manifest: SourceManifest): Session {
        val sessionRef = AtomicReference<Session?>()
''',
    '''    private fun cancelIdleCleanup() {
        suspendFuture?.cancel(false)
        destroyFuture?.cancel(false)
        suspendFuture = null
        destroyFuture = null
    }

    private fun scheduleIdleCleanup() {
        val sessionId = active?.sessionId ?: return
        suspendFuture = idleExecutor.schedule({
            synchronized(operationLock) {
                val session = active?.takeIf { it.sessionId == sessionId && !it.rendererGone }
                if (session != null) suspendSession(session)
            }
        }, SESSION_SUSPEND_AFTER_MS, TimeUnit.MILLISECONDS)
        destroyFuture = idleExecutor.schedule({
            synchronized(operationLock) {
                if (active?.sessionId == sessionId) destroyActive(clearCookies = false)
            }
        }, SESSION_DESTROY_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    private fun suspendSession(session: Session) {
        if (session.suspended) return
        runCatching {
            runOnMain(5_000L) {
                session.webView.onPause()
                SourceBrowserViewportHost.detach(session.webView, session.viewportAttachment)
                session.viewportAttachment = null
            }
        }.onSuccess {
            session.suspended = true
        }
    }

    private fun resumeSession(session: Session) {
        if (!session.suspended) return
        runOnMain(5_000L) {
            if (session.viewportAttachment == null) {
                session.viewportAttachment = SourceBrowserViewportHost.attach(session.webView)
            }
            session.webView.onResume()
        }
        session.suspended = false
    }

    private fun ensureServiceWorkerClientInstalled() {
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

    private fun ensureSession(manifest: SourceManifest, initialUrl: String?): Session {
        active?.takeIf { it.manifest.id == manifest.id && !it.rendererGone }?.let { return it }
        destroyActive(clearCookies = false)
        clearWebViewCookies()
        val session = runOnMain(20_000) { createSession(manifest) }
        active = session
        initialUrl?.let { importCookiesIntoWebView(manifest, it) }
        return session
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createSession(manifest: SourceManifest): Session {
        ensureServiceWorkerClientInstalled()
        val sessionRef = AtomicReference<Session?>()
''',
    "idle lifecycle and lazy service worker methods",
)

replace_once(
    '''            session.webView.stopLoading()
            session.webView.loadUrl("about:blank")
            session.webView.clearHistory()
            session.webView.clearCache(true)
            session.webView.removeAllViews()''',
    '''            session.webView.stopLoading()
            session.webView.loadUrl("about:blank")
            session.webView.clearHistory()
            session.webView.removeAllViews()''',
    "avoid cache wipe on ordinary session destroy",
)

replace_once(
    '''        runCatching {
            runOnMain(10_000) {
                WebStorage.getInstance().deleteAllData()
                WebView(appContext).apply { clearCache(true); clearHistory(); clearFormData(); destroy() }
            }
        }
''',
    '''        runCatching {
            runOnMain(10_000) {
                WebStorage.getInstance().deleteAllData()
            }
        }
''',
    "avoid throwaway WebView and global cache wipe",
)

replace_once(
    '''        @Volatile var rendererGone: Boolean = false
        @Volatile var userAgent: String = ""''',
    '''        @Volatile var rendererGone: Boolean = false
        @Volatile var suspended: Boolean = false
        @Volatile var userAgent: String = ""''',
    "session suspended state",
)

replace_once(
    '''        const val INTERNAL_DIAGNOSTIC_REQUEST_ID = "__nghetruyenDiagnosticRequestId"
        const val INTERNAL_DIAGNOSTIC_OPERATION_ID = "__nghetruyenDiagnosticOperationId"
        val PAGE_STABILITY_SCRIPT = """''',
    '''        const val INTERNAL_DIAGNOSTIC_REQUEST_ID = "__nghetruyenDiagnosticRequestId"
        const val INTERNAL_DIAGNOSTIC_OPERATION_ID = "__nghetruyenDiagnosticOperationId"
        const val SESSION_SUSPEND_AFTER_MS = 30_000L
        const val SESSION_DESTROY_AFTER_MS = 10 * 60_000L
        val PAGE_STABILITY_SCRIPT = """''',
    "idle constants",
)

PATH.write_text(text, encoding="utf-8")

# Guard the performance intent itself so a source shift cannot silently turn this into a no-op.
final = PATH.read_text(encoding="utf-8")
assert "ServiceWorkerController.getInstance()" in final
assert "init {\n        runCatching {\n            ServiceWorkerController.getInstance()" not in final
assert "source-browser-idle" in final
assert "session.webView.onPause()" in final
assert "session.webView.onResume()" in final
assert 'WebView(appContext).apply { clearCache(true)' not in final
assert "session.webView.clearCache(true)" not in final
assert "SESSION_SUSPEND_AFTER_MS = 30_000L" in final
assert "SESSION_DESTROY_AFTER_MS = 10 * 60_000L" in final
print("Round 5 browser performance patch applied with all guards satisfied.")
