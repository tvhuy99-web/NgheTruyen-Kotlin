package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.diagnostics.DiagnosticEvent

internal fun latestDiagnosticScreenKey(events: List<DiagnosticEvent>): String =
    events.lastOrNull()?.attributes?.get("screen").orEmpty()

/**
 * Gives a transient Activity its own diagnostics screen and restores the parent logical screen when
 * the Activity closes. This keeps screen-scoped diagnostics truly screen-local even when navigation
 * temporarily leaves the Compose hierarchy (source login, diagnostic browser, etc.).
 */
internal class DiagnosticTransientScreenScope private constructor(
    private val diagnostics: SourceDiagnosticRuntime,
    private val parentScreenKey: String,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        if (parentScreenKey.isNotBlank()) diagnostics.onScreenChanged(parentScreenKey)
    }

    companion object {
        fun enter(diagnostics: SourceDiagnosticRuntime, screenKey: String): DiagnosticTransientScreenScope {
            // Every runtime event is stamped with the active screen. Read it before switching because
            // onScreenChanged() intentionally clears the old screen in screen-scoped mode.
            val parent = latestDiagnosticScreenKey(diagnostics.recorder.snapshot())
            diagnostics.onScreenChanged(screenKey)
            return DiagnosticTransientScreenScope(diagnostics, parent)
        }
    }
}
