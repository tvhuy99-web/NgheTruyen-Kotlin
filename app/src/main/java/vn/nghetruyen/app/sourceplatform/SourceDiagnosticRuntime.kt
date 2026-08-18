package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.nghetruyen.app.BuildConfig
import vn.nghetruyen.app.startup.StartupWorkGate
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticEvidenceRecorder
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticRecorder
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticJsonExporter
import vn.nghetruyen.source.diagnostics.DiagnosticLevel
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticRedactor
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.diagnostics.SourceSnapshotSanitizer
import vn.nghetruyen.source.diagnostics.SourceTraceExplorer
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticActiveOperation(
    val operationId: String,
    val traceId: String,
    val sourceId: String,
    val category: DiagnosticCategory,
    val kind: String,
    val flow: String,
    val stage: String,
    val timeoutMs: Long?,
    val deadlineEpochMs: Long?,
    val startedAtEpochMs: Long,
    val lastEventAtEpochMs: Long,
    val startEvent: String,
    val lastEvent: String,
    val screenGeneration: Long?,
)











class SourceDiagnosticRuntime(private val context: Context) {
    private val prefs = context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE)
    private val activityTracker = DiagnosticActivityTracker()
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val criticalStoreDelegate = lazy { CriticalDiagnosticStore(context) }
    private val criticalStore by criticalStoreDelegate
    private val continuousStoreDelegate = lazy { ContinuousDiagnosticStore(context) }
    private val continuousStore by continuousStoreDelegate
    private val changeVersion = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changeVersion.asStateFlow()

    @Volatile var mode: String = MODE_OFF
        private set

    private fun signalChanged() {
        changeVersion.update { version -> if (version == Long.MAX_VALUE) 1L else version + 1L }
    }

    private val criticalMirror = DiagnosticSink { event ->
        if (!PersistentCriticalDiagnosticPolicy.shouldPersist(event)) return@DiagnosticSink
        criticalStore.emit(event)
        signalChanged()
    }
    private val mirror = DiagnosticSink { event ->
        activityTracker.emit(event)
        if (mode == MODE_CONTINUOUS) continuousStoreForWrite().emit(event)
        signalChanged()
    }
    private val evidenceMirror = object : DiagnosticEvidenceSink {
        override val enabled: Boolean get() = mode == MODE_CONTINUOUS
        override fun capture(evidence: DiagnosticEvidence) {
            if (mode == MODE_CONTINUOUS) continuousStoreForWrite().capture(evidence)
            signalChanged()
        }
    }

    val recorder = BoundedDiagnosticRecorder(
        maxEvents = MAX_IN_MEMORY_EVENTS,
        level = DiagnosticLevel.OFF,
        mirror = mirror,
        alwaysMirror = criticalMirror,
    )
    val evidence = BoundedDiagnosticEvidenceRecorder(
        maxBytes = 64L * 1024L * 1024L,
        maxItems = 2_048,
        maxItemBytes = 16 * 1024 * 1024,
        mirror = evidenceMirror,
    )

    @Volatile private var activeScreenKey: String = ""
    @Volatile private var activeScreenSessionId: String = ""


    val advanced: Boolean get() = mode != MODE_OFF
    val crashSafe: Boolean get() = mode == MODE_CONTINUOUS

    init {
        val restoredMode = normalizeMode(prefs.getString(KEY_MODE, MODE_OFF).orEmpty())
        mode = restoredMode
        applyMode(restoredMode)
        if (StartupWorkGate.isBeforeFirstFrame()) {
            diskScope.launch {
                if (StartupWorkGate.awaitFirstFrame()) hydratePersistentState()
            }
        } else {
            hydratePersistentState()
        }
    }

    private fun hydratePersistentState() {
        criticalStore
        if (mode == MODE_CONTINUOUS) {
            recorder.restore(continuousStoreForWrite().restoreRecentEvents(MAX_IN_MEMORY_EVENTS))
            restoreCriticalHistory()
        }
        signalChanged()
    }

    private fun continuousStoreForWrite(): ContinuousDiagnosticStore = continuousStore.apply {
        enabled = mode == MODE_CONTINUOUS
    }

    fun setMode(requested: String): String {
        val normalized = normalizeMode(requested)
        val previous = mode
        if (normalized == previous) return normalized


        if (previous == MODE_CONTINUOUS) {
            mark(
                name = "DIAGNOSTICS_MODE_CHANGED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf("from" to previous, "to" to normalized),
            )
        }

        val currentEvents = recorder.snapshot()
        mode = normalized
        prefs.edit().putString(KEY_MODE, normalized).apply()

        when (normalized) {
            MODE_CONTINUOUS -> {
                // append-only
                continuousStore.seedMissing(currentEvents)
                clearCurrentSession()
                activeScreenSessionId = UUID.randomUUID().toString()
                applyMode(normalized)
                recorder.restore(continuousStore.restoreRecentEvents(MAX_IN_MEMORY_EVENTS))
                restoreCriticalHistory()
                mark(
                    name = "DIAGNOSTICS_MODE_CHANGED",
                    category = DiagnosticCategory.RUNTIME,
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf("from" to previous, "to" to normalized),
                )
            }
            MODE_SCREEN -> {
                clearCurrentSession()
                activeScreenKey = ""
                activeScreenSessionId = ""
                applyMode(normalized)
                mark(
                    name = "DIAGNOSTICS_MODE_CHANGED",
                    category = DiagnosticCategory.RUNTIME,
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf("from" to previous, "to" to normalized),
                )
            }
            else -> {
                clearCurrentSession()
                activeScreenKey = ""
                activeScreenSessionId = ""
                applyMode(MODE_OFF)
                signalChanged()
            }
        }
        return normalized
    }







    fun onScreenChanged(screenKey: String, handoffTraceIds: Set<String> = emptySet()): Boolean {
        val next = screenKey.trim().take(500).ifBlank { "unknown" }
        if (next == activeScreenKey) return false
        val previous = activeScreenKey
        activeScreenKey = next
        activeScreenSessionId = UUID.randomUUID().toString()
        return when (mode) {
            MODE_SCREEN -> {
                val selectedHandoff = handoffTraceIds.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .take(64)
                    .toSet()
                val handoffAttributes = mapOf(
                    "screen" to next,
                    "screenSessionId" to activeScreenSessionId,
                )
                recorder.rotateScreen(selectedHandoff, handoffAttributes)
                evidence.retainTraces(
                    traceIds = selectedHandoff,
                    targetGeneration = recorder.currentScreenGeneration(),
                    handoffAttributes = handoffAttributes,
                )
                val recorderStats = recorder.stats()
                val evidenceStats = evidence.stats()
                mark(
                    name = "DIAGNOSTIC_SCREEN_STARTED",
                    category = DiagnosticCategory.RUNTIME,
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf(
                        "screen" to next,
                        "previousScreen" to previous,
                        "screenGeneration" to recorder.currentScreenGeneration().toString(),
                        "screenIsolation" to if (selectedHandoff.isEmpty()) "strict-origin" else "selective-causal-handoff",
                        "handoffTraceCount" to selectedHandoff.size.toString(),
                        "handoffEventCount" to recorderStats.screenHandoffEventsRetained.toString(),
                        "handoffEvidenceCount" to evidenceStats.screenHandoffItemsRetained.toString(),
                        "rotationDiscardedEventCount" to recorderStats.screenRotationEventsDiscarded.toString(),
                        "rotationDiscardedEvidenceCount" to evidenceStats.screenRotationItemsDiscarded.toString(),
                        "carriedActiveTraceCount" to "0",
                    ),
                )
                true
            }
            MODE_CONTINUOUS -> {
                mark(
                    name = "DIAGNOSTIC_SCREEN_CHANGED",
                    category = DiagnosticCategory.RUNTIME,
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf("screen" to next, "previousScreen" to previous),
                )
                true
            }
            else -> false
        }
    }

    fun mark(
        name: String,
        category: DiagnosticCategory = DiagnosticCategory.RUNTIME,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        sourceId: String = "app",
        traceId: String = "",
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (mode == MODE_OFF) return
        recorder.emit(
            DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId.ifBlank { "app:${UUID.randomUUID()}" },
                sourceId = sourceId.ifBlank { "app" },
                category = category,
                name = name.take(160),
                severity = severity,
                durationMs = durationMs,
                attributes = attributes + mapOf(
                    "diagnosticsMode" to mode,
                    "screen" to activeScreenKey,
                    "screenSessionId" to activeScreenSessionId,
                ),
            ),
        )
    }

    fun activitySnapshot(): List<DiagnosticActiveOperation> =
        if (mode == MODE_SCREEN) activityTracker.snapshot(recorder.currentScreenGeneration())
        else activityTracker.snapshot()

    fun activityLines(nowMs: Long = System.currentTimeMillis()): List<String> = activitySnapshot().map { operation ->
        val elapsed = (nowMs - operation.startedAtEpochMs).coerceAtLeast(0L)
        val deadline = operation.deadlineEpochMs?.let { deadlineAt ->
            val remaining = deadlineAt - nowMs
            if (remaining >= 0L) " • còn=${remaining}ms" else " • ĐÃ QUÁ HẠN ${-remaining}ms"
        }.orEmpty()
        "${operation.sourceId} • ${operation.flow}/${operation.kind} • ${operation.stage} • ${elapsed}ms$deadline • op=${operation.operationId.take(36)}"
    }

    fun persistentCriticalCount(): Int = criticalStore.eventCount

    fun cachedPersistentCriticalCount(): Int =
        if (criticalStoreDelegate.isInitialized()) criticalStore.eventCount else 0

    fun sessionEventCount(): Int = recorder.stats().itemCount





    fun persistentCriticalSnapshot(): List<DiagnosticEvent> =
        if (mode == MODE_OFF) criticalStore.snapshot() else emptyList()


    fun clearBlackBox() {
        clearCurrentSession()
        continuousStore.clear()
        criticalStore.clear()
        if (mode == MODE_CONTINUOUS) continuousStore.enabled = true
        signalChanged()
    }

    fun exportBundle(
        events: List<DiagnosticEvent>,
        installed: List<SourcePackUiInfo>,
        repositories: List<SourceRepositoryUiInfo>,
        runtimeState: Map<String, String> = emptyMap(),
        backupLogTail: String = "",
    ): ByteArray {
        val safeEvents = events.sortedBy(DiagnosticEvent::timestampEpochMs)
        val recorderStats = recorder.stats()
        val evidenceStats = evidence.stats()
        val deepReport = DiagnosticDeepBlackBox.analyze(
            events = safeEvents,
            nowMs = System.currentTimeMillis(),
            recorderStats = recorderStats,
            evidenceStats = evidenceStats,
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.addText("README.txt", readme())
            zip.addText("report/log.txt", DiagnosticHumanFormatter.formatEvents(safeEvents, mode))
            zip.addBytes("report/events.json", DiagnosticJsonExporter.export(safeEvents))
            zip.addText("report/environment.json", environment(safeEvents).toString(2))
            zip.addText("report/installed_sources.json", installedJson(installed).toString(2))
            zip.addText("report/repositories.json", repositoriesJson(repositories).toString(2))
            zip.addText("report/app_runtime.json", JSONObject(DiagnosticRedactor.redact(runtimeState)).toString(2))
            zip.addText("report/active_operations.json", activeOperationsJson().toString(2))
            zip.addText("report/traces.txt", traceReport(safeEvents))
            zip.addText("report/operations.json", deepReport.operationsJson)
            zip.addText("report/flows.json", deepReport.flowsJson)
            zip.addText("report/browser_sessions.json", deepReport.browserSessionsJson)
            zip.addText("report/data_loss.json", JSONObject(deepReport.dataLossJson).apply {
                put("persistentDroppedEventSegments", continuousStore.droppedEventSegments)
                put("persistentRejectedEvidence", continuousStore.rejectedEvidenceCount)
                put("staleScreenEventsDropped", recorderStats.staleScreenEventsDropped)
                put("staleScreenEvidenceDropped", evidenceStats.staleScreenItemsDropped)
                put("screenRotationEventsDiscarded", recorderStats.screenRotationEventsDiscarded)
                put("screenHandoffEventsRetained", recorderStats.screenHandoffEventsRetained)
                put("screenRotationEvidenceDiscarded", evidenceStats.screenRotationItemsDiscarded)
                put("screenHandoffEvidenceRetained", evidenceStats.screenHandoffItemsRetained)
                put("screenIsolationDropsIntentional", true)
                if (
                    recorderStats.staleScreenEventsDropped > 0 || evidenceStats.staleScreenItemsDropped > 0 ||
                    recorderStats.screenRotationEventsDiscarded > 0 || evidenceStats.screenRotationItemsDiscarded > 0
                ) {
                    put("lossVisible", true)
                    put("lossReason", "screen-isolation-discard")
                }
            }.toString(2))
            zip.addText("report/persistent_install_failures.json", DiagnosticJsonExporter.export(criticalStore.snapshot()).toString(Charsets.UTF_8))
            deepReport.flowLogs.forEach { (flow, log) ->
                zip.addText("flows/${safePath(flow)}.log", log)
            }
            if (activeScreenKey.isNotBlank()) zip.addText("report/current_screen.txt", activeScreenKey)
            if (activeScreenSessionId.isNotBlank()) zip.addText("report/current_screen_session.txt", activeScreenSessionId)
            if (mode == MODE_SCREEN) zip.addText("report/current_screen_generation.txt", recorder.currentScreenGeneration().toString())
            if (backupLogTail.isNotBlank()) {
                zip.addText("report/backup_tail.log", DiagnosticRedactor.redactLongText(backupLogTail, 64_000))
            }

            evidence.snapshot().forEachIndexed { index, item ->
                val base = "evidence/current/${index.toString().padStart(4, '0')}-${safePath(item.name)}"
                zip.addBytes(base, safeEvidenceBytes(item))
                zip.addText("$base.meta.json", evidenceMeta(item).toString(2))
                if (item.contentType.contains("html", ignoreCase = true)) {
                    val text = item.data.toString(Charsets.UTF_8)
                    zip.addText(
                        "evidence/sanitized/${index.toString().padStart(4, '0')}-${safePath(item.name)}",
                        SourceSnapshotSanitizer.sanitizeHtml(text, 8 * 1024 * 1024),
                    )
                }
            }

            if (mode == MODE_CONTINUOUS) {
                continuousStore.filesForExport().forEach { (name, file) ->
                    zip.addFile("continuous/$name", file)
                }
                zip.addText("continuous/status.json", continuousStore.statusJson().toString(2))
            }
        }
        return output.toByteArray()
    }

    private fun clearCurrentSession() {
        recorder.clear()
        evidence.clear()
        activityTracker.clear()
    }

    private fun restoreCriticalHistory() {
        val existing = recorder.snapshot().map(::eventIdentity).toHashSet()
        recorder.restore(criticalStore.snapshot().filter { eventIdentity(it) !in existing })
    }

    private fun eventIdentity(event: DiagnosticEvent): String = listOf(
        event.timestampEpochMs.toString(), event.traceId, event.sourceId, event.name,
    ).joinToString("|")

    private fun applyMode(value: String) {
        recorder.level = when (value) {
            MODE_SCREEN, MODE_CONTINUOUS -> DiagnosticLevel.VERBOSE
            else -> DiagnosticLevel.OFF
        }
        evidence.enabled = value == MODE_SCREEN || value == MODE_CONTINUOUS
        if (continuousStoreDelegate.isInitialized()) {
            continuousStore.enabled = value == MODE_CONTINUOUS
        }
        if (value == MODE_OFF) activityTracker.clear()
    }

    private fun normalizeMode(value: String): String = when (value) {
        MODE_OFF -> MODE_OFF
        MODE_SCREEN, "screen", "advanced_ram" -> MODE_SCREEN
        MODE_CONTINUOUS, "continuous", "advanced_crash" -> MODE_CONTINUOUS
        else -> MODE_OFF
    }

    private fun environment(events: List<DiagnosticEvent>): JSONObject = JSONObject().apply {
        put("generatedAt", Instant.now().toString())
        put("diagnosticMode", mode)
        put("screenScoped", mode == MODE_SCREEN)
        put("screenIsolation", if (mode == MODE_SCREEN) "strict-origin" else "continuous-or-off")
        put("screenGeneration", recorder.currentScreenGeneration())
        put("continuous", mode == MODE_CONTINUOUS)
        put("activeScreen", activeScreenKey)
        put("activeScreenSessionId", activeScreenSessionId)
        put("appVersionName", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE)
        put("buildType", BuildConfig.BUILD_TYPE)
        put("debugBuild", BuildConfig.DEBUG)
        put("diagnosticBuildId", BuildConfig.DIAGNOSTIC_BUILD_ID)
        put("symbolMappingIdentity", "${BuildConfig.VERSION_CODE}:${BuildConfig.DIAGNOSTIC_BUILD_ID}")
        put("androidSdk", Build.VERSION.SDK_INT)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("eventCount", events.size)
        put("activeOperationCount", activitySnapshot().size)
        val eventStats = recorder.stats()
        val stats = evidence.stats()
        put("ramEventItems", eventStats.itemCount)
        put("ramEventEvicted", eventStats.evictedEvents)
        put("staleScreenEventsDropped", eventStats.staleScreenEventsDropped)
        put("ramEvidenceItems", stats.itemCount)
        put("ramEvidenceBytes", stats.retainedBytes)
        put("ramEvidenceEvictedItems", stats.evictedItems)
        put("ramEvidenceTruncatedItems", stats.truncatedItems)
        put("staleScreenEvidenceDropped", stats.staleScreenItemsDropped)
        put("continuousEventCount", continuousStore.eventCount)
        put("continuousEvidenceBytes", continuousStore.evidenceBytes)
        put("continuousEvidenceRejected", continuousStore.rejectedEvidenceCount)
        put("persistentInstallFailureCount", criticalStore.eventCount)
    }

    private fun activeOperationsJson(): JSONArray = JSONArray().apply {
        activitySnapshot().forEach { operation ->
            put(JSONObject().apply {
                put("traceId", operation.traceId)
                put("operationId", operation.operationId)
                put("sourceId", operation.sourceId)
                put("category", operation.category.name)
                put("kind", operation.kind)
                put("flow", operation.flow)
                put("stage", operation.stage)
                put("timeoutMs", operation.timeoutMs ?: JSONObject.NULL)
                put("deadlineEpochMs", operation.deadlineEpochMs ?: JSONObject.NULL)
                put("startedAtEpochMs", operation.startedAtEpochMs)
                put("lastEventAtEpochMs", operation.lastEventAtEpochMs)
                put("startEvent", operation.startEvent)
                put("lastEvent", operation.lastEvent)
                put("screenGeneration", operation.screenGeneration ?: JSONObject.NULL)
            })
        }
    }

    private fun installedJson(items: List<SourcePackUiInfo>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("version", item.version)
                put("enabled", item.enabled)
                put("ecosystem", item.ecosystem)
                put("runtimeMode", item.runtimeMode)
                put("contentType", item.contentType)
                put("compatibilityProfile", item.compatibilityProfile)
            })
        }
    }

    private fun repositoriesJson(items: List<SourceRepositoryUiInfo>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("url", DiagnosticRedactor.redactLongText(item.url, 4_096))
                put("packageCount", item.packageCount)
            })
        }
    }

    private fun traceReport(events: List<DiagnosticEvent>): String = SourceTraceExplorer.summarize(events)
        .joinToString("\n") { trace ->
            listOf(
                trace.traceId,
                trace.sourceId,
                "events=${trace.eventCount}",
                "errors=${trace.errorCount}",
                "durationMs=${trace.durationMs}",
                "categories=${trace.categories.joinToString(",")}",
                "final=${trace.finalEvent}",
            ).joinToString(" | ")
        }

    private fun evidenceMeta(item: DiagnosticEvidence): JSONObject = JSONObject().apply {
        put("timestampEpochMs", item.timestampEpochMs)
        put("traceId", item.traceId)
        put("sourceId", item.sourceId)
        put("category", item.category.name)
        put("name", item.name)
        put("contentType", item.contentType)
        put("bytes", item.data.size)
        put("attributes", JSONObject(DiagnosticRedactor.redact(item.attributes)))
    }

    private fun safeEvidenceBytes(item: DiagnosticEvidence): ByteArray = when {
        item.contentType.contains("html", ignoreCase = true) -> DiagnosticRedactor.redactHtmlPreservingStructure(
            item.data.toString(Charsets.UTF_8),
            8 * 1024 * 1024,
        ).toByteArray(Charsets.UTF_8)
        item.contentType.startsWith("text/", true) || item.contentType.contains("json", true) -> DiagnosticRedactor.redactLongText(
            item.data.toString(Charsets.UTF_8),
            8 * 1024 * 1024,
        ).toByteArray(Charsets.UTF_8)
        else -> item.data
    }

    private fun readme(): String = buildString {
        appendLine("NgheTruyen diagnostic package")
        appendLine("Mode: ${when (mode) { MODE_SCREEN -> "screen-scoped"; MODE_CONTINUOUS -> "continuous"; else -> "off" }}")
        appendLine("Normal UI is intentionally a single readable log. This ZIP keeps the detailed structured evidence used for diagnosis.")
        appendLine()
        appendLine("Modes:")
        appendLine("- off: records no session timeline/evidence; only the last 100 install/import failures are retained so package errors are never lost.")
        appendLine("- screen-scoped: strict provenance with selective causal handoff. A screen change starts a new immutable generation; only the trace that directly produced the destination screen may be handed off. Unrelated previous-screen data is discarded, late unrelated callbacks are dropped, and both are counted explicitly.")
        appendLine("- continuous: verbose events persist across screens and process restarts in a bounded two-segment history until explicitly cleared.")
        appendLine()
        appendLine("Contents:")
        appendLine("- report/log.txt: Lua-style readable timeline with cause and suggestion for failures.")
        appendLine("- report/events.json + report/traces.txt: structured events and trace summaries. Every retained event includes diagnosticScreenGeneration and diagnosticScreenDisposition.")
        appendLine("- report/operations.json: explicit operations, deadlines, stages, polling and current/terminal state; legacy events use compatibility reconstruction.")
        appendLine("- report/flows.json + flows/*.log: latest state and raw timeline split by Lua-style diagnostic flow.")
        appendLine("- report/browser_sessions.json: browser counters, safe capability probes, late callbacks and last error state.")
        appendLine("- report/data_loss.json: RAM eviction/truncation plus intentional stale-screen event/evidence drop counters so missing evidence is never silent.")
        appendLine("- report/persistent_install_failures.json: the last 100 install/import failures, retained even when diagnostics were off.")
        appendLine("- report/current_screen_session.txt + report/current_screen_generation.txt: opaque identifiers for the current screen-scoped diagnostic generation.")
        appendLine("- evidence/current/: evidence that belongs only to the current screen generation.")
        appendLine("- evidence/sanitized/: sanitized HTML snapshots for inspection.")
        appendLine("- continuous/: bounded persisted event history and evidence when continuous mode has been used.")
        appendLine("- environment/runtime/source/repository snapshots for reproduction context.")
        appendLine()
        appendLine("Sensitive credential-like fields, headers and values are redacted before storage/export where supported.")
        appendLine("Persistent binary evidence is never evicted; after ${ContinuousDiagnosticStore.MAX_PERSISTED_EVIDENCE_BYTES / (1024 * 1024)} MiB new evidence is rejected and counted. Event JSONL uses two 16 MiB segments and reports dropped rotations.")
    }

    private fun safePath(value: String): String = value
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("_") { part -> part.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100) }
        .ifBlank { "evidence.bin" }

    private fun ZipOutputStream.addText(name: String, value: String) = addBytes(name, value.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.addBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.addFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(this) }
        closeEntry()
    }

    companion object {
        private const val KEY_MODE = "diagnostics.mode"
        private const val MAX_IN_MEMORY_EVENTS = 20_000
        const val MODE_OFF = "off"
        const val MODE_SCREEN = "basic"
        const val MODE_CONTINUOUS = "advanced"
        @Deprecated("Use MODE_SCREEN") const val MODE_ADVANCED_RAM = MODE_SCREEN
        @Deprecated("Use MODE_CONTINUOUS") const val MODE_ADVANCED_CRASH = MODE_CONTINUOUS
    }
}

