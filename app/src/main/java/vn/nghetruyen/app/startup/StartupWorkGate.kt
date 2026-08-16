package vn.nghetruyen.app.startup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver

/** Keeps optional startup work off the critical path until the first Activity is ready to draw. */
object StartupWorkGate {
    @Volatile
    private var firstFrameReached = false

    fun isBeforeFirstFrame(): Boolean = !firstFrameReached

    fun install(application: Application) {
        if (firstFrameReached) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (firstFrameReached) return
                val observer = activity.window.decorView.viewTreeObserver
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        firstFrameReached = true
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
}
