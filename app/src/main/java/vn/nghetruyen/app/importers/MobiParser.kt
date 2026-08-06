package vn.nghetruyen.app.importers

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Bounded parser for unencrypted PalmDOC/MOBI containers.
 *
 * It supports raw PalmDOC, PalmDOC LZ77, and bounded HUFF/CDIC text records.
 * DRM-encrypted books are rejected explicitly instead of returning corrupted
 * text. MOBI 8 / KF8-only text containers are accepted when their text records
 * use one of the supported compression modes.
 */
object MobiParser {
    data class Document(
        val title: String,
        val text: String,
        val encoding: String,
        val compression: Int,
        val formatVersion: Int,
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): Document {
        require(bytes.size >= PDB_HEADER_BYTES) { "Tệp PalmDOC/MOBI quá ngắn." }
        val recordCount = u16(bytes, 76)
        require(recordCount in 2..MAX_RECORDS) { "Số record PalmDOC/MOBI không hợp lệ: $recordCount" }
        val recordTableEnd = PDB_HEADER_BYTES + recordCount * RECORD_INFO_BYTES
        require(recordTableEnd <= bytes.size) { "Bảng record PalmDOC/MOBI bị cắt." }

        val offsets = IntArray(recordCount + 1)
        for (index in 0 until recordCount) {
            val offset = u32(bytes, PDB_HEADER_BYTES + index * RECORD_INFO_BYTES)
            require(offset in recordTableEnd..bytes.size) { "Offset record $index không hợp lệ." }
            if (index > 0) require(offset >= offsets[index - 1]) { "Record PalmDOC/MOBI không đúng thứ tự." }
            offsets[index] = offset
        }
        offsets[recordCount] = bytes.size

        val record0 = slice(bytes, offsets[0], offsets[1])
        require(record0.size >= PALMDOC_HEADER_BYTES) { "Thiếu PalmDOC header." }
        val compression = u16(record0, 0)
        val declaredTextLength = u32(record0, 4)
        val textRecordCount = u16(record0, 8)
        val encryptionType = u16(record0, 12)
        require(encryptionType == 0) { "Sách MOBI/AZW có DRM hoặc mã hóa, không thể nhập an toàn." }
        require(textRecordCount in 1 until recordCount) { "Số record văn bản không hợp lệ." }
        require(declaredTextLength in 1..MAX_TEXT_BYTES) { "Nội dung MOBI vượt giới hạn an toàn." }

        val mobiStart = if (record0.size >= PALMDOC_HEADER_BYTES + 4 && ascii(record0, PALMDOC_HEADER_BYTES, 4) == "MOBI") {
            PALMDOC_HEADER_BYTES
        } else {
            -1
        }
        val declaredCharset = if (mobiStart >= 0 && record0.size >= mobiStart + 16) {
            when (u32(record0, mobiStart + 12)) {
                65001 -> Charsets.UTF_8
                65002 -> Charsets.UTF_16
                1252 -> Charset.forName("windows-1252")
                else -> Charsets.UTF_8
            }
        } else {
            null
        }
        val title = readTitle(record0, mobiStart, declaredCharset ?: Charsets.UTF_8).ifBlank { fallbackTitle }
        val formatVersion = if (mobiStart >= 0 && record0.size >= mobiStart + 24) u32(record0, mobiStart + 20) else 0

        val output = ArrayList<Byte>(declaredTextLength.coerceAtMost(64 * 1024))
        if (compression == 17480) {
            require(mobiStart >= 0 && record0.size >= mobiStart + 104) { "MOBI HUFF/CDIC thiếu metadata." }
            val huffRecordIndex = u32(record0, mobiStart + 96)
            val huffRecordCount = u32(record0, mobiStart + 100)
            require(huffRecordCount in 2..64) { "Số record HUFF/CDIC không hợp lệ." }
            require(huffRecordIndex >= 1 && huffRecordIndex + huffRecordCount <= recordCount) {
                "Vùng HUFF/CDIC vượt bảng record."
            }
            val compressedRecords = (1..textRecordCount).map { index ->
                slice(bytes, offsets[index], offsets[index + 1])
            }
            val huffRecord = slice(bytes, offsets[huffRecordIndex], offsets[huffRecordIndex + 1])
            val cdicRecords = (huffRecordIndex + 1 until huffRecordIndex + huffRecordCount).map { index ->
                slice(bytes, offsets[index], offsets[index + 1])
            }
            HuffCdicDecoder.decodeRecords(
                huffRecord = huffRecord,
                cdicRecords = cdicRecords,
                compressedRecords = compressedRecords,
                maxOutputBytes = declaredTextLength.coerceAtMost(MAX_TEXT_BYTES),
            ).take(declaredTextLength).forEach(output::add)
        } else {
            for (index in 1..textRecordCount) {
                val record = slice(bytes, offsets[index], offsets[index + 1])
                val decoded = when (compression) {
                    1 -> record
                    2 -> PalmDocCompression.decode(record, MAX_TEXT_BYTES - output.size)
                    else -> throw UnsupportedOperationException("Kiểu nén PalmDOC không hỗ trợ: $compression")
                }
                val remaining = declaredTextLength - output.size
                if (remaining <= 0) break
                decoded.take(remaining).forEach(output::add)
                require(output.size <= MAX_TEXT_BYTES) { "Nội dung MOBI vượt giới hạn an toàn." }
            }
        }
        require(output.isNotEmpty()) { "MOBI không chứa văn bản có thể đọc." }
        val exact = output.take(declaredTextLength.coerceAtMost(output.size)).toByteArray()
        val charset = declaredCharset ?: detectPalmDocCharset(exact)
        val text = exact.toString(charset)
            .replace('\u0000', ' ')
            .trim()
        require(text.isNotBlank()) { "MOBI không chứa văn bản có thể đọc." }
        return Document(title, text, charset.name(), compression, formatVersion)
    }


