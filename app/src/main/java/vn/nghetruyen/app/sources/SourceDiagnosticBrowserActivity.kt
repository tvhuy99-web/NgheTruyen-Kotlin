package vn.nghetruyen.app.sources

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.NgheTruyenApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated, source-scoped login diagnostics browser.
 *
 * It records URLs, methods, resource classes, header names and browser lifecycle events, but never
 * records request header values, response bodies, passwords, form values or cookie values.
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
    private lateinit var logView: TextView
    private val entries = ArrayDeque<DiagnosticEntry>()
    private val requests = ArrayDeque<DiagnosticRequest>()
    private var verbose = false
    private var requestCount = 0
    private var strictOrigins = true
    private var blockExternalResources = true
    private var dialogPolicy = DialogPolicy.CANCEL
    private var userAgentMode = 0

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output -> output.write(exportJson().toByteArray(Charsets.UTF_8)) }
                ?: error("Không mở được tệp xuất.")
        }.onSuccess { setStatus("Đã xuất nhật ký chẩn đoán đã khử dữ liệu nhạy cảm.") }
            .onFailure { setStatus(it.message ?: "Không xuất được nhật ký.") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
        allowedHosts = intent.getStringArrayExtra(EXTRA_ALLOWED_HOSTS)?.map(String::lowercase)?.toSet().orEmpty()
        require(sourceId.isNotBlank() && initialUrl.isNotBlank() && allowedHosts.isNotEmpty()) { "Thiếu cấu hình chẩn đoán nguồn." }
        require(isAllowed(initialUrl)) { "URL chẩn đoán nằm ngoài allowlist." }
        sessionStore = (application as NgheTruyenApplication).container.sourceSessionStore

        WebView.setWebContentsDebuggingEnabled(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        status = TextView(this).apply {
            text = "Trình duyệt chẩn đoán chỉ ghi metadata đã khử bí mật. Mật khẩu, nội dung biểu mẫu và giá trị cookie không được ghi."
            setPadding(20, 12, 20, 12)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        urlField = EditText(this).apply { setSingleLine(true); setText(initialUrl); hint = "URL HTTPS thuộc nguồn" }
        root.addView(status, matchWrap())
        root.addView(progress, matchWrap())
        root.addView(urlField, matchWrap())
        root.addView(row(
            button("←") { if (webView.canGoBack()) webView.goBack() },
            button("→") { if (webView.canGoForward()) webView.goForward() },
            button("TẢI LẠI") { webView.reload() },
            button("ĐI") { navigate(urlField.text.toString()) },
        ), matchWrap())
        root.addView(row(
            button("KIỂM TRA JS") { runJavaScriptProbe() },
            button("COOKIE") { runCookieProbe() },
            button("QUÉT DOM") { runDomProbe() },
            button("REQUEST") { summarizeRequests() },
        ), matchWrap())
        root.addView(row(
            button("LƯU PHIÊN") { captureSession(); setStatus("Đã lưu phiên nguồn. Cookie chỉ được lưu mã hóa trong kho phiên.") },
            button("MỨC LOG") { verbose = !verbose; record("INFO", "LOG_LEVEL", if (verbose) "VERBOSE" else "BASIC") },
            button("SAO CHÉP") { copyLog() },
            button("XUẤT JSON") { exportLauncher.launch("source-${sourceId.take(40)}-diagnostics.json") },
            button("XÓA LOG") { entries.clear(); requests.clear(); requestCount = 0; renderLog() },
        ), matchWrap())
        root.addView(row(
            button("UA") { cycleUserAgent() },
            button("MIỀN") { strictOrigins = !strictOrigins; record("POLICY", "ORIGIN_MODE", if (strictOrigins) "SOURCE_ONLY" else "COMPATIBLE_HTTPS") },
            button("TÀI NGUYÊN") { blockExternalResources = !blockExternalResources; record("POLICY", "RESOURCE_MODE", if (blockExternalResources) "BLOCK_EXTERNAL" else "OBSERVE_EXTERNAL") },
            button("DIALOG") { dialogPolicy = dialogPolicy.next(); record("POLICY", "DIALOG_MODE", dialogPolicy.name) },
            button("XÓA COOKIE") { clearSourceCookies() },
            button("STORAGE") { runStorageProbe() },
        ), matchWrap())

        webView = WebView(this).apply web@{
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
                userAgentString = DIAGNOSTIC_USER_AGENT
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@web, false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) { this@SourceDiagnosticBrowserActivity.progress.progress = newProgress }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    record("CONSOLE", "${message.messageLevel()}@${message.lineNumber()}", sanitize(message.message(), 800))
                    return true
                }
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    record("WARN", "POPUP_BLOCKED", "isUserGesture=$isUserGesture")
                    return false
                }
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean =
                    handleDialog("ALERT", url, message, null, result, null)

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean =
                    handleDialog("CONFIRM", url, message, null, result, null)

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                    handleDialog("PROMPT", url, message, defaultValue, null, result)
            }
            webViewClient = diagnosticClient()
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
        logView = TextView(this).apply { setTextIsSelectable(true); setPadding(16, 10, 16, 16); textSize = 11f }
        root.addView(ScrollView(this).apply { addView(logView, matchWrap()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        setContentView(root)
        seedWebViewCookies()
        record("INFO", "DIAGNOSTIC_STARTED", "source=$sourceId hosts=${allowedHosts.sorted().joinToString()}")
        navigate(initialUrl)
    }

    private fun diagnosticClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (!isAllowed(target) && (strictOrigins || !isHttps(target))) {
                record("SECURITY", "NAVIGATION_BLOCKED", redactUrl(target))
                setStatus("Đã chặn điều hướng theo chính sách miền hiện tại.")
                return true
            }
            if (!isAllowed(target)) record("WARN", "EXTERNAL_NAVIGATION_ALLOWED", redactUrl(target))
            record("NAV", "NAVIGATION", "${request.method} ${redactUrl(target)}")
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            urlField.setText(url)
            record("PAGE", "START", redactUrl(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            urlField.setText(url)
            captureSession()
            record("PAGE", "FINISH", "${redactUrl(url)} title=${sanitize(view.title.orEmpty(), 200)}")
            setStatus("Trang đã tải. Có $requestCount request metadata trong phiên chẩn đoán.")
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            requestCount++
            val target = request.url.toString()
            val redacted = redactUrl(target)
            val external = !isAllowed(target)
            val blocked = external && (blockExternalResources || !isHttps(target))
            val detail = "${request.method} $redacted main=${request.isForMainFrame} external=$external blocked=$blocked headers=${request.requestHeaders.keys.sorted().joinToString()}"
            recordRequest(request, redacted, blocked)
            if (blocked) {
                record("SECURITY", "RESOURCE_BLOCKED", detail)
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), byteArrayOf().inputStream())
            }
            if (external) record("WARN", "EXTERNAL_RESOURCE_ALLOWED", detail)
            else if (verbose || request.isForMainFrame) record("REQUEST", resourceType(target), detail)
            return null
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            record("ERROR", "WEB_ERROR", "code=${error.errorCode} main=${request.isForMainFrame} url=${redactUrl(request.url.toString())} desc=${sanitize(error.description.toString(), 300)}")
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
        if ((!isAllowed(target) && strictOrigins) || !isHttps(target)) {
            setStatus(if (strictOrigins) "URL phải dùng HTTPS và thuộc allowlist của nguồn." else "URL phải dùng HTTPS.")
            record("SECURITY", "URL_REJECTED", redactUrl(target))
            return
        }
        record("NAV", "LOAD_URL", redactUrl(target))
        webView.loadUrl(target)
    }

    private fun runJavaScriptProbe() = evaluateProbe(
        "JS_PROBE",
        "JSON.stringify({href:location.href,title:document.title,readyState:document.readyState,links:document.links.length,forms:document.forms.length,localStorageKeys:Object.keys(localStorage).length})",
    )

    private fun runDomProbe() = evaluateProbe(
        "DOM_PROBE",
        "JSON.stringify({title:document.title,textLength:(document.body&&document.body.innerText||'').length,htmlLength:(document.documentElement&&document.documentElement.outerHTML||'').length,links:document.links.length,forms:document.forms.length,scripts:document.scripts.length,iframes:document.querySelectorAll('iframe').length})",
    )

    private fun evaluateProbe(name: String, script: String) {
        webView.evaluateJavascript(script) { raw -> record("PROBE", name, sanitize(decodeJs(raw), 1_500)) }
    }

    private fun runStorageProbe() = evaluateProbe(
        "STORAGE_PROBE",
        "(function(){function s(x){var n=0;for(var i=0;i<x.length;i++){var k=x.key(i);n+=(k||'').length+(x.getItem(k)||'').length;}return {count:x.length,characters:n};}return JSON.stringify({local:s(localStorage),session:s(sessionStorage),indexedDb:!!window.indexedDB,serviceWorker:!!navigator.serviceWorker});})()",
    )

    private fun runCookieProbe() {
        val url = webView.url ?: initialUrl
        val header = CookieManager.getInstance().getCookie(url).orEmpty()
        val names = CookieHeaderCodec.cookieNames(header).distinct().sorted()
        record("PROBE", "COOKIE", "host=${Uri.parse(url).host.orEmpty()} count=${names.size} names=${names.joinToString()} storedSession=${sessionStore.hasSession(sourceId)}")
    }

    private fun summarizeRequests() {
        val requestEntries = entries.filter { it.level == "REQUEST" || it.category.startsWith("HTTP_") }
        val hosts = requestEntries.mapNotNull { Regex("https://([^/\\s?]+)").find(it.detail)?.groupValues?.getOrNull(1) }.groupingBy { it }.eachCount()
        record("PROBE", "REQUEST_SUMMARY", "total=$requestCount logged=${requestEntries.size} hosts=${hosts.entries.sortedByDescending { it.value }.joinToString { "${it.key}:${it.value}" }}")
    }

    private fun cycleUserAgent() {
        userAgentMode = (userAgentMode + 1) % 3
        val ua = when (userAgentMode) {
            1 -> DESKTOP_USER_AGENT
            2 -> WebSettings.getDefaultUserAgent(this)
            else -> DIAGNOSTIC_USER_AGENT
        }
        webView.settings.userAgentString = ua.take(512)
        record("POLICY", "USER_AGENT", when (userAgentMode) { 1 -> "DESKTOP"; 2 -> "SYSTEM"; else -> "DIAGNOSTIC" })
        webView.reload()
    }

    private fun clearSourceCookies() {
        val manager = CookieManager.getInstance()
        allowedHosts.forEach { host ->
            val url = "https://$host/"
            CookieHeaderCodec.cookieNames(manager.getCookie(url).orEmpty()).forEach { name ->
                manager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
            }
        }
        manager.flush()
        sessionStore.clear(sourceId)
        record("SESSION", "COOKIES_CLEARED", "source=$sourceId")
    }

    private fun handleDialog(
        type: String,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsResult?,
        promptResult: JsPromptResult?,
    ): Boolean {
        record("DIALOG", type, "url=${redactUrl(url.orEmpty())} message=${sanitize(message.orEmpty(), 500)} defaultLength=${defaultValue.orEmpty().length} policy=${dialogPolicy.name}")
        when (dialogPolicy) {
            DialogPolicy.ACCEPT -> if (promptResult != null) promptResult.confirm(defaultValue.orEmpty()) else result?.confirm()
            DialogPolicy.CANCEL -> if (promptResult != null) promptResult.cancel() else result?.cancel()
            DialogPolicy.DEFAULT_VALUE -> if (promptResult != null) promptResult.confirm(defaultValue.orEmpty()) else result?.confirm()
        }
        return true
    }

    private fun recordRequest(request: WebResourceRequest, redactedUrl: String, blocked: Boolean) {
        if (requests.size >= MAX_REQUESTS) requests.removeFirst()
        requests.add(
            DiagnosticRequest(
                timestamp = System.currentTimeMillis(),
                method = request.method.take(16),
                url = redactedUrl,
                host = request.url.host.orEmpty().lowercase().take(255),
                mainFrame = request.isForMainFrame,
                resourceType = resourceType(request.url.toString()),
                headerNames = request.requestHeaders.keys.map(String::lowercase).distinct().sorted().take(64),
                blocked = blocked,
            ),
        )
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

    private fun record(level: String, category: String, detail: String) {
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.add(DiagnosticEntry(System.currentTimeMillis(), level, category, sanitize(detail, 2_000)))
        renderLog()
    }

    private fun renderLog() {
        if (!::logView.isInitialized) return
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        logView.text = entries.joinToString("\n") { "${formatter.format(Date(it.timestamp))} ${it.level}/${it.category} ${it.detail}" }
    }

    private fun copyLog() {
        val text = entries.joinToString("\n") { "${it.timestamp} ${it.level}/${it.category} ${it.detail}" }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Source diagnostics", text))
        setStatus("Đã sao chép nhật ký đã khử dữ liệu nhạy cảm.")
    }

    private fun exportJson(): String = JSONObject().apply {
        put("schema", 2)
        put("sourceId", sourceId)
        put("allowedHosts", JSONArray(allowedHosts.sorted()))
        put("verbose", verbose)
        put("requestCount", requestCount)
        put("originPolicy", if (strictOrigins) "SOURCE_ONLY" else "COMPATIBLE_HTTPS")
        put("resourcePolicy", if (blockExternalResources) "BLOCK_EXTERNAL" else "OBSERVE_EXTERNAL")
        put("dialogPolicy", dialogPolicy.name)
        put("userAgentMode", userAgentMode)
        put("exportedAtEpochMs", System.currentTimeMillis())
        put("requests", JSONArray(requests.map { item -> JSONObject().apply {
            put("timestampEpochMs", item.timestamp)
            put("method", item.method)
            put("url", item.url)
            put("host", item.host)
            put("mainFrame", item.mainFrame)
            put("resourceType", item.resourceType)
            put("headerNames", JSONArray(item.headerNames))
            put("blocked", item.blocked)
        } }))
        put("events", JSONArray(entries.map { entry -> JSONObject().apply {
            put("timestampEpochMs", entry.timestamp)
            put("level", entry.level)
            put("category", entry.category)
            put("detail", entry.detail)
        } }))
    }.toString(2)

    private fun setStatus(value: String) { status.text = value }

    private fun isHttps(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null
    }

    private fun isAllowed(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    private fun redactUrl(value: String): String = runCatching {
        val uri = Uri.parse(value)
        uri.buildUpon().clearQuery().fragment(null).build().toString()
    }.getOrDefault(value.substringBefore('?').substringBefore('#')).take(1_000)

    private fun resourceType(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "SCRIPT"
        "css" -> "STYLE"
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico" -> "IMAGE"
        "woff", "woff2", "ttf", "otf" -> "FONT"
        "json" -> "JSON"
        "mp3", "m4a", "mp4", "webm" -> "MEDIA"
        else -> "RESOURCE"
    }

    private fun decodeJs(raw: String?): String = raw?.let { value ->
        runCatching { org.json.JSONTokener(value).nextValue()?.toString().orEmpty() }.getOrDefault(value)
    }.orEmpty()

    private fun sanitize(raw: String, max: Int): String = raw
        .replace(Regex("(?i)(authorization|cookie|set-cookie|password|passwd|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "$1=<redacted>")
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .take(max)

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun row(vararg views: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }
    private fun matchWrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onPause() { captureSession(); super.onPause() }
    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeAllViews(); webView.destroy()
        }
        super.onDestroy()
    }

    data class DiagnosticEntry(val timestamp: Long, val level: String, val category: String, val detail: String)
    data class DiagnosticRequest(
        val timestamp: Long,
        val method: String,
        val url: String,
        val host: String,
        val mainFrame: Boolean,
        val resourceType: String,
        val headerNames: List<String>,
        val blocked: Boolean,
    )

    private enum class DialogPolicy {
        CANCEL,
        ACCEPT,
        DEFAULT_VALUE;

        fun next(): DialogPolicy = entries[(ordinal + 1) % entries.size]
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_INITIAL_URL = "initial_url"
        const val EXTRA_ALLOWED_HOSTS = "allowed_hosts"
        private const val MAX_ENTRIES = 1_000
        private const val MAX_REQUESTS = 2_000
        private const val DIAGNOSTIC_USER_AGENT = "NgheTruyen-SourceDiagnostic/2.5 Android"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
