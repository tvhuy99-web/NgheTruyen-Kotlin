#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, value: str) -> None:
    (ROOT / path).write_text(value, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    value = read(path)
    if new in value:
        return
    if old not in value:
        raise SystemExit(f"missing migration anchor in {path}: {old[:120]!r}")
    write(path, value.replace(old, new, 1))


# 1) Recorder keeps fatal breadcrumbs even while the visible diagnostics mode is OFF.
path = "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt"
replace_once(
    path,
    """data class DiagnosticEvent(\n    val timestampEpochMs: Long,\n    val traceId: String,\n    val sourceId: String,\n    val sourceVersion: String? = null,\n    val category: DiagnosticCategory,\n    val name: String,\n    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,\n    val durationMs: Long? = null,\n    val attributes: Map<String, String> = emptyMap(),\n)\n\nfun interface DiagnosticSink""",
    """data class DiagnosticEvent(\n    val timestampEpochMs: Long,\n    val traceId: String,\n    val sourceId: String,\n    val sourceVersion: String? = null,\n    val category: DiagnosticCategory,\n    val name: String,\n    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,\n    val durationMs: Long? = null,\n    val attributes: Map<String, String> = emptyMap(),\n)\n\n/**\n * Tiny always-on breadcrumb policy. Diagnostics OFF still hides the UI and suppresses normal\n * telemetry, but fatal errors and install/trust/security warnings survive so a user can enable\n * diagnostics after a failed extension install and still have something actionable to inspect.\n */\nfun DiagnosticEvent.shouldRetainWhenDiagnosticsOff(): Boolean =\n    severity == DiagnosticSeverity.ERROR ||\n        (severity == DiagnosticSeverity.WARN && category in setOf(\n            DiagnosticCategory.PACKAGE,\n            DiagnosticCategory.TRUST,\n            DiagnosticCategory.STORE,\n            DiagnosticCategory.SECURITY,\n        ))\n\nfun interface DiagnosticSink""",
)
replace_once(
    path,
    """    override fun emit(event: DiagnosticEvent) {\n        if (level == DiagnosticLevel.OFF) return\n        if (level == DiagnosticLevel.BASIC && event.severity == DiagnosticSeverity.DEBUG) return\n        val safe = event.copy(attributes = DiagnosticRedactor.redact(event.attributes))\n        lock.withLock {\n            while (events.size >= maxEvents) events.removeFirst()\n            events.addLast(safe)\n        }\n        runCatching { mirror.emit(safe) }\n    }\n\n    fun snapshot""",
    """    override fun emit(event: DiagnosticEvent) {\n        val critical = event.shouldRetainWhenDiagnosticsOff()\n        if (level == DiagnosticLevel.OFF && !critical) return\n        if (level == DiagnosticLevel.BASIC && event.severity == DiagnosticSeverity.DEBUG && !critical) return\n        val safe = event.copy(attributes = DiagnosticRedactor.redact(event.attributes))\n        lock.withLock {\n            while (events.size >= maxEvents) events.removeFirst()\n            events.addLast(safe)\n        }\n        runCatching { mirror.emit(safe) }\n    }\n\n    /** Restore already-redacted persisted breadcrumbs without mirroring them back to disk. */\n    fun restore(restored: List<DiagnosticEvent>) = lock.withLock {\n        restored.takeLast(maxEvents).forEach { event ->\n            val safe = event.copy(attributes = DiagnosticRedactor.redact(event.attributes))\n            while (events.size >= maxEvents) events.removeFirst()\n            events.addLast(safe)\n        }\n    }\n\n    fun snapshot""",
)

# 2) Replace the app runtime with a dual Advanced profile + active-operation tracker + persistent critical ring.
runtime_path = ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt"
runtime_path.write_text(r'''package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.BuildConfig
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticEvidenceRecorder
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticRecorder
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticJsonExporter
import vn.nghetruyen.source.diagnostics.DiagnosticLevel
import vn.nghetruyen.source.diagnostics.DiagnosticRedactor
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.diagnostics.SourceSnapshotSanitizer
import vn.nghetruyen.source.diagnostics.SourceTraceExplorer
import vn.nghetruyen.source.diagnostics.shouldRetainWhenDiagnosticsOff
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticActiveOperation(
    val traceId: String,
    val sourceId: String,
    val category: DiagnosticCategory,
    val startedAtEpochMs: Long,
    val lastEventAtEpochMs: Long,
    val startEvent: String,
    val lastEvent: String,
)

class SourceDiagnosticRuntime(private val context: Context) {
    private val prefs = context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE)
    private val activityTracker = DiagnosticActivityTracker()
    private val crashStore = CrashSafeDiagnosticStore(context)
    private val mirror = DiagnosticSink { event ->
        activityTracker.emit(event)
        crashStore.emit(event)
    }

    val recorder = BoundedDiagnosticRecorder(
        maxEvents = 20_000,
        level = DiagnosticLevel.OFF,
        mirror = mirror,
    )
    val evidence = BoundedDiagnosticEvidenceRecorder(
        maxBytes = 64L * 1024L * 1024L,
        maxItems = 2_048,
        maxItemBytes = 16 * 1024 * 1024,
        mirror = crashStore,
    )

    @Volatile var mode: String = "off"
        private set

    val advanced: Boolean get() = mode == MODE_ADVANCED_RAM || mode == MODE_ADVANCED_CRASH
    val crashSafe: Boolean get() = mode == MODE_ADVANCED_CRASH

    init {
        applyMode(prefs.getString(KEY_MODE, "off").orEmpty(), rotateCrashSafe = true)
        recorder.restore(crashStore.restoreCriticalEvents())
    }

    fun setMode(requested: String): String {
        val normalized = normalizeMode(requested)
        val previous = mode
        val enteringCrashSafe = normalized == MODE_ADVANCED_CRASH && previous != MODE_ADVANCED_CRASH
        if (normalized == "off" && previous != "off") {
            mark(
                name = "DIAGNOSTICS_MODE_CHANGED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf("from" to previous, "to" to normalized),
            )
        }
        mode = normalized
        prefs.edit().putString(KEY_MODE, normalized).apply()
        applyMode(normalized, rotateCrashSafe = enteringCrashSafe)
        if (normalized != "off") {
            mark(
                name = "DIAGNOSTICS_MODE_CHANGED",
                category = DiagnosticCategory.RUNTIME,
                severity = DiagnosticSeverity.INFO,
                attributes = mapOf("from" to previous, "to" to normalized),
            )
        }
        return normalized
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
        val event = DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            traceId = traceId.ifBlank { "app:${UUID.randomUUID()}" },
            sourceId = sourceId.ifBlank { "app" },
            category = category,
            name = name.take(160),
            severity = severity,
            durationMs = durationMs,
            attributes = attributes + ("diagnosticsMode" to mode),
        )
        if (mode == "off" && !event.shouldRetainWhenDiagnosticsOff()) return
        recorder.emit(event)
    }

    fun activitySnapshot(): List<DiagnosticActiveOperation> = activityTracker.snapshot()

    fun activityLines(nowMs: Long = System.currentTimeMillis()): List<String> = activitySnapshot().map { operation ->
        val elapsed = (nowMs - operation.startedAtEpochMs).coerceAtLeast(0L)
        "${operation.sourceId} • ${operation.category}/${operation.lastEvent} • ${elapsed}ms • trace=${operation.traceId.take(28)}"
    }

    fun persistentCriticalCount(): Int = crashStore.persistentCriticalCount()

    fun clearBlackBox() {
        recorder.clear()
        evidence.clear()
        activityTracker.clear()
        crashStore.clear()
    }

    fun exportBundle(
        events: List<DiagnosticEvent>,
        installed: List<SourcePackUiInfo>,
        repositories: List<SourceRepositoryUiInfo>,
        runtimeState: Map<String, String> = emptyMap(),
        backupLogTail: String = "",
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.addText("README.txt", readme())
            zip.addBytes("report/events.json", DiagnosticJsonExporter.export(events))
            zip.addText("report/environment.json", environment(events).toString(2))
            zip.addText("report/installed_sources.json", installedJson(installed).toString(2))
            zip.addText("report/repositories.json", repositoriesJson(repositories).toString(2))
            zip.addText("report/app_runtime.json", JSONObject(DiagnosticRedactor.redact(runtimeState)).toString(2))
            zip.addText("report/active_operations.json", activeOperationsJson().toString(2))
            if (backupLogTail.isNotBlank()) {
                zip.addText("report/backup_tail.log", DiagnosticRedactor.redactLongText(backupLogTail, 64_000))
            }
            zip.addText("report/traces.txt", traceReport(events))

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

            crashStore.snapshotForExport().forEach { (name, bytes) ->
                zip.addBytes("crash-safe/$name", safeStoredBytes(name, bytes))
            }
        }
        return output.toByteArray()
    }

    private fun applyMode(raw: String, rotateCrashSafe: Boolean) {
        val normalized = normalizeMode(raw)
        mode = normalized
        recorder.level = when (normalized) {
            "basic" -> DiagnosticLevel.BASIC
            MODE_ADVANCED_RAM, MODE_ADVANCED_CRASH -> DiagnosticLevel.VERBOSE
            else -> DiagnosticLevel.OFF
        }
        evidence.enabled = normalized == MODE_ADVANCED_RAM || normalized == MODE_ADVANCED_CRASH
        crashStore.enabled = normalized == MODE_ADVANCED_CRASH
        if (normalized == MODE_ADVANCED_CRASH && rotateCrashSafe) crashStore.beginSession()
        if (normalized == "off") activityTracker.clear()
    }

    private fun normalizeMode(value: String): String = when (value) {
        "advanced" -> MODE_ADVANCED_CRASH // migrate the old single Advanced mode safely.
        "off", "basic", MODE_ADVANCED_RAM, MODE_ADVANCED_CRASH -> value
        else -> "off"
    }

    private fun environment(events: List<DiagnosticEvent>): JSONObject = JSONObject().apply {
        put("generatedAt", Instant.now().toString())
        put("diagnosticMode", mode)
        put("advanced", advanced)
        put("crashSafe", crashSafe)
        put("appVersionName", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("eventCount", events.size)
        put("activeOperationCount", activityTracker.snapshot().size)
        put("persistentCriticalCount", persistentCriticalCount())
        val stats = evidence.stats()
        put("evidenceItems", stats.itemCount)
        put("evidenceBytes", stats.retainedBytes)
        put("evidenceEvictedItems", stats.evictedItems)
    }

    private fun activeOperationsJson(): JSONArray = JSONArray().apply {
        activityTracker.snapshot().forEach { operation ->
            put(JSONObject().apply {
                put("traceId", operation.traceId)
                put("sourceId", operation.sourceId)
                put("category", operation.category.name)
                put("startedAtEpochMs", operation.startedAtEpochMs)
                put("lastEventAtEpochMs", operation.lastEventAtEpochMs)
                put("startEvent", operation.startEvent)
                put("lastEvent", operation.lastEvent)
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

    private fun safeEvidenceBytes(item: DiagnosticEvidence): ByteArray {
        if (item.contentType.contains("html", ignoreCase = true)) {
            return DiagnosticRedactor.redactHtmlPreservingStructure(
                item.data.toString(Charsets.UTF_8),
                8 * 1024 * 1024,
            ).toByteArray(Charsets.UTF_8)
        }
        if (item.contentType.startsWith("text/", true) || item.contentType.contains("json", true)) {
            return DiagnosticRedactor.redactLongText(
                item.data.toString(Charsets.UTF_8),
                8 * 1024 * 1024,
            ).toByteArray(Charsets.UTF_8)
        }
        return item.data
    }

    private fun safeStoredBytes(name: String, bytes: ByteArray): ByteArray = when {
        name.endsWith(".html", true) -> DiagnosticRedactor.redactHtmlPreservingStructure(
            bytes.toString(Charsets.UTF_8), 8 * 1024 * 1024,
        ).toByteArray(Charsets.UTF_8)
        name.endsWith(".json", true) || name.endsWith(".jsonl", true) || name.endsWith(".txt", true) || name.endsWith(".log", true) ->
            DiagnosticRedactor.redactLongText(bytes.toString(Charsets.UTF_8), 8 * 1024 * 1024).toByteArray(Charsets.UTF_8)
        else -> bytes
    }

    private fun readme(): String = buildString {
        appendLine("NgheTruyen diagnostic black box")
        appendLine("Mode: $mode")
        appendLine("This bundle keeps high-fidelity browser/runtime evidence for debugging while redacting common credentials on export.")
        when (mode) {
            MODE_ADVANCED_RAM -> appendLine("Advanced RAM-only keeps up to 64 MiB of evidence in memory and does not write the current diagnostic session to the crash-safe journal.")
            MODE_ADVANCED_CRASH -> appendLine("Advanced crash-safe keeps up to 64 MiB of evidence plus a rolling private-storage journal so the previous process can be inspected after a crash.")
            "basic" -> appendLine("Basic records INFO/WARN/ERROR structured events without browser/runtime evidence payloads.")
            else -> appendLine("Diagnostics UI is off. Only a bounded always-on critical breadcrumb ring may be retained for fatal/install/trust/security failures.")
        }
        appendLine("HTML keeps DOM, script and style structure; common credentials and sensitive values are redacted during export.")
        appendLine("The report includes a sanitized app runtime snapshot, active-operation state, and the backup/restore log tail when available.")
        appendLine("crash-safe/critical/events.jsonl retains at most 100 critical breadcrumbs so failed extension installs are diagnosable even if diagnostics was previously off.")
        if (crashSafe) appendLine("The crash-safe/previous section may contain the final evidence from the process before the latest restart.")
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

    companion object {
        private const val KEY_MODE = "diagnostics.mode"
        const val MODE_ADVANCED_RAM = "advanced_ram"
        const val MODE_ADVANCED_CRASH = "advanced_crash"
    }
}

private class DiagnosticActivityTracker : DiagnosticSink {
    private val lock = Any()
    private val active = linkedMapOf<String, DiagnosticActiveOperation>()

    override fun emit(event: DiagnosticEvent) = synchronized(lock) {
        val traceId = event.traceId.trim()
        if (traceId.isBlank()) return@synchronized
        val name = event.name.uppercase()
        when {
            isStart(name) -> {
                active[traceId] = DiagnosticActiveOperation(
                    traceId = traceId,
                    sourceId = event.sourceId,
                    category = event.category,
                    startedAtEpochMs = event.timestampEpochMs,
                    lastEventAtEpochMs = event.timestampEpochMs,
                    startEvent = event.name,
                    lastEvent = event.name,
                )
                while (active.size > 100) active.remove(active.entries.first().key)
            }
            isTerminal(name) -> active.remove(traceId)
            traceId in active -> {
                val current = active.getValue(traceId)
                active[traceId] = current.copy(
                    lastEventAtEpochMs = event.timestampEpochMs,
                    lastEvent = event.name,
                )
            }
        }
    }

    fun snapshot(): List<DiagnosticActiveOperation> = synchronized(lock) {
        active.values.sortedByDescending(DiagnosticActiveOperation::lastEventAtEpochMs)
    }

    fun clear() = synchronized(lock) { active.clear() }

    private fun isStart(name: String): Boolean = name.endsWith("_START") || name.endsWith("_STARTED")

    private fun isTerminal(name: String): Boolean {
        if (name.endsWith("_ITEM_COMPLETED") || name.endsWith("_SEGMENT_COMPLETED")) return false
        return name.endsWith("_COMPLETED") ||
            name.endsWith("_FAILED") ||
            name.endsWith("_ERROR") ||
            name.endsWith("_DONE") ||
            name.endsWith("_CANCELLED") ||
            name.endsWith("_STOPPED")
    }
}

private class CrashSafeDiagnosticStore(private val context: Context) : DiagnosticSink, DiagnosticEvidenceSink {
    private val lock = Any()
    private val root = File(context.filesDir, "source-diagnostics-blackbox")
    private val live = File(root, "live")
    private val previous = File(root, "previous")
    private val criticalFile = File(root, "critical-events.jsonl")
    @Volatile override var enabled: Boolean = false

    init {
        live.mkdirs()
    }

    fun beginSession() = synchronized(lock) {
        root.mkdirs()
        if (live.exists() && live.walkTopDown().any { it.isFile }) {
            previous.deleteRecursively()
            if (!live.renameTo(previous)) {
                copyDirectory(live, previous)
                live.deleteRecursively()
            }
        } else {
            live.deleteRecursively()
        }
        live.mkdirs()
    }

    override fun emit(event: DiagnosticEvent) {
        if (event.shouldRetainWhenDiagnosticsOff()) persistCritical(event)
        if (!enabled) return
        synchronized(lock) {
            live.mkdirs()
            val file = File(live, "events.jsonl")
            if (file.exists() && file.length() >= 8L * 1024L * 1024L) {
                File(live, "events.previous.jsonl").delete()
                file.renameTo(File(live, "events.previous.jsonl"))
            }
            file.appendText(DiagnosticJsonExporter.eventLine(event) + "\n")
        }
    }

    override fun capture(evidence: DiagnosticEvidence) {
        if (!enabled) return
        synchronized(lock) {
            val directory = File(live, "evidence").also(File::mkdirs)
            val base = evidence.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(140).ifBlank { "evidence.bin" }
            val file = File(directory, "${evidence.timestampEpochMs}-${base}")
            file.writeBytes(evidence.data)
            trimEvidence(directory)
        }
    }

    fun restoreCriticalEvents(): List<DiagnosticEvent> = synchronized(lock) {
        if (!criticalFile.isFile) return@synchronized emptyList()
        criticalFile.readLines().takeLast(MAX_CRITICAL_EVENTS).mapNotNull(::parseEventLine)
    }

    fun persistentCriticalCount(): Int = synchronized(lock) {
        if (!criticalFile.isFile) 0 else criticalFile.useLines { it.count() }.coerceAtMost(MAX_CRITICAL_EVENTS)
    }

    private fun persistCritical(event: DiagnosticEvent) = synchronized(lock) {
        root.mkdirs()
        criticalFile.appendText(DiagnosticJsonExporter.eventLine(event) + "\n")
        val lines = criticalFile.readLines()
        if (lines.size > MAX_CRITICAL_EVENTS) {
            criticalFile.writeText(lines.takeLast(MAX_CRITICAL_EVENTS).joinToString("\n", postfix = "\n"))
        }
    }

    private fun parseEventLine(line: String): DiagnosticEvent? = runCatching {
        val json = JSONObject(line)
        val attributes = buildMap<String, String> {
            val raw = json.optJSONObject("attributes")
            if (raw != null) {
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

    fun clear() = synchronized(lock) {
        root.deleteRecursively()
        live.mkdirs()
    }

    fun snapshotForExport(): List<Pair<String, ByteArray>> = synchronized(lock) {
        val out = mutableListOf<Pair<String, ByteArray>>()
        if (criticalFile.isFile) out += "critical/events.jsonl" to criticalFile.readBytes()
        if (previous.exists()) {
            previous.walkTopDown().filter(File::isFile).forEach { file ->
                out += "previous/${file.relativeTo(previous).invariantSeparatorsPath}" to file.readBytes()
            }
        }
        live.listFiles()?.filter { it.isFile && it.name.startsWith("events") }?.forEach { file ->
            out += "current/${file.name}" to file.readBytes()
        }
        out
    }

    private fun trimEvidence(directory: File) {
        val files = directory.listFiles()?.filter(File::isFile)?.sortedBy(File::lastModified)?.toMutableList() ?: return
        var total = files.sumOf(File::length)
        while (files.isNotEmpty() && total > 64L * 1024L * 1024L) {
            val file = files.removeAt(0)
            total -= file.length()
            file.delete()
        }
    }

    private fun copyDirectory(from: File, to: File) {
        from.walkTopDown().forEach { file ->
            val target = File(to, file.relativeTo(from).path)
            if (file.isDirectory) target.mkdirs() else {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    companion object {
        private const val MAX_CRITICAL_EVENTS = 100
    }
}
''', encoding="utf-8")

# 3) Main UI state receives real active operations instead of guessing from unrelated loading booleans.
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
replace_once(
    path,
    '    val diagnosticsMode: String = "off",\n    val sleepTimerStatus: String = "Đang tắt",',
    '    val diagnosticsMode: String = "off",\n    val diagnosticActiveOperations: List<String> = emptyList(),\n    val diagnosticPersistentCriticalCount: Int = 0,\n    val sleepTimerStatus: String = "Đang tắt",',
)
replace_once(
    path,
    '            diagnosticsMode = container.sourceDiagnostics.mode,\n            backupHistory = container.backupHistoryStore.entries(),',
    '            diagnosticsMode = container.sourceDiagnostics.mode,\n            diagnosticActiveOperations = container.sourceDiagnostics.activityLines(),\n            diagnosticPersistentCriticalCount = container.sourceDiagnostics.persistentCriticalCount(),\n            backupHistory = container.backupHistoryStore.entries(),',
)
replace_once(
    path,
    '                        diagnosticsMode = container.sourceDiagnostics.mode,\n                        sourceDiagnosticCount = events.size,\n                        sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),\n                        sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),',
    '                        diagnosticsMode = container.sourceDiagnostics.mode,\n                        diagnosticActiveOperations = container.sourceDiagnostics.activityLines(),\n                        diagnosticPersistentCriticalCount = container.sourceDiagnostics.persistentCriticalCount(),\n                        sourceDiagnosticCount = events.size,\n                        sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),\n                        sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),',
)
replace_once(
    path,
    '                sourceDiagnosticCount = container.sourcePlatformManager.diagnosticsSnapshot().size,\n                sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),\n                sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),\n            )',
    '                sourceDiagnosticCount = container.sourcePlatformManager.diagnosticsSnapshot().size,\n                sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),\n                sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),\n                diagnosticActiveOperations = container.sourceDiagnostics.activityLines(),\n                diagnosticPersistentCriticalCount = container.sourceDiagnostics.persistentCriticalCount(),\n            )',
)
replace_once(
    path,
    '        "sourcePacks" to snapshot.sourcePacks.size.toString(),\n    )',
    '        "sourcePacks" to snapshot.sourcePacks.size.toString(),\n        "diagnosticActiveOperations" to snapshot.diagnosticActiveOperations.size.toString(),\n        "diagnosticPersistentCriticalCount" to snapshot.diagnosticPersistentCriticalCount.toString(),\n    )',
)
replace_once(
    path,
    '        showMessage("Đã xóa nhật ký, bằng chứng Advanced và hộp đen crash-safe.")',
    '        showMessage("Đã xóa nhật ký, evidence RAM, critical breadcrumbs và hộp đen crash-safe.")',
)

# 4) Replace the diagnostics chrome with operation-driven status and dual Advanced explanations.
chrome_path = ROOT / "app/src/main/java/vn/nghetruyen/app/ui/components/ReferenceDiagnosticsChrome.kt"
chrome_path.write_text(r'''package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticUi
import vn.nghetruyen.app.ui.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Global Lua-style diagnostics chrome backed by the actual diagnostic operation tracker. */
@Composable
fun ReferenceDiagnosticsChrome(
    state: MainUiState,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    if (state.diagnosticsMode == "off") return

    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(state.diagnosticsMode) {
        if (state.diagnosticsMode == "off") showLog = false
    }

    val recording = state.diagnosticActiveOperations.isNotEmpty()
    val label = when {
        recording -> "ĐANG GHI NHẬT KÝ..."
        state.sourceDiagnosticCount > 0 -> "XEM NHẬT KÝ"
        else -> "CHƯA CÓ NHẬT KÝ"
    }

    Surface(color = Color(0xFFE5E5EA), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { showLog = true },
            modifier = Modifier.fillMaxWidth().padding(3.dp),
        ) {
            Text(label)
        }
    }

    if (showLog) {
        ReferenceDiagnosticsDialog(
            state = state,
            onExport = onExport,
            onClear = onClear,
            onDismiss = { showLog = false },
        )
    }
}

@Composable
private fun ReferenceDiagnosticsDialog(
    state: MainUiState,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val events = state.sourceDiagnostics.take(200)
    val traces = state.sourceTraces.take(100)
    val errorCount = events.count { it.severity == "ERROR" }
    val warningCount = events.count { it.severity == "WARN" }
    val grouped = events.groupBy(::diagnosticGroup)
    val timestamp = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NHẬT KÝ CHẨN ĐOÁN") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Mức: ${diagnosticsModeLabel(state.diagnosticsMode)} • ${state.sourceDiagnosticCount} sự kiện • $errorCount lỗi • $warningCount cảnh báo",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    diagnosticsModeDescription(state.diagnosticsMode),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (state.diagnosticPersistentCriticalCount > 0) {
                    Text(
                        "Critical breadcrumbs luôn giữ: ${state.diagnosticPersistentCriticalCount}/100. Các lỗi nghiêm trọng/cài extension vẫn còn khi trước đó bạn để Nhật ký = Tắt.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f).padding(2.dp)) {
                        Text(if (isAdvancedDiagnostics(state.diagnosticsMode)) "XUẤT HỘP ĐEN" else "XUẤT NHẬT KÝ")
                    }
                    Button(onClick = onClear, modifier = Modifier.weight(1f).padding(2.dp)) {
                        Text("XÓA NHẬT KÝ")
                    }
                }

                if (state.diagnosticActiveOperations.isNotEmpty()) {
                    DiagnosticSection("ĐANG HOẠT ĐỘNG (${state.diagnosticActiveOperations.size})") {
                        state.diagnosticActiveOperations.take(20).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }

                DiagnosticSection("TRẠNG THÁI RUNTIME") {
                    val p = state.playback
                    Text(
                        "Đích=${state.destination} • tab=${state.rootTab} • source=${state.selectedSourceId} • loading=${state.loading} • AI=${state.aiBusy}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "TTS: playing=${p.isPlaying} • prep=${p.preparationState} • story=${p.storyId.take(32)} • chapter=${p.chapterId.take(32)} • đoạn=${p.paragraphIndex + 1} • unit=${p.currentUnitId ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Giọng: rate=${"%.2f".format(Locale.ROOT, p.rate)} • pitch=${"%.2f".format(Locale.ROOT, p.pitch)} • volume=${"%.2f".format(Locale.ROOT, p.volume)} • Sonic=${state.sonicProcessingEnabled}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                DiagnosticSection("VIETPHRASE / AI") {
                    Text(
                        "VietPhrase=${state.vietPhraseEnabled} • rules=${state.vietPhraseRules.size} • dictionaries=${state.vietPhraseDictionaryStates.size} • suggestions=${state.vietPhraseSuggestions.size} • onlineBusy=${state.vietPhraseOnlineBusy}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "AI provider=${state.aiOnline.provider} • enabled=${state.aiOnline.enabled} • model=${state.aiOnline.model.take(80)} • requestBusy=${state.aiBusy} • modelDiscovery=${state.aiModelDiscoveryBusy}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                val activeDownloads = state.downloads.filter { it.state != "COMPLETED" }.take(20)
                val failedChapters = state.downloadFailures.take(20)
                if (activeDownloads.isNotEmpty() || failedChapters.isNotEmpty()) {
                    DiagnosticSection("TẢI TRUYỆN") {
                        activeDownloads.forEach { job ->
                            Text(
                                "${job.state} • ${job.sourceId}/${job.storyId.take(24)} • ${job.completedChapters}/${job.totalChapters} • ${job.currentChapterTitle.take(80)} • retry=${job.retryCount}${job.errorMessage?.let { " • $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        failedChapters.forEach { failure ->
                            Text(
                                "LỖI CHƯƠNG ${failure.chapterIndex + 1} • ${failure.chapterTitle.take(80)} • ${failure.errorMessage.take(180)} • retry=${failure.retryCount}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                val exportJobs = state.audioExports.filter { it.state != "COMPLETED" }.take(20)
                if (exportJobs.isNotEmpty()) {
                    DiagnosticSection("XUẤT SÁCH NÓI") {
                        exportJobs.forEach { job ->
                            Text(
                                "${job.state}/${job.stage} • ${job.storyTitle.take(80)} • ${job.outputFormat} • ${job.completedSegments}/${job.totalSegments}${job.errorMessage?.let { " • $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                if (state.backupLogText.isNotBlank()) {
                    DiagnosticSection("SAO LƯU / KHÔI PHỤC") {
                        Text("Tệp: ${state.backupLogPath}", style = MaterialTheme.typography.bodySmall)
                        Text(state.backupLogText.takeLast(8_000), style = MaterialTheme.typography.bodySmall)
                    }
                }

                DIAGNOSTIC_GROUP_ORDER.forEach { group ->
                    val rows = grouped[group].orEmpty()
                    if (rows.isNotEmpty()) {
                        DiagnosticSection("$group (${rows.size})") {
                            rows.forEach { event ->
                                val time = timestamp.format(Date(event.timestampEpochMs))
                                val duration = event.durationMs?.let { " • ${it}ms" }.orEmpty()
                                Text(
                                    "$time • ${event.severity} • ${event.category}/${event.name}$duration",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (event.severity == "ERROR") FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    "${event.sourceId} • trace=${event.traceId.take(28)}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (traces.isNotEmpty()) {
                    DiagnosticSection("TRACE (${traces.size})") {
                        traces.forEach { trace ->
                            Text(
                                "${if (trace.failed) "LỖI" else "OK"} • ${trace.sourceId} • ${trace.eventCount} sự kiện • ${(trace.endedAtEpochMs - trace.startedAtEpochMs).coerceAtLeast(0)}ms • ${trace.traceId}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                if (events.isEmpty()) {
                    Text(
                        "Chưa có sự kiện. Nhật ký sẽ bắt đầu xuất hiện ngay khi có request nguồn, WebView, AI, TTS hoặc tác vụ nền.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun DiagnosticSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp),
    )
    content()
}

private fun isAdvancedDiagnostics(mode: String): Boolean =
    mode == "advanced" || mode == "advanced_ram" || mode == "advanced_crash"

private fun diagnosticsModeLabel(mode: String): String = when (mode) {
    "advanced_ram" -> "Gỡ lỗi nâng cao • RAM-only"
    "advanced_crash", "advanced" -> "Gỡ lỗi nâng cao • crash-safe"
    "basic" -> "Gỡ lỗi cơ bản"
    else -> "Tắt"
}

private fun diagnosticsModeDescription(mode: String): String = when (mode) {
    "advanced_ram" -> "Advanced RAM-only ghi DEBUG + evidence browser/runtime tối đa 64 MiB trong RAM; evidence phiên hiện tại biến mất khi tiến trình chết."
    "advanced_crash", "advanced" -> "Advanced crash-safe ghi DEBUG + evidence tối đa 64 MiB và journal riêng tư để giữ dấu vết của tiến trình trước sau crash."
    else -> "Basic ghi INFO/WARN/ERROR. Critical breadcrumb nghiêm trọng vẫn được giữ giới hạn để không mất lỗi cài extension xảy ra trước khi bạn bật Nhật ký."
}

private fun diagnosticGroup(event: SourceDiagnosticUi): String {
    val key = "${event.category}/${event.name}".uppercase(Locale.ROOT)
    return when {
        event.severity == "ERROR" || event.severity == "WARN" -> "LỖI & CẢNH BÁO"
        "BROWSER" in key || "WEBVIEW" in key || "RENDERER" in key || "DOM" in key -> "TRÌNH DUYỆT / WEBVIEW / DOM"
        "NETWORK" in key || "HTTP" in key || "SSL" in key || "COOKIE" in key || "WEBSOCKET" in key -> "MẠNG / HTTP / COOKIE"
        "AI_" in key || "VOICE_CAST" in key || "SCENE_MUSIC" in key || "NARRATION" in key -> "AI / PHÂN VAI / NHẠC CẢNH"
        "TTS" in key || "SONIC" in key || "PLAYBACK" in key || "AUDIO_FOCUS" in key -> "TTS / SONIC / PLAYBACK"
        "DOWNLOAD" in key || "AUDIO_EXPORT" in key || "VIETPHRASE" in key || "BACKUP" in key || "RESTORE" in key -> "TẢI / XUẤT / VIETPHRASE / BACKUP"
        "PACKAGE" in key || "STORE" in key || "SOURCE" in key || "VBOOK" in key || "LUA" in key || "PARSER" in key || "EXTENSION" in key -> "NGUỒN / VBOOK / EXTENSION / PARSER"
        "SECURITY" in key || "TRUST" in key || "REPLAY" in key -> "BẢO MẬT / TRUST / REPLAY"
        else -> "RUNTIME / KHÁC"
    }
}

private val DIAGNOSTIC_GROUP_ORDER = listOf(
    "LỖI & CẢNH BÁO",
    "TRÌNH DUYỆT / WEBVIEW / DOM",
    "MẠNG / HTTP / COOKIE",
    "NGUỒN / VBOOK / EXTENSION / PARSER",
    "AI / PHÂN VAI / NHẠC CẢNH",
    "TTS / SONIC / PLAYBACK",
    "TẢI / XUẤT / VIETPHRASE / BACKUP",
    "BẢO MẬT / TRUST / REPLAY",
    "RUNTIME / KHÁC",
)
''', encoding="utf-8")

# 5) Settings exposes both Advanced profiles and treats both as black-box modes.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
replace_once(
    path,
    '            "basic" -> "Gỡ lỗi cơ bản"\n            "advanced" -> "Gỡ lỗi nâng cao"\n            else -> "Tắt"',
    '            "basic" -> "Gỡ lỗi cơ bản"\n            "advanced_ram" -> "Gỡ lỗi nâng cao • RAM-only"\n            "advanced_crash", "advanced" -> "Gỡ lỗi nâng cao • crash-safe"\n            else -> "Tắt"',
)
replace_once(
    path,
    '                    "off" to "Tắt",\n                    "basic" to "Gỡ lỗi cơ bản",\n                    "advanced" to "Gỡ lỗi nâng cao",',
    '                    "off" to "Tắt",\n                    "basic" to "Gỡ lỗi cơ bản",\n                    "advanced_ram" to "Gỡ lỗi nâng cao • RAM-only",\n                    "advanced_crash" to "Gỡ lỗi nâng cao • crash-safe",',
)
value = read(path)
value = value.replace('state.diagnosticsMode == "advanced"', 'state.diagnosticsMode.startsWith("advanced")')
write(path, value)

# 6) Fix the duplicated audio error marker introduced by the previous migration.
path = "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt"
value = read(path)
pattern = re.compile(r'(\n\s*container\.sourceDiagnostics\.mark\(name = "AUDIO_EXPORT_RUNTIME_ERROR"[^\n]*\))(?:\1)+')
# Exact duplicate lines can differ only by indentation capture behavior, so use line-based collapse too.
lines = value.splitlines()
out = []
for line in lines:
    if out and line.strip().startswith('container.sourceDiagnostics.mark(name = "AUDIO_EXPORT_RUNTIME_ERROR"') and line.strip() == out[-1].strip():
        continue
    out.append(line)
write(path, "\n".join(out) + ("\n" if value.endswith("\n") else ""))

# 7) Strengthen the parity gate so these behaviors cannot silently regress.
path = "scripts/check_lua_diagnostics_ui_parity.py"
replace_once(
    path,
    '    "shared app diagnostic mark API": "fun mark(" in runtime and "DIAGNOSTICS_MODE_CHANGED" in runtime,',
    '    "shared app diagnostic mark API": "fun mark(" in runtime and "DIAGNOSTICS_MODE_CHANGED" in runtime,\n    "dual Advanced profiles": all(token in runtime for token in ("advanced_ram", "advanced_crash", "crashSafe")),\n    "active operation tracker": "DiagnosticActivityTracker" in runtime and "diagnosticActiveOperations" in vm and "ĐANG HOẠT ĐỘNG" in chrome,\n    "critical breadcrumbs while OFF": "shouldRetainWhenDiagnosticsOff" in runtime and "MAX_CRITICAL_EVENTS = 100" in runtime,',
)
replace_once(
    path,
    '    "audio export diagnostics": all(\n        marker in audio',
    '    "audio runtime error marker is not duplicated": audio.count("AUDIO_EXPORT_RUNTIME_ERROR") == 1,\n    "audio export diagnostics": all(\n        marker in audio',
)

print("DIAGNOSTICS_DEEPENING_STAGE_A=APPLIED")
