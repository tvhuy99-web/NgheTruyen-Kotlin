package vn.nghetruyen.app.ai.vietphrase

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


object VietPhraseArchiveCodec {
    data class Archive(val rules: List<VietPhraseRule>, val checksum: String)

    fun encode(rules: List<VietPhraseRule>): ByteArray {
        require(rules.size <= MAX_RULES) { "Snapshot VietPhrase có quá nhiều quy tắc." }
        val stable = rules.sortedWith(
            compareBy<VietPhraseRule> { it.kind.name }
                .thenBy { it.scope.name }
                .thenBy { it.storyId.orEmpty() }
                .thenBy { it.source }
                .thenBy { it.id },
        )
        val body = buildString {
            appendLine("NGHETRUYEN_VIETPHRASE_ARCHIVE\t3")
            stable.forEach { rule ->
                append(
                    listOf(
                        rule.id,
                        rule.source,
                        rule.target,
                        rule.kind.name,
                        rule.priority.toString(),
                        if (rule.enabled) "1" else "0",
                        rule.scope.name,
                        rule.storyId.orEmpty(),
                        rule.matchMode.name,
                        if (rule.ignoreCase) "1" else "0",
                        rule.updatedAt.toString(),
                    ).joinToString("\t", transform = ::encodeField),
                ).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_UNCOMPRESSED_BYTES) { "Snapshot VietPhrase vượt giới hạn." }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(body) }
        return output.toByteArray().also { require(it.size <= MAX_COMPRESSED_BYTES) { "Snapshot VietPhrase nén vượt giới hạn." } }
    }

    fun decode(bytes: ByteArray): Archive {
        require(bytes.size <= MAX_COMPRESSED_BYTES) { "Snapshot VietPhrase vượt giới hạn." }
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_UNCOMPRESSED_BYTES) { "Snapshot VietPhrase giải nén vượt giới hạn." }
                output.write(buffer, 0, count)
            }
        }
        val text = output.toString(Charsets.UTF_8.name())
        val lines = text.lineSequence().toList()
        require(lines.firstOrNull() == "NGHETRUYEN_VIETPHRASE_ARCHIVE\t3") { "Snapshot VietPhrase sai phiên bản." }
        val rules = lines.drop(1).filter(String::isNotBlank).mapIndexed { index, line ->
            val fields = line.split('\t').map(::decodeField)
            require(fields.size == 11) { "Snapshot VietPhrase lỗi tại dòng ${index + 2}." }
            VietPhraseRule(
                id = fields[0],
                source = fields[1],
                target = fields[2],
                kind = VietPhraseDictionaryKind.valueOf(fields[3]),
                priority = fields[4].toInt(),
                enabled = fields[5] == "1",
                scope = VietPhraseScope.valueOf(fields[6]),
                storyId = fields[7].ifBlank { null },
                matchMode = VietPhraseMatchMode.valueOf(fields[8]),
                ignoreCase = fields[9] == "1",
                updatedAt = fields[10].toLong(),
            )
        }
        require(rules.size <= MAX_RULES) { "Snapshot VietPhrase có quá nhiều quy tắc." }
        return Archive(rules, checksum(rules))
    }

    fun checksum(rules: List<VietPhraseRule>): String = sha256(encode(rules))
    fun checksumBytes(bytes: ByteArray): String = sha256(bytes)

    private fun encodeField(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeField(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    const val MAX_RULES = 1_000_000
    const val MAX_COMPRESSED_BYTES = 128 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
}
