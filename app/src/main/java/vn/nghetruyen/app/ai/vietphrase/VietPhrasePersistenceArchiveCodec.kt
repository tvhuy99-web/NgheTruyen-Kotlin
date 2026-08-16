package vn.nghetruyen.app.ai.vietphrase

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

 
object VietPhrasePersistenceArchiveCodec {
    data class DictionaryState(
        val id: String,
        val kind: VietPhraseDictionaryKind,
        val scope: VietPhraseScope,
        val storyId: String?,
        val enabled: Boolean,
        val sourceName: String,
        val sourceFormat: String,
        val checksum: String,
        val entryCount: Int,
        val revision: Long,
        val importedAt: Long,
    )

    data class Archive(
        val rules: List<VietPhraseRule>,
        val dictionaryStates: List<DictionaryState>,
        val legacyRuleOnly: Boolean = false,
    )

    fun encode(rules: List<VietPhraseRule>, dictionaryStates: List<DictionaryState>): ByteArray {
        require(rules.size <= VietPhraseArchiveCodec.MAX_RULES) { "Snapshot có quá nhiều quy tắc." }
        require(dictionaryStates.size <= MAX_DICTIONARY_STATES) { "Snapshot có quá nhiều trạng thái từ điển." }
        val text = buildString {
            appendLine(HEADER)
            rules.sortedWith(ruleOrder).forEach { rule ->
                append("R\t")
                appendLine(listOf(
                    rule.id, rule.source, rule.target, rule.kind.name, rule.priority.toString(),
                    if (rule.enabled) "1" else "0", rule.scope.name, rule.storyId.orEmpty(),
                    rule.matchMode.name, if (rule.ignoreCase) "1" else "0", rule.updatedAt.toString(),
                ).joinToString("\t", transform = ::encodeField))
            }
            dictionaryStates.sortedWith(stateOrder).forEach { state ->
                append("S\t")
                appendLine(listOf(
                    state.id, state.kind.name, state.scope.name, state.storyId.orEmpty(),
                    if (state.enabled) "1" else "0", state.sourceName, state.sourceFormat,
                    state.checksum, state.entryCount.toString(), state.revision.toString(),
                    state.importedAt.toString(),
                ).joinToString("\t", transform = ::encodeField))
            }
        }.toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_UNCOMPRESSED_BYTES) { "Snapshot VietPhrase vượt giới hạn." }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text) }
        return output.toByteArray().also { require(it.size <= MAX_COMPRESSED_BYTES) { "Snapshot nén vượt giới hạn." } }
    }

    fun decode(bytes: ByteArray): Archive {
        require(bytes.size <= MAX_COMPRESSED_BYTES) { "Snapshot VietPhrase vượt giới hạn." }
        val uncompressed = inflate(bytes)
        val lines = uncompressed.toString(Charsets.UTF_8).lineSequence().toList()
        require(lines.firstOrNull() == HEADER) { "Snapshot persistence sai phiên bản." }
        val rules = mutableListOf<VietPhraseRule>()
        val states = mutableListOf<DictionaryState>()
        lines.drop(1).filter(String::isNotBlank).forEachIndexed { index, line ->
            val type = line.substringBefore('\t')
            val fields = line.substringAfter('\t', "").split('\t').map(::decodeField)
            when (type) {
                "R" -> {
                    require(fields.size == 11) { "Rule snapshot lỗi tại dòng ${index + 2}." }
                    rules += VietPhraseRule(
                        id = fields[0], source = fields[1], target = fields[2],
                        kind = VietPhraseDictionaryKind.valueOf(fields[3]), priority = fields[4].toInt(),
                        enabled = fields[5] == "1", scope = VietPhraseScope.valueOf(fields[6]),
                        storyId = fields[7].ifBlank { null }, matchMode = VietPhraseMatchMode.valueOf(fields[8]),
                        ignoreCase = fields[9] == "1", updatedAt = fields[10].toLong(),
                    )
                }
                "S" -> {
                    require(fields.size == 11) { "Dictionary state snapshot lỗi tại dòng ${index + 2}." }
                    states += DictionaryState(
                        id = fields[0], kind = VietPhraseDictionaryKind.valueOf(fields[1]),
                        scope = VietPhraseScope.valueOf(fields[2]), storyId = fields[3].ifBlank { null },
                        enabled = fields[4] == "1", sourceName = fields[5], sourceFormat = fields[6],
                        checksum = fields[7], entryCount = fields[8].toInt(), revision = fields[9].toLong(),
                        importedAt = fields[10].toLong(),
                    )
                }
                else -> error("Loại bản ghi snapshot không hỗ trợ: $type")
            }
        }
        require(rules.size <= VietPhraseArchiveCodec.MAX_RULES) { "Snapshot có quá nhiều quy tắc." }
        require(states.size <= MAX_DICTIONARY_STATES) { "Snapshot có quá nhiều trạng thái từ điển." }
        return Archive(rules, states)
    }

     
    fun decodeCompatible(bytes: ByteArray): Archive = runCatching { decode(bytes) }.getOrElse {
        Archive(VietPhraseArchiveCodec.decode(bytes).rules, emptyList(), legacyRuleOnly = true)
    }

    fun checksumBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun inflate(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_UNCOMPRESSED_BYTES) { "Snapshot giải nén vượt giới hạn." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun encodeField(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeField(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private val ruleOrder = compareBy<VietPhraseRule> { it.kind.name }
        .thenBy { it.scope.name }.thenBy { it.storyId.orEmpty() }.thenBy { it.source }.thenBy { it.id }
    private val stateOrder = compareBy<DictionaryState> { it.kind.name }
        .thenBy { it.scope.name }.thenBy { it.storyId.orEmpty() }.thenBy { it.id }

    private const val HEADER = "NGHETRUYEN_VIETPHRASE_PERSISTENCE\t1"
    const val MAX_DICTIONARY_STATES = 10_000
    const val MAX_COMPRESSED_BYTES = 128 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
}
