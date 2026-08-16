package vn.nghetruyen.app.sourceplatform

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

/**
 * Captures the information that ordinary in-process source diagnostics cannot see when Android
 * terminates the app. The next launch records Android's historical exit reason plus any uncaught
 * exception envelope persisted immediately before the default crash handler runs.
 */
object ProcessExitDiagnostics {
    private const val PREFS = "process_exit_diagnostics"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val KEY_CRASH_TIMESTAMP = "crash_timestamp"
    private const val KEY_CRASH_THREAD = "crash_thread"
    private const val KEY_CRASH_TYPE = "crash_type"
    private const val KEY_CRASH_MESSAGE = "crash_message"
    private const val KEY_CRASH_STACK = "crash_stack"
    private const val MAX_STACK_CHARS = 16_000
    private val handlingCrash = AtomicBoolean(false)

    fun install(
        application: Application,
        diagnosticsProvider: () -> SourceDiagnosticRuntime,
    ) {
        recordPreviousExit(application, diagnosticsProvider)
        recordPersistedCrash(application, diagnosticsProvider)
        installUncaughtExceptionRecorder(application, diagnosticsProvider)
    }

    private fun recordPreviousExit(
        application: Application,
        diagnosticsProvider: () -> SourceDiagnosticRuntime,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val diagnostics = runCatching(diagnosticsProvider).getOrNull() ?: return
        if (diagnostics.mode == SourceDiagnosticRuntime.MODE_OFF) return
        val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRecorded = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(application.packageName, 0, 5)
                .asSequence()
                .filter { it.timestamp > lastRecorded }
                .maxByOrNull(ApplicationExitInfo::getTimestamp)
        }.getOrNull() ?: return

        diagnostics.mark(
            name = "PREVIOUS_PROCESS_EXIT",
            category = DiagnosticCategory.RUNTIME,
            severity = severityFor(exit.reason),
            sourceId = "app",
            attributes = mapOf(
                "reason" to exit.reason.toString(),
                "reasonName" to reasonName(exit.reason),
                "status" to exit.status.toString(),
                "importance" to exit.importance.toString(),
                "timestampEpochMs" to exit.timestamp.toString(),
                "pssKb" to exit.pss.toString(),
                "rssKb" to exit.rss.toString(),
                "pid" to exit.pid.toString(),
                "processName" to exit.processName.orEmpty().take(300),
                "description" to exit.description.orEmpty().take(2_000),
            ),
        )
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, exit.timestamp).apply()
    }

    private fun recordPersistedCrash(
        application: Application,
        diagnosticsProvider: () -> SourceDiagnosticRuntime,
    ) {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_CRASH_TIMESTAMP, 0L)
        if (timestamp <= 0L) return
        val diagnostics = runCatching(diagnosticsProvider).getOrNull() ?: return
        if (diagnostics.mode == SourceDiagnosticRuntime.MODE_OFF) return
        diagnostics.mark(
            name = "PREVIOUS_UNCAUGHT_EXCEPTION",
            category = DiagnosticCategory.RUNTIME,
            severity = DiagnosticSeverity.ERROR,
            sourceId = "app",
            attributes = mapOf(
                "timestampEpochMs" to timestamp.toString(),
                "thread" to prefs.getString(KEY_CRASH_THREAD, "").orEmpty().take(300),
                "errorType" to prefs.getString(KEY_CRASH_TYPE, "").orEmpty().take(500),
                "error" to prefs.getString(KEY_CRASH_MESSAGE, "").orEmpty().take(2_000),
                "stackTrace" to prefs.getString(KEY_CRASH_STACK, "").orEmpty().take(MAX_STACK_CHARS),
            ),
        )
        prefs.edit()
            .remove(KEY_CRASH_TIMESTAMP)
            .remove(KEY_CRASH_THREAD)
            .remove(KEY_CRASH_TYPE)
            .remove(KEY_CRASH_MESSAGE)
            .remove(KEY_CRASH_STACK)
            .apply()
    }

    private fun installUncaughtExceptionRecorder(
        application: Application,
        diagnosticsProvider: () -> SourceDiagnosticRuntime,
    ) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handlingCrash.compareAndSet(false, true)) {
                val timestamp = System.currentTimeMillis()
                val stack = runCatching { error.stackTraceToString() }
                    .getOrElse { "${error.javaClass.name}: ${error.message.orEmpty()}" }
                    .take(MAX_STACK_CHARS)
                application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_CRASH_TIMESTAMP, timestamp)
                    .putString(KEY_CRASH_THREAD, thread.name.take(300))
                    .putString(KEY_CRASH_TYPE, error.javaClass.name.take(500))
                    .putString(KEY_CRASH_MESSAGE, error.message.orEmpty().take(2_000))
                    .putString(KEY_CRASH_STACK, stack)
                    .commit()
                runCatching {
                    diagnosticsProvider().mark(
                        name = "APP_UNCAUGHT_EXCEPTION",
                        category = DiagnosticCategory.RUNTIME,
                        severity = DiagnosticSeverity.ERROR,
                        sourceId = "app",
                        attributes = mapOf(
                            "timestampEpochMs" to timestamp.toString(),
                            "thread" to thread.name.take(300),
                            "errorType" to error.javaClass.name.take(500),
                            "error" to error.message.orEmpty().take(2_000),
                            "stackTrace" to stack,
                        ),
                    )
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun severityFor(reason: Int): DiagnosticSeverity = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> DiagnosticSeverity.ERROR

        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> DiagnosticSeverity.WARN

        else -> DiagnosticSeverity.INFO
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }
}
