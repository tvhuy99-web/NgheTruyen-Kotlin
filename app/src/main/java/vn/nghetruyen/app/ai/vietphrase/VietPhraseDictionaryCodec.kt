package vn.nghetruyen.app.ai.vietphrase

import java.nio.charset.Charset
import java.util.Locale


object VietPhraseDictionaryCodec {
    data class DecodeResult(
        val kind: VietPhraseDictionaryKind,
        val rules: List<VietPhraseRule>,
        val delimiter: String,
        val duplicateCount: Int,
    )

    fun decode(
        bytes: ByteArray,
        fileName: String,
        expectedKind: VietPhraseDictionaryKind? = null,
        maxRecords: Int = MAX_RECORDS,
    ): DecodeResult {
        require(bytes.size <= MAX_FILE_BYTES) { "Tệp từ điển vượt giới hạn an toàn." }
        val kind = expectedKind ?: VietPhraseDictionaryKind.fromFileName(fileName)
            ?: throw IllegalArgumentException("Không xác định được loại từ điển từ tên tệp: $fileName")
        val text = decodeText(bytes)
        val delimiter = detectDelimiter(text)
        val unique = LinkedHashMap<String, VietPhraseRule>()
        var duplicates = 0
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith("//")) return@forEachIndexed
            val pair = splitLine(rawLine, delimiter) ?: throw IllegalArgumentException("Dòng ${index + 1} không đúng định dạng từ điển.")
            val source = unescape(pair.first).trim()
            val target = unescape(pair.second).trim()
            require(source.isNotBlank()) { "Dòng ${index + 1} có cụm nguồn rỗng." }
            require(source.length <= MAX_SOURCE_CHARS && target.length <= MAX_TARGET_CHARS) { "Dòng ${index + 1} quá dài." }
            val key = source.lowercase(Locale.ROOT)
            if (unique.containsKey(key)) duplicates += 1
            unique[key] = VietPhraseRule(
                id = "${kind.name.lowercase()}:${shaStable(source)}",
                source = source,
                target = target,
                kind = kind,
                matchMode = if (kind == VietPhraseDictionaryKind.LUAT_NHAN || PLACEHOLDER.containsMatchIn(source)) VietPhraseMatchMode.TEMPLATE else VietPhraseMatchMode.LITERAL,
            )
            require(unique.size <= maxRecords.coerceIn(1, MAX_RECORDS)) { "Tệp có quá nhiều mục từ." }
        }
        return DecodeResult(kind, unique.values.toList(), delimiter, duplicates)
    }

    fun encode(rules: List<VietPhraseRule>, kind: VietPhraseDictionaryKind): String = buildString {
        appendLine("# NgheTruyen VietPhrase bundle v2")
        appendLine("# kind=${kind.name}")
        rules.asSequence().filter { it.kind == kind }.sortedWith(compareByDescending<VietPhraseRule> { it.source.length }.thenByDescending { it.effectivePriority }.thenBy { it.id }).forEach { rule ->
            append(escape(rule.source)).append('=').append(escape(rule.target)).append('\n')
        }
    }

    fun detectDelimiter(text: String): String {
        val candidates = listOf("\t", "=>", "=", "||")
        val scores = candidates.associateWith { delimiter ->
            text.lineSequence().take(200).count { line ->
                val clean = line.trim()
                clean.isNotEmpty() && !clean.startsWith('#') && !clean.startsWith("//") && splitLine(line, delimiter)?.let { it.first.isNotBlank() } == true
            }
        }
        return scores.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { candidates.indexOf(it.key) })?.takeIf { it.value > 0 }?.key
            ?: throw IllegalArgumentException("Không phát hiện được dấu phân cách từ điển.")
    }

    private fun splitLine(raw: String, delimiter: String): Pair<String, String>? {
        val index = raw.indexOf(delimiter)
        if (index <= 0) return null
        return raw.substring(0, index) to raw.substring(index + delimiter.length)
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16LE"))
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16BE"))
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] != '\\' || cursor == value.lastIndex) {
                out.append(value[cursor++]); continue
            }
            when (value[cursor + 1]) {
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'n' -> out.append('\n')
                '\\' -> out.append('\\')
                else -> { out.append('\\'); out.append(value[cursor + 1]) }
            }
            cursor += 2
        }
        return out.toString()
    }

    private fun shaStable(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    private val PLACEHOLDER = Regex("\\{\\d+}")
    const val MAX_RECORDS = 500_000
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_SOURCE_CHARS = 2_000
    const val MAX_TARGET_CHARS = 4_000
}
