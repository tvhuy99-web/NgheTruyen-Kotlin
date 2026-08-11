package vn.nghetruyen.app.sourceplatform

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Canonical WebView configuration for installed extensions.
 *
 * The goal is browser parity, not a second permission system. JavaScript, DOM storage, mixed HTTP
 * content, third-party cookies, scripted windows and media are browser features available to every
 * installed extension. The containment boundary stays at Android: file/content access and Java
 * object bridges are intentionally outside this policy.
 */
object ExtensionWebViewAuthority {
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(context: Context, webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = WebSettings.getDefaultUserAgent(context)
            cacheMode = WebSettings.LOAD_DEFAULT
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }
}