internal class DiagnosticActivityTracker : DiagnosticSink {
    private val lock = Any()
    private val active = linkedMapOf<String, DiagnosticActiveOperation>()

    override fun emit(event: DiagnosticEvent) = synchronized(lock) {
        val traceId = event.traceId.trim()
        if (traceId.isBlank()) return@synchronized
        val name = event.name.uppercase()
        val operationId = DiagnosticOperationContract.id(event) ?: traceId
        val state = DiagnosticOperationContract.state(event)
        if (state == null && isDiagnosticBoundary(name)) return@synchronized
        val current = active[operationId]
        when {
            (state == DiagnosticOperationState.STARTED || state == null && isStart(name)) -> {
                active[operationId] = DiagnosticActiveOperation(
                    operationId = operationId,
                    traceId = traceId,
                    sourceId = event.sourceId,
                    category = event.category,
                    kind = event.attributes[DiagnosticOperationContract.KIND] ?: operationStem(name),
                    flow = event.attributes[DiagnosticOperationContract.FLOW] ?: event.attributes["flow"] ?: event.category.name.lowercase(),
                    stage = event.attributes[DiagnosticOperationContract.STAGE] ?: event.name,
                    timeoutMs = event.attributes[DiagnosticOperationContract.TIMEOUT_MS]?.toLongOrNull(),
                    deadlineEpochMs = event.attributes[DiagnosticOperationContract.DEADLINE_EPOCH_MS]?.toLongOrNull(),
                    startedAtEpochMs = event.timestampEpochMs,
                    lastEventAtEpochMs = event.timestampEpochMs,
                    startEvent = event.name,
                    lastEvent = event.name,
                    screenGeneration = event.attributes["diagnosticScreenGeneration"]?.toLongOrNull(),
                )
                while (active.size > 100) active.remove(active.entries.first().key)
            }
            state in TERMINAL_STATES -> active.remove(operationId)
            state == null && isTerminal(name) && current != null && operationStem(name) == operationStem(current.startEvent.uppercase()) -> active.remove(operationId)
            current != null -> active[operationId] = current.copy(
                sourceId = event.sourceId.takeIf { it.isNotBlank() && it != "manual-import" } ?: current.sourceId,
                lastEventAtEpochMs = event.timestampEpochMs,
                lastEvent = event.name,
                kind = event.attributes[DiagnosticOperationContract.KIND] ?: current.kind,
                flow = event.attributes[DiagnosticOperationContract.FLOW] ?: event.attributes["flow"] ?: current.flow,
                stage = event.attributes[DiagnosticOperationContract.STAGE] ?: event.attributes["stage"] ?: event.name,
                timeoutMs = event.attributes[DiagnosticOperationContract.TIMEOUT_MS]?.toLongOrNull() ?: current.timeoutMs,
                deadlineEpochMs = event.attributes[DiagnosticOperationContract.DEADLINE_EPOCH_MS]?.toLongOrNull() ?: current.deadlineEpochMs,
            )
        }
    }

