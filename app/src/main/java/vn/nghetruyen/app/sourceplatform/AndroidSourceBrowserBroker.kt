package vn.nghetruyen.app.sourceplatform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
import android.webkit.WebSettings
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
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.network.SourceOriginPolicy
import vn.nghetruyen.source.network.PublicAddressPolicy
import org.json.JSONTokener
import vn.nghetruyen.source.api.SourceBrowserDialog
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Serialized WebView capability broker.
 *
 * Android WebView owns one CookieManager/profile per process, so this implementation exports/imports
 * a source-partitioned cookie jar and clears WebView state whenever the active SourcePack changes.
 * The response marks this fallback as degraded isolation. No Java object is exposed to page scripts.
 */
class AndroidSourceBrowserBroker(
    context: Context,
    private val cookiePartition: SourceCookiePartition,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
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
        return synchronized(operationLock) {
            runCatching {
                require(request.sourceId == manifest.id) { "SOURCE_BROWSER_SOURCE_ID_MISMATCH" }
                require(request.timeoutMs in 100L..120_000L) { "SOURCE_BROWSER_TIMEOUT_INVALID" }
                require(request.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_BROWSER_OUTPUT_LIMIT_INVALID" }
                requireCapability(manifest, request.action)
                val session = ensureSession(manifest, request.url)
                diagnostics.emit(event(manifest, request, "BROWSER_ACTION_STARTED", attributes = mapOf("action" to request.action.name)))
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
                syncCookiesFromWebView(manifest, session.webView.url ?: request.url)
                val metadata = session.metadata.toList()
                SourceBrowserResponse(
                    finalUrl = session.webView.url,
                    title = session.webView.title,
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
                        "requests" to it.requestMetadata.size.toString(),
                        "degradedIsolation" to it.degradedIsolation.toString(),
                    )))
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
                        "code" to code.name,
                        "error" to (error.message ?: error.javaClass.simpleName),
                    )))
                    SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_BROWSER_FAILED", request.traceId, error))
                },
            )
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
        webView.settings.apply {
            javaScriptEnabled = manifest.capabilities.browser.pageJavaScript
            domStorageEnabled = manifest.capabilities.storageBytes > 0 || manifest.capabilities.browser.pageJavaScript
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "NgheTruyen-SourceBrowser/2 Android"
            cacheMode = WebSettings.LOAD_DEFAULT
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                val decision = sessionRef.get()?.recordDialog("alert", message, null, url) ?: DialogDecision(false, null)
                if (decision.accepted) result.confirm() else result.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                val decision = sessionRef.get()?.recordDialog("confirm", message, null, url) ?: DialogDecision(false, null)
                if (decision.accepted) result.confirm() else result.cancel()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                val decision = sessionRef.get()?.recordDialog("prompt", message, defaultValue, url) ?: DialogDecision(false, null)
                if (decision.accepted) result.confirm(decision.value ?: defaultValue.orEmpty()) else result.cancel()
                return true
            }
        }
        webView.setDownloadListener { _, _, _, _, _ ->
            sessionRef.get()?.pendingError?.compareAndSet(null, "SOURCE_BROWSER_DOWNLOAD_BLOCKED")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAllowedRedirect(manifest, request.url.toString())) return false
                sessionRef.get()?.apply {
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_NAVIGATION_DENIED")
                    if (request.isForMainFrame) pageLatch?.countDown()
                    record(request, resourceType = "navigation-blocked")
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                sessionRef.get()?.recordUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                sessionRef.get()?.apply {
                    recordUrl(url)
                    pageLatch?.countDown()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) sessionRef.get()?.apply {
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_LOAD_ERROR:${error.errorCode}")
                    pageLatch?.countDown()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                sessionRef.get()?.apply {
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_SSL_ERROR")
                    pageLatch?.countDown()
                }
            }

            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                callback.backToSafety(true)
                sessionRef.get()?.apply {
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_SAFE_BROWSING_BLOCKED:$threatType")
                    pageLatch?.countDown()
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val session = sessionRef.get()
                if (session?.isBlocked(request.url.toString()) == true) {
                    session.record(request, resourceType = "policy-blocked")
                    return blockedResponse()
                }
                if (!isAllowedRedirect(manifest, request.url.toString())) {
                    session?.record(request, resourceType = "resource-blocked")
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
        runOnMain(5_000) { session.webView.loadUrl(url) }
        if (!latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_TIMEOUT")
        session.pageLatch = null
        session.pendingError.get()?.let(::error)
        return session.webView.url
    }

    private fun loadHtml(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String? {
        val baseUrl = request.url ?: manifest.origins.firstOrNull() ?: error("SOURCE_BROWSER_BASE_URL_REQUIRED")
        require(isAllowedInitial(manifest, baseUrl)) { "SOURCE_BROWSER_NAVIGATION_DENIED" }
        val html = request.value ?: error("SOURCE_BROWSER_HTML_REQUIRED")
        require(html.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
        val latch = CountDownLatch(1)
        session.pageLatch = latch
        session.pendingError.set(null)
        runOnMain(5_000) { session.webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null) }
        if (!latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_TIMEOUT")
        session.pageLatch = null
        session.pendingError.get()?.let(::error)
        return session.webView.url ?: baseUrl
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
        val url = request.url ?: session.webView.url ?: manifest.origins.firstOrNull().orEmpty()
        val direction = request.options["direction"]?.lowercase() ?: "both"
        require(direction in setOf("both", "browser_to_native", "native_to_browser")) { "SOURCE_BROWSER_SYNC_DIRECTION_INVALID" }
        if (direction == "both" || direction == "native_to_browser") importCookiesIntoWebView(manifest, url)
        if (direction == "both" || direction == "browser_to_native") syncCookiesFromWebView(manifest, url)
        return cookiePartition.readCookieHeader(manifest.id, url).orEmpty()
    }

    private fun setCookies(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String {
        val url = request.url ?: session.webView.url ?: manifest.origins.firstOrNull().orEmpty()
        val cookies = (request.values + listOfNotNull(request.value)).map(String::trim).filter(String::isNotBlank).take(128)
        require(cookies.all { it.length <= 8_192 }) { "SOURCE_BROWSER_COOKIE_TOO_LARGE" }
        cookiePartition.mergeSetCookieHeaders(manifest.id, url, cookies)
        importCookiesIntoWebView(manifest, url)
        return cookiePartition.readCookieHeader(manifest.id, url).orEmpty()
    }

    private fun clearCookies(session: Session, manifest: SourceManifest, request: SourceBrowserRequest): String {
        val url = request.url ?: session.webView.url ?: manifest.origins.firstOrNull().orEmpty()
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
        do {
            val found = evaluate(session, "Boolean(document.querySelector(${jsString(selector)}))", 5_000) == "true"
            if (found) return "true"
            Thread.sleep(100)
        } while (clockMs() < deadline && !session.rendererGone)
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
        do {
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
                    return encoded
                }
            }
            Thread.sleep(50)
        } while (clockMs() < deadline)
        runCatching { evaluate(session, "delete (window.__ngheAsyncResults||{})[${jsString(token)}]", 2_000L) }
        error("SOURCE_BROWSER_TIMEOUT")
    }

    private fun evaluate(session: Session, expression: String, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        val output = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        main.post {
            try {
                session.webView.evaluateJavascript(expression, ValueCallback { raw ->
                    output.set(decodeJavascriptResult(raw))
                    latch.countDown()
                })
            } catch (t: Throwable) {
                error.set(t)
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs.coerceAtMost(120_000), TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_TIMEOUT")
        error.get()?.let { throw it }
        return output.get().orEmpty()
    }

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
        syncCookiesFromWebView(session.manifest, session.webView.url)
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
    ) = DiagnosticEvent(clockMs(), request.traceId, manifest.id, manifest.version.toString(), DiagnosticCategory.BROWSER, name, severity, durationMs, attributes)

    private class Session(
        val manifest: SourceManifest,
        val webView: WebView,
    ) {
        val metadata = ArrayDeque<SourceBrowserRequestMetadata>()
        val dialogs = ArrayDeque<SourceBrowserDialog>()
        val pendingError = AtomicReference<String?>()
        private val dialogSequence = AtomicLong()
        @Volatile var pageLatch: CountDownLatch? = null
        @Volatile var rendererGone: Boolean = false
        @Volatile var userAgent: String = ""
        @Volatile var blockPatterns: List<String> = emptyList()
        @Volatile var dialogPolicy: DialogPolicy = DialogPolicy("dismiss", "")

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

        fun isBlocked(url: String): Boolean = blockPatterns.any { pattern ->
            when {
                pattern.startsWith("regex:") -> runCatching { Regex(pattern.removePrefix("regex:"), RegexOption.IGNORE_CASE).containsMatchIn(url) }.getOrDefault(false)
                '*' in pattern -> runCatching { Regex(Regex.escape(pattern).replace("\\*", ".*"), RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)
                else -> url.contains(pattern, ignoreCase = true)
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

    private data class DialogPolicy(val defaultAction: String, val defaultValue: String)
    private data class DialogDecision(val accepted: Boolean, val value: String?)
}
