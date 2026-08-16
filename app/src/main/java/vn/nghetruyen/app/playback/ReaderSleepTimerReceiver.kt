package vn.nghetruyen.app.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

 
object ReaderSleepTimerStore {
    private const val PREFS = "reader_sleep_timer"
    private const val KEY_DEADLINE = "deadline_millis"

    fun get(context: Context): Long? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_DEADLINE, 0L)
        .takeIf { it > 0L }

    fun set(context: Context, deadlineMillis: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (deadlineMillis == null) remove(KEY_DEADLINE) else putLong(KEY_DEADLINE, deadlineMillis)
            }
            .apply()
    }
}

object ReaderSleepTimerAlarm {
    private const val REQUEST_CODE = 7405

    fun schedule(context: Context, deadlineMillis: Long?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        alarm.cancel(pending)
        if (deadlineMillis == null) return
        val triggerAt = deadlineMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        if (Build.VERSION.SDK_INT >= 23) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            @Suppress("DEPRECATION")
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun restore(context: Context) {
        val deadline = ReaderSleepTimerStore.get(context)
        if (SleepTimerPolicy.hasExpired(deadline, System.currentTimeMillis())) {
            startExpiryService(context)
        } else {
            schedule(context, deadline)
        }
    }

    fun clear(context: Context) {
        ReaderSleepTimerStore.set(context, null)
        schedule(context, null)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReaderSleepTimerReceiver::class.java).setAction(ACTION_EXPIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun startExpiryService(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ReaderPlaybackService::class.java)
                .setAction(ReaderPlaybackService.ACTION_SLEEP_TIMER_EXPIRED),
        )
    }

    const val ACTION_EXPIRE = "vn.nghetruyen.action.SLEEP_TIMER_EXPIRED_ALARM"
}

class ReaderSleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ReaderSleepTimerAlarm.ACTION_EXPIRE -> ReaderSleepTimerAlarm.startExpiryService(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> ReaderSleepTimerAlarm.restore(context)
        }
    }
}
