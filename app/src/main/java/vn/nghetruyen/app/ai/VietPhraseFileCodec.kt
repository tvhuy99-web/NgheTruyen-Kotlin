package vn.nghetruyen.app.ai

import vn.nghetruyen.app.data.local.VietPhraseEntity
import java.util.Locale

/** Portable UTF-8 VietPhrase format. TSV is canonical; source=target is accepted for legacy files. */
object VietPhraseFileCodec {
    data class Record(
        val source: String,
        val target: String,
        val priority: Int = 0,
        val enabled: Boolean = true,
    )

    fun decode(text: String, maxRecords: Int = MAX_RECORDS): List<Record> {
        require(text.length <= MAX_TEXT_CHARS) { "Tệp VietPhrase vượt giới hạn an toàn." }
        val records = LinkedHashMap<String, Record>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { lineNumber, raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val record = parseLine(raw) ?: throw IllegalArgumentException("Dòng ${lineNumber + 1} không đúng định dạng VietPhrase.")
            require(record.source.length <= 200 && record.target.length <= 400) {
                "Dòng ${lineNumber + 1} có cụm từ quá dài."
            }
            val key = record.source.lowercase(Locale.ROOT)
            records[key] = record
            require(records.size <= maxRecords.coerceIn(1, MAX_RECORDS)) { "Tệp VietPhrase có quá nhiều quy tắc." }
        }
        return records.values.toList()
    }

    fun encode(items: List<VietPhraseEntity>): String = buildString {
        appendLine("# NgheTruyen VietPhrase v1")
        appendLine("# source\\ttarget\\tpriority\\tenabled")
        items.sortedWith(compareByDescending<VietPhraseEntity> { it.priority }.thenByDescending { it.source.length })
            .forEach { item ->
                append(escape(item.source)).append('\t')
                    .append(escape(item.target)).append('\t')
                    .append(item.priority.coerceIn(-100, 100)).append('\t')
                    .append(item.enabled)
                    .append('\n')
            }
    }

    private fun parseLine(raw: String): Record? {
        val fields = raw.split('\t')
        if (fields.size >= 2) {
            val source = unescape(fields[0]).trim()
            val target = unescape(fields[1]).trim()
            if (source.isBlank() || target.isBlank()) return null
            return Record(
                source = source,
                target = target,
                priority = fields.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(-100, 100) ?: 0,
                enabled = fields.getOrNull(3)?.trim()?.toBooleanStrictOrNull() ?: true,
            )
        }
        val separator = raw.indexOf("=>").takeIf { it >= 0 }
            ?.let { it to 2 }
            ?: raw.indexOf('=').takeIf { it >= 0 }?.let { it to 1 }
            ?: return null
        val source = raw.substring(0, separator.first).trim()
        val target = raw.substring(separator.first + separator.second).trim()
        if (source.isBlank() || target.isBlank()) return null
        return Record(source, target)
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.lastIndex) {
                append(character)
                index += 1
                continue
            }
            when (val next = value[index + 1]) {
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                '\\' -> append('\\')
                else -> { append('\\'); append(next) }
            }
            index += 2
        }
    }

    const val MAX_RECORDS = 100_000
    const val MAX_TEXT_CHARS = 16 * 1024 * 1024
}
