package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.BuildConfig
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticEvidenceRecorder
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticRecorder
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticJsonExporter
import vn.nghetruyen.source.diagnostics.DiagnosticLevel
import vn.nghetruyen.source.diagnostics.DiagnosticRedactor
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.diagnostics.SourceSnapshotSanitizer
import vn.nghetruyen.source.diagnostics.SourceTraceExplorer
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourceDiagnosticRuntime(private val context: Context) {
    private val prefs = context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE)
    private val crashStore = CrashSafeDiagnosticStore(context)
    val recorder = BoundedDiagnosticRecorder(
        maxEvents = 20_000,
        level = DiagnosticLevel.OFF,
        mirror = crashStore,
    )
    val evidence = BoundedDiagnosticEvidenceRecorder(
        maxBytes = 64L * 1024L * 1024L,
        maxItems = 2_048,
        maxItemBytes = 16 * 1024 * 1024,
        mirror = crashStore,
    )

    @Volatile var mode: String = "off"
        private set

    init {
        applyMode(prefs.getString(KEY_MODE, "off").orEmpty(), rotateAdvanced = true)
    }

    fun setMode(requested: String): String {
        val normalized = normalizeMode(requested)
        val enteringAdvanced = normalized == "advanced" && mode != "advanced"
        mode = normalized
        prefs.edit().putString(KEY_MODE, normalized).apply()
        applyMode(normalized, rotateAdvanced = enteringAdvanced)
        return normalized
    }

    fun clearBlackBox() {
        recorder.clear()
        evidence.clear()
        crashStore.clear()
    }

    fun exportBundle(
        events: List<DiagnosticEvent>,
        installed: List<SourcePackUiInfo>,
        repositories: List<SourceRepositoryUiInfo>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.addText("README.txt", readme())
            zip.addBytes("report/events.json", DiagnosticJsonExporter.export(events))
            zip.addText("report/environment.json", environment(events).toString(2))
            zip.addText("report/installed_sources.json", installedJson(installed).toString(2))
            zip.addText("report/repositories.json", repositoriesJson(repositories).toString(2))
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

    private fun applyMode(raw: String, rotateAdvanced: Boolean) {
        val normalized = normalizeMode(raw)
        mode = normalized
        recorder.level = when (normalized) {
            "basic" -> DiagnosticLevel.BASIC
            "advanced" -> DiagnosticLevel.VERBOSE
            else -> DiagnosticLevel.OFF
        }
        evidence.enabled = normalized == "advanced"
        if (normalized == "advanced") {
            if (rotateAdvanced) crashStore.beginSession()
            crashStore.enabled = true
        } else {
            crashStore.enabled = false
        }
    }

    private fun normalizeMode(value: String): String =
        value.takeIf { it in setOf("off", "basic", "advanced") } ?: "off"

    private fun environment(events: List<DiagnosticEvent>): JSONObject = JSONObject().apply {
        put("generatedAt", Instant.now().toString())
        put("diagnosticMode", mode)
        put("appVersionName", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("eventCount", events.size)
        val stats = evidence.stats()
        put("evidenceItems", stats.itemCount)
        put("evidenceBytes", stats.retainedBytes)
        put("evidenceEvictedItems", stats.evictedItems)
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
        appendLine("This bundle intentionally keeps high-fidelity browser/runtime evidence for debugging.")
        appendLine("HTML keeps DOM, script and style structure; common credentials and sensitive values are redacted during export.")
        appendLine("Advanced mode retains up to 64 MiB of evidence and a crash-safe rolling journal in private app storage.")
        appendLine("The crash-safe/previous section may contain the final evidence from the process before the latest restart.")
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
    }
}

private class CrashSafeDiagnosticStore(private val context: Context) : DiagnosticSink, DiagnosticEvidenceSink {
    private val lock = Any()
    private val root = File(context.filesDir, "source-diagnostics-blackbox")
    private val live = File(root, "live")
    private val previous = File(root, "previous")
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

    fun clear() = synchronized(lock) {
        root.deleteRecursively()
        live.mkdirs()
    }

    fun snapshotForExport(): List<Pair<String, ByteArray>> = synchronized(lock) {
        val out = mutableListOf<Pair<String, ByteArray>>()
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
}
