package vn.nghetruyen.app.startup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Keeps optional UI startup work off the critical path until the first Activity is ready to draw. */
object StartupWorkGate {
    @Volatile
    private var firstActivityStartupActive = false

    @Volatile
    private var firstFrameReached = false

    private val firstFrameLatch = CountDownLatch(1)

    /** Arm the gate only for a real UI launch. Background-only processes remain unrestricted. */
    fun beginFirstActivityStartup() {
        if (!firstFrameReached) firstActivityStartupActive = true
    }

    fun isBeforeFirstFrame(): Boolean = firstActivityStartupActive && !firstFrameReached

    /** For background startup tasks only; never call this from the main thread. */
    fun awaitFirstFrame(timeoutMillis: Long = FIRST_FRAME_WAIT_TIMEOUT_MILLIS): Boolean {
        if (isBeforeFirstFrame()) firstFrameLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return !isBeforeFirstFrame()
    }

    fun install(application: Application) {
        if (firstFrameReached) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!isBeforeFirstFrame()) return
                val observer = activity.window.decorView.viewTreeObserver
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        firstFrameReached = true
                        firstActivityStartupActive = false
                        firstFrameLatch.countDown()
                        if (observer.isAlive) observer.removeOnPreDrawListener(this)
                        return true
                    }
                }
                observer.addOnPreDrawListener(listener)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private const val FIRST_FRAME_WAIT_TIMEOUT_MILLIS = 5_000L
}
