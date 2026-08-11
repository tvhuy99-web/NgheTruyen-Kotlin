package vn.nghetruyen.app.sourceplatform

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
