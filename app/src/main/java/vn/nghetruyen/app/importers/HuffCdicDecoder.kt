package vn.nghetruyen.app.importers

import java.io.ByteArrayOutputStream

/**
 * Bounded MOBI HUFF/CDIC decoder.
 *
 * The table layout and decoding procedure follow the public libmobi implementation,
 * rewritten in Kotlin with explicit bounds, recursion, and output limits.
 */
internal object HuffCdicDecoder {
    private const val MASK_32 = 0xffff_ffffL
    private const val HUFF_HEADER_BYTES = 16
    private const val CDIC_HEADER_BYTES = 16
    private const val CODE_TABLE_SIZE = 33
    private const val MAX_DEPTH = 20
    private const val MAX_CDIC_RECORDS = 64
    private const val MAX_CODE_LENGTH = 16

    private data class Dictionary(
        val table1: LongArray,
        val minCode: LongArray,
        val maxCode: LongArray,
        val codeLength: Int,
        val indexCount: Int,
        val symbolOffsets: IntArray,
        val symbolBlocks: List<ByteArray>,
    )

    fun decodeRecords(
        huffRecord: ByteArray,
        cdicRecords: List<ByteArray>,
        compressedRecords: List<ByteArray>,
        maxOutputBytes: Int,
    ): ByteArray {
        require(maxOutputBytes > 0) { "Giới hạn HUFF/CDIC không hợp lệ." }
        val dictionary = parseDictionary(huffRecord, cdicRecords)
        val output = ByteArrayOutputStream(minOf(maxOutputBytes, 256 * 1024))
        compressedRecords.forEach { record ->
            decodeInto(record, dictionary, output, maxOutputBytes, depth = 0)
        }
        return output.toByteArray()
    }

    private fun parseDictionary(huffRecord: ByteArray, cdicRecords: List<ByteArray>): Dictionary {
        require(cdicRecords.isNotEmpty() && cdicRecords.size <= MAX_CDIC_RECORDS) {
            "Số record CDIC không hợp lệ."
        }
        require(ascii(huffRecord, 0, 4) == "HUFF") { "Không tìm thấy HUFF record." }
        require(huffRecord.size >= HUFF_HEADER_BYTES) { "HUFF record quá ngắn." }
        val headerLength = u32(huffRecord, 4)
        val data1Offset = u32(huffRecord, 8)
        val data2Offset = u32(huffRecord, 12)
        require(headerLength >= HUFF_HEADER_BYTES) { "HUFF header không hợp lệ." }
        require(data1Offset >= HUFF_HEADER_BYTES && data1Offset + 256 * 4 <= huffRecord.size) {
            "Bảng HUFF data1 bị cắt."
        }
        require(data2Offset >= HUFF_HEADER_BYTES && data2Offset + 32 * 8 <= huffRecord.size) {
            "Bảng HUFF data2 bị cắt."
        }

        val table1 = LongArray(256) { index -> u32Long(huffRecord, data1Offset + index * 4) }
        val minCode = LongArray(CODE_TABLE_SIZE)
        val maxCode = LongArray(CODE_TABLE_SIZE)
        minCode[0] = 0L
        maxCode[0] = MASK_32
        for (length in 1 until CODE_TABLE_SIZE) {
            val pairOffset = data2Offset + (length - 1) * 8
            val min = u32Long(huffRecord, pairOffset)
            val max = u32Long(huffRecord, pairOffset + 4)
            val shift = 32 - length
            minCode[length] = (min shl shift) and MASK_32
            maxCode[length] = (((max + 1L) shl shift) - 1L) and MASK_32
        }

        var globalIndexCount = -1
        var codeLength = -1
        var indexRead = 0
        var symbolOffsets: IntArray? = null
        val symbolBlocks = ArrayList<ByteArray>(cdicRecords.size)
        cdicRecords.forEach { record ->
            require(record.size >= CDIC_HEADER_BYTES && ascii(record, 0, 4) == "CDIC") {
                "CDIC record không hợp lệ."
            }
            val header = u32(record, 4)
            val indexCount = u32(record, 8)
            val currentCodeLength = u32(record, 12)
            require(header >= CDIC_HEADER_BYTES) { "CDIC header không hợp lệ." }
            require(indexCount in 1..(1 shl MAX_CODE_LENGTH) * MAX_CDIC_RECORDS) {
                "CDIC index count vượt giới hạn."
            }
            require(currentCodeLength in 1..MAX_CODE_LENGTH) { "CDIC code length không hợp lệ." }
            if (globalIndexCount < 0) {
                globalIndexCount = indexCount
                codeLength = currentCodeLength
                symbolOffsets = IntArray(indexCount)
            } else {
                require(globalIndexCount == indexCount && codeLength == currentCodeLength) {
                    "Các CDIC record không cùng cấu hình."
                }
            }
            val remaining = globalIndexCount - indexRead
            val countInRecord = minOf(remaining, 1 shl codeLength)
            require(CDIC_HEADER_BYTES + countInRecord * 2 <= record.size) { "Bảng offset CDIC bị cắt." }
            val base = record.copyOfRange(CDIC_HEADER_BYTES, record.size)
            repeat(countInRecord) { localIndex ->
                val offset = u16(record, CDIC_HEADER_BYTES + localIndex * 2)
                require(offset + 2 <= base.size) { "Offset biểu tượng CDIC vượt record." }
                val length = u16(base, offset) and 0x7fff
                require(offset + 2 + length <= base.size) { "Biểu tượng CDIC bị cắt." }
                symbolOffsets!![indexRead++] = offset
            }
            symbolBlocks += base
        }
        require(globalIndexCount > 0 && indexRead == globalIndexCount) {
            "Không đọc đủ bảng biểu tượng CDIC."
        }
        return Dictionary(
            table1 = table1,
            minCode = minCode,
            maxCode = maxCode,
            codeLength = codeLength,
            indexCount = globalIndexCount,
            symbolOffsets = symbolOffsets!!,
            symbolBlocks = symbolBlocks,
        )
    }

