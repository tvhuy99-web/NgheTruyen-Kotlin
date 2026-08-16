package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.diagnostics.DiagnosticEvent

internal fun latestDiagnosticScreenKey(events: List<DiagnosticEvent>): String =
    events.lastOrNull()?.attributes?.get("screen").orEmpty()






internal object DiagnosticScreenRestoreCoordinator {
    private data class PendingRestore(
        val diagnostics: SourceDiagnosticRuntime,
        val screenKey: String,
    )

    private val lock = Any()
    private val pending = ArrayDeque<PendingRestore>()

    fun defer(diagnostics: SourceDiagnosticRuntime, screenKey: String) {
        if (screenKey.isBlank()) return
        synchronized(lock) {
            pending.addLast(PendingRestore(diagnostics, screenKey))
        }
    }

    fun restoreNext(): Boolean {
        val restore = synchronized(lock) {
            if (pending.isEmpty()) null else pending.removeFirst()
        } ?: return false
        return restore.diagnostics.onScreenChanged(restore.screenKey)
    }
}






internal class DiagnosticTransientScreenScope private constructor(
    private val diagnostics: SourceDiagnosticRuntime,
    private val parentScreenKey: String,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        DiagnosticScreenRestoreCoordinator.defer(diagnostics, parentScreenKey)
    }

    companion object {
        fun enter(diagnostics: SourceDiagnosticRuntime, screenKey: String): DiagnosticTransientScreenScope {


            val parent = latestDiagnosticScreenKey(diagnostics.recorder.snapshot())
            diagnostics.onScreenChanged(screenKey)
            return DiagnosticTransientScreenScope(diagnostics, parent)
        }
    }
}
