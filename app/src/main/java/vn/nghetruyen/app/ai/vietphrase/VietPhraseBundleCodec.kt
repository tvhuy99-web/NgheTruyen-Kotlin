package vn.nghetruyen.app.ai.vietphrase

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

 
object VietPhraseBundleCodec {
    data class Bundle(
        val rules: List<VietPhraseRule>,
        val importedKinds: Set<VietPhraseDictionaryKind>,
        val duplicateCount: Int,
        val warnings: List<String>,
        val dictionaryStates: List<VietPhrasePersistenceArchiveCodec.DictionaryState> = emptyList(),
        val legacyRuleOnly: Boolean = false,
    )

    fun decodeZip(bytes: ByteArray): Bundle {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Gói từ điển vượt giới hạn an toàn." }
        val rules = mutableListOf<VietPhraseRule>()
        val kinds = linkedSetOf<VietPhraseDictionaryKind>()
        val warnings = mutableListOf<String>()
        var duplicateCount = 0
        var totalUncompressed = 0L
        var entries = 0
        var archiveLoaded = false
        var archiveStates: List<VietPhrasePersistenceArchiveCodec.DictionaryState> = emptyList()
        var archiveLegacyRuleOnly = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                require(entries <= MAX_ENTRIES) { "Gói có quá nhiều tệp." }
                val safeName = safeEntryName(entry)
                if (entry.isDirectory) continue
                val content = readEntry(zip, MAX_ENTRY_BYTES)
                totalUncompressed += content.size
                require(totalUncompressed <= MAX_UNCOMPRESSED_BYTES) { "Gói giải nén vượt giới hạn an toàn." }
                if (safeName.equals(ARCHIVE_ENTRY, ignoreCase = true)) {
                    val archive = VietPhrasePersistenceArchiveCodec.decodeCompatible(content)
                    rules.clear()
                    rules += archive.rules
                    kinds.clear()
                    kinds += archive.rules.map { it.kind }
                    archiveStates = archive.dictionaryStates
                    archiveLegacyRuleOnly = archive.legacyRuleOnly
                    archiveLoaded = true
                    continue
                }
                val kind = VietPhraseDictionaryKind.fromFileName(safeName)
                if (kind == null) {
                    if (!safeName.equals("manifest.properties", ignoreCase = true)) warnings += "Bỏ qua tệp không nhận diện: $safeName"
                    continue
                }
                if (archiveLoaded) continue 
                val lower = safeName.lowercase()
                if (lower.endsWith(".dic") || lower.endsWith(".dat")) {
                    val decoded = VietPhraseBinaryDictionaryCodec.decode(content, safeName, kind)
                    rules += decoded.rules
                    kinds += kind
                    duplicateCount += decoded.duplicateCount
                    warnings += decoded.warnings
                } else {
                    val decoded = VietPhraseDictionaryCodec.decode(content, safeName, kind)
                    rules += decoded.rules
                    kinds += kind
                    duplicateCount += decoded.duplicateCount
                }
            }
        }
        require(kinds.isNotEmpty()) { "Gói không chứa từ điển được hỗ trợ." }
        return Bundle(rules, kinds, duplicateCount, warnings, archiveStates, archiveLegacyRuleOnly)
    }

    fun encodeZip(
        rules: List<VietPhraseRule>,
        dictionaryStates: List<VietPhrasePersistenceArchiveCodec.DictionaryState> = emptyList(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val activeKinds = VietPhraseDictionaryKind.entries.filter { kind -> rules.any { it.kind == kind } }
            zip.putNextEntry(ZipEntry(ARCHIVE_ENTRY))
            zip.write(VietPhrasePersistenceArchiveCodec.encode(rules, dictionaryStates))
            zip.closeEntry()
            for (kind in activeKinds) {
                zip.putNextEntry(ZipEntry(kind.fileName))
                zip.write(VietPhraseDictionaryCodec.encode(rules, kind).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.properties"))
            val manifest = buildString {
                appendLine("formatVersion=3")
                appendLine("engine=nghetruyen-vietphrase-v3")
                appendLine("kinds=${activeKinds.joinToString(",") { it.name }}")
                appendLine("dictionaryStates=${dictionaryStates.size}")
            }
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray().also { require(it.size <= MAX_ARCHIVE_BYTES) { "Gói xuất vượt giới hạn." } }
    }

    private fun safeEntryName(entry: ZipEntry): String {
        val name = entry.name.replace('\\', '/')
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "Đường dẫn tuyệt đối không hợp lệ." }
        val parts = name.split('/').filter(String::isNotBlank)
        require(parts.none { it == ".." || it == "." }) { "Phát hiện path traversal trong gói." }
        require(parts.size <= 8) { "Đường dẫn trong gói quá sâu." }
        return parts.joinToString("/")
    }

    private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Tệp trong gói vượt giới hạn." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun drain(zip: ZipInputStream, limit: Int): Int = readEntry(zip, limit).size

    const val ARCHIVE_ENTRY = "nghetruyen-rules.vpa"
    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 96 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024
}
