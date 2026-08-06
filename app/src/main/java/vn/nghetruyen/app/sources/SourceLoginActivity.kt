package vn.nghetruyen.app.sources

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import vn.nghetruyen.app.NgheTruyenApplication

class SourceLoginActivity : ComponentActivity() {
    private lateinit var sourceId: String
    private lateinit var loginUrl: String
    private lateinit var allowedHosts: Set<String>
    private lateinit var sessionStore: SourceSessionStore
    private lateinit var webView: WebView
    private lateinit var status: TextView

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
        sessionStore = (application as NgheTruyenApplication).container.sourceSessionStore

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Đăng nhập trực tiếp trên trang nguồn. Ứng dụng chỉ lưu cookie phiên đã mã hóa, không đọc hoặc lưu mật khẩu."
            setPadding(24, 18, 24, 18)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val done = Button(this).apply {
            text = "LƯU PHIÊN VÀ ĐÓNG"
            setOnClickListener {
                captureSession()
                setResult(RESULT_OK)
                finish()
            }
        }
        val clear = Button(this).apply {
            text = "XÓA PHIÊN"
            setOnClickListener {
                clearSessionCookies()
                status.text = "Đã xóa phiên của nguồn này."
            }
        }
        actions.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

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
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (!isAllowed(target)) {
                        status.text = "Đã chặn điều hướng ra ngoài miền của nguồn."
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (isAllowed(url)) captureSession()
                    status.text = if (sessionStore.hasSession(sourceId)) {
                        "Đã nhận cookie phiên. Bạn có thể đóng màn hình và thử mở lại chương."
                    } else {
                        "Trang đã tải. Hãy đăng nhập nếu nguồn yêu cầu."
                    }
                }
            }
        }
        root.addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(actions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        seedWebViewCookies()
        webView.loadUrl(loginUrl)
    }

    override fun onPause() {
        captureSession()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
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
