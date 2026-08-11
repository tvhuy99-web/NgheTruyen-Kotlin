package vn.nghetruyen.app.sources

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Source-scoped diagnostics browser matching the option hierarchy of the reference XPK.
 * Only redacted metadata is recorded. Passwords, form values, response bodies and cookie values
 * are never written to the diagnostic log.
 */
class SourceDiagnosticBrowserActivity : ComponentActivity() {
    private lateinit var sourceId: String
    private lateinit var initialUrl: String
    private lateinit var allowedHosts: Set<String>
    private lateinit var sessionStore: SourceSessionStore
    private lateinit var webView: WebView
    private lateinit var urlField: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var diagnostics: SourceDiagnosticRuntime
    private lateinit var diagnosticTraceId: String
    private var diagnosticStartedAt = 0L

    private val entries = ArrayDeque<DiagnosticEntry>()
    private var requestCount = 0
    private val browserPrefs by lazy {
        getSharedPreferences(SourceLoginActivity.BROWSER_PREFS, MODE_PRIVATE)
    }
    private var desktopCompat = false
    private var logLevel = 1
    private var autoClearLogOnClose = true

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(exportJson().toByteArray(Charsets.UTF_8))
            } ?: error("Không mở được tệp xuất.")
        }.onSuccess {
            status.text = "Đã xuất nhật ký chẩn đoán đã khử dữ liệu nhạy cảm."
        }.onFailure {
            status.text = it.message ?: "Không xuất được nhật ký."
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
        allowedHosts = intent.getStringArrayExtra(EXTRA_ALLOWED_HOSTS)?.map(String::lowercase)?.toSet().orEmpty()
        require(sourceId.isNotBlank() && initialUrl.isNotBlank() && allowedHosts.isNotEmpty()) {
            "Thiếu cấu hình chẩn đoán nguồn."
        }
        require(isAllowed(initialUrl)) { "URL chẩn đoán nằm ngoài allowlist." }
        val app = application as NgheTruyenApplication
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

        WebView.setWebContentsDebuggingEnabled(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Trình duyệt chẩn đoán chỉ ghi metadata đã khử bí mật."
            setPadding(20, 12, 20, 12)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        urlField = EditText(this).apply {
            setSingleLine(true)
            setText(initialUrl)
            hint = "URL HTTPS thuộc nguồn"
        }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        fun row(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        root.addView(status, matchWrap())
        root.addView(progress, matchWrap())
        root.addView(
            row(
                button("QUAY LẠI") { if (webView.canGoBack()) webView.goBack() },
                button("TIẾN TỚI") { if (webView.canGoForward()) webView.goForward() },
                button("TÙY CHỌN") { showBrowserOptions() },
            ),
            matchWrap(),
        )
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("ĐI TỚI") { navigate(urlField.text.toString()) })
        }
        root.addView(addressRow, matchWrap())

        webView = WebView(this).apply browser@{
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = currentUserAgent()
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@browser, false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@SourceDiagnosticBrowserActivity.progress.progress = newProgress
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    record("CONSOLE", "${message.messageLevel()}@${message.lineNumber()}", sanitize(message.message(), 800))
                    return true
                }
            }
            webViewClient = diagnosticClient()
        }
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        seedWebViewCookies()
        record("INFO", "BROWSER_STARTED", "source=$sourceId")
        navigate(initialUrl)
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
                    3 -> finish()
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun showOtherOptions() {
        val chrome = if (desktopCompat) "BẬT" else "TẮT"
        val level = SourceLoginActivity.logLevelLabel(logLevel).uppercase()
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
                        browserPrefs.edit().putBoolean(SourceLoginActivity.KEY_CHROME_COMPAT, desktopCompat).apply()
                        webView.settings.userAgentString = currentUserAgent()
                        webView.reload()
                    }
                    1 -> showDiagnosticsDialog()
                    2 -> showLogLevelDialog()
                    3 -> {
                        autoClearLogOnClose = !autoClearLogOnClose
                        browserPrefs.edit()
                            .putBoolean(SourceLoginActivity.KEY_AUTO_CLEAR_LOG_ON_CLOSE, autoClearLogOnClose)
                            .apply()
                    }
                    4 -> {
                        val target = webView.url?.takeIf(::isHttps) ?: initialUrl
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    }
                }
            }
            .setNegativeButton("ĐÓNG", null)
            .show()
    }

    private fun showLogLevelDialog() {
        AlertDialog.Builder(this)
            .setTitle("MỨC GHI NHẬT KÝ")
            .setSingleChoiceItems(arrayOf("TẮT", "CƠ BẢN", "CHI TIẾT"), logLevel) { dialog, which ->
                logLevel = which.coerceIn(0, 2)
                browserPrefs.edit().putInt(SourceLoginActivity.KEY_LOG_LEVEL, logLevel).apply()
                record("INFO", "LOG_LEVEL", SourceLoginActivity.logLevelLabel(logLevel))
                dialog.dismiss()
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun showDiagnosticsDialog() {
        AlertDialog.Builder(this)
            .setTitle("CHẨN ĐOÁN TRÌNH DUYỆT")
            .setItems(
                arrayOf(
                    "LÀM MỚI",
                    "SAO CHÉP NHẬT KÝ",
                    "XUẤT NHẬT KÝ",
                    "KIỂM TRA JS",
                    "KIỂM TRA COOKIE",
                    "QUÉT TRANG",
                    "XÓA NHẬT KÝ",
                ),
            ) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> copyLog()
                    2 -> exportLauncher.launch("source-${sourceId.take(40)}-diagnostics.json")
                    3 -> runJavaScriptProbe()
                    4 -> runCookieProbe()
                    5 -> runDomProbe()
                    6 -> clearLog()
                }
            }
            .setNegativeButton("ĐÓNG", null)
            .show()
    }

    private fun confirmClearLoginData() {
        val host = runCatching { Uri.parse(webView.url ?: initialUrl).host }.getOrNull()
            ?: allowedHosts.firstOrNull().orEmpty()
        AlertDialog.Builder(this)
            .setTitle("XÓA DỮ LIỆU ĐĂNG NHẬP")
            .setMessage("Xóa cookie và dữ liệu đăng nhập của $host?")
            .setPositiveButton("XÓA") { _, _ ->
                SourceLoginActivity.clearStoredSession(sourceId, allowedHosts, sessionStore)
                status.text = "Đã xóa dữ liệu đăng nhập của trang."
                webView.reload()
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun diagnosticClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (!isAllowed(target)) {
                record("SECURITY", "NAVIGATION_BLOCKED", redactUrl(target))
                status.text = "Đã chặn điều hướng ra ngoài miền của nguồn."
                return true
            }
            record("NAV", "NAVIGATION", redactUrl(target))
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            urlField.setText(url)
            record("PAGE", "START", redactUrl(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            urlField.setText(url)
            captureSession()
            record("PAGE", "FINISH", redactUrl(url))
            status.text = "Trang đã tải. Có $requestCount request trong phiên chẩn đoán."
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            requestCount += 1
            record(
                "REQUEST",
                request.method,
                "${redactUrl(request.url.toString())} main=${request.isForMainFrame} headers=${request.requestHeaders.keys.sorted().joinToString()}",
            )
            return null
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            record(
                "ERROR",
                "WEB_${error.errorCode}",
                "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())} desc=${sanitize(error.description.toString(), 300)}",
            )
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            record("HTTP", "HTTP_${errorResponse.statusCode}", "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())}")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            record("SECURITY", "SSL_BLOCKED", "primary=${error.primaryError} url=${redactUrl(error.url.orEmpty())}")
        }
    }

    private fun navigate(raw: String) {
        val target = raw.trim()
        if (!isAllowed(target)) {
            status.text = "URL phải dùng HTTPS và thuộc allowlist của nguồn."
            record("SECURITY", "URL_REJECTED", redactUrl(target))
            return
        }
        record("NAV", "LOAD_URL", redactUrl(target))
        webView.loadUrl(target)
    }

    private fun runJavaScriptProbe() {
        webView.evaluateJavascript(
            "JSON.stringify({href:location.href,title:document.title,readyState:document.readyState,links:document.links.length,forms:document.forms.length})",
        ) { raw ->
            record("PROBE", "JS", sanitize(decodeJs(raw), 1_500))
            status.text = "Đã kiểm tra JavaScript."
        }
    }

    private fun runDomProbe() {
        webView.evaluateJavascript(
            "JSON.stringify({title:document.title,textLength:(document.body&&document.body.innerText||'').length,links:document.links.length,forms:document.forms.length,scripts:document.scripts.length,iframes:document.querySelectorAll('iframe').length})",
        ) { raw ->
            record("PROBE", "DOM", sanitize(decodeJs(raw), 1_500))
            status.text = "Đã quét metadata trang."
        }
    }

    private fun runCookieProbe() {
        val url = webView.url ?: initialUrl
        val header = CookieManager.getInstance().getCookie(url).orEmpty()
        val names = CookieHeaderCodec.cookieNames(header).distinct().sorted()
        record(
            "PROBE",
            "COOKIE",
            "host=${Uri.parse(url).host.orEmpty()} count=${names.size} names=${names.joinToString()} storedSession=${sessionStore.hasSession(sourceId)}",
        )
        status.text = "Đã kiểm tra cookie theo tên, không ghi giá trị cookie."
    }

    private fun record(level: String, category: String, detail: String) {
        val safeDetail = sanitize(detail, 2_000)
        mirrorGlobal(level, category, safeDetail)
        val normalized = level.uppercase(Locale.ROOT)
        val keepLocal = when (normalized) {
            "SECURITY", "PROBE" -> true
            "REQUEST", "CONSOLE" -> logLevel >= 2
            else -> logLevel >= 1
        }
        if (!keepLocal) return
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

    private fun copyLog() {
        val text = renderLogText()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Nhật ký chẩn đoán", text))
        status.text = "Đã sao chép nhật ký chẩn đoán."
    }

    private fun clearLog() {
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

    private fun renderLogText(): String = buildString {
        appendLine("Nguồn: $sourceId")
        appendLine("Mức ghi: ${SourceLoginActivity.logLevelLabel(logLevel)}")
        appendLine("Request: $requestCount")
        entries.forEach { entry ->
            appendLine("${formatTime(entry.timestamp)} ${entry.level} ${entry.category} ${entry.detail}")
        }
    }

    private fun exportJson(): String = JSONObject()
        .put("sourceId", sourceId)
        .put("logLevel", SourceLoginActivity.logLevelLabel(logLevel))
        .put("requestCount", requestCount)
        .put(
            "entries",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("timestamp", entry.timestamp)
                            .put("level", entry.level)
                            .put("category", entry.category)
                            .put("detail", entry.detail),
                    )
                }
            },
        )
        .toString(2)

    private fun currentUserAgent(): String = if (desktopCompat) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    } else {
        WebSettings.getDefaultUserAgent(this)
    }

    private fun captureSession() {
        val manager = CookieManager.getInstance()
        val merged = allowedHosts.mapNotNull { host -> manager.getCookie("https://$host/") }
            .fold(sessionStore.cookieHeader(sourceId).orEmpty()) { current, header ->
                CookieHeaderCodec.merge(current, header.split(';').map { "$it; Path=/" })
            }
        sessionStore.replaceCookieHeader(sourceId, merged)
        manager.flush()
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

    override fun onPause() {
        if (::webView.isInitialized) captureSession()
        super.onPause()
    }

    override fun onDestroy() {
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
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isHttps(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrBlank()
    }

    private fun isAllowed(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    private fun redactUrl(value: String): String = runCatching {
        val uri = Uri.parse(value)
        if (uri.scheme != "https") return@runCatching "[non-https]"
        val path = uri.encodedPath.orEmpty().take(300)
        "https://${uri.host.orEmpty()}$path"
    }.getOrDefault("[invalid-url]")

    private fun sanitize(value: String, max: Int): String = value
        .replace(Regex("(?i)(password|passwd|token|secret|authorization|cookie)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(max)

    private fun decodeJs(value: String): String = runCatching {
        if (value == "null") "null" else JSONObject("{\"v\":$value}").optString("v", value)
    }.getOrDefault(value)

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(timestamp))

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    data class DiagnosticEntry(
        val timestamp: Long,
        val level: String,
        val category: String,
        val detail: String,
    )

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_INITIAL_URL = "initial_url"
        const val EXTRA_ALLOWED_HOSTS = "allowed_hosts"
        const val EXTRA_TRACE_ID = "diagnostic_trace_id"
        private const val MAX_LOG_ENTRIES = 400
    }
}
