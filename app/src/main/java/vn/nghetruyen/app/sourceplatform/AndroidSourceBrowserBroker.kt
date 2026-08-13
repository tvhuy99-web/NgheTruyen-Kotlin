package vn.nghetruyen.app.sourceplatform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceBrowserRequestMetadata
import vn.nghetruyen.source.api.SourceBrowserResponse
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.diagnostics.DiagnosticThrowableFormatter
import vn.nghetruyen.source.network.SourceOriginPolicy
import vn.nghetruyen.source.network.PublicAddressPolicy
import org.json.JSONTokener
import org.json.JSONObject
import vn.nghetruyen.source.api.SourceBrowserDialog
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serialized WebView capability broker.
 *
 * Android WebView owns one CookieManager/profile per process, so this implementation exports/imports
 * a source-partitioned cookie jar and clears WebView state whenever the active SourcePack changes.
 * The response marks this fallback as degraded isolation. No Java object is exposed to page scripts.
 * Browser feature defaults are centralized in [ExtensionWebViewAuthority] so installed extensions
 * receive a capable browser without adding another per-feature permission maze.
 */
class AndroidSourceBrowserBroker(
    context: Context,
    private val cookiePartition: SourceCookiePartition,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) : SourceBrowserBroker {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val operationLock = Any()
    private var active: Session? = null
    private val recoveredSources = linkedSetOf<String>()

    init {
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return synchronized(operationLock) {
                        val session = active ?: return@synchronized blockedResponse()
                        if (!isAllowedRedirect(session.manifest, request.url.toString())) {
                            session.record(request, resourceType = "service-worker-blocked")
                            blockedResponse()
                        } else {
                            session.takeIf { it.manifest.capabilities.browser.serviceWorkerCapture }
                                ?.record(request, resourceType = "service-worker")
                            null
                        }
                    }
                }
            })
        }
    }

    override fun execute(manifest: SourceManifest, request: SourceBrowserRequest): SourcePlatformResult<SourceBrowserResponse> {
        val started = clockMs()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure(SourceErrorCode.BROWSER_UNAVAILABLE, "SOURCE_BROWSER_BLOCKING_CALL_ON_MAIN", request)
        }
        val requestId = UUID.randomUUID().toString()
        val trackedRequest = request.copy(options = request.options + mapOf(
            INTERNAL_DIAGNOSTIC_REQUEST_ID to requestId,
            INTERNAL_DIAGNOSTIC_OPERATION_ID to "browser:${request.traceId.ifBlank { "no-trace" }}:$requestId",
        ))
        return synchronized(operationLock) {
            val request = trackedRequest
            runCatching {
                require(request.sourceId == manifest.id) { "SOURCE_BROWSER_SOURCE_ID_MISMATCH" }
                require(request.timeoutMs in 100L..120_000L) { "SOURCE_BROWSER_TIMEOUT_INVALID" }
                require(request.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_BROWSER_OUTPUT_LIMIT_INVALID" }
                requireCapability(manifest, request.action)
                val session = ensureSession(manifest, request.url)
                if (request.action == SourceBrowserAction.NAVIGATE || request.action == SourceBrowserAction.LOAD_HTML) {
                    session.navigationGeneration += 1
                }
                session.currentTraceId = request.traceId
                captureBrowserEnvironment(session, request)
                diagnostics.emit(event(manifest, request, "BROWSER_ACTION_STARTED", attributes = mapOf(
                    "action" to request.action.name,
                    "flow" to "browser",
                    "stage" to "action_start",
                    "timeoutMs" to request.timeoutMs.toString(),
                    "deadlineEpochMs" to (started + request.timeoutMs).toString(),
                    "requestId" to requestId,
                    "sessionId" to session.sessionId,
                    "navigationGeneration" to session.navigationGeneration.toString(),
                    "url" to diagnosticUrl(request.url.orEmpty()),
                )))
                val value = when (request.action) {
                    SourceBrowserAction.NAVIGATE -> navigate(session, manifest, request)
                    SourceBrowserAction.LOAD_HTML -> loadHtml(session, manifest, request)
                    SourceBrowserAction.WAIT_SELECTOR -> waitSelector(session, request)
                    SourceBrowserAction.DOM_SNAPSHOT -> domSnapshot(session, request)
                    SourceBrowserAction.CLICK -> click(session, request)
                    SourceBrowserAction.INPUT -> input(session, request)
                    SourceBrowserAction.EVALUATE_PAGE_SCRIPT -> evaluatePageScript(session, request)
                    SourceBrowserAction.EVALUATE_PAGE_SCRIPT_ASYNC -> evaluatePageScriptAsync(session, request)
                    SourceBrowserAction.REQUEST_METADATA -> null
                    SourceBrowserAction.SET_USER_AGENT -> setUserAgent(session, request)
                    SourceBrowserAction.SET_BLOCK_PATTERNS -> setBlockPatterns(session, request)
                    SourceBrowserAction.SET_DIALOG_POLICY -> setDialogPolicy(session, request)
                    SourceBrowserAction.DIALOGS -> null
                    SourceBrowserAction.WAIT_DIALOG -> waitDialog(session, request)?.message
                    SourceBrowserAction.SYNC_SESSION -> syncSession(session, manifest, request)
                    SourceBrowserAction.SET_COOKIES -> setCookies(session, manifest, request)
                    SourceBrowserAction.CLEAR_COOKIES -> clearCookies(session, manifest, request)
                    SourceBrowserAction.CLOSE_SESSION -> {
                        destroyActive(clearCookies = false)
                        null
                    }
                    SourceBrowserAction.CLEAR_SESSION -> {
                        destroyActive(clearCookies = true)
                        null
                    }
                }
                if (session.rendererGone) error("SOURCE_BROWSER_RENDERER_GONE")
                val webViewState = snapshotWebView(session)
                val logicalUrl = reconcileLogicalPageUrl(session, webViewState)
                syncCookiesFromWebView(manifest, logicalUrl ?: request.url)
                val metadata = session.metadata.toList()
                SourceBrowserResponse(
                    finalUrl = logicalUrl,
                    title = webViewState.title,
                    value = value,
                    requestMetadata = metadata,
                    dialogs = session.dialogs.toList(),
                    degradedIsolation = true,
                    rendererRecovered = recoveredSources.remove(manifest.id),
                    traceId = request.traceId,
                )
            }.fold(
                onSuccess = {
                    diagnostics.emit(event(manifest, request, "BROWSER_ACTION_COMPLETED", durationMs = clockMs() - started, attributes = mapOf(
                    "action" to request.action.name,
                    "flow" to "browser",
                    "stage" to "action_completed",
                    "requests" to it.requestMetadata.size.toString(),
                    "degradedIsolation" to it.degradedIsolation.toString(),
                )))
                active?.let { session -> captureBrowserEvidence(session, request, "completed-${request.action.name.lowercase()}") }
                SourcePlatformResult.Success(it)

                },
                onFailure = { error ->
                    val code = when {
                        error.message?.contains("NAVIGATION") == true || error.message?.contains("ORIGIN") == true -> SourceErrorCode.BROWSER_NAVIGATION_DENIED
                        error.message?.contains("SELECTOR") == true -> SourceErrorCode.BROWSER_SELECTOR_NOT_FOUND
                        error.message?.contains("RENDERER") == true -> SourceErrorCode.BROWSER_RENDERER_GONE
                        error.message?.contains("OUTPUT_TOO_LARGE") == true -> SourceErrorCode.BROWSER_OUTPUT_TOO_LARGE
                        error.message?.contains("TIMEOUT") == true -> SourceErrorCode.BROWSER_TIMEOUT
                        else -> SourceErrorCode.BROWSER_UNAVAILABLE
                    }
                    diagnostics.emit(event(manifest, request, "BROWSER_ACTION_FAILED", DiagnosticSeverity.ERROR, clockMs() - started, mapOf(
                    "action" to request.action.name,
                    "flow" to "browser",
                    "stage" to "action_failed",
                    "code" to code.name,
                    "error" to (error.message ?: error.javaClass.simpleName),
                ) + DiagnosticThrowableFormatter.attributes(error)))
                active?.let { session -> captureBrowserEvidence(session, request, "failed-${request.action.name.lowercase()}") }
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_BROWSER_FAILED", request.traceId, error))

                },
            )
        }
    }

    private fun captureBrowserEvidence(session: Session, request: SourceBrowserRequest, reason: String) {
        if (!evidence.enabled || request.action in setOf(SourceBrowserAction.CLOSE_SESSION, SourceBrowserAction.CLEAR_SESSION)) return
        val trace = request.traceId.ifBlank { session.currentTraceId.ifBlank { "browser-session:${request.sourceId}" } }
        val html = runCatching {
            evaluate(
                session,
                "document.documentElement ? document.documentElement.outerHTML : ''",
                request.timeoutMs.coerceIn(100L, 5_000L),
            )
        }.getOrNull()
        val webViewState = runCatching { snapshotWebView(session, request.timeoutMs.coerceIn(100L, 5_000L)) }.getOrNull()
        val logicalUrl = webViewState?.let { reconcileLogicalPageUrl(session, it) } ?: session.logicalPageUrl
        val readyState = runCatching { evaluate(session, "document.readyState || ''", request.timeoutMs.coerceIn(100L, 2_000L)) }.getOrNull().orEmpty()
        val pageCookieCount = runCatching {
            evaluate(session, "document.cookie ? document.cookie.split(';').filter(Boolean).length : 0", request.timeoutMs.coerceIn(100L, 2_000L)).toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        val domStorageAvailable = runCatching {
            evaluate(session, "(()=>{try{return typeof window.localStorage !== 'undefined'}catch(e){return false}})()", request.timeoutMs.coerceIn(100L, 2_000L)) == "true"
        }.getOrDefault(false)
        val stateAttributes = mapOf(
            "action" to request.action.name,
            "flow" to "browser",
            "stage" to "page_probe",
            "url" to diagnosticUrl(logicalUrl ?: request.url.orEmpty()),
            "title" to webViewState?.title.orEmpty().take(500),
            "progress" to (webViewState?.progress ?: 0).toString(),
            "readyState" to readyState.take(100),
            "pageJavaScript" to if (readyState.isNotBlank()) "ok" else "unknown",
            "pageCookieCount" to pageCookieCount.toString(),
            "pageDomStorage" to domStorageAvailable.toString(),
            "requests" to session.metadata.size.toString(),
            "mainFrameRequests" to session.metadata.count { it.mainFrame }.toString(),
            "blockedRequests" to session.metadata.count { it.resourceType?.contains("blocked", true) == true }.toString(),
            "lateCallbacks" to session.lateCallbacks.toString(),
            "pageStartedCount" to session.pageStartedCount.toString(),
            "pageFinishedCount" to session.pageFinishedCount.toString(),
            "rendererGone" to session.rendererGone.toString(),
            "pendingError" to session.pendingError.get().orEmpty().take(500),
        )
        diagnostics.emit(event(session.manifest, request, "BROWSER_STATE_SNAPSHOT", DiagnosticSeverity.DEBUG, attributes = stateAttributes))
        evidence.capture(DiagnosticEvidence(
            timestampEpochMs = clockMs(),
            traceId = trace,
            sourceId = request.sourceId,
            category = DiagnosticCategory.BROWSER,
            name = "browser-${reason}-state-${clockMs()}.json",
            contentType = "application/json",
            data = JSONObject(stateAttributes).toString(2).toByteArray(Charsets.UTF_8),
            attributes = mapOf("flow" to "browser", "stage" to "page_probe"),
        ))
        val pageForensicsText = runCatching {
            evaluate(session, BrowserForensics.pageScript, request.timeoutMs.coerceIn(200L, 3_000L))
        }.getOrNull().orEmpty()
        BrowserForensics.parse(pageForensicsText)?.let { pageForensics ->
            val forensicAttributes = BrowserForensics.summary(
                pageForensics,
                session.sessionId,
                session.navigationGeneration,
            ).toMutableMap()
            request.selector?.takeIf(String::isNotBlank)?.let { selector ->
                forensicAttributes["selector"] = selector.take(1_000)
                forensicAttributes["selectorCount"] = runCatching {
                    evaluate(
                        session,
                        "(()=>{try{return document.querySelectorAll(${jsString(selector)}).length}catch(e){return -2}})()",
                        request.timeoutMs.coerceIn(100L, 1_500L),
                    ).toLongOrNull() ?: -1L
                }.getOrDefault(-1L).toString()
            }
            diagnostics.emit(event(
                session.manifest,
                request,
                "BROWSER_PAGE_FORENSICS",
                DiagnosticSeverity.DEBUG,
                attributes = forensicAttributes,
            ))
            evidence.capture(DiagnosticEvidence(
                timestampEpochMs = clockMs(),
                traceId = trace,
                sourceId = request.sourceId,
                category = DiagnosticCategory.BROWSER,
                name = "browser-${reason}-page-forensics-${clockMs()}.json",
                contentType = "application/json",
                data = pageForensics.toString(2).toByteArray(Charsets.UTF_8),
                attributes = mapOf("flow" to "browser", "stage" to "page_forensics"),
            ))
        }
        if (html != null) {
            evidence.capture(DiagnosticEvidence(
                timestampEpochMs = clockMs(),
                traceId = trace,
                sourceId = request.sourceId,
                category = DiagnosticCategory.BROWSER,
                name = "browser-${reason}-${clockMs()}.html",
                contentType = "text/html",
                data = html.toByteArray(Charsets.UTF_8),
                attributes = mapOf(
                    "action" to request.action.name,
                    "url" to diagnosticUrl(logicalUrl ?: request.url.orEmpty()),
                    "title" to webViewState?.title.orEmpty(),
                    "requests" to session.metadata.size.toString(),
                ),
            ))
        }
        val metadata = session.metadata.joinToString("\n") { item ->
            listOf(
                "timestamp=${item.timestampEpochMs}",
                "method=${item.method}",
                "mainFrame=${item.mainFrame}",
                "type=${item.resourceType.orEmpty()}",
                "url=${diagnosticUrl(item.url)}",
                "headerNames=${item.headerNames.sorted().joinToString(",")}",
            ).joinToString(" | ")
        }
        if (metadata.isNotBlank()) {
            evidence.capture(DiagnosticEvidence(
                timestampEpochMs = clockMs(),
                traceId = trace,
                sourceId = request.sourceId,
                category = DiagnosticCategory.NETWORK,
                name = "browser-${reason}-requests-${clockMs()}.log",
                contentType = "text/plain",
                data = metadata.toByteArray(Charsets.UTF_8),
            ))
        }
    }

    private fun captureBrowserEnvironment(session: Session, request: SourceBrowserRequest) {
        if (session.environmentCaptured) return
        session.environmentCaptured = true
        val attributes = runCatching {
            runOnMain(5_000L) {
                val settings = session.webView.settings
                val manager = CookieManager.getInstance()
                val pkg = WebView.getCurrentWebViewPackage()
                val pageUrl = request.url ?: session.logicalPageUrl.orEmpty()
                val cookieHeader = if (pageUrl.isBlank()) "" else manager.getCookie(pageUrl).orEmpty()
                mapOf(
                    "flow" to "browser",
                    "stage" to "environment_probe",
                    "webViewPackage" to pkg?.packageName.orEmpty(),
                    "webViewVersion" to pkg?.versionName.orEmpty(),
                    "userAgent" to settings.userAgentString.orEmpty().take(1_000),
                    "javaScriptEnabled" to settings.javaScriptEnabled.toString(),
                    "domStorageEnabled" to settings.domStorageEnabled.toString(),
                    "databaseEnabled" to settings.databaseEnabled.toString(),
                    "allowFileAccess" to settings.allowFileAccess.toString(),
                    "allowContentAccess" to settings.allowContentAccess.toString(),
                    "mixedContentMode" to settings.mixedContentMode.toString(),
                    "safeBrowsingEnabled" to settings.safeBrowsingEnabled.toString(),
                    "loadsImagesAutomatically" to settings.loadsImagesAutomatically.toString(),
                    "blockNetworkLoads" to settings.blockNetworkLoads.toString(),
                    "acceptCookies" to manager.acceptCookie().toString(),
                    "acceptThirdPartyCookies" to manager.acceptThirdPartyCookies(session.webView).toString(),
                    "cookieCount" to cookieHeader.split(';').count { it.contains('=') }.toString(),
                )
            }
        }.getOrElse { error ->
            mapOf(
                "flow" to "browser",
                "stage" to "environment_probe",
                "probeError" to (error.message ?: error.javaClass.simpleName).take(500),
            )
        }
        diagnostics.emit(event(session.manifest, request, "BROWSER_ENVIRONMENT_SNAPSHOT", DiagnosticSeverity.DEBUG, attributes = attributes))
        if (evidence.enabled) {
            evidence.capture(DiagnosticEvidence(
                timestampEpochMs = clockMs(),
                traceId = request.traceId,
                sourceId = request.sourceId,
                category = DiagnosticCategory.BROWSER,
                name = "browser-environment-${clockMs()}.json",
                contentType = "application/json",
                data = JSONObject(attributes).toString(2).toByteArray(Charsets.UTF_8),
                attributes = mapOf("flow" to "browser", "stage" to "environment_probe"),
            ))
        }
    }

    private fun requireCapability(manifest: SourceManifest, action: SourceBrowserAction) {
        val capability = manifest.capabilities.browser
        val allowed = when (action) {
            SourceBrowserAction.NAVIGATE, SourceBrowserAction.LOAD_HTML, SourceBrowserAction.WAIT_SELECTOR,
            SourceBrowserAction.SET_USER_AGENT, SourceBrowserAction.SET_BLOCK_PATTERNS, SourceBrowserAction.SET_DIALOG_POLICY,
            SourceBrowserAction.DIALOGS, SourceBrowserAction.WAIT_DIALOG, SourceBrowserAction.SYNC_SESSION,
            SourceBrowserAction.SET_COOKIES, SourceBrowserAction.CLEAR_COOKIES, SourceBrowserAction.CLOSE_SESSION,
            SourceBrowserAction.CLEAR_SESSION -> capability.navigate
            SourceBrowserAction.DOM_SNAPSHOT -> capability.domSnapshot
            SourceBrowserAction.CLICK -> capability.click
            SourceBrowserAction.INPUT -> capability.input
            SourceBrowserAction.EVALUATE_PAGE_SCRIPT, SourceBrowserAction.EVALUATE_PAGE_SCRIPT_ASYNC -> capability.pageJavaScript
            SourceBrowserAction.REQUEST_METADATA -> capability.requestMetadata || capability.serviceWorkerCapture
        }
        require(allowed) { "SOURCE_BROWSER_CAPABILITY_DENIED:${action.name}" }
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
        val sessionRef = AtomicReference<Session?>()
        val webView = WebView(appContext)
        ExtensionWebViewAuthority.apply(appContext, webView)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val session = sessionRef.get() ?: return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(appContext)
                ExtensionWebViewAuthority.apply(appContext, popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (isAllowedRedirect(manifest, url)) {
                            session.record(request, resourceType = "popup-navigation")
                            session.webView.loadUrl(url)
                        } else {
                            session.record(request, resourceType = "popup-blocked")
                        }
                        popupView.destroy()
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                diagnostics.emit(sessionEvent(manifest, session, "BROWSER_POPUP_CREATED", attributes = mapOf(
                    "dialog" to isDialog.toString(),
                    "userGesture" to isUserGesture.toString(),
                )))
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val session = sessionRef.get() ?: return false
                val severity = when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> DiagnosticSeverity.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> DiagnosticSeverity.WARN
                    ConsoleMessage.MessageLevel.DEBUG -> DiagnosticSeverity.DEBUG
                    else -> DiagnosticSeverity.INFO
                }
                val message = consoleMessage.message().orEmpty().take(16_000)
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = clockMs(),
                    traceId = session.currentTraceId.ifBlank { "browser-session:${manifest.id}" },
                    sourceId = manifest.id,
                    sourceVersion = manifest.version.toString(),
                    category = DiagnosticCategory.BROWSER,
                    name = "BROWSER_CONSOLE",
                    severity = severity,
                    attributes = mapOf(
                        "message" to message,
                        "line" to consoleMessage.lineNumber().toString(),
                        "source" to diagnosticUrl(consoleMessage.sourceId().orEmpty()),
                    ),
                ))
                evidence.capture(DiagnosticEvidence(
                    timestampEpochMs = clockMs(),
                    traceId = session.currentTraceId,
                    sourceId = manifest.id,
                    category = DiagnosticCategory.BROWSER,
                    name = "browser-console-${clockMs()}.log",
                    contentType = "text/plain",
                    data = "${diagnosticUrl(consoleMessage.sourceId().orEmpty())}:${consoleMessage.lineNumber()} $message".toByteArray(Charsets.UTF_8),
                ))
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                val session = sessionRef.get() ?: return
                val previous = session.lastProgressLogged
                if (newProgress == 100 || previous < 0 || kotlin.math.abs(newProgress - previous) >= 10) {
                    session.lastProgressLogged = newProgress
                    diagnostics.emit(sessionEvent(manifest, session, "BROWSER_PROGRESS", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "stage" to "progress",
                        "progress" to newProgress.toString(),
                        "previousProgress" to previous.toString(),
                    )))
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                val session = sessionRef.get()
                val decision = session?.recordDialog("alert", message, null, url) ?: DialogDecision(false, null)
                if (session != null) diagnostics.emit(sessionEvent(manifest, session, "BROWSER_JS_DIALOG", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "stage" to "js_dialog",
                    "dialogType" to "alert",
                    "accepted" to decision.accepted.toString(),
                    "message" to vn.nghetruyen.source.diagnostics.DiagnosticRedactor.redactLongText(message, 500),
                    "url" to diagnosticUrl(url),
                )))
                if (decision.accepted) result.confirm() else result.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                val session = sessionRef.get()
                val decision = session?.recordDialog("confirm", message, null, url) ?: DialogDecision(false, null)
                if (session != null) diagnostics.emit(sessionEvent(manifest, session, "BROWSER_JS_DIALOG", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "stage" to "js_dialog",
                    "dialogType" to "confirm",
                    "accepted" to decision.accepted.toString(),
                    "message" to vn.nghetruyen.source.diagnostics.DiagnosticRedactor.redactLongText(message, 500),
                    "url" to diagnosticUrl(url),
                )))
                if (decision.accepted) result.confirm() else result.cancel()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                val session = sessionRef.get()
                val decision = session?.recordDialog("prompt", message, defaultValue, url) ?: DialogDecision(false, null)
                if (session != null) diagnostics.emit(sessionEvent(manifest, session, "BROWSER_JS_DIALOG", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "stage" to "js_dialog",
                    "dialogType" to "prompt",
                    "accepted" to decision.accepted.toString(),
                    "message" to vn.nghetruyen.source.diagnostics.DiagnosticRedactor.redactLongText(message, 500),
                    "url" to diagnosticUrl(url),
                )))
                if (decision.accepted) result.confirm(decision.value ?: defaultValue.orEmpty()) else result.cancel()
                return true
            }
        }
        webView.setDownloadListener { url, _, _, mimeType, contentLength ->
            sessionRef.get()?.let { session ->
                diagnostics.emit(sessionEvent(manifest, session, "BROWSER_DOWNLOAD_REQUESTED", attributes = mapOf(
                    "url" to diagnosticUrl(url.orEmpty()),
                    "mimeType" to mimeType.orEmpty(),
                    "contentLength" to contentLength.toString(),
                )))
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val session = sessionRef.get()
                val url = request.url.toString()
                if (session?.allowsTrustedLoadHtmlInternalNavigation(url, request.isForMainFrame) == true) return false
                if (isAllowedRedirect(manifest, url)) return false
                session?.apply {
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_NAVIGATION_DENIED")
                    if (request.isForMainFrame) pageLatch?.countDown()
                    record(request, resourceType = "navigation-blocked")
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_NAVIGATION_BLOCKED", DiagnosticSeverity.WARN, DiagnosticCategory.SECURITY, mapOf(
                        "url" to diagnosticUrl(url),
                        "mainFrame" to request.isForMainFrame.toString(),
                    )))
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                sessionRef.get()?.apply {
                    val late = pageLatch == null
                    if (late) lateCallbacks += 1
                    pageStartedCount += 1
                    updateLogicalPageUrlFromWebView(url)
                    recordUrl(url)
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_PAGE_STARTED", DiagnosticSeverity.INFO, attributes = mapOf(
                        "flow" to "browser",
                        "stage" to "page_started",
                        "url" to diagnosticUrl(url),
                        "requests" to metadata.size.toString(),
                        "late" to late.toString(),
                        "lateCallbacks" to lateCallbacks.toString(),
                        "pageStartedCount" to pageStartedCount.toString(),
                    )))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                sessionRef.get()?.apply {
                    val late = pageLatch == null
                    if (late) lateCallbacks += 1
                    pageFinishedCount += 1
                    updateLogicalPageUrlFromWebView(url)
                    recordUrl(url)
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_PAGE_FINISHED", DiagnosticSeverity.INFO, attributes = mapOf(
                        "flow" to "browser",
                        "stage" to "page_finished",
                        "url" to diagnosticUrl(url),
                        "requests" to metadata.size.toString(),
                        "late" to late.toString(),
                        "lateCallbacks" to lateCallbacks.toString(),
                        "pageFinishedCount" to pageFinishedCount.toString(),
                    )))
                    pageLatch?.countDown()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                sessionRef.get()?.apply {
                    diagnostics.emit(sessionEvent(
                        manifest,
                        this,
                        "BROWSER_WEB_ERROR",
                        if (request.isForMainFrame) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARN,
                        DiagnosticCategory.NETWORK,
                        mapOf(
                            "code" to error.errorCode.toString(),
                            "description" to error.description.toString().take(400),
                            "url" to diagnosticUrl(request.url.toString()),
                            "mainFrame" to request.isForMainFrame.toString(),
                        ),
                    ))
                    if (request.isForMainFrame) {
                        pendingError.compareAndSet(null, "SOURCE_BROWSER_LOAD_ERROR:${error.errorCode}")
                        pageLatch?.countDown()
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                sessionRef.get()?.apply {
                    diagnostics.emit(sessionEvent(
                        manifest,
                        this,
                        "BROWSER_HTTP_ERROR",
                        if (request.isForMainFrame) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARN,
                        DiagnosticCategory.NETWORK,
                        mapOf(
                            "stage" to "http_response",
                            "statusCode" to errorResponse.statusCode.toString(),
                            "reasonPhrase" to errorResponse.reasonPhrase.orEmpty().take(300),
                            "mimeType" to errorResponse.mimeType.orEmpty().take(200),
                            "encoding" to errorResponse.encoding.orEmpty().take(100),
                            "responseHeaderNames" to errorResponse.responseHeaders?.keys?.sorted()?.take(64)?.joinToString(",").orEmpty(),
                            "url" to diagnosticUrl(request.url.toString()),
                            "mainFrame" to request.isForMainFrame.toString(),
                        ),
                    ))
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                sessionRef.get()?.apply {
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_SSL_ERROR", DiagnosticSeverity.ERROR, DiagnosticCategory.SECURITY, mapOf(
                        "primary" to error.primaryError.toString(),
                        "url" to diagnosticUrl(error.url.orEmpty()),
                    )))
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_SSL_ERROR")
                    pageLatch?.countDown()
                }
            }

            @android.annotation.TargetApi(27)
            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                callback.backToSafety(true)
                sessionRef.get()?.apply {
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_SAFE_BROWSING_BLOCKED", DiagnosticSeverity.ERROR, DiagnosticCategory.SECURITY, mapOf(
                        "threatType" to threatType.toString(),
                        "url" to diagnosticUrl(request.url.toString()),
                    )))
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_SAFE_BROWSING_BLOCKED:$threatType")
                    pageLatch?.countDown()
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val session = sessionRef.get()
                val url = request.url.toString()
                if (session?.isBlocked(url) == true) {
                    session.record(request, resourceType = "policy-blocked")
                    diagnostics.emit(sessionEvent(manifest, session, "BROWSER_RESOURCE_POLICY_BLOCKED", DiagnosticSeverity.DEBUG, DiagnosticCategory.SECURITY, mapOf(
                        "url" to diagnosticUrl(url),
                        "mainFrame" to request.isForMainFrame.toString(),
                    )))
                    return blockedResponse()
                }
                if (session?.allowsTrustedLoadHtmlInternalNavigation(url, request.isForMainFrame) == true) return null
                if (!isAllowedRedirect(manifest, url)) {
                    session?.record(request, resourceType = "resource-blocked")
                    if (session != null) {
                        diagnostics.emit(sessionEvent(manifest, session, "BROWSER_RESOURCE_ORIGIN_BLOCKED", DiagnosticSeverity.WARN, DiagnosticCategory.SECURITY, mapOf(
                            "url" to diagnosticUrl(url),
                            "mainFrame" to request.isForMainFrame.toString(),
                        )))
                    }
                    if (request.isForMainFrame) {
                        session?.pendingError?.compareAndSet(null, "SOURCE_BROWSER_NAVIGATION_DENIED")
                        session?.pageLatch?.countDown()
                    }
                    return blockedResponse()
                }
                session?.record(request, null)
                return null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                sessionRef.get()?.apply {
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_RENDERER_GONE", DiagnosticSeverity.ERROR, DiagnosticCategory.BROWSER, mapOf(
                        "didCrash" to detail.didCrash().toString(),
                        "requests" to metadata.size.toString(),
                    )))
                    rendererGone = true
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_RENDERER_GONE:${detail.didCrash()}")
                    pageLatch?.countDown()
                }
                recoveredSources += manifest.id
                runCatching { view.destroy() }
                return true
            }
        }
        val session = Session(manifest, webView)
        sessionRef.set(session)
        return session
    }

    private fun navigate(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String? {
        val url = request.url ?: error("SOURCE_BROWSER_NAVIGATION_URL_REQUIRED")
        require(isAllowedInitial(manifest, url)) { "SOURCE_BROWSER_NAVIGATION_DENIED" }
        importCookiesIntoWebView(manifest, url)
        val latch = CountDownLatch(1)
        session.pageLatch = latch
        session.pendingError.set(null)
        session.logicalPageUrl = url
        runOnMain(5_000) { session.webView.loadUrl(url) }
        if (!latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_TIMEOUT")
        session.pageLatch = null
        session.pendingError.get()?.let(::error)
        return reconcileLogicalPageUrl(session, snapshotWebView(session))
    }

    private fun loadHtml(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String? {
        val baseUrl = request.url ?: manifest.origins.firstOrNull() ?: error("SOURCE_BROWSER_BASE_URL_REQUIRED")
        require(isAllowedInitial(manifest, baseUrl)) { "SOURCE_BROWSER_NAVIGATION_DENIED" }
        val html = request.value ?: error("SOURCE_BROWSER_HTML_REQUIRED")
        require(html.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
        val latch = CountDownLatch(1)
        session.pageLatch = latch
        session.pendingError.set(null)
        session.logicalPageUrl = baseUrl
        session.trustedLoadHtmlInFlight = true
        try {
            runOnMain(5_000) { session.webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null) }
            if (!latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_TIMEOUT")
            session.pendingError.get()?.let(::error)
            return baseUrl
        } finally {
            session.trustedLoadHtmlInFlight = false
            session.pageLatch = null
        }
    }

    private fun setUserAgent(session: Session, request: SourceBrowserRequest): String {
        val userAgent = request.value.orEmpty().trim().take(512)
        require(userAgent.isNotBlank()) { "SOURCE_BROWSER_USER_AGENT_REQUIRED" }
        runOnMain(5_000) { session.webView.settings.userAgentString = userAgent }
        session.userAgent = userAgent
        return userAgent
    }

    private fun setBlockPatterns(session: Session, request: SourceBrowserRequest): String {
        val patterns = request.values.map(String::trim).filter(String::isNotBlank).distinct().take(128)
        require(patterns.all { it.length <= 512 }) { "SOURCE_BROWSER_BLOCK_PATTERN_INVALID" }
        session.blockPatterns = patterns
        return patterns.size.toString()
    }

    private fun setDialogPolicy(session: Session, request: SourceBrowserRequest): String {
        val action = request.options["defaultAction"]?.lowercase() ?: "dismiss"
        require(action in setOf("accept", "confirm", "dismiss", "cancel", "passthrough")) { "SOURCE_BROWSER_DIALOG_POLICY_INVALID" }
        session.dialogPolicy = DialogPolicy(action, request.options["defaultValue"].orEmpty().take(4_096))
        return action
    }

    private fun waitDialog(session: Session, request: SourceBrowserRequest): SourceBrowserDialog? {
        val afterId = request.options["afterId"]?.toLongOrNull() ?: 0L
        val type = request.options["type"].orEmpty().lowercase()
        val match = request.options["match"].orEmpty()
        val mode = request.options["matchMode"].orEmpty().ifBlank { "contains" }
        val deadline = clockMs() + request.timeoutMs
        do {
            session.dialogs.firstOrNull { dialog ->
                dialog.id > afterId && (type.isBlank() || type == "any" || dialog.type.equals(type, true)) &&
                    (match.isBlank() || when (mode.lowercase()) {
                        "equals" -> dialog.message.equals(match, true)
                        "regex" -> runCatching { Regex(match, RegexOption.IGNORE_CASE).containsMatchIn(dialog.message) }.getOrDefault(false)
                        else -> dialog.message.contains(match, true)
                    })
            }?.let { return it }
            Thread.sleep(100)
        } while (clockMs() < deadline)
        return null
    }

    private fun syncSession(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String {
        val url = request.url ?: session.logicalPageUrl ?: reconcileLogicalPageUrl(session, snapshotWebView(session)) ?: manifest.origins.firstOrNull().orEmpty()
        val direction = request.options["direction"]?.lowercase() ?: "both"
        require(direction in setOf("both", "browser_to_native", "native_to_browser")) { "SOURCE_BROWSER_SYNC_DIRECTION_INVALID" }
        if (direction == "both" || direction == "native_to_browser") importCookiesIntoWebView(manifest, url)
        if (direction == "both" || direction == "browser_to_native") syncCookiesFromWebView(manifest, url)
        return cookiePartition.readCookieHeader(manifest.id, url).orEmpty()
    }

    private fun setCookies(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String {
        val url = request.url ?: session.logicalPageUrl ?: reconcileLogicalPageUrl(session, snapshotWebView(session)) ?: manifest.origins.firstOrNull().orEmpty()
        val cookies = (request.values + listOfNotNull(request.value)).map(String::trim).filter(String::isNotBlank).take(128)
        require(cookies.all { it.length <= 8_192 }) { "SOURCE_BROWSER_COOKIE_TOO_LARGE" }
        cookiePartition.mergeSetCookieHeaders(manifest.id, url, cookies)
        importCookiesIntoWebView(manifest, url)
        return cookiePartition.readCookieHeader(manifest.id, url).orEmpty()
    }

    private fun clearCookies(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String {
        val url = request.url ?: session.logicalPageUrl ?: reconcileLogicalPageUrl(session, snapshotWebView(session)) ?: manifest.origins.firstOrNull().orEmpty()
        if (request.values.isEmpty()) {
            cookiePartition.clear(manifest.id)
            clearWebViewCookies()
        } else {
            val current = cookiePartition.readCookieHeader(manifest.id, url).orEmpty()
            val denied = request.values.map(String::trim).toSet()
            val kept = current.split(';').map(String::trim).filter { token -> token.substringBefore('=').trim() !in denied }
            cookiePartition.clear(manifest.id)
            if (kept.isNotEmpty()) cookiePartition.mergeSetCookieHeaders(manifest.id, url, kept.map { "$it; Path=/; Secure" })
            clearWebViewCookies()
            importCookiesIntoWebView(manifest, url)
        }
        return cookiePartition.readCookieHeader(manifest.id, url).orEmpty()
    }

    private fun waitSelector(session: Session, request: SourceBrowserRequest): String {
        val selector = request.selector ?: error("SOURCE_BROWSER_SELECTOR_REQUIRED")
        val deadline = clockMs() + request.timeoutMs
        var polls = 0
        do {
            polls += 1
            val found = evaluate(session, "Boolean(document.querySelector(${jsString(selector)}))", 5_000) == "true"
            if (found) {
                diagnostics.emit(event(manifest = session.manifest, request = request, name = "BROWSER_SELECTOR_FOUND", severity = DiagnosticSeverity.INFO, attributes = mapOf(
                    "selector" to selector.take(1_000),
                    "flow" to "browser",
                    "stage" to "selector_probe",
                    "polls" to polls.toString(),
                    "elapsedMs" to (request.timeoutMs - (deadline - clockMs()).coerceAtLeast(0L)).toString(),
                )))
                return "true"
            }
            if (polls == 1 || polls % 5 == 0) {
                diagnostics.emit(event(manifest = session.manifest, request = request, name = "BROWSER_SELECTOR_PROBE", severity = DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "selector" to selector.take(1_000),
                    "flow" to "browser",
                    "stage" to "selector_probe",
                    "polls" to polls.toString(),
                    "remainingMs" to (deadline - clockMs()).coerceAtLeast(0L).toString(),
                )))
            }
            Thread.sleep(100)
        } while (clockMs() < deadline && !session.rendererGone)
        diagnostics.emit(event(manifest = session.manifest, request = request, name = "BROWSER_SELECTOR_TIMEOUT", severity = DiagnosticSeverity.WARN, attributes = mapOf(
                    "selector" to selector.take(1_000),
                    "flow" to "browser",
                    "stage" to "selector_probe",
                    "polls" to polls.toString(),
                )))
        error("SOURCE_BROWSER_SELECTOR_NOT_FOUND")
    }

    private fun domSnapshot(session: Session, request: SourceBrowserRequest): String {
        val html = evaluate(session, "document.documentElement ? document.documentElement.outerHTML : ''", request.timeoutMs)
        require(html.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
        return html
    }

    private fun click(session: Session, request: SourceBrowserRequest): String {
        val selector = request.selector ?: error("SOURCE_BROWSER_SELECTOR_REQUIRED")
        return evaluate(session, "(()=>{const e=document.querySelector(${jsString(selector)});if(!e)return false;e.click();return true;})()", request.timeoutMs)
            .also { require(it == "true") { "SOURCE_BROWSER_SELECTOR_NOT_FOUND" } }
    }

    private fun input(session: Session, request: SourceBrowserRequest): String {
        val selector = request.selector ?: error("SOURCE_BROWSER_SELECTOR_REQUIRED")
        val value = request.value.orEmpty()
        return evaluate(session, "(()=>{const e=document.querySelector(${jsString(selector)});if(!e)return false;e.focus();e.value=${jsString(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true;})()", request.timeoutMs)
            .also { require(it == "true") { "SOURCE_BROWSER_SELECTOR_NOT_FOUND" } }
    }

    private fun evaluatePageScript(session: Session, request: SourceBrowserRequest): String {
        val script = request.script ?: error("SOURCE_BROWSER_SCRIPT_REQUIRED")
        require(script.length <= 64 * 1024) { "SOURCE_BROWSER_SCRIPT_TOO_LARGE" }
        val value = evaluate(session, "(()=>{return (0,eval)(${jsString(script)});})()", request.timeoutMs)
        require(value.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
        return value
    }

    private fun evaluatePageScriptAsync(session: Session, request: SourceBrowserRequest): String {
        val script = request.script ?: error("SOURCE_BROWSER_SCRIPT_REQUIRED")
        require(script.length <= 64 * 1024) { "SOURCE_BROWSER_SCRIPT_TOO_LARGE" }
        val token = "nghe_${request.traceId.replace(Regex("[^A-Za-z0-9_]"), "_").take(80)}_${System.nanoTime()}"
        val bootstrap = """
            (()=>{
              window.__ngheAsyncResults=window.__ngheAsyncResults||Object.create(null);
              const id=${jsString(token)};
              window.__ngheAsyncResults[id]={done:false,value:null,error:null};
              Promise.resolve().then(()=>{return (0,eval)(${jsString(script)});})
                .then(v=>{window.__ngheAsyncResults[id]={done:true,value:v===undefined?null:v,error:null};})
                .catch(e=>{window.__ngheAsyncResults[id]={done:true,value:null,error:String(e&&e.message?e.message:e)};});
              return id;
            })()
        """.trimIndent()
        evaluate(session, bootstrap, minOf(5_000L, request.timeoutMs))
        val deadline = clockMs() + request.timeoutMs
        var polls = 0
        do {
            polls += 1
            val state = evaluate(session, "JSON.stringify((window.__ngheAsyncResults||{})[${jsString(token)}]||null)", minOf(5_000L, (deadline - clockMs()).coerceAtLeast(100L)))
            if (state.isNotBlank() && state != "null") {
                val parsed = runCatching { org.json.JSONObject(state) }.getOrNull()
                if (parsed?.optBoolean("done") == true) {
                    evaluate(session, "delete (window.__ngheAsyncResults||{})[${jsString(token)}]", 2_000L)
                    val error = parsed.optString("error").takeIf(String::isNotBlank)
                    if (error != null) error("SOURCE_BROWSER_ASYNC_SCRIPT_FAILED:$error")
                    val value = parsed.opt("value")
                    val encoded = when (value) {
                        null, org.json.JSONObject.NULL -> ""
                        is String -> value
                        else -> value.toString()
                    }
                    require(encoded.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
                    diagnostics.emit(event(session.manifest, request, "BROWSER_ASYNC_SCRIPT_RESOLVED", DiagnosticSeverity.INFO, attributes = mapOf(
                        "polls" to polls.toString(),
                        "outputBytes" to encoded.toByteArray(Charsets.UTF_8).size.toString(),
                    )))
                    return encoded
                }
            }
            if (polls == 1 || polls % 10 == 0) {
                diagnostics.emit(event(session.manifest, request, "BROWSER_ASYNC_SCRIPT_POLL", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "polls" to polls.toString(),
                    "remainingMs" to (deadline - clockMs()).coerceAtLeast(0L).toString(),
                )))
            }
            Thread.sleep(50)
        } while (clockMs() < deadline)
        runCatching { evaluate(session, "delete (window.__ngheAsyncResults||{})[${jsString(token)}]", 2_000L) }
        diagnostics.emit(event(session.manifest, request, "BROWSER_ASYNC_SCRIPT_TIMEOUT", DiagnosticSeverity.WARN, attributes = mapOf("polls" to polls.toString())))
        error("SOURCE_BROWSER_TIMEOUT")
    }

    private fun evaluate(session: Session, expression: String, timeoutMs: Long): String {
        val startedAt = clockMs()
        val boundedTimeout = timeoutMs.coerceAtMost(120_000)
        val latch = CountDownLatch(1)
        val output = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        val timedOut = AtomicBoolean(false)
        diagnostics.emit(sessionEvent(session.manifest, session, "BROWSER_EVAL_STARTED", DiagnosticSeverity.DEBUG, attributes = mapOf(
            "stage" to "evaluate",
            "timeoutMs" to boundedTimeout.toString(),
        )))
        main.post {
            try {
                session.webView.evaluateJavascript(expression, ValueCallback { raw ->
                    val decoded = decodeJavascriptResult(raw)
                    output.set(decoded)
                    val late = timedOut.get()
                    if (late) session.lateCallbacks += 1
                    diagnostics.emit(sessionEvent(session.manifest, session, "BROWSER_EVAL_CALLBACK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "stage" to "evaluate_callback",
                        "late" to late.toString(),
                        "lateCallbacks" to session.lateCallbacks.toString(),
                        "outputBytes" to decoded.toByteArray(Charsets.UTF_8).size.toString(),
                        "elapsedMs" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),
                    )))
                    latch.countDown()
                })
            } catch (t: Throwable) {
                error.set(t)
                diagnostics.emit(sessionEvent(session.manifest, session, "BROWSER_EVAL_ERROR", DiagnosticSeverity.WARN, attributes = mapOf(
                    "stage" to "evaluate",
                    "error" to (t.message ?: t.javaClass.simpleName).take(500),
                    "elapsedMs" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),
                )))
                latch.countDown()
            }
        }
        if (!latch.await(boundedTimeout, TimeUnit.MILLISECONDS)) {
            timedOut.set(true)
            diagnostics.emit(sessionEvent(session.manifest, session, "BROWSER_EVAL_TIMEOUT", DiagnosticSeverity.WARN, attributes = mapOf(
                "stage" to "evaluate",
                "timeoutMs" to boundedTimeout.toString(),
                "elapsedMs" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),
            )))
            error("SOURCE_BROWSER_TIMEOUT")
        }
        error.get()?.let { throw it }
        return output.get().orEmpty().also { decoded ->
            diagnostics.emit(sessionEvent(session.manifest, session, "BROWSER_EVAL_COMPLETED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                "stage" to "evaluate",
                "elapsedMs" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),
                "outputBytes" to decoded.toByteArray(Charsets.UTF_8).size.toString(),
            )))
        }
    }

    private fun snapshotWebView(session: Session, timeoutMs: Long = 5_000L): WebViewState =
        runOnMain(timeoutMs) { WebViewState(session.webView.url, session.webView.title, session.webView.progress) }

    private fun reconcileLogicalPageUrl(session: Session, state: WebViewState): String? {
        val webUrl = state.url
        if (webUrl != null && isHttpUrl(webUrl)) session.logicalPageUrl = webUrl
        return session.logicalPageUrl ?: webUrl
    }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        Uri.parse(url).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun isAllowedInitial(manifest: SourceManifest, url: String): Boolean = runCatching {
        val uri = SourceOriginPolicy.requireInitialUrl(manifest, url)
        PublicAddressPolicy.requirePublic(resolver(uri.host))
        true
    }.getOrDefault(false)

    private fun isAllowedRedirect(manifest: SourceManifest, url: String): Boolean = runCatching {
        val uri = SourceOriginPolicy.requireRedirectUrl(manifest, url)
        PublicAddressPolicy.requirePublic(resolver(uri.host))
        true
    }.getOrDefault(false)

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun importCookiesIntoWebView(manifest: SourceManifest, url: String) {
        if (manifest.capabilities.cookies.name != "BROWSER_SHARED") return
        val manager = CookieManager.getInstance()
        cookiePartition.exportSetCookieHeaders(manifest.id, url).forEach { manager.setCookie(url, it, null) }
        manager.flush()
    }

    private fun syncCookiesFromWebView(manifest: SourceManifest, url: String?) {
        if (manifest.capabilities.cookies.name != "BROWSER_SHARED" || url.isNullOrBlank()) return
        val header = runOnMain(5_000) { CookieManager.getInstance().getCookie(url) }.orEmpty()
        if (header.isNotBlank()) {
            cookiePartition.mergeSetCookieHeaders(manifest.id, url, header.split("; ").map { "$it; Path=/; Secure" })
        }
        CookieManager.getInstance().flush()
    }

    private fun destroyActive(clearCookies: Boolean) {
        val session = active ?: return
        val currentUrl = session.logicalPageUrl ?: runCatching { reconcileLogicalPageUrl(session, snapshotWebView(session)) }.getOrNull()
        syncCookiesFromWebView(session.manifest, currentUrl)
        runCatching { runOnMain(10_000) { session.webView.stopLoading(); session.webView.loadUrl("about:blank"); session.webView.clearHistory(); session.webView.clearCache(true); session.webView.removeAllViews(); session.webView.destroy() } }
        active = null
        if (clearCookies) cookiePartition.clear(session.manifest.id)
    }

    private fun clearWebViewCookies() {
        val latch = CountDownLatch(1)
        main.post { CookieManager.getInstance().removeAllCookies { latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        CookieManager.getInstance().flush()
        runCatching {
            runOnMain(10_000) {
                WebStorage.getInstance().deleteAllData()
                WebView(appContext).apply { clearCache(true); clearHistory(); clearFormData(); destroy() }
            }
        }
    }

    private fun <T> runOnMain(timeoutMs: Long, block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post { result.set(runCatching(block)); latch.countDown() }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_TIMEOUT")
        return result.get().getOrThrow()
    }

    private fun decodeJavascriptResult(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue()?.toString().orEmpty() }.getOrDefault(raw)
    }

    private fun jsString(value: String): String = org.json.JSONObject.quote(value)

    private fun failure(code: SourceErrorCode, message: String, request: SourceBrowserRequest) =
        SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId))

    private fun event(
        manifest: SourceManifest,
        request: SourceBrowserRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ): DiagnosticEvent {
        val state = when (name) {
            "BROWSER_ACTION_STARTED" -> DiagnosticOperationState.STARTED
            "BROWSER_ACTION_COMPLETED" -> DiagnosticOperationState.COMPLETED
            "BROWSER_ACTION_FAILED" -> if (attributes["code"]?.contains("TIMEOUT") == true) {
                DiagnosticOperationState.TIMEOUT
            } else {
                DiagnosticOperationState.FAILED
            }
            else -> DiagnosticOperationState.STAGE
        }
        val operation = DiagnosticOperationContract.attributes(
            id = request.options[INTERNAL_DIAGNOSTIC_OPERATION_ID]
                ?: "browser:${request.traceId.ifBlank { "no-trace" }}:${request.action.name}",
            kind = request.action.name,
            flow = "browser",
            state = state,
            stage = attributes["stage"] ?: name,
            timeoutMs = attributes["timeoutMs"]?.toLongOrNull(),
            deadlineEpochMs = attributes["deadlineEpochMs"]?.toLongOrNull(),
        )
        return DiagnosticEvent(
            clockMs(), request.traceId, manifest.id, manifest.version.toString(),
            DiagnosticCategory.BROWSER, name, severity, durationMs,
            operation + mapOf("requestId" to request.options[INTERNAL_DIAGNOSTIC_REQUEST_ID].orEmpty()) + attributes,
        )
    }

    private fun sessionEvent(
        manifest: SourceManifest,
        session: Session,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        category: DiagnosticCategory = DiagnosticCategory.BROWSER,
        attributes: Map<String, String> = emptyMap(),
    ): DiagnosticEvent {
        val base = mapOf(
            "flow" to "browser",
            DiagnosticOperationContract.ID to "browser-session:${session.sessionId}",
            DiagnosticOperationContract.KIND to "BROWSER_SESSION",
            DiagnosticOperationContract.FLOW to "browser",
            DiagnosticOperationContract.STATE to DiagnosticOperationState.STAGE.name,
            DiagnosticOperationContract.STAGE to name,
            "sessionId" to session.sessionId,
            "navigationGeneration" to session.navigationGeneration.toString(),
            "loaded" to (session.pageFinishedCount > 0 && session.pendingError.get().isNullOrBlank()).toString(),
            "currentUrl" to diagnosticUrl(session.logicalPageUrl.orEmpty()),
        )
        return DiagnosticEvent(
            clockMs(),
            session.currentTraceId.ifBlank { "browser-session:${manifest.id}" },
            manifest.id,
            manifest.version.toString(),
            category,
            name,
            severity,
            null,
            base + attributes,
        )
    }

    private fun diagnosticUrl(value: String): String = runCatching {
        val uri = java.net.URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) "[redacted-url]"
        else "https://${uri.host}${uri.rawPath.orEmpty().take(300)}"
    }.getOrDefault("[invalid-url]")

    private class Session(
        val manifest: SourceManifest,
        val webView: WebView,
    ) {
        val sessionId: String = "browser:${manifest.id}:${UUID.randomUUID().toString().take(12)}"
        val metadata = ArrayDeque<SourceBrowserRequestMetadata>()
        val dialogs = ArrayDeque<SourceBrowserDialog>()
        @Volatile var currentTraceId: String = ""
        val pendingError = AtomicReference<String?>()
        private val dialogSequence = AtomicLong()
        @Volatile var pageLatch: CountDownLatch? = null
        @Volatile var rendererGone: Boolean = false
        @Volatile var userAgent: String = ""
        @Volatile var blockPatterns: List<String> = emptyList()
        @Volatile var dialogPolicy: DialogPolicy = DialogPolicy("dismiss", "")
        @Volatile var trustedLoadHtmlInFlight: Boolean = false
        @Volatile var logicalPageUrl: String? = null
        @Volatile var environmentCaptured: Boolean = false
        @Volatile var lateCallbacks: Int = 0
        @Volatile var pageStartedCount: Int = 0
        @Volatile var pageFinishedCount: Int = 0
        @Volatile var navigationGeneration: Long = 0
        @Volatile var lastProgressLogged: Int = -1

        fun record(request: WebResourceRequest, resourceType: String?) {
            if (!manifest.capabilities.browser.requestMetadata && resourceType == null) return
            if (metadata.size >= 500) metadata.removeFirst()
            metadata.addLast(SourceBrowserRequestMetadata(
                url = request.url.toString().take(4096),
                method = request.method.take(16),
                mainFrame = request.isForMainFrame,
                resourceType = resourceType,
                headerNames = request.requestHeaders.keys.take(64).toSet(),
                timestampEpochMs = System.currentTimeMillis(),
            ))
        }

        fun recordUrl(url: String) {
            if (metadata.size >= 500) metadata.removeFirst()
            metadata.addLast(SourceBrowserRequestMetadata(url.take(4096), "GET", true, "navigation", emptySet(), System.currentTimeMillis()))
        }

        fun updateLogicalPageUrlFromWebView(url: String) {
            if (isHttpOrHttps(url)) logicalPageUrl = url
        }

        private fun isHttpOrHttps(url: String): Boolean = runCatching {
            Uri.parse(url).scheme?.lowercase() in setOf("http", "https")
        }.getOrDefault(false)

        fun isBlocked(url: String): Boolean = blockPatterns.any { pattern ->
            when {
                pattern.startsWith("regex:") -> runCatching { Regex(pattern.removePrefix("regex:"), RegexOption.IGNORE_CASE).containsMatchIn(url) }.getOrDefault(false)
                '*' in pattern -> runCatching { Regex(Regex.escape(pattern).replace("\\*", ".*"), RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)
                else -> url.contains(pattern, ignoreCase = true)
            }
        }

        fun allowsTrustedLoadHtmlInternalNavigation(url: String, mainFrame: Boolean): Boolean {
            if (!trustedLoadHtmlInFlight || !mainFrame) return false
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            return when (uri.scheme?.lowercase()) {
                "about" -> url.equals("about:blank", ignoreCase = true)
                "data" -> url.startsWith("data:text/html", ignoreCase = true)
                else -> false
            }
        }

        fun recordDialog(type: String, message: String, defaultValue: String?, url: String?): DialogDecision {
            val action = dialogPolicy.defaultAction
            val accepted = action in setOf("accept", "confirm", "passthrough")
            val value = if (type == "prompt" && accepted) dialogPolicy.defaultValue.ifBlank { defaultValue.orEmpty() } else null
            val dialog = SourceBrowserDialog(
                id = dialogSequence.incrementAndGet(),
                type = type,
                message = message.take(8_192),
                defaultValue = defaultValue?.take(4_096),
                pageUrl = url?.take(4_096),
                accepted = accepted,
                responseValue = value?.take(4_096),
                timestampEpochMs = System.currentTimeMillis(),
            )
            if (dialogs.size >= 100) dialogs.removeFirst()
            dialogs.addLast(dialog)
            return DialogDecision(accepted, value)
        }
    }

    private data class WebViewState(val url: String?, val title: String?, val progress: Int)
    private data class DialogPolicy(val defaultAction: String, val defaultValue: String)
    private data class DialogDecision(val accepted: Boolean, val value: String?)

    private companion object {
        const val INTERNAL_DIAGNOSTIC_REQUEST_ID = "__nghetruyenDiagnosticRequestId"
        const val INTERNAL_DIAGNOSTIC_OPERATION_ID = "__nghetruyenDiagnosticOperationId"
    }
}
