package vn.nghetruyen.app.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BackupHistoryEntry(
    val id: String,
    val timestampEpochMs: Long,
    val operation: String,
    val success: Boolean,
    val summary: String,
    val errorCode: String? = null,
    val components: List<String> = emptyList(),
)

class BackupHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("backup_history_v1", Context.MODE_PRIVATE)
    private val logFile = File(appContext.filesDir, "diagnostics/backup_restore.log")

    @Synchronized
    fun entries(): List<BackupHistoryEntry> = decode(preferences.getString(KEY, null))

    @Synchronized
    fun record(
        operation: String,
        success: Boolean,
        summary: String,
        errorCode: String? = null,
        components: Collection<String> = emptyList(),
    ): BackupHistoryEntry {
        val entry = BackupHistoryEntry(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = System.currentTimeMillis(),
            operation = operation.take(20),
            success = success,
            summary = summary.trim().take(1200),
            errorCode = errorCode?.trim()?.take(120),
            components = components.map(String::trim).filter(String::isNotBlank).distinct().take(20),
        )
        val updated = (listOf(entry) + entries()).distinctBy(BackupHistoryEntry::id).take(MAX_ENTRIES)
        preferences.edit().putString(KEY, encode(updated)).apply()
        appendTextLog(entry)
        return entry
    }

    @Synchronized
    fun logPath(): String = logFile.absolutePath

    @Synchronized
    fun logText(): String {
        if (logFile.isFile) {
            return runCatching { logFile.readText() }.getOrDefault("").takeLast(MAX_LOG_CHARS)
        }
        return entries().sortedBy(BackupHistoryEntry::timestampEpochMs).joinToString("\n") { formatEntry(it) }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY).apply()
        runCatching { if (logFile.exists()) logFile.delete() }
    }

    private fun appendTextLog(entry: BackupHistoryEntry) {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(formatEntry(entry) + "\n")
            if (logFile.length() > MAX_LOG_BYTES) {
                val trimmed = logFile.readText().takeLast(MAX_LOG_CHARS)
                logFile.writeText(trimmed.substringAfter('\n', trimmed))
            }
        }
    }

    private fun formatEntry(item: BackupHistoryEntry): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(item.timestampEpochMs))
        val state = if (item.success) "THÀNH CÔNG" else "THẤT BẠI"
        val componentText = item.components.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " | $it" }.orEmpty()
        val errorText = item.errorCode?.takeIf(String::isNotBlank)?.let { " | $it" }.orEmpty()
        return "$stamp | ${item.operation} | $state$errorText$componentText | ${item.summary}"
    }

    private fun encode(items: List<BackupHistoryEntry>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("timestampEpochMs", item.timestampEpochMs)
                put("operation", item.operation)
                put("success", item.success)
                put("summary", item.summary)
                put("errorCode", item.errorCode ?: JSONObject.NULL)
                put("components", JSONArray(item.components))
            })
        }
    }.toString()

    private fun decode(raw: String?): List<BackupHistoryEntry> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_ENTRIES)) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id").takeIf(String::isNotBlank) ?: continue
                add(BackupHistoryEntry(
                    id = id,
                    timestampEpochMs = obj.optLong("timestampEpochMs", 0L),
                    operation = obj.optString("operation").take(20),
                    success = obj.optBoolean("success", false),
                    summary = obj.optString("summary").take(1200),
                    errorCode = obj.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }?.take(120),
                    components = obj.optJSONArray("components")?.let { values ->
                        buildList { for (i in 0 until values.length()) values.optString(i).takeIf(String::isNotBlank)?.let(::add) }
                    }.orEmpty(),
                ))
            }
        }.sortedByDescending(BackupHistoryEntry::timestampEpochMs)
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 100
        private const val MAX_LOG_BYTES = 512 * 1024L
        private const val MAX_LOG_CHARS = 400_000
    }
}
