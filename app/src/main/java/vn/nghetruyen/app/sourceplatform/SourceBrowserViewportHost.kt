package vn.nghetruyen.app.sourceplatform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap










internal object SourceBrowserViewportHost : Application.ActivityLifecycleCallbacks {
    private var initialized = false
    private var resumedActivity = WeakReference<Activity>(null)
    private val attachments = Collections.newSetFromMap(IdentityHashMap<Attachment, Boolean>())

    @Synchronized
    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        application.registerActivityLifecycleCallbacks(this)
    }

    @Synchronized
    fun attach(webView: WebView): Attachment {
        check(webView.parent == null) { "SOURCE_BROWSER_WEBVIEW_ALREADY_ATTACHED" }
        val metrics = webView.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(MIN_VIEWPORT_WIDTH_PX)
        val height = metrics.heightPixels.coerceAtLeast(MIN_VIEWPORT_HEIGHT_PX)
        val activity = resumedActivity.get()?.takeUnless(Activity::isFinishing)
        val root = activity?.window?.decorView as? ViewGroup
        val host = FrameLayout(activity ?: webView.context).apply {
            alpha = 0.01f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            translationX = -(width * 2f)
        }
        host.addView(webView, FrameLayout.LayoutParams(width, height))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        host.measure(widthSpec, heightSpec)
        host.layout(0, 0, width, height)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, width, height)
        val attachment = Attachment(host, width, height)
        attachments += attachment
        if (root != null && root.isAttachedToWindow) attachToRoot(attachment, root)
        return attachment
    }

    @Synchronized
    fun detach(webView: WebView, attachment: Attachment?) {
        val host = attachment?.host ?: (webView.parent as? ViewGroup)
        (host?.parent as? ViewGroup)?.removeView(host)
        host?.removeView(webView)
        attachment?.let(attachments::remove)
        attachment?.attachedToWindow = false
    }

    @Synchronized
    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (!root.isAttachedToWindow) return
        attachments.toList().forEach { attachToRoot(it, root) }
    }

    @Synchronized
    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
    }

    @Synchronized
    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
        val root = activity.window?.decorView as? ViewGroup
        attachments.forEach { attachment ->
            if (attachment.host.parent === root) {
                root?.removeView(attachment.host)
                attachment.attachedToWindow = false
            }
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    class Attachment internal constructor(
        internal val host: ViewGroup,
        val widthPx: Int,
        val heightPx: Int,
    ) {
        @Volatile var attachedToWindow: Boolean = false
            internal set
    }

    private fun attachToRoot(attachment: Attachment, root: ViewGroup) {
        val currentParent = attachment.host.parent as? ViewGroup
        if (currentParent !== root) {
            currentParent?.removeView(attachment.host)
            root.addView(attachment.host, ViewGroup.LayoutParams(attachment.widthPx, attachment.heightPx))
        }
        attachment.attachedToWindow = attachment.host.isAttachedToWindow
    }

    private const val MIN_VIEWPORT_WIDTH_PX = 320
    private const val MIN_VIEWPORT_HEIGHT_PX = 480
}
