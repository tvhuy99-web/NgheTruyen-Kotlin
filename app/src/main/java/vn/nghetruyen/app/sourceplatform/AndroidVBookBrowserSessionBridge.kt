package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android WebView side of the vBook browser/HTTP shared session. */
internal class AndroidVBookBrowserSessionBridge(
    context: Context,
    private val cookieReader: SourceWebViewCookieReader?,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun userAgent(): String = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("")

    fun readCookies(sourceId: String, requestUrl: String): String? =
        cookieReader?.readWebViewCookieHeader(sourceId, requestUrl)

    fun writeCookies(sourceId: String, responseUrl: String, setCookies: List<String>) {
        if (sourceId.isBlank() || !responseUrl.startsWith("https://", ignoreCase = true) || setCookies.isEmpty()) return
        val write = {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            setCookies.take(128).filter(String::isNotBlank).forEach { cookie ->
                manager.setCookie(responseUrl, cookie)
            }
            manager.flush()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(write)
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                runCatching(write)
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
    }
}
