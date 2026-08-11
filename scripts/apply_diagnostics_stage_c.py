#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, value: str) -> None:
    (ROOT / path).write_text(value, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    value = read(path)
    if new in value:
        return
    if old not in value:
        raise SystemExit(f"missing Stage C migration anchor in {path}: {old[:180]!r}")
    write(path, value.replace(old, new, 1))


# 1) Crash-safe profile writes redacted text/HTML evidence to disk while RAM-only keeps the
# high-fidelity in-memory evidence. This preserves crash forensics without unnecessarily leaving
# credential-bearing page state in private storage.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt"
replace_once(
    path,
    '''            val file = File(directory, "${evidence.timestampEpochMs}-${base}")
            file.writeBytes(evidence.data)
            trimEvidence(directory)
''',
    '''            val file = File(directory, "${evidence.timestampEpochMs}-${base}")
            file.writeBytes(redactEvidenceForDisk(evidence))
            trimEvidence(directory)
''',
)
replace_once(
    path,
    '''    private fun trimEvidence(directory: File) {
''',
    '''    private fun redactEvidenceForDisk(evidence: DiagnosticEvidence): ByteArray = when {
        evidence.contentType.contains("html", ignoreCase = true) -> DiagnosticRedactor.redactHtmlPreservingStructure(
            evidence.data.toString(Charsets.UTF_8),
            8 * 1024 * 1024,
        ).toByteArray(Charsets.UTF_8)
        evidence.contentType.startsWith("text/", ignoreCase = true) || evidence.contentType.contains("json", ignoreCase = true) ->
            DiagnosticRedactor.redactLongText(
                evidence.data.toString(Charsets.UTF_8),
                8 * 1024 * 1024,
            ).toByteArray(Charsets.UTF_8)
        else -> evidence.data
    }

    private fun trimEvidence(directory: File) {
''',
)

# 2) The real login WebView becomes diagnostic-first. It records the actual failing session instead
# of requiring the user to reproduce the bug in a second diagnostic browser.
path = "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt"
replace_once(
    path,
    '''import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
''',
    '''import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
''',
)
replace_once(
    path,
    '''import vn.nghetruyen.app.NgheTruyenApplication
''',
    '''import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.util.UUID
''',
)
replace_once(
    path,
    '''    private lateinit var status: TextView
    private lateinit var addressField: EditText

    private val browserPrefs by lazy { getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE) }
    private var desktopCompat = false
    private var logLevel = 0
    private var autoClearLogOnClose = false
''',
    '''    private lateinit var status: TextView
    private lateinit var addressField: EditText
    private lateinit var diagnostics: SourceDiagnosticRuntime
    private lateinit var diagnosticTraceId: String
    private var diagnosticStartedAt: Long = 0L
    private var requestCount: Int = 0

    private val browserPrefs by lazy { getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE) }
    private var desktopCompat = false
    private var logLevel = 1
    private var autoClearLogOnClose = true
''',
)
replace_once(
    path,
    '''        sessionStore = (application as NgheTruyenApplication).container.sourceSessionStore
        desktopCompat = browserPrefs.getBoolean(KEY_CHROME_COMPAT, false)
        logLevel = browserPrefs.getInt(KEY_LOG_LEVEL, 0).coerceIn(0, 2)
        autoClearLogOnClose = browserPrefs.getBoolean(KEY_AUTO_CLEAR_LOG_ON_CLOSE, false)
''',
    '''        val app = application as NgheTruyenApplication
        sessionStore = app.container.sourceSessionStore
        diagnostics = app.container.sourceDiagnostics
        diagnosticTraceId = "login:$sourceId:${UUID.randomUUID()}"
        diagnosticStartedAt = System.currentTimeMillis()
        desktopCompat = browserPrefs.getBoolean(KEY_CHROME_COMPAT, false)
        logLevel = browserPrefs.getInt(KEY_LOG_LEVEL, 1).coerceIn(0, 2)
        autoClearLogOnClose = browserPrefs.getBoolean(KEY_AUTO_CLEAR_LOG_ON_CLOSE, true)
        diagnostic(
            name = "SOURCE_LOGIN_STARTED",
            severity = DiagnosticSeverity.INFO,
            attributes = mapOf(
                "url" to diagnosticUrl(loginUrl),
                "allowedHosts" to allowedHosts.size.toString(),
                "localLogLevel" to logLevelLabel(logLevel),
                "autoClearLocalLog" to autoClearLogOnClose.toString(),
            ),
        )
''',
)
replace_once(
    path,
    '''            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (!isAllowed(target)) {
                        status.text = "Đã chặn điều hướng ra ngoài miền của nguồn."
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    addressField.setText(url)
                    if (isAllowed(url)) captureSession()
                    status.text = if (sessionStore.hasSession(sourceId)) {
                        "Đã nhận cookie phiên. Bạn có thể đóng màn hình và thử mở lại chương."
                    } else {
                        "Trang đã tải. Hãy đăng nhập nếu nguồn yêu cầu."
                    }
                }
            }
''',
    '''            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (!isAllowed(target)) {
                        diagnostic(
                            name = "SOURCE_LOGIN_NAVIGATION_BLOCKED",
                            severity = DiagnosticSeverity.WARN,
                            category = DiagnosticCategory.SECURITY,
                            attributes = mapOf("url" to diagnosticUrl(target), "mainFrame" to request.isForMainFrame.toString()),
                        )
                        status.text = "Đã chặn điều hướng ra ngoài miền của nguồn."
                        return true
                    }
                    diagnostic(
                        name = "SOURCE_LOGIN_NAVIGATION",
                        severity = DiagnosticSeverity.DEBUG,
                        attributes = mapOf("url" to diagnosticUrl(target), "mainFrame" to request.isForMainFrame.toString()),
                    )
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    diagnostic(
                        name = "SOURCE_LOGIN_PAGE_STARTED",
                        severity = DiagnosticSeverity.INFO,
                        attributes = mapOf("url" to diagnosticUrl(url), "requestCount" to requestCount.toString()),
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    addressField.setText(url)
                    if (isAllowed(url)) captureSession()
                    val stored = sessionStore.hasSession(sourceId)
                    diagnostic(
                        name = "SOURCE_LOGIN_PAGE_FINISHED",
                        severity = DiagnosticSeverity.INFO,
                        attributes = mapOf(
                            "url" to diagnosticUrl(url),
                            "requestCount" to requestCount.toString(),
                            "storedSession" to stored.toString(),
                        ),
                    )
                    status.text = if (stored) {
                        "Đã nhận cookie phiên. Bạn có thể đóng màn hình và thử mở lại chương."
                    } else {
                        "Trang đã tải. Hãy đăng nhập nếu nguồn yêu cầu."
                    }
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    requestCount += 1
                    if (requestCount <= 20 || requestCount % 25 == 0) {
                        diagnostic(
                            name = "SOURCE_LOGIN_REQUEST",
                            severity = DiagnosticSeverity.DEBUG,
                            category = DiagnosticCategory.NETWORK,
                            attributes = mapOf(
                                "method" to request.method.take(16),
                                "url" to diagnosticUrl(request.url.toString()),
                                "mainFrame" to request.isForMainFrame.toString(),
                                "headerNames" to request.requestHeaders.keys.sorted().take(64).joinToString(","),
                                "requestCount" to requestCount.toString(),
                            ),
                        )
                    }
                    return null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    diagnostic(
                        name = "SOURCE_LOGIN_WEB_ERROR",
                        severity = if (request.isForMainFrame) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARN,
                        category = DiagnosticCategory.NETWORK,
                        attributes = mapOf(
                            "code" to error.errorCode.toString(),
                            "description" to error.description.toString().take(400),
                            "url" to diagnosticUrl(request.url.toString()),
                            "mainFrame" to request.isForMainFrame.toString(),
                        ),
                    )
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    diagnostic(
                        name = "SOURCE_LOGIN_HTTP_ERROR",
                        severity = DiagnosticSeverity.WARN,
                        category = DiagnosticCategory.NETWORK,
                        attributes = mapOf(
                            "status" to errorResponse.statusCode.toString(),
                            "url" to diagnosticUrl(request.url.toString()),
                            "mainFrame" to request.isForMainFrame.toString(),
                        ),
                    )
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    diagnostic(
                        name = "SOURCE_LOGIN_SSL_BLOCKED",
                        severity = DiagnosticSeverity.ERROR,
                        category = DiagnosticCategory.SECURITY,
                        attributes = mapOf("primary" to error.primaryError.toString(), "url" to diagnosticUrl(error.url.orEmpty())),
                    )
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    diagnostic(
                        name = "SOURCE_LOGIN_RENDERER_GONE",
                        severity = DiagnosticSeverity.ERROR,
                        attributes = mapOf("didCrash" to detail.didCrash().toString(), "requestCount" to requestCount.toString()),
                    )
                    return false
                }
            }
''',
)
replace_once(
    path,
    '''            .setPositiveButton("XÓA") { _, _ ->
                clearSessionCookies()
                status.text = "Đã xóa dữ liệu đăng nhập của trang."
                webView.reload()
            }
''',
    '''            .setPositiveButton("XÓA") { _, _ ->
                clearSessionCookies()
                diagnostic(
                    name = "SOURCE_LOGIN_SESSION_CLEARED",
                    severity = DiagnosticSeverity.INFO,
                    category = DiagnosticCategory.SECURITY,
                    attributes = mapOf("host" to host),
                )
                status.text = "Đã xóa dữ liệu đăng nhập của trang."
                webView.reload()
            }
''',
)
replace_once(
    path,
    '''    private fun openDiagnosticBrowser() {
        val target = webView.url?.takeIf(::isAllowed) ?: loginUrl
        startActivity(Intent(this, SourceDiagnosticBrowserActivity::class.java).apply {
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_SOURCE_ID, sourceId)
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_INITIAL_URL, target)
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_ALLOWED_HOSTS, allowedHosts.toTypedArray())
        })
    }
''',
    '''    private fun openDiagnosticBrowser() {
        val target = webView.url?.takeIf(::isAllowed) ?: loginUrl
        diagnostic(
            name = "SOURCE_LOGIN_DIAGNOSTIC_BROWSER_OPENED",
            severity = DiagnosticSeverity.INFO,
            attributes = mapOf("url" to diagnosticUrl(target)),
        )
        startActivity(Intent(this, SourceDiagnosticBrowserActivity::class.java).apply {
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_SOURCE_ID, sourceId)
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_INITIAL_URL, target)
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_ALLOWED_HOSTS, allowedHosts.toTypedArray())
            putExtra(SourceDiagnosticBrowserActivity.EXTRA_TRACE_ID, diagnosticTraceId)
        })
    }
''',
)
replace_once(
    path,
    '''    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
''',
    '''    override fun onDestroy() {
        if (::diagnostics.isInitialized) {
            diagnostic(
                name = "SOURCE_LOGIN_STOPPED",
                severity = DiagnosticSeverity.INFO,
                durationMs = (System.currentTimeMillis() - diagnosticStartedAt).coerceAtLeast(0L),
                attributes = mapOf(
                    "requestCount" to requestCount.toString(),
                    "storedSession" to (::sessionStore.isInitialized && sessionStore.hasSession(sourceId)).toString(),
                ),
            )
        }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
''',
)
replace_once(
    path,
    '''        sessionStore.replaceCookieHeader(sourceId, merged)
        manager.flush()
    }
''',
    '''        sessionStore.replaceCookieHeader(sourceId, merged)
        manager.flush()
        if (::diagnostics.isInitialized) {
            val names = CookieHeaderCodec.cookieNames(merged).distinct().sorted()
            diagnostic(
                name = "SOURCE_LOGIN_SESSION_CAPTURED",
                severity = DiagnosticSeverity.DEBUG,
                category = DiagnosticCategory.SECURITY,
                attributes = mapOf(
                    "cookieCount" to names.size.toString(),
                    "cookieNames" to names.take(64).joinToString(","),
                    "storedSession" to sessionStore.hasSession(sourceId).toString(),
                ),
            )
        }
    }
''',
)
replace_once(
    path,
    '''    private fun clearSessionCookies() {
        clearStoredSession(sourceId, allowedHosts, sessionStore)
    }

    private fun isAllowed(value: String): Boolean {
''',
    '''    private fun clearSessionCookies() {
        clearStoredSession(sourceId, allowedHosts, sessionStore)
    }

    private fun diagnostic(
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        category: DiagnosticCategory = DiagnosticCategory.BROWSER,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (!::diagnostics.isInitialized || !::diagnosticTraceId.isInitialized) return
        diagnostics.mark(
            name = name,
            category = category,
            severity = severity,
            sourceId = sourceId,
            traceId = diagnosticTraceId,
            durationMs = durationMs,
            attributes = attributes,
        )
    }

    private fun diagnosticUrl(value: String): String = runCatching {
        val uri = Uri.parse(value)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return@runCatching "[redacted-url]"
        "https://${uri.host}${uri.encodedPath.orEmpty().take(300)}"
    }.getOrDefault("[invalid-url]")

    private fun isAllowed(value: String): Boolean {
''',
)

# 3) Diagnostic browser local entries mirror into the same global trace. Local level defaults match
# Lua (Basic, auto-clear true), while the shared black box follows the app-level mode independently.
path = "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt"
replace_once(
    path,
    '''import vn.nghetruyen.app.NgheTruyenApplication
import java.text.SimpleDateFormat
''',
    '''import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.text.SimpleDateFormat
''',
)
replace_once(
    path,
    '''import java.util.Locale
''',
    '''import java.util.Locale
import java.util.UUID
''',
)
replace_once(
    path,
    '''    private lateinit var progress: ProgressBar

    private val entries = ArrayDeque<DiagnosticEntry>()
''',
    '''    private lateinit var progress: ProgressBar
    private lateinit var diagnostics: SourceDiagnosticRuntime
    private lateinit var diagnosticTraceId: String
    private var diagnosticStartedAt = 0L

    private val entries = ArrayDeque<DiagnosticEntry>()
''',
)
replace_once(
    path,
    '''    private var desktopCompat = false
    private var logLevel = 0
    private var autoClearLogOnClose = false
''',
    '''    private var desktopCompat = false
    private var logLevel = 1
    private var autoClearLogOnClose = true
''',
)
replace_once(
    path,
    '''        sessionStore = (application as NgheTruyenApplication).container.sourceSessionStore
        desktopCompat = browserPrefs.getBoolean(SourceLoginActivity.KEY_CHROME_COMPAT, false)
        logLevel = browserPrefs.getInt(SourceLoginActivity.KEY_LOG_LEVEL, 0).coerceIn(0, 2)
        autoClearLogOnClose = browserPrefs.getBoolean(SourceLoginActivity.KEY_AUTO_CLEAR_LOG_ON_CLOSE, false)
''',
    '''        val app = application as NgheTruyenApplication
        sessionStore = app.container.sourceSessionStore
        diagnostics = app.container.sourceDiagnostics
        diagnosticTraceId = intent.getStringExtra(EXTRA_TRACE_ID).orEmpty().ifBlank { "diagnostic-browser:$sourceId:${UUID.randomUUID()}" }
        diagnosticStartedAt = System.currentTimeMillis()
        desktopCompat = browserPrefs.getBoolean(SourceLoginActivity.KEY_CHROME_COMPAT, false)
        logLevel = browserPrefs.getInt(SourceLoginActivity.KEY_LOG_LEVEL, 1).coerceIn(0, 2)
        autoClearLogOnClose = browserPrefs.getBoolean(SourceLoginActivity.KEY_AUTO_CLEAR_LOG_ON_CLOSE, true)
        diagnostics.mark(
            name = "DIAGNOSTIC_BROWSER_STARTED",
            category = DiagnosticCategory.BROWSER,
            severity = DiagnosticSeverity.INFO,
            sourceId = sourceId,
            traceId = diagnosticTraceId,
            attributes = mapOf("url" to redactUrl(initialUrl), "localLogLevel" to SourceLoginActivity.logLevelLabel(logLevel)),
        )
''',
)
replace_once(
    path,
    '''    private fun record(level: String, category: String, detail: String) {
        if (logLevel == 0 && level !in setOf("SECURITY", "PROBE")) return
        entries.addLast(DiagnosticEntry(System.currentTimeMillis(), level, category, sanitize(detail, 2_000)))
        while (entries.size > MAX_LOG_ENTRIES) entries.removeFirst()
    }
''',
    '''    private fun record(level: String, category: String, detail: String) {
        val safeDetail = sanitize(detail, 2_000)
        mirrorGlobal(level, category, safeDetail)
        if (logLevel == 0 && level !in setOf("SECURITY", "PROBE")) return
        entries.addLast(DiagnosticEntry(System.currentTimeMillis(), level, category, safeDetail))
        while (entries.size > MAX_LOG_ENTRIES) entries.removeFirst()
    }

    private fun mirrorGlobal(level: String, category: String, detail: String) {
        if (!::diagnostics.isInitialized || !::diagnosticTraceId.isInitialized) return
        val severity = when (level.uppercase(Locale.ROOT)) {
            "ERROR" -> DiagnosticSeverity.ERROR
            "SECURITY", "HTTP" -> DiagnosticSeverity.WARN
            "REQUEST", "CONSOLE" -> DiagnosticSeverity.DEBUG
            else -> DiagnosticSeverity.INFO
        }
        val diagnosticCategory = when (level.uppercase(Locale.ROOT)) {
            "SECURITY" -> DiagnosticCategory.SECURITY
            "REQUEST", "HTTP", "NAV" -> DiagnosticCategory.NETWORK
            else -> DiagnosticCategory.BROWSER
        }
        val safeName = "DIAGNOSTIC_BROWSER_${level}_${category}"
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(150)
        diagnostics.mark(
            name = safeName,
            category = diagnosticCategory,
            severity = severity,
            sourceId = sourceId,
            traceId = diagnosticTraceId,
            attributes = mapOf("detail" to detail, "localLogLevel" to SourceLoginActivity.logLevelLabel(logLevel)),
        )
    }
''',
)
replace_once(
    path,
    '''    private fun clearLog() {
        entries.clear()
        requestCount = 0
        status.text = "Đã xóa nhật ký chẩn đoán."
    }
''',
    '''    private fun clearLog() {
        entries.clear()
        requestCount = 0
        if (::diagnostics.isInitialized) {
            diagnostics.mark(
                name = "DIAGNOSTIC_BROWSER_LOCAL_LOG_CLEARED",
                category = DiagnosticCategory.BROWSER,
                severity = DiagnosticSeverity.INFO,
                sourceId = sourceId,
                traceId = diagnosticTraceId,
            )
        }
        status.text = "Đã xóa nhật ký chẩn đoán."
    }
''',
)
replace_once(
    path,
    '''    override fun onDestroy() {
        if (autoClearLogOnClose) clearLog()
        if (::webView.isInitialized) {
''',
    '''    override fun onDestroy() {
        if (::diagnostics.isInitialized && ::diagnosticTraceId.isInitialized) {
            diagnostics.mark(
                name = "DIAGNOSTIC_BROWSER_STOPPED",
                category = DiagnosticCategory.BROWSER,
                severity = DiagnosticSeverity.INFO,
                sourceId = sourceId,
                traceId = diagnosticTraceId,
                durationMs = (System.currentTimeMillis() - diagnosticStartedAt).coerceAtLeast(0L),
                attributes = mapOf("requestCount" to requestCount.toString(), "storedSession" to sessionStore.hasSession(sourceId).toString()),
            )
        }
        if (autoClearLogOnClose) clearLog()
        if (::webView.isInitialized) {
''',
)
replace_once(
    path,
    '''        const val EXTRA_ALLOWED_HOSTS = "allowed_hosts"
        private const val MAX_LOG_ENTRIES = 400
''',
    '''        const val EXTRA_ALLOWED_HOSTS = "allowed_hosts"
        const val EXTRA_TRACE_ID = "diagnostic_trace_id"
        private const val MAX_LOG_ENTRIES = 400
''',
)

# 4) WebView capability broker now emits the missing page/error/selector/async timeline and removes
# query strings from evidence metadata. This is the Android equivalent of Lua browser snapshots,
# network_requests and selector_probes.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt"
replace_once(
    path,
    '''                    "url" to (session.webView.url ?: request.url.orEmpty()),
''',
    '''                    "url" to diagnosticUrl(session.webView.url ?: request.url.orEmpty()),
''',
)
replace_once(
    path,
    '''        val metadata = session.metadata.joinToString("\\n") { it.toString() }
''',
    '''        val metadata = session.metadata.joinToString("\\n") { item ->
            listOf(
                "timestamp=${item.timestampEpochMs}",
                "method=${item.method}",
                "mainFrame=${item.mainFrame}",
                "type=${item.resourceType.orEmpty()}",
                "url=${diagnosticUrl(item.url)}",
                "headerNames=${item.headerNames.sorted().joinToString(",")}",
            ).joinToString(" | ")
        }
''',
)
replace_once(
    path,
    '''                        "source" to consoleMessage.sourceId().orEmpty(),
''',
    '''                        "source" to diagnosticUrl(consoleMessage.sourceId().orEmpty()),
''',
)
replace_once(
    path,
    '''            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
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

            @android.annotation.TargetApi(27)
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
''',
    '''            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAllowedRedirect(manifest, request.url.toString())) return false
                sessionRef.get()?.apply {
                    pendingError.compareAndSet(null, "SOURCE_BROWSER_NAVIGATION_DENIED")
                    if (request.isForMainFrame) pageLatch?.countDown()
                    record(request, resourceType = "navigation-blocked")
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_NAVIGATION_BLOCKED", DiagnosticSeverity.WARN, DiagnosticCategory.SECURITY, mapOf(
                        "url" to diagnosticUrl(request.url.toString()),
                        "mainFrame" to request.isForMainFrame.toString(),
                    )))
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                sessionRef.get()?.apply {
                    recordUrl(url)
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_PAGE_STARTED", DiagnosticSeverity.INFO, attributes = mapOf(
                        "url" to diagnosticUrl(url),
                        "requests" to metadata.size.toString(),
                    )))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                sessionRef.get()?.apply {
                    recordUrl(url)
                    diagnostics.emit(sessionEvent(manifest, this, "BROWSER_PAGE_FINISHED", DiagnosticSeverity.INFO, attributes = mapOf(
                        "url" to diagnosticUrl(url),
                        "requests" to metadata.size.toString(),
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
                if (session?.isBlocked(request.url.toString()) == true) {
                    session.record(request, resourceType = "policy-blocked")
                    diagnostics.emit(sessionEvent(manifest, session, "BROWSER_RESOURCE_POLICY_BLOCKED", DiagnosticSeverity.DEBUG, DiagnosticCategory.SECURITY, mapOf(
                        "url" to diagnosticUrl(request.url.toString()),
                        "mainFrame" to request.isForMainFrame.toString(),
                    )))
                    return blockedResponse()
                }
                if (!isAllowedRedirect(manifest, request.url.toString())) {
                    session?.record(request, resourceType = "resource-blocked")
                    if (session != null) {
                        diagnostics.emit(sessionEvent(manifest, session, "BROWSER_RESOURCE_ORIGIN_BLOCKED", DiagnosticSeverity.WARN, DiagnosticCategory.SECURITY, mapOf(
                            "url" to diagnosticUrl(request.url.toString()),
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
''',
)
replace_once(
    path,
    '''    private fun waitSelector(session: Session, request: SourceBrowserRequest): String {
        val selector = request.selector ?: error("SOURCE_BROWSER_SELECTOR_REQUIRED")
        val deadline = clockMs() + request.timeoutMs
        do {
            val found = evaluate(session, "Boolean(document.querySelector(${jsString(selector)}))", 5_000) == "true"
            if (found) return "true"
            Thread.sleep(100)
        } while (clockMs() < deadline && !session.rendererGone)
        error("SOURCE_BROWSER_SELECTOR_NOT_FOUND")
    }
''',
    '''    private fun waitSelector(session: Session, request: SourceBrowserRequest): String {
        val selector = request.selector ?: error("SOURCE_BROWSER_SELECTOR_REQUIRED")
        val deadline = clockMs() + request.timeoutMs
        var polls = 0
        do {
            polls += 1
            val found = evaluate(session, "Boolean(document.querySelector(${jsString(selector)}))", 5_000) == "true"
            if (found) {
                diagnostics.emit(event(manifest = session.manifest, request = request, name = "BROWSER_SELECTOR_FOUND", severity = DiagnosticSeverity.INFO, attributes = mapOf(
                    "selector" to selector.take(1_000),
                    "polls" to polls.toString(),
                    "elapsedMs" to (request.timeoutMs - (deadline - clockMs()).coerceAtLeast(0L)).toString(),
                )))
                return "true"
            }
            if (polls == 1 || polls % 5 == 0) {
                diagnostics.emit(event(manifest = session.manifest, request = request, name = "BROWSER_SELECTOR_PROBE", severity = DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "selector" to selector.take(1_000),
                    "polls" to polls.toString(),
                    "remainingMs" to (deadline - clockMs()).coerceAtLeast(0L).toString(),
                )))
            }
            Thread.sleep(100)
        } while (clockMs() < deadline && !session.rendererGone)
        diagnostics.emit(event(manifest = session.manifest, request = request, name = "BROWSER_SELECTOR_TIMEOUT", severity = DiagnosticSeverity.WARN, attributes = mapOf(
            "selector" to selector.take(1_000),
            "polls" to polls.toString(),
        )))
        error("SOURCE_BROWSER_SELECTOR_NOT_FOUND")
    }
''',
)
replace_once(
    path,
    '''        val deadline = clockMs() + request.timeoutMs
        do {
            val state = evaluate(session, "JSON.stringify((window.__ngheAsyncResults||{})[${jsString(token)}]||null)", minOf(5_000L, (deadline - clockMs()).coerceAtLeast(100L)))
''',
    '''        val deadline = clockMs() + request.timeoutMs
        var polls = 0
        do {
            polls += 1
            val state = evaluate(session, "JSON.stringify((window.__ngheAsyncResults||{})[${jsString(token)}]||null)", minOf(5_000L, (deadline - clockMs()).coerceAtLeast(100L)))
''',
)
replace_once(
    path,
    '''                    require(encoded.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
                    return encoded
                }
            }
            Thread.sleep(50)
        } while (clockMs() < deadline)
        runCatching { evaluate(session, "delete (window.__ngheAsyncResults||{})[${jsString(token)}]", 2_000L) }
        error("SOURCE_BROWSER_TIMEOUT")
''',
    '''                    require(encoded.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_BROWSER_OUTPUT_TOO_LARGE" }
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
''',
)
replace_once(
    path,
    '''    private fun event(
        manifest: SourceManifest,
        request: SourceBrowserRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(clockMs(), request.traceId, manifest.id, manifest.version.toString(), DiagnosticCategory.BROWSER, name, severity, durationMs, attributes)
''',
    '''    private fun event(
        manifest: SourceManifest,
        request: SourceBrowserRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(clockMs(), request.traceId, manifest.id, manifest.version.toString(), DiagnosticCategory.BROWSER, name, severity, durationMs, attributes)

    private fun sessionEvent(
        manifest: SourceManifest,
        session: Session,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        category: DiagnosticCategory = DiagnosticCategory.BROWSER,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        clockMs(),
        session.currentTraceId.ifBlank { "browser-session:${manifest.id}" },
        manifest.id,
        manifest.version.toString(),
        category,
        name,
        severity,
        null,
        attributes,
    )

    private fun diagnosticUrl(value: String): String = runCatching {
        val uri = java.net.URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) "[redacted-url]"
        else "https://${uri.host}${uri.rawPath.orEmpty().take(300)}"
    }.getOrDefault("[invalid-url]")
''',
)

# 5) vBook executor/bridge gains a stage timeline plus high-fidelity Advanced evidence. The outer
# VBOOK_ACTION_STARTED/COMPLETED/FAILED trace remains authoritative, so the global dashboard can show
# exactly which executor/bridge stage is currently active.
path = "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt"
replace_once(
    path,
    '''import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
''',
    '''import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
''',
)
replace_once(
    path,
    '''class VBookJsRuntime(
    private val brokers: SourceCapabilityBrokers = SourceCapabilityBrokers(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
''',
    '''class VBookJsRuntime(
    private val brokers: SourceCapabilityBrokers = SourceCapabilityBrokers(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) {
''',
)
replace_once(
    path,
    '''        diagnostics.emit(event(manifest, request, "VBOOK_ACTION_STARTED", attributes = mapOf("action" to request.action.name)))
        return runCatching {
            sandboxExecutor(manifest, timeoutMs).execute { cx, scope, budget ->
                VBookSafeRhinoBoundary.installCurrentContext()
                installHostApi(cx, scope, manifest, resources, request, budget)
                cx.evaluateString(scope, BOOTSTRAP, "vbook-bootstrap", 1, null)
                val loaded = linkedSetOf<String>()
                val loader = ScriptLoader(cx, scope, resources, loaded, budget)
                loader.install()
                loader.load(action.entry)
                val execute = ScriptableObject.getProperty(scope, "execute") as? Function
                    ?: error("VBOOK_EXECUTE_FUNCTION_MISSING:${action.entry}")
                val args = actionArguments(cx, scope, request)
                val rawResult = execute.call(cx, scope, scope, args)
                ScriptableObject.putProperty(scope, "__ngheResult", rawResult)
                val json = Context.toString(cx.evaluateString(scope, "JSON.stringify(__ngheResult)", "vbook-result", 1, null))
                require(json != "undefined" && json.toByteArray(Charsets.UTF_8).size <= action.maxOutputBytes) { "VBOOK_OUTPUT_TOO_LARGE" }
                val parsed = JsonCodec.parse(json, maxDepth = 96, maxNodes = manifest.runtime.instructionBudget)
                val normalized = normalizeResult(request, parsed)
                val bytes = JsonCodec.stringify(normalized).toByteArray(Charsets.UTF_8).size
                require(bytes <= action.maxOutputBytes) { "VBOOK_OUTPUT_TOO_LARGE" }
                SourceActionResponse(normalized, request.traceId, budget.instructions.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }.value
''',
    '''        diagnostics.emit(event(manifest, request, "VBOOK_ACTION_STARTED", attributes = mapOf("action" to request.action.name)))
        runCatching { JsonCodec.stringify(request.input) }.getOrNull()?.let { input ->
            captureEvidence(manifest, request, "executor-input.json", "application/json", input, mapOf("action" to request.action.name))
        }
        return runCatching {
            sandboxExecutor(manifest, timeoutMs).execute { cx, scope, budget ->
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_SANDBOX_ENTERED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "action" to request.action.name,
                    "timeoutMs" to timeoutMs.toString(),
                    "instructionBudget" to manifest.runtime.instructionBudget.toString(),
                    "memoryBudgetBytes" to manifest.runtime.memoryBudgetBytes.toString(),
                )))
                VBookSafeRhinoBoundary.installCurrentContext()
                installHostApi(cx, scope, manifest, resources, request, budget)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_HOST_API_READY", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                cx.evaluateString(scope, BOOTSTRAP, "vbook-bootstrap", 1, null)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_BOOTSTRAP_EVALUATED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "instructions" to budget.instructions.toString(),
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                val loaded = linkedSetOf<String>()
                val loader = ScriptLoader(cx, scope, resources, loaded, budget) { resourcePath, bytes ->
                    diagnostics.emit(event(manifest, request, "VBOOK_RESOURCE_LOADED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "path" to resourcePath.take(300),
                        "bytes" to bytes.toString(),
                        "loadedCount" to loaded.size.toString(),
                        "instructions" to budget.instructions.toString(),
                    )))
                }
                loader.install()
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_ENTRY_LOADING", DiagnosticSeverity.DEBUG, attributes = mapOf("entry" to action.entry.take(300))))
                loader.load(action.entry)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_ENTRY_LOADED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "entry" to action.entry.take(300),
                    "loadedResources" to loaded.size.toString(),
                    "instructions" to budget.instructions.toString(),
                )))
                val execute = ScriptableObject.getProperty(scope, "execute") as? Function
                    ?: error("VBOOK_EXECUTE_FUNCTION_MISSING:${action.entry}")
                val args = actionArguments(cx, scope, request)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_EXECUTOR_CALL", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "action" to request.action.name,
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                val rawResult = execute.call(cx, scope, scope, args)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_EXECUTOR_RETURNED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "resultType" to (rawResult?.javaClass?.simpleName ?: "null"),
                    "instructions" to budget.instructions.toString(),
                )))
                ScriptableObject.putProperty(scope, "__ngheResult", rawResult)
                val json = Context.toString(cx.evaluateString(scope, "JSON.stringify(__ngheResult)", "vbook-result", 1, null))
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_RESULT_STRINGIFIED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "bytes" to json.toByteArray(Charsets.UTF_8).size.toString(),
                    "instructions" to budget.instructions.toString(),
                )))
                captureEvidence(manifest, request, "executor-result-raw.json", "application/json", json, mapOf("action" to request.action.name))
                require(json != "undefined" && json.toByteArray(Charsets.UTF_8).size <= action.maxOutputBytes) { "VBOOK_OUTPUT_TOO_LARGE" }
                val parsed = JsonCodec.parse(json, maxDepth = 96, maxNodes = manifest.runtime.instructionBudget)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_RESULT_PARSED", DiagnosticSeverity.DEBUG, attributes = mapOf("instructions" to budget.instructions.toString())))
                val normalized = normalizeResult(request, parsed)
                val normalizedJson = JsonCodec.stringify(normalized)
                val bytes = normalizedJson.toByteArray(Charsets.UTF_8).size
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_RESULT_NORMALIZED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "bytes" to bytes.toString(),
                    "instructions" to budget.instructions.toString(),
                )))
                captureEvidence(manifest, request, "executor-result-normalized.json", "application/json", normalizedJson, mapOf("action" to request.action.name))
                require(bytes <= action.maxOutputBytes) { "VBOOK_OUTPUT_TOO_LARGE" }
                SourceActionResponse(normalized, request.traceId, budget.instructions.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }.value
''',
)
replace_once(
    path,
    '''                val result = brokers.nativeHooks.execute(manifest, SourceNativeHookRequest(
                    sourceId = manifest.id,
                    sourceCode = sourceCode,
                    hookName = hookName,
                    inputJson = inputJson,
                    instructionBudget = manifest.runtime.instructionBudget,
                    timeoutMs = (budget.deadlineMs - clockMs()).coerceIn(100L, 30_000L),
                    memoryBudgetBytes = manifest.runtime.memoryBudgetBytes,
                    moduleSources = nativeModuleSources(resources),
                    resourceSources = nativeResourceSources(resources),
                    traceId = request.traceId,
                ))
                val output = when (result) {
                    is SourcePlatformResult.Success -> result.value
                    is SourcePlatformResult.Failure -> error("NATIVE_LUA_${result.error.code}:${result.error.message}")
                }
                return cx.evaluateString(scope, "JSON.parse(${JsonCodec.stringify(JsonValue.Str(output))})", "native-hook-output", 1, null)
''',
    '''                diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_NATIVE_HOOK_STARTED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "hook" to hookName.take(160),
                    "inputBytes" to inputJson.toByteArray(Charsets.UTF_8).size.toString(),
                    "sourceBytes" to sourceCode.size.toString(),
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                captureEvidence(manifest, request, "bridge-$hookName-input.json", "application/json", inputJson, mapOf("hook" to hookName))
                val result = brokers.nativeHooks.execute(manifest, SourceNativeHookRequest(
                    sourceId = manifest.id,
                    sourceCode = sourceCode,
                    hookName = hookName,
                    inputJson = inputJson,
                    instructionBudget = manifest.runtime.instructionBudget,
                    timeoutMs = (budget.deadlineMs - clockMs()).coerceIn(100L, 30_000L),
                    memoryBudgetBytes = manifest.runtime.memoryBudgetBytes,
                    moduleSources = nativeModuleSources(resources),
                    resourceSources = nativeResourceSources(resources),
                    traceId = request.traceId,
                ))
                val output = when (result) {
                    is SourcePlatformResult.Success -> {
                        diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_NATIVE_HOOK_COMPLETED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                            "hook" to hookName.take(160),
                            "outputBytes" to result.value.toByteArray(Charsets.UTF_8).size.toString(),
                            "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                        )))
                        captureEvidence(manifest, request, "bridge-$hookName-output.json", "application/json", result.value, mapOf("hook" to hookName))
                        result.value
                    }
                    is SourcePlatformResult.Failure -> {
                        diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_NATIVE_HOOK_FAILED", DiagnosticSeverity.ERROR, attributes = mapOf(
                            "hook" to hookName.take(160),
                            "code" to result.error.code.name,
                            "message" to result.error.message.take(500),
                        )))
                        error("NATIVE_LUA_${result.error.code}:${result.error.message}")
                    }
                }
                return cx.evaluateString(scope, "JSON.parse(${JsonCodec.stringify(JsonValue.Str(output))})", "native-hook-output", 1, null)
''',
)
replace_once(
    path,
    '''    private fun event(manifest: SourceManifest, request: SourceActionRequest, name: String, severity: DiagnosticSeverity = DiagnosticSeverity.INFO, durationMs: Long? = null, attributes: Map<String, String> = emptyMap()) =
        DiagnosticEvent(clockMs(), request.traceId, manifest.id, manifest.version.toString(), DiagnosticCategory.RUNTIME, name, severity, durationMs, attributes)

    companion object {
''',
    '''    private fun event(manifest: SourceManifest, request: SourceActionRequest, name: String, severity: DiagnosticSeverity = DiagnosticSeverity.INFO, durationMs: Long? = null, attributes: Map<String, String> = emptyMap()) =
        DiagnosticEvent(clockMs(), request.traceId, manifest.id, manifest.version.toString(), DiagnosticCategory.RUNTIME, name, severity, durationMs, attributes)

    private fun captureEvidence(
        manifest: SourceManifest,
        request: SourceActionRequest,
        name: String,
        contentType: String,
        text: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (!evidence.enabled || text.isBlank()) return
        evidence.capture(
            DiagnosticEvidence(
                timestampEpochMs = clockMs(),
                traceId = request.traceId,
                sourceId = manifest.id,
                category = DiagnosticCategory.RUNTIME,
                name = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180),
                contentType = contentType,
                data = text.toByteArray(Charsets.UTF_8),
                attributes = attributes + mapOf("action" to request.action.name),
            ),
        )
    }

    companion object {
''',
)
replace_once(
    path,
    '''private class ScriptLoader(
    private val cx: Context,
    private val scope: Scriptable,
    private val resources: SourceResourceProvider,
    private val loaded: MutableSet<String>,
    private val budget: RhinoExecutionBudget,
) {
''',
    '''private class ScriptLoader(
    private val cx: Context,
    private val scope: Scriptable,
    private val resources: SourceResourceProvider,
    private val loaded: MutableSet<String>,
    private val budget: RhinoExecutionBudget,
    private val onLoad: (String, Int) -> Unit = { _, _ -> },
) {
''',
)
replace_once(
    path,
    '''        budget.charge(1 + bytes.size / 128)
        cx.evaluateString(scope, bytes.toString(Charsets.UTF_8), path, 1, null)
''',
    '''        budget.charge(1 + bytes.size / 128)
        cx.evaluateString(scope, bytes.toString(Charsets.UTF_8), path, 1, null)
        onLoad(path, bytes.size)
''',
)

# Pass the shared evidence sink to the main vBook runtime.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt"
replace_once(
    path,
    '''    private val vBookRuntime = VBookJsRuntime(brokers, diagnostics)
''',
    '''    private val vBookRuntime = VBookJsRuntime(brokers, diagnostics, evidence = evidence)
''',
)

# 6) Guarantee an always-on persistent breadcrumb at user-facing extension install boundaries. Some
# failures happen before a lower verifier/runtime has emitted an event, so this boundary event is the
# last-resort equivalent of Lua extension_install_logs.
replace_once(
    path,
    '''    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> = runCatching {
        val normalized = normalizeRepositoryUrl(url)
        val raw = repositoryHttpClient.fetchIndex(normalized, SourceRepositoryVerifier.MAX_INDEX_BYTES)
        val verified = when (val result = repositoryVerifier.verify(raw, trustRegistry.allKeys())) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        rememberRepository(normalized, raw, verified)
        repositories().first { it.id == verified.index.repositoryId }
    }
''',
    '''    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> = runCatching {
        val normalized = normalizeRepositoryUrl(url)
        val raw = repositoryHttpClient.fetchIndex(normalized, SourceRepositoryVerifier.MAX_INDEX_BYTES)
        val verified = when (val result = repositoryVerifier.verify(raw, trustRegistry.allKeys())) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        rememberRepository(normalized, raw, verified)
        repositories().first { it.id == verified.index.repositoryId }
    }.onFailure { recordExtensionFailure("repository_refresh", null, it) }
''',
)
replace_once(
    path,
    '''        require(pack.packageSha256 == entry.packageSha256) { "Hash gói nguồn không khớp repository." }
        preparePack(pack)
    }

    fun prepareInstall(input: InputStream): Result<SourceInstallPreview> = runCatching {
''',
    '''        require(pack.packageSha256 == entry.packageSha256) { "Hash gói nguồn không khớp repository." }
        preparePack(pack)
    }.onFailure { recordExtensionFailure("repository_prepare_install", sourceId, it) }

    fun prepareInstall(input: InputStream): Result<SourceInstallPreview> = runCatching {
''',
)
replace_once(
    path,
    '''        }
        preparePack(pack)
    }

    /**
     * Preview a raw vBook ZIP without converting it to SourcePack.''',
    '''        }
        preparePack(pack)
    }.onFailure { recordExtensionFailure("sourcepack_prepare_install", null, it) }

    /**
     * Preview a raw vBook ZIP without converting it to SourcePack.''',
)
replace_once(
    path,
    '''            fixtureCount = preview.validation.audit?.features?.size ?: 0,
        )
    }

    fun prepareNativeLuaImport''',
    '''            fixtureCount = preview.validation.audit?.features?.size ?: 0,
        )
    }.onFailure { recordExtensionFailure("vbook_prepare_import", "vbook-import", it) }

    fun prepareNativeLuaImport''',
)
replace_once(
    path,
    '''    fun prepareNativeLuaImport(input: InputStream): Result<SourceInstallPreview> = runCatching {
        val (pack, warnings) = NativeLuaArchiveImporter.import(input)
        pendingWarnings = warnings
        preparePack(pack)
    }
''',
    '''    fun prepareNativeLuaImport(input: InputStream): Result<SourceInstallPreview> = runCatching {
        val (pack, warnings) = NativeLuaArchiveImporter.import(input)
        pendingWarnings = warnings
        preparePack(pack)
    }.onFailure { recordExtensionFailure("native_lua_prepare_import", "native-lua-import", it) }
''',
)
replace_once(
    path,
    '''        pendingWarnings = emptyList()
        installedPacks().first { it.id == installed.sourceId }
    }

    fun cancelPendingInstall()''',
    '''        pendingWarnings = emptyList()
        installedPacks().first { it.id == installed.sourceId }
    }.onFailure {
        recordExtensionFailure("confirm_install", pendingVBook?.sourceId ?: pendingPack?.manifest?.id, it)
    }

    fun cancelPendingInstall()''',
)
replace_once(
    path,
    '''    private data class PendingVBookImport(
''',
    '''    private fun recordExtensionFailure(stage: String, sourceId: String?, error: Throwable) {
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = "extension-install:${UUID.randomUUID()}",
                sourceId = sourceId?.takeIf(String::isNotBlank) ?: "source-platform",
                category = DiagnosticCategory.PACKAGE,
                name = "SOURCE_EXTENSION_INSTALL_FAILED",
                severity = DiagnosticSeverity.ERROR,
                attributes = mapOf(
                    "stage" to stage,
                    "message" to (error.message ?: error.javaClass.simpleName).take(1_000),
                    "errorType" to error.javaClass.simpleName,
                    "pendingWarnings" to pendingWarnings.take(20).joinToString(" | ").take(2_000),
                ),
            ),
        )
    }

    private data class PendingVBookImport(
''',
)

# 7) Unit-test the always-on critical breadcrumb contract.
path = "source-diagnostics/src/test/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnosticsTest.kt"
replace_once(
    path,
    '''    @Test fun redactsSecretsAndBoundsEvents() {
        val recorder = BoundedDiagnosticRecorder(2, DiagnosticLevel.VERBOSE)
        repeat(3) { index -> recorder.emit(DiagnosticEvent(index.toLong(), "t", "s", category = DiagnosticCategory.NETWORK, name = "request", attributes = mapOf("Authorization" to "Bearer secret-$index"))) }
        val events = recorder.snapshot()
        assertTrue(events.size == 2)
        assertTrue(events.all { it.attributes["Authorization"]!!.startsWith("<redacted:") })
        assertFalse(DiagnosticJsonExporter.export(events).toString(Charsets.UTF_8).contains("secret-"))
    }
''',
    '''    @Test fun redactsSecretsAndBoundsEvents() {
        val recorder = BoundedDiagnosticRecorder(2, DiagnosticLevel.VERBOSE)
        repeat(3) { index -> recorder.emit(DiagnosticEvent(index.toLong(), "t", "s", category = DiagnosticCategory.NETWORK, name = "request", attributes = mapOf("Authorization" to "Bearer secret-$index"))) }
        val events = recorder.snapshot()
        assertTrue(events.size == 2)
        assertTrue(events.all { it.attributes["Authorization"]!!.startsWith("<redacted:") })
        assertFalse(DiagnosticJsonExporter.export(events).toString(Charsets.UTF_8).contains("secret-"))
    }

    @Test fun diagnosticsOffRetainsOnlyCriticalBreadcrumbs() {
        val recorder = BoundedDiagnosticRecorder(20, DiagnosticLevel.OFF)
        recorder.emit(DiagnosticEvent(1, "a", "source", category = DiagnosticCategory.RUNTIME, name = "normal", severity = DiagnosticSeverity.INFO))
        recorder.emit(DiagnosticEvent(2, "b", "source", category = DiagnosticCategory.RUNTIME, name = "runtime_warn", severity = DiagnosticSeverity.WARN))
        recorder.emit(DiagnosticEvent(3, "c", "source", category = DiagnosticCategory.PACKAGE, name = "install_warn", severity = DiagnosticSeverity.WARN))
        recorder.emit(DiagnosticEvent(4, "d", "source", category = DiagnosticCategory.RUNTIME, name = "fatal", severity = DiagnosticSeverity.ERROR))
        val names = recorder.snapshot().map(DiagnosticEvent::name)
        assertTrue(names == listOf("install_warn", "fatal"))
    }
''',
)

# 8) Extend the parity gate to lock in the deep browser/vBook/login/install behavior.
path = "scripts/check_lua_diagnostics_ui_parity.py"
replace_once(
    path,
    '''browser = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
''',
    '''browser = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
login = text("app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt")
diagnostic_browser = text("app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt")
vbook_runtime = text("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
source_manager = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt")
''',
)
replace_once(
    path,
    '''    "download diagnostics": all(
''',
    '''    "real login browser diagnostics": all(marker in login for marker in (
        "SOURCE_LOGIN_STARTED",
        "SOURCE_LOGIN_PAGE_STARTED",
        "SOURCE_LOGIN_PAGE_FINISHED",
        "SOURCE_LOGIN_REQUEST",
        "SOURCE_LOGIN_WEB_ERROR",
        "SOURCE_LOGIN_SSL_BLOCKED",
        "SOURCE_LOGIN_SESSION_CAPTURED",
        "SOURCE_LOGIN_STOPPED",
        "EXTRA_TRACE_ID",
    )) and "getInt(KEY_LOG_LEVEL, 1)" in login and "getBoolean(KEY_AUTO_CLEAR_LOG_ON_CLOSE, true)" in login,
    "diagnostic browser mirrors global trace": "mirrorGlobal" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STARTED" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STOPPED" in diagnostic_browser,
    "deep browser WebView timeline": all(marker in browser for marker in (
        "BROWSER_PAGE_STARTED",
        "BROWSER_PAGE_FINISHED",
        "BROWSER_WEB_ERROR",
        "BROWSER_SSL_ERROR",
        "BROWSER_SAFE_BROWSING_BLOCKED",
        "BROWSER_RENDERER_GONE",
        "BROWSER_SELECTOR_PROBE",
        "BROWSER_SELECTOR_FOUND",
        "BROWSER_SELECTOR_TIMEOUT",
        "BROWSER_ASYNC_SCRIPT_POLL",
        "BROWSER_ASYNC_SCRIPT_RESOLVED",
    )),
    "vBook executor bridge diagnostics": all(marker in vbook_runtime for marker in (
        "VBOOK_STAGE_SANDBOX_ENTERED",
        "VBOOK_STAGE_HOST_API_READY",
        "VBOOK_STAGE_BOOTSTRAP_EVALUATED",
        "VBOOK_RESOURCE_LOADED",
        "VBOOK_STAGE_EXECUTOR_CALL",
        "VBOOK_STAGE_EXECUTOR_RETURNED",
        "VBOOK_STAGE_RESULT_NORMALIZED",
        "VBOOK_BRIDGE_NATIVE_HOOK_STARTED",
        "VBOOK_BRIDGE_NATIVE_HOOK_COMPLETED",
        "VBOOK_BRIDGE_NATIVE_HOOK_FAILED",
        "executor-result-raw.json",
    )),
    "extension install critical boundary": "SOURCE_EXTENSION_INSTALL_FAILED" in source_manager and "recordExtensionFailure" in source_manager,
    "crash-safe text evidence redacted on disk": "redactEvidenceForDisk" in runtime and "redactHtmlPreservingStructure" in runtime,
    "download diagnostics": all(
''',
)

print("DIAGNOSTICS_DEEPENING_STAGE_C=APPLIED")
