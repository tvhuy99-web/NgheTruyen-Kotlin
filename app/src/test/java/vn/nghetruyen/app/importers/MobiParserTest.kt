package vn.nghetruyen.app.importers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobiParserTest {
    @Test fun decodesPalmDocSpaceCompression() {
        val compressed = byteArrayOf(0xC8.toByte(), 0x65, 0x6c, 0x6c, 0x6f)
        assertEquals(" Hello", PalmDocCompression.decode(compressed, 100).toString(Charsets.UTF_8))
    }

    @Test fun parsesUncompressedPalmDocContainer() {
        val html = "<h1>Chương 1</h1><p>Xin chào thế giới.</p>".toByteArray()
        val file = palmDoc(html, compression = 1)
        val parsed = MobiParser.parse(file, "Sách thử")
        assertEquals("Sách thử", parsed.title)
        assertEquals(1, parsed.compression)
        assertTrue(parsed.text.contains("Xin chào"))
    }

    @Test fun parsesKf8OnlyTextContainer() {
        val text = "<p>Văn bản KF8 thuần.</p>".toByteArray()
        val parsed = MobiParser.parse(mobiContainer(text, compression = 1, version = 8), "KF8")
        assertEquals(8, parsed.formatVersion)
        assertTrue(parsed.text.contains("KF8 thuần"))
    }

    @Test fun decodesBoundedHuffCdicTextRecord() {
        val parsed = MobiParser.parse(huffCdicMobi(), "HUFF")
        assertEquals(17_480, parsed.compression)
        assertEquals("AAAAAAAA", parsed.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEncryptedPalmDoc() {
        val html = "Nội dung".toByteArray()
        MobiParser.parse(palmDoc(html, compression = 1, encryption = 1), "Khóa")
    }

    private fun huffCdicMobi(): ByteArray {
        val record0 = ByteArray(120)
        putU16(record0, 0, 17_480)
        putU32(record0, 4, 8)
        putU16(record0, 8, 1)
        putU16(record0, 10, 4096)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(record0, 16)
        putU32(record0, 20, 104)
        putU32(record0, 28, 65_001)
        putU32(record0, 36, 8)
        putU32(record0, 112, 2)
        putU32(record0, 116, 2)

        val text = byteArrayOf(0)
        val huff = ByteArray(16 + 256 * 4 + 32 * 8)
        "HUFF".toByteArray(Charsets.US_ASCII).copyInto(huff, 0)
        putU32(huff, 4, 24)
        putU32(huff, 8, 16)
        putU32(huff, 12, 16 + 256 * 4)
        repeat(256) { putU32(huff, 16 + it * 4, 0x81) }

        val cdic = ByteArray(21)
        "CDIC".toByteArray(Charsets.US_ASCII).copyInto(cdic, 0)
        putU32(cdic, 4, 16)
        putU32(cdic, 8, 1)
        putU32(cdic, 12, 1)
        putU16(cdic, 16, 2)
        putU16(cdic, 18, 0x8001)
        cdic[20] = 'A'.code.toByte()
        return pdb(listOf(record0, text, huff, cdic))
    }

    private fun mobiContainer(text: ByteArray, compression: Int, version: Int): ByteArray {
        val record0 = ByteArray(120)
        putU16(record0, 0, compression)
        putU32(record0, 4, text.size)
        putU16(record0, 8, 1)
        putU16(record0, 10, 4096)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(record0, 16)
        putU32(record0, 20, 104)
        putU32(record0, 28, 65_001)
        putU32(record0, 36, version)
        return pdb(listOf(record0, text))
    }

    private fun palmDoc(text: ByteArray, compression: Int, encryption: Int = 0): ByteArray {
        val record0 = ByteArray(16)
        putU16(record0, 0, compression)
        putU32(record0, 4, text.size)
        putU16(record0, 8, 1)
        putU16(record0, 10, 4096)
        putU16(record0, 12, encryption)
        return pdb(listOf(record0, text))
    }

    private fun pdb(records: List<ByteArray>): ByteArray {
        val tableEnd = 78 + records.size * 8
        val total = tableEnd + records.sumOf(ByteArray::size)
        val bytes = ByteArray(total)
        putU16(bytes, 76, records.size)
        var offset = tableEnd
        records.forEachIndexed { index, record ->
            putU32(bytes, 78 + index * 8, offset)
            record.copyInto(bytes, offset)
            offset += record.size
        }
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