    fun snapshot(screenGeneration: Long? = null): List<DiagnosticActiveOperation> = synchronized(lock) {
        active.values
            .asSequence()
            .filter { screenGeneration == null || it.screenGeneration == screenGeneration }
            .sortedByDescending(DiagnosticActiveOperation::lastEventAtEpochMs)
            .toList()
    }

    fun clear() = synchronized(lock) { active.clear() }

    private fun isStart(name: String): Boolean = name.endsWith("_START") || name.endsWith("_STARTED")
    private fun isDiagnosticBoundary(name: String): Boolean =
        name.startsWith("DIAGNOSTIC_SCREEN_") || name.startsWith("DIAGNOSTICS_MODE_")
    private fun isTerminal(name: String): Boolean = TERMINAL_SUFFIXES.any(name::endsWith)
    private fun operationStem(name: String): String {
        if (name.endsWith("_VERIFIED")) return name.removeSuffix("_VERIFIED") + "_VERIFY"
        return (START_SUFFIXES + TERMINAL_SUFFIXES).firstOrNull(name::endsWith)?.let(name::removeSuffix) ?: name
    }

    companion object {
        private val START_SUFFIXES = listOf("_STARTED", "_START")
        private val TERMINAL_SUFFIXES = listOf(
            "_COMPLETED", "_FAILED", "_ERROR", "_DONE", "_CANCELLED", "_STOPPED", "_TIMEOUT",
            "_VERIFIED", "_SUCCEEDED", "_SUCCESS", "_FINISHED", "_OK",
        )
        private val TERMINAL_STATES = setOf(
            DiagnosticOperationState.COMPLETED,
            DiagnosticOperationState.FAILED,
            DiagnosticOperationState.CANCELLED,
            DiagnosticOperationState.TIMEOUT,
        )
    }
}

