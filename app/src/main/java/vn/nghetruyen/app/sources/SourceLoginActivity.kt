package vn.nghetruyen.app.sources

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.sourceplatform.DiagnosticTransientScreenScope
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.util.UUID

class SourceLoginActivity : ComponentActivity() {
    private lateinit var sourceId: String
    private lateinit var loginUrl: String
    private lateinit var allowedHosts: Set<String>
    private lateinit var sessionStore: SourceSessionStore
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var addressField: EditText
    private lateinit var diagnostics: SourceDiagnosticRuntime
    private lateinit var diagnosticScreenScope: DiagnosticTransientScreenScope
    private lateinit var diagnosticTraceId: String
    private var diagnosticStartedAt: Long = 0L
    private var requestCount: Int = 0

    private val browserPrefs by lazy { getSharedPreferences(BROWSER_PREFS, MODE_PRIVATE) }
    private var desktopCompat = false
    private var logLevel = 1
    private var autoClearLogOnClose = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        allowedHosts = intent.getStringArrayExtra(EXTRA_ALLOWED_HOSTS)?.toSet().orEmpty()
        require(sourceId.isNotBlank() && loginUrl.isNotBlank() && allowedHosts.isNotEmpty()) {
            "Thiếu cấu hình đăng nhập nguồn."
        }
        require(isAllowed(loginUrl)) { "URL đăng nhập nằm ngoài allowlist." }
        val app = application as NgheTruyenApplication
        sessionStore = app.container.sourceSessionStore
        diagnostics = app.container.sourceDiagnostics
        diagnosticScreenScope = DiagnosticTransientScreenScope.enter(
            diagnostics = diagnostics,
            screenKey = diagnosticScreenKey(),
        )
        beginDiagnosticSession(resumed = false)
        desktopCompat = browserPrefs.getBoolean(KEY_CHROME_COMPAT, false)
        logLevel = browserPrefs.getInt(KEY_LOG_LEVEL, 1).coerceIn(0, 2)
        autoClearLogOnClose = browserPrefs.getBoolean(KEY_AUTO_CLEAR_LOG_ON_CLOSE, true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Đăng nhập trực tiếp trên trang nguồn. Ứng dụng chỉ lưu cookie phiên đã mã hóa, không đọc hoặc lưu mật khẩu."
            setPadding(24, 18, 24, 18)
        }
        addressField = EditText(this).apply {
            setSingleLine(true)
            setText(loginUrl)
            hint = "URL HTTPS thuộc nguồn"
        }

        fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        fun actionRow(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        val navigation = actionRow(
            actionButton("QUAY LẠI") { if (webView.canGoBack()) webView.goBack() },
            actionButton("TIẾN TỚI") { if (webView.canGoForward()) webView.goForward() },
            actionButton("TÙY CHỌN") { showBrowserOptions() },
        )
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addressField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionButton("ĐI TỚI") {
                val target = addressField.text.toString().trim()
                if (isAllowed(target)) webView.loadUrl(target)
                else status.text = "URL phải dùng HTTPS và thuộc miền của nguồn."
            })
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.userAgentString = currentUserAgent()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
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
                    if (diagnostics.advanced || requestCount <= 20 || requestCount % 25 == 0) {
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
                    status.text = "Tiến trình WebView đã dừng. Nhật ký đã ghi lại sự cố."
                    runCatching { view.destroy() }
                    finish()
                    return true
                }
            }
        }
        root.addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navigation, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(addressRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        seedWebViewCookies()
        webView.loadUrl(loginUrl)
    }

    private fun beginDiagnosticSession(resumed: Boolean) {
        diagnosticTraceId = "login:$sourceId:${UUID.randomUUID()}"
        diagnosticStartedAt = System.currentTimeMillis()
        requestCount = 0
        diagnostic(
            name = "SOURCE_LOGIN_STARTED",
            severity = DiagnosticSeverity.INFO,
            attributes = mapOf(
                "url" to diagnosticUrl(loginUrl),
                "allowedHosts" to allowedHosts.size.toString(),
                "resumed" to resumed.toString(),
            ),
        )
    }

    private fun showBrowserOptions() {
        AlertDialog.Builder(this)
            .setTitle("TÙY CHỌN TRÌNH DUYỆT")
            .setItems(
                arrayOf(
                    "LÀM MỚI",
                    "TÙY CHỌN KHÁC",
                    "XÓA DỮ LIỆU ĐĂNG NHẬP CỦA TRANG",
                    "ĐÓNG TRÌNH DUYỆT",
                ),
            ) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> showOtherOptions()
                    2 -> confirmClearLoginData()
                    3 -> {
                        captureSession()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun showOtherOptions() {
        val chrome = if (desktopCompat) "BẬT" else "TẮT"
        val level = logLevelLabel(logLevel).uppercase()
        val autoClear = if (autoClearLogOnClose) "BẬT" else "TẮT"
        AlertDialog.Builder(this)
            .setTitle("TÙY CHỌN KHÁC")
            .setItems(
                arrayOf(
                    "TƯƠNG THÍCH CHROME: $chrome",
                    "CHẨN ĐOÁN TRÌNH DUYỆT",
                    "MỨC GHI NHẬT KÝ: $level",
                    "TỰ XÓA NHẬT KÝ KHI ĐÓNG: $autoClear",
                    "MỞ BẰNG TRÌNH DUYỆT HỆ THỐNG",
                ),
            ) { _, which ->
                when (which) {
                    0 -> {
                        desktopCompat = !desktopCompat
                        browserPrefs.edit().putBoolean(KEY_CHROME_COMPAT, desktopCompat).apply()
                        webView.settings.userAgentString = currentUserAgent()
                        webView.reload()
                    }
                    1 -> openDiagnosticBrowser()
                    2 -> showLogLevelDialog()
                    3 -> {
                        autoClearLogOnClose = !autoClearLogOnClose
                        browserPrefs.edit().putBoolean(KEY_AUTO_CLEAR_LOG_ON_CLOSE, autoClearLogOnClose).apply()
                    }
                    4 -> openSystemBrowser()
                }
            }
            .setNegativeButton("ĐÓNG", null)
            .show()
    }

    private fun showLogLevelDialog() {
        val labels = arrayOf("TẮT", "CƠ BẢN", "CHI TIẾT")
        AlertDialog.Builder(this)
            .setTitle("MỨC GHI NHẬT KÝ")
            .setSingleChoiceItems(labels, logLevel) { dialog, which ->
                logLevel = which.coerceIn(0, 2)
                browserPrefs.edit().putInt(KEY_LOG_LEVEL, logLevel).apply()
                dialog.dismiss()
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun confirmClearLoginData() {
        val host = runCatching { Uri.parse(webView.url ?: loginUrl).host }.getOrNull()
            ?: allowedHosts.firstOrNull().orEmpty()
        AlertDialog.Builder(this)
            .setTitle("XÓA DỮ LIỆU ĐĂNG NHẬP")
            .setMessage("Xóa cookie và dữ liệu đăng nhập của $host?")
            .setPositiveButton("XÓA") { _, _ ->
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
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun openDiagnosticBrowser() {
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

    private fun openSystemBrowser() {
        val target = webView.url?.takeIf(::isAllowed) ?: loginUrl
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }

    private fun currentUserAgent(): String = if (desktopCompat) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    } else {
        WebSettings.getDefaultUserAgent(this)
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized || !::diagnostics.isInitialized) return
        val last = diagnostics.recorder.snapshot().lastOrNull()
        val restoredFreshScreen = diagnostics.mode == SourceDiagnosticRuntime.MODE_SCREEN &&
            last?.name == "DIAGNOSTIC_SCREEN_STARTED" &&
            last.attributes["screen"] == diagnosticScreenKey()
        if (restoredFreshScreen) beginDiagnosticSession(resumed = true)
        webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            captureSession()
        }
        super.onPause()
    }

    override fun onDestroy() {
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
        if (::diagnosticScreenScope.isInitialized) diagnosticScreenScope.close()
        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    private fun captureSession() {
        val manager = CookieManager.getInstance()
        val merged = allowedHosts.mapNotNull { host -> manager.getCookie("https://$host/") }
            .fold(sessionStore.cookieHeader(sourceId).orEmpty()) { current, header ->
                CookieHeaderCodec.merge(current, header.split(';').map { "$it; Path=/" })
            }
        sessionStore.replaceCookieHeader(sourceId, merged)
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

    private fun seedWebViewCookies() {
        val cookie = sessionStore.cookieHeader(sourceId) ?: return
        val manager = CookieManager.getInstance()
        allowedHosts.forEach { host ->
            cookie.split(';').map(String::trim).filter(String::isNotBlank).forEach { item ->
                manager.setCookie("https://$host/", "$item; Path=/; Secure; SameSite=Lax")
            }
        }
        manager.flush()
    }

    private fun clearSessionCookies() {
        clearStoredSession(sourceId, allowedHosts, sessionStore)
    }

    private fun diagnosticScreenKey(): String = "source-login:${sourceId.take(120)}"

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
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_ALLOWED_HOSTS = "allowed_hosts"

        const val BROWSER_PREFS = "reference_browser_options"
        const val KEY_CHROME_COMPAT = "chrome_compat"
        const val KEY_LOG_LEVEL = "log_level"
        const val KEY_AUTO_CLEAR_LOG_ON_CLOSE = "auto_clear_log_on_close"

        fun logLevelLabel(level: Int): String = when (level) {
            1 -> "Cơ bản"
            2 -> "Chi tiết"
            else -> "Tắt"
        }

        fun clearStoredSession(sourceId: String, allowedHosts: Set<String>, sessionStore: SourceSessionStore) {
            val manager = CookieManager.getInstance()
            val cookieHeaders = buildList {
                sessionStore.cookieHeader(sourceId)?.let(::add)
                allowedHosts.mapNotNullTo(this) { host -> manager.getCookie("https://$host/") }
            }
            val cookieNames = cookieHeaders.flatMap(CookieHeaderCodec::cookieNames).distinct()
            allowedHosts.forEach { host ->
                cookieNames.forEach { name ->
                    manager.setCookie("https://$host/", "$name=; Path=/; Max-Age=0; Secure; SameSite=Lax")
                }
            }
            manager.flush()
            sessionStore.clear(sourceId)
        }
    }
}
