package vn.nghetruyen.app.startup

import android.app.Activity
import android.view.ViewTreeObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    fun beginFirstActivityStartup(activity: Activity) {
        if (firstFrameReached || firstActivityStartupActive) return
        firstActivityStartupActive = true
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

    fun isBeforeFirstFrame(): Boolean = firstActivityStartupActive && !firstFrameReached

    /** For background startup tasks only; never call this from the main thread. */
    fun awaitFirstFrame(timeoutMillis: Long = FIRST_FRAME_WAIT_TIMEOUT_MILLIS): Boolean {
        if (isBeforeFirstFrame()) firstFrameLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return !isBeforeFirstFrame()
    }

    /** Suspend callers without blocking the main thread, then allow their optional work to continue. */
    suspend fun awaitFirstFrameAsync(timeoutMillis: Long = FIRST_FRAME_WAIT_TIMEOUT_MILLIS): Boolean =
        if (!isBeforeFirstFrame()) true else withContext(Dispatchers.IO) { awaitFirstFrame(timeoutMillis) }

    private const val FIRST_FRAME_WAIT_TIMEOUT_MILLIS = 5_000L
}