internal object PersistentCriticalDiagnosticPolicy {
    fun shouldPersist(event: DiagnosticEvent): Boolean {
        if (isObsolete(event)) return false
        if (event.severity !in setOf(DiagnosticSeverity.ERROR, DiagnosticSeverity.WARN)) return false
        val name = event.name.uppercase()
        return event.category in setOf(
            DiagnosticCategory.PACKAGE,
            DiagnosticCategory.TRUST,
            DiagnosticCategory.STORE,
            DiagnosticCategory.SECURITY,
        ) || listOf("INSTALL", "IMPORT", "PACKAGE", "REPOSITORY").any(name::contains)
    }

    fun isObsolete(event: DiagnosticEvent): Boolean =
        event.name == "BUILTIN_SOURCEPACK_BOOTSTRAP_FAILED" &&
            event.sourceId == "builtin:demo.ntsource"
}

private class CriticalDiagnosticStore(context: Context) : DiagnosticSink {
    private val lock = Any()
    private val root = File(context.filesDir, "source-diagnostics-critical")
    private val eventFile = File(root, "install-failures.jsonl")

    @Volatile var eventCount: Int = 0
        private set

    init {
        root.mkdirs()
        purgeObsoleteEvents()
        eventCount = snapshot().size
    }

    override fun emit(event: DiagnosticEvent) {
        if (!PersistentCriticalDiagnosticPolicy.shouldPersist(event)) return
        synchronized(lock) {
            root.mkdirs()
            eventFile.appendText(DiagnosticJsonExporter.eventLine(event) + "\n")
            eventCount += 1
            if (eventCount > MAX_EVENTS) compact()
        }
    }

    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) {
        if (!eventFile.isFile) return@synchronized emptyList()
        eventFile.useLines { lines -> lines.mapNotNull(::parseDiagnosticEventLine).toList().takeLast(MAX_EVENTS) }
    }

    fun clear() = synchronized(lock) {
        runCatching { eventFile.delete() }
        eventCount = 0
    }

    private fun compact() {
        val retained = eventFile.useLines { lines -> lines.toList().takeLast(MAX_EVENTS) }
        rewrite(retained)
    }

    private fun purgeObsoleteEvents() = synchronized(lock) {
        if (!eventFile.isFile) return@synchronized
        val existing = eventFile.useLines { it.toList() }
        val retained = existing.filterNot { line ->
            parseDiagnosticEventLine(line)?.let(PersistentCriticalDiagnosticPolicy::isObsolete) == true
        }
        if (retained.size != existing.size) rewrite(retained.takeLast(MAX_EVENTS))
    }

    private fun rewrite(retained: List<String>) {
        val temporary = File(root, "install-failures.tmp")
        temporary.writeText(retained.joinToString(separator = "\n", postfix = if (retained.isEmpty()) "" else "\n"))
        if (eventFile.exists() && !eventFile.delete()) return
        if (!temporary.renameTo(eventFile)) {
            eventFile.writeText(retained.joinToString(separator = "\n", postfix = if (retained.isEmpty()) "" else "\n"))
            temporary.delete()
        }
        eventCount = retained.size
    }

    companion object {
        private const val MAX_EVENTS = 100
    }
}

