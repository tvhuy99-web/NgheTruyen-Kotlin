package vn.nghetruyen.app.ai.vietphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VietPhraseBinaryDictionaryCodecTest {
    @Test
    fun decodesGroupedDotNetDic() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(2)
            listOf("天道", "叶凡", "Thiên Đạo", "Diệp Phàm").forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                write7Bit(stream, bytes.size)
                stream.write(bytes)
            }
        }
        val result = VietPhraseBinaryDictionaryCodec.decode(output.toByteArray(), "Names.dic")
        assertEquals(VietPhraseBinaryDictionaryCodec.Format.DIC_DOTNET_UTF8_GROUPED, result.format)
        assertEquals(listOf("天道", "叶凡"), result.rules.map { it.source })
    }

    @Test
    fun decodesCompiledDat() {
        val nodeCount = 22_828
        val childIndex = 22_827
        val base = IntArray(nodeCount)
        val check = IntArray(nodeCount)
        base[0] = 1
        base[childIndex] = 2
        check[childIndex] = 1
        base[2] = -1
        check[2] = 2
        val values = "thiên\n".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + nodeCount * 4 + 4 + nodeCount * 4 + 4 + values.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(nodeCount)
        base.forEach(buffer::putInt)
        buffer.putInt(nodeCount)
        check.forEach(buffer::putInt)
        buffer.putInt(1)
        buffer.put(values)
        val result = VietPhraseBinaryDictionaryCodec.decode(buffer.array(), "HV.dat")
        assertEquals(VietPhraseBinaryDictionaryCodec.Format.DAT_DOUBLE_ARRAY_TRIE, result.format)
        assertEquals("天", result.rules.single().source)
        assertEquals("thiên", result.rules.single().target)
    }

    @Test
    fun rejectsTrailingBinaryPayload() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(1)
            stream.writeUTF("天")
            stream.writeUTF("thiên")
            repeat(70) { stream.writeByte(7) }
        }
        assertTrue(runCatching { VietPhraseBinaryDictionaryCodec.decode(output.toByteArray(), "HV.dic") }.isFailure)
    }

    private fun write7Bit(stream: DataOutputStream, number: Int) {
        var value = number
        while (value >= 0x80) {
            stream.writeByte((value and 0x7F) or 0x80)
            value = value ushr 7
        }
        stream.writeByte(value)
    }
}