    private fun detectPalmDocCharset(bytes: ByteArray): Charset = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        Charsets.UTF_8
    } catch (_: CharacterCodingException) {
        Charset.forName("windows-1252")
    }

    private fun readTitle(record0: ByteArray, mobiStart: Int, charset: Charset): String {
        if (mobiStart < 0 || record0.size < mobiStart + 76) return ""
        val offset = u32(record0, mobiStart + 68)
        val length = u32(record0, mobiStart + 72)
        if (length <= 0 || length > MAX_TITLE_BYTES) return ""
        if (offset < 0 || offset + length > record0.size) return ""
        return record0.copyOfRange(offset, offset + length)
            .toString(charset)
            .replace('\u0000', ' ')
            .trim()
    }

    private fun slice(bytes: ByteArray, start: Int, end: Int): ByteArray {
        require(start in 0..end && end <= bytes.size) { "Phạm vi record không hợp lệ." }
        return bytes.copyOfRange(start, end)
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        bytes.copyOfRange(offset, offset + count).toString(Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "Thiếu dữ liệu số 16-bit." }
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Thiếu dữ liệu số 32-bit." }
        val value = ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
        require(value <= Int.MAX_VALUE) { "Giá trị MOBI vượt giới hạn." }
        return value.toInt()
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }

    private const val PDB_HEADER_BYTES = 78
    private const val RECORD_INFO_BYTES = 8
    private const val PALMDOC_HEADER_BYTES = 16
    private const val MAX_RECORDS = 65_535
    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024
    private const val MAX_TITLE_BYTES = 4 * 1024
}

object PalmDocCompression {
    fun decode(input: ByteArray, maxOutputBytes: Int): ByteArray {
        require(maxOutputBytes >= 0) { "Giới hạn giải nén không hợp lệ." }
        val output = ArrayList<Byte>(minOf(input.size * 2, maxOutputBytes))
        var cursor = 0
        while (cursor < input.size) {
            val current = input[cursor++].toInt() and 0xff
            when {
                current == 0 -> append(output, 0, maxOutputBytes)
                current in 1..8 -> {
                    require(cursor + current <= input.size) { "PalmDOC literal run bị cắt." }
                    repeat(current) { append(output, input[cursor++].toInt() and 0xff, maxOutputBytes) }
                }
                current in 0x09..0x7f -> append(output, current, maxOutputBytes)
                current in 0x80..0xbf -> {
                    require(cursor < input.size) { "PalmDOC back-reference bị cắt." }
                    val next = input[cursor++].toInt() and 0xff
                    val pair = (current shl 8) or next
                    val distance = (pair and 0x3fff) shr 3
                    val length = (pair and 0x7) + 3
                    require(distance in 1..output.size) { "PalmDOC back-reference không hợp lệ." }
                    repeat(length) {
                        val value = output[output.size - distance].toInt() and 0xff
                        append(output, value, maxOutputBytes)
                    }
                }
                else -> {
                    append(output, 0x20, maxOutputBytes)
                    append(output, current xor 0x80, maxOutputBytes)
                }
            }
        }
        return ByteArray(output.size) { output[it] }
    }

    private fun append(output: MutableList<Byte>, value: Int, maxOutputBytes: Int) {
        require(output.size < maxOutputBytes) { "Dữ liệu PalmDOC giải nén vượt giới hạn an toàn." }
        output += value.toByte()
    }
}