private class ContinuousDiagnosticStore(private val context: Context) : DiagnosticSink, DiagnosticEvidenceSink {
    private val lock = Any()
    private val root = File(context.filesDir, "source-diagnostics-continuous")
    private val eventFile = File(root, "events.jsonl")
    private val previousEventFile = File(root, "events.previous.jsonl")
    private val evidenceRoot = File(root, "evidence")

    @Volatile override var enabled: Boolean = false
    @Volatile var eventCount: Long = 0
        private set
    @Volatile var evidenceBytes: Long = 0
        private set
    @Volatile var rejectedEvidenceCount: Long = 0
        private set
    @Volatile var droppedEventSegments: Long = 0
        private set
    @Volatile private var lastEventEpochMs: Long = 0

    init {
        root.mkdirs()
        evidenceRoot.mkdirs()
        rebuildStats()
    }

    override fun emit(event: DiagnosticEvent) {
        if (!enabled) return
        appendEvent(event)
    }

    override fun capture(evidence: DiagnosticEvidence) {
        if (!enabled) return
        val payload = redactEvidenceForDisk(evidence)
        synchronized(lock) {
            if (evidenceBytes + payload.size > MAX_PERSISTED_EVIDENCE_BYTES) {
                rejectedEvidenceCount += 1
                return
            }
            evidenceRoot.mkdirs()
            val base = evidence.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "evidence.bin" }
            val stem = "${evidence.timestampEpochMs}-${UUID.randomUUID().toString().take(8)}-$base"
            File(evidenceRoot, stem).writeBytes(payload)
            File(evidenceRoot, "$stem.meta.json").writeText(JSONObject().apply {
                put("timestampEpochMs", evidence.timestampEpochMs)
                put("traceId", evidence.traceId)
                put("sourceId", evidence.sourceId)
                put("category", evidence.category.name)
                put("name", evidence.name)
                put("contentType", evidence.contentType)
                put("bytes", payload.size)
                put("attributes", JSONObject(DiagnosticRedactor.redact(evidence.attributes)))
            }.toString(2))
            evidenceBytes += payload.size
        }
    }

    fun seedMissing(events: List<DiagnosticEvent>) {
        val threshold = lastEventEpochMs
        events.sortedBy(DiagnosticEvent::timestampEpochMs)
            .filter { it.timestampEpochMs > threshold }
            .forEach(::appendEvent)
    }

    fun restoreRecentEvents(limit: Int): List<DiagnosticEvent> = synchronized(lock) {
        if ((!eventFile.isFile && !previousEventFile.isFile) || limit <= 0) return@synchronized emptyList()
        val ring = ArrayDeque<DiagnosticEvent>(limit)
        listOf(previousEventFile, eventFile).filter(File::isFile).forEach { file ->
            file.useLines { lines ->
                lines.forEach { line ->
                    parseDiagnosticEventLine(line)?.let { event ->
                        while (ring.size >= limit) ring.removeFirst()
                        ring.addLast(event)
                    }
                }
            }
        }
        ring.toList()
    }

    fun filesForExport(): List<Pair<String, File>> = synchronized(lock) {
        if (!root.exists()) return@synchronized emptyList()
        root.walkTopDown()
            .filter(File::isFile)
            .map { file -> file.relativeTo(root).invariantSeparatorsPath to file }
            .toList()
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("appendOnly", false)
        put("boundedRotation", true)
        put("eventSegmentLimitBytes", MAX_EVENT_SEGMENT_BYTES)
        put("droppedEventSegments", droppedEventSegments)
        put("eventCount", eventCount)
        put("lastEventEpochMs", lastEventEpochMs)
        put("evidenceBytes", evidenceBytes)
        put("evidenceLimitBytes", MAX_PERSISTED_EVIDENCE_BYTES)
        put("rejectedEvidenceCount", rejectedEvidenceCount)
    }

    fun clear() = synchronized(lock) {
        root.deleteRecursively()
        root.mkdirs()
        evidenceRoot.mkdirs()
        eventCount = 0
        evidenceBytes = 0
        rejectedEvidenceCount = 0
        droppedEventSegments = 0
        lastEventEpochMs = 0
    }

    private fun appendEvent(event: DiagnosticEvent) = synchronized(lock) {
        root.mkdirs()
        val line = DiagnosticJsonExporter.eventLine(event) + "\n"
        val lineBytes = line.toByteArray(Charsets.UTF_8).size
        if (eventFile.isFile && eventFile.length() + lineBytes > MAX_EVENT_SEGMENT_BYTES) rotateEvents()
        eventFile.appendText(line)
        eventCount += 1
        lastEventEpochMs = maxOf(lastEventEpochMs, event.timestampEpochMs)
    }

    private fun rebuildStats() = synchronized(lock) {
        eventCount = 0
        lastEventEpochMs = 0
        listOf(previousEventFile, eventFile).filter(File::isFile).forEach { file ->
            file.useLines { lines ->
                lines.forEach { line ->
                    eventCount += 1
                    parseDiagnosticEventLine(line)?.let {
                        lastEventEpochMs = maxOf(lastEventEpochMs, it.timestampEpochMs)
                    }
                }
            }
        }
        evidenceBytes = evidenceRoot.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".meta.json") }
            .sumOf(File::length)
    }

    private fun rotateEvents() {
        if (previousEventFile.isFile) {
            val removed = previousEventFile.useLines { it.count().toLong() }
            if (previousEventFile.delete()) {
                eventCount = (eventCount - removed).coerceAtLeast(0L)
                droppedEventSegments += 1
            }
        }
        if (eventFile.isFile && !eventFile.renameTo(previousEventFile)) {
            val retained = eventFile.readBytes()
            previousEventFile.writeBytes(retained)
            eventFile.delete()
        }
    }

    private fun redactEvidenceForDisk(evidence: DiagnosticEvidence): ByteArray = when {
        evidence.contentType.contains("html", ignoreCase = true) -> DiagnosticRedactor.redactHtmlPreservingStructure(
            evidence.data.toString(Charsets.UTF_8),
            8 * 1024 * 1024,
        ).toByteArray(Charsets.UTF_8)
        evidence.contentType.startsWith("text/", ignoreCase = true) || evidence.contentType.contains("json", ignoreCase = true) -> DiagnosticRedactor.redactLongText(
            evidence.data.toString(Charsets.UTF_8),
            8 * 1024 * 1024,
        ).toByteArray(Charsets.UTF_8)
        else -> evidence.data.copyOf()
    }

    companion object {
        private const val MAX_EVENT_SEGMENT_BYTES = 16L * 1024L * 1024L
        const val MAX_PERSISTED_EVIDENCE_BYTES = 512L * 1024L * 1024L
    }
}

private fun parseDiagnosticEventLine(line: String): DiagnosticEvent? = runCatching {
    val json = JSONObject(line)
    val attributes = buildMap<String, String> {
        json.optJSONObject("attributes")?.let { raw ->
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, raw.optString(key))
            }
        }
    }
    DiagnosticEvent(
        timestampEpochMs = json.optLong("timestampEpochMs"),
        traceId = json.optString("traceId"),
        sourceId = json.optString("sourceId"),
        sourceVersion = json.optString("sourceVersion").takeIf { it.isNotBlank() && it != "null" },
        category = DiagnosticCategory.valueOf(json.optString("category")),
        name = json.optString("name"),
        severity = DiagnosticSeverity.valueOf(json.optString("severity")),
        durationMs = if (json.has("durationMs") && !json.isNull("durationMs")) json.optLong("durationMs") else null,
        attributes = attributes,
    )
}.getOrNull()