    private fun decodeInto(
        input: ByteArray,
        dictionary: Dictionary,
        output: ByteArrayOutputStream,
        maxOutputBytes: Int,
        depth: Int,
    ) {
        require(depth <= MAX_DEPTH) { "HUFF/CDIC lồng quá sâu." }
        var bitPosition = 0
        val totalBits = input.size * 8
        while (bitPosition < totalBits) {
            val code = peek32(input, bitPosition)
            val t1 = dictionary.table1[(code ushr 24).toInt() and 0xff]
            var codeLength = (t1 and 0x1f).toInt()
            require(codeLength in 1..32) { "HUFF code length không hợp lệ." }
            var maxCode = ((((t1 ushr 8) + 1L) shl (32 - codeLength)) - 1L) and MASK_32
            if ((t1 and 0x80L) == 0L) {
                while (codeLength < 32 && unsignedLess(code, dictionary.minCode[codeLength])) {
                    codeLength += 1
                }
                require(codeLength < CODE_TABLE_SIZE) { "HUFF code không ánh xạ được." }
                maxCode = dictionary.maxCode[codeLength]
            }
            if (bitPosition + codeLength > totalBits) break
            bitPosition += codeLength
            val shift = 32 - codeLength
            val index = (((maxCode - code) and MASK_32) ushr shift).toInt()
            require(index in 0 until dictionary.indexCount) { "HUFF symbol index vượt bảng." }
            val blockIndex = index ushr dictionary.codeLength
            require(blockIndex in dictionary.symbolBlocks.indices) { "HUFF trỏ tới CDIC record không tồn tại." }
            val block = dictionary.symbolBlocks[blockIndex]
            val symbolOffset = dictionary.symbolOffsets[index]
            val rawLength = u16(block, symbolOffset)
            val isDecoded = rawLength and 0x8000 != 0
            val length = rawLength and 0x7fff
            val payloadStart = symbolOffset + 2
            require(payloadStart + length <= block.size) { "HUFF symbol bị cắt." }
            val payload = block.copyOfRange(payloadStart, payloadStart + length)
            if (isDecoded) {
                appendBounded(output, payload, maxOutputBytes)
            } else {
                decodeInto(payload, dictionary, output, maxOutputBytes, depth + 1)
            }
        }
    }

    private fun appendBounded(output: ByteArrayOutputStream, bytes: ByteArray, maxOutputBytes: Int) {
        require(output.size() + bytes.size <= maxOutputBytes) { "Dữ liệu HUFF/CDIC vượt giới hạn an toàn." }
        output.write(bytes)
    }

    private fun peek32(bytes: ByteArray, bitPosition: Int): Long {
        var value = 0L
        repeat(32) { offset ->
            value = value shl 1
            val absoluteBit = bitPosition + offset
            if (absoluteBit < bytes.size * 8) {
                val byteValue = bytes[absoluteBit / 8].toInt() and 0xff
                value = value or ((byteValue ushr (7 - absoluteBit % 8)) and 1).toLong()
            }
        }
        return value and MASK_32
    }

    private fun unsignedLess(left: Long, right: Long): Boolean =
        java.lang.Long.compareUnsigned(left and MASK_32, right and MASK_32) < 0

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String {
        require(offset >= 0 && offset + count <= bytes.size) { "Thiếu magic HUFF/CDIC." }
        return bytes.copyOfRange(offset, offset + count).toString(Charsets.US_ASCII)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "Thiếu số 16-bit HUFF/CDIC." }
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        val value = u32Long(bytes, offset)
        require(value <= Int.MAX_VALUE) { "Giá trị HUFF/CDIC vượt giới hạn." }
        return value.toInt()
    }

    private fun u32Long(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Thiếu số 32-bit HUFF/CDIC." }
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }
}
