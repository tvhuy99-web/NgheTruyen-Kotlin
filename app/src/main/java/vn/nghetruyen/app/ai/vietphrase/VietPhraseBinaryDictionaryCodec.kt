package vn.nghetruyen.app.ai.vietphrase

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.ArrayDeque












object VietPhraseBinaryDictionaryCodec {
    enum class Format {
        DIC_LINES,
        DIC_JAVA_UTF_GROUPED,
        DIC_JAVA_UTF_PAIRED,
        DIC_DOTNET_UTF8_GROUPED,
        DIC_DOTNET_UTF8_PAIRED,
        DIC_U32_BE_UTF8_GROUPED,
        DIC_U32_BE_UTF8_PAIRED,
        DIC_U32_LE_UTF8_GROUPED,
        DIC_U32_LE_UTF8_PAIRED,
        DAT_DOUBLE_ARRAY_TRIE,
    }

    data class DecodeResult(
        val kind: VietPhraseDictionaryKind,
        val rules: List<VietPhraseRule>,
        val format: Format,
        val skippedCount: Int,
        val duplicateCount: Int,
        val warnings: List<String> = emptyList(),
    )

    private enum class Codec { JAVA_UTF, DOTNET_UTF8, U32_BE_UTF8, U32_LE_UTF8 }
    private enum class Layout { GROUPED, PAIRED }
    private data class Candidate(val codec: Codec, val layout: Layout, val score: Int)
    private data class Header(val count: Int, val byteOrder: ByteOrder)

    fun decode(
        bytes: ByteArray,
        fileName: String,
        expectedKind: VietPhraseDictionaryKind? = null,
        maxRecords: Int = MAX_RECORDS,
    ): DecodeResult {
        require(bytes.isNotEmpty()) { "Tệp DIC/DAT rỗng." }
        require(bytes.size <= MAX_FILE_BYTES) { "Tệp DIC/DAT vượt giới hạn an toàn." }
        val kind = expectedKind ?: VietPhraseDictionaryKind.fromFileName(fileName)
            ?: throw IllegalArgumentException("Không xác định được loại từ điển từ tên tệp: $fileName")
        val headers = readHeaders(bytes, maxRecords)
        require(headers.isNotEmpty()) { "Header DIC/DAT không hợp lệ." }

        val errors = mutableListOf<String>()
        for (header in headers) {
            runCatching { decodeDat(bytes, header, kind) }
                .onSuccess { if (it != null) return it }
                .onFailure { errors += "DAT/${header.byteOrder}: ${it.message}" }
            runCatching { decodeLineDic(bytes, header, kind) }
                .onSuccess { if (it != null) return it }
                .onFailure { errors += "LINES/${header.byteOrder}: ${it.message}" }
            val candidates = probeBinaryCandidates(bytes, header)
            for (candidate in candidates) {
                runCatching { decodeBinaryDic(bytes, header, kind, candidate) }
                    .onSuccess { return it }
                    .onFailure { errors += "${candidate.codec}/${candidate.layout}/${header.byteOrder}: ${it.message}" }
            }
        }
        throw IllegalArgumentException(
            "Không nhận dạng được DIC/DAT. " + errors.take(12).joinToString(" | "),
        )
    }

    private fun readHeaders(bytes: ByteArray, maxRecords: Int): List<Header> {
        if (bytes.size < 4) return emptyList()
        val limit = maxRecords.coerceIn(1, MAX_RECORDS)
        val be = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val le = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val headers = mutableListOf<Header>()
        fun addHeader(count: Int, order: ByteOrder) {
            if (count in 1..limit && bytes.size >= 4 + count * 2L && headers.none { header -> header.count == count && header.byteOrder == order }) {
                headers += Header(count, order)
            }
        }
        addHeader(be, ByteOrder.BIG_ENDIAN)
        addHeader(le, ByteOrder.LITTLE_ENDIAN)
        return headers.sortedWith(compareBy<Header> { if (it.byteOrder == ByteOrder.BIG_ENDIAN) 0 else 1 }.thenBy { it.count })
    }

    private fun decodeLineDic(bytes: ByteArray, header: Header, kind: VietPhraseDictionaryKind): DecodeResult? {
        val body = bytes.copyOfRange(4, bytes.size)
        if (body.any { it == 0.toByte() }) return null
        val text = decodeStrictUtf8(body) ?: return null
        val lines = text.removePrefix("\uFEFF").lineSequence().toMutableList()
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        if (lines.size != header.count * 2) return null
        val keys = lines.take(header.count)
        if (!plausibleTerms(keys)) return null
        return buildRules(kind, keys, lines.drop(header.count), Format.DIC_LINES)
    }

    private fun probeBinaryCandidates(bytes: ByteArray, header: Header): List<Candidate> = Codec.entries.mapNotNull { codec ->
        runCatching {
            val input = DataInputStream(BufferedInputStream(ByteArrayInputStream(bytes, 4, bytes.size - 4)))
            val wanted = minOf(32, header.count * 2)
            val samples = ArrayList<String>(wanted)
            repeat(wanted) { samples += readString(input, codec) }
            require(samples.all(::plausibleString)) { "Chuỗi probe chứa ký tự điều khiển." }
            val layout = guessLayout(samples)
            val nonEmpty = samples.count(String::isNotEmpty)
            val han = samples.count(::containsHan)
            Candidate(codec, layout, nonEmpty + han * 2)
        }.getOrNull()
    }.sortedByDescending(Candidate::score)

    private fun decodeBinaryDic(
        bytes: ByteArray,
        header: Header,
        kind: VietPhraseDictionaryKind,
        candidate: Candidate,
    ): DecodeResult {
        val input = DataInputStream(BufferedInputStream(ByteArrayInputStream(bytes, 4, bytes.size - 4)))
        val keys = ArrayList<String>(header.count)
        val values = ArrayList<String>(header.count)
        when (candidate.layout) {
            Layout.PAIRED -> repeat(header.count) {
                keys += readString(input, candidate.codec)
                values += readString(input, candidate.codec)
            }
            Layout.GROUPED -> {
                repeat(header.count) { keys += readString(input, candidate.codec) }
                repeat(header.count) { values += readString(input, candidate.codec) }
            }
        }
        require(plausibleTerms(keys)) { "Bố cục DIC không hợp lý hoặc khóa không phải văn bản Hán." }
        val trailing = input.readBytes()
        require(trailing.size < 64 && trailing.all { it == 0.toByte() || it == 9.toByte() || it == 10.toByte() || it == 13.toByte() || it == 32.toByte() }) {
            "DIC còn dữ liệu dư; codec hoặc layout không đúng."
        }
        val format = when (candidate.codec to candidate.layout) {
            Codec.JAVA_UTF to Layout.GROUPED -> Format.DIC_JAVA_UTF_GROUPED
            Codec.JAVA_UTF to Layout.PAIRED -> Format.DIC_JAVA_UTF_PAIRED
            Codec.DOTNET_UTF8 to Layout.GROUPED -> Format.DIC_DOTNET_UTF8_GROUPED
            Codec.DOTNET_UTF8 to Layout.PAIRED -> Format.DIC_DOTNET_UTF8_PAIRED
            Codec.U32_BE_UTF8 to Layout.GROUPED -> Format.DIC_U32_BE_UTF8_GROUPED
            Codec.U32_BE_UTF8 to Layout.PAIRED -> Format.DIC_U32_BE_UTF8_PAIRED
            Codec.U32_LE_UTF8 to Layout.GROUPED -> Format.DIC_U32_LE_UTF8_GROUPED
            Codec.U32_LE_UTF8 to Layout.PAIRED -> Format.DIC_U32_LE_UTF8_PAIRED
            else -> error("Codec/layout DIC không hỗ trợ.")
        }
        return buildRules(kind, keys, values, format)
    }

     
    private fun decodeDat(bytes: ByteArray, header: Header, kind: VietPhraseDictionaryKind): DecodeResult? {
        if (header.byteOrder != ByteOrder.BIG_ENDIAN) return null
        val nodeCount = header.count
        val secondCountOffset = 4L + nodeCount * 4L
        val checkStart = secondCountOffset + 4L
        val valueCountOffset = checkStart + nodeCount * 4L
        val valuesStart = valueCountOffset + 4L
        if (valuesStart >= bytes.size || valuesStart > Int.MAX_VALUE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.getInt(0) != nodeCount || buffer.getInt(4) != 1) return null
        if (buffer.getInt(secondCountOffset.toInt()) != nodeCount) return null
        val valueCount = buffer.getInt(valueCountOffset.toInt())
        if (valueCount !in 1..nodeCount || valueCount > MAX_RECORDS) return null

        val base = IntArray(nodeCount) { index -> buffer.getInt(4 + index * 4) }
        val check = IntArray(nodeCount) { index -> buffer.getInt(checkStart.toInt() + index * 4) }
        val valueText = decodeStrictUtf8(bytes.copyOfRange(valuesStart.toInt(), bytes.size)) ?: return null
        val values = valueText.lineSequence().toMutableList().also { while (it.lastOrNull()?.isEmpty() == true) it.removeAt(it.lastIndex) }
        if (values.size != valueCount) return null

        val heads = IntArray(nodeCount)
        val next = IntArray(nodeCount)
        for (nodeIndex in nodeCount - 1 downTo 1) {
            val parentBase = check[nodeIndex]
            if (parentBase in 1 until nodeCount && nodeIndex != parentBase) {
                val childBase = base[nodeIndex]
                if (childBase in 1 until nodeCount) {
                    next[nodeIndex] = heads[parentBase]
                    heads[parentBase] = nodeIndex
                }
            }
        }
        val rootBase = base[0]
        if (rootBase !in 1 until nodeCount) return null

        data class Frame(val stateBase: Int, var child: Int, val depth: Int, var terminalDone: Boolean = false)
        val stack = ArrayDeque<Frame>()
        val pathUnits = IntArray(MAX_TERM_UTF16_UNITS)
        val terms = ArrayList<String>(valueCount)
        var expected = 0
        stack.add(Frame(rootBase, heads[rootBase], 0))
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.terminalDone) {
                frame.terminalDone = true
                if (check[frame.stateBase] == frame.stateBase && base[frame.stateBase] < 0) {
                    val valueIndex = -base[frame.stateBase] - 1
                    require(valueIndex == expected) { "Thứ tự giá trị DAT không liên tục tại $expected/$valueIndex." }
                    val term = utf16UnitsToString(pathUnits, frame.depth)
                    require(term.isNotBlank() && term == term.trim() && !hasControl(term)) { "Khóa DAT không hợp lệ." }
                    terms += term.removePrefix("\uFEFF")
                    expected++
                }
            } else if (frame.child != 0) {
                val childIndex = frame.child
                frame.child = next[childIndex]
                val codeUnit = childIndex - frame.stateBase - 1
                val childBase = base[childIndex]
                if (codeUnit in 0..0xFFFF && childBase in 1 until nodeCount) {
                    require(frame.depth < pathUnits.size) { "Khóa DAT dài quá giới hạn." }
                    pathUnits[frame.depth] = codeUnit
                    stack.add(Frame(childBase, heads[childBase], frame.depth + 1))
                }
            } else {
                stack.removeLast()
            }
        }
        require(expected == valueCount && terms.size == values.size) { "DAT khai báo $valueCount mục nhưng giải mã được $expected." }
        return buildRules(kind, terms, values, Format.DAT_DOUBLE_ARRAY_TRIE)
    }

    private fun buildRules(
        kind: VietPhraseDictionaryKind,
        keys: List<String>,
        values: List<String>,
        format: Format,
    ): DecodeResult {
        require(keys.size == values.size) { "Số khóa và giá trị DIC không khớp." }
        val unique = LinkedHashMap<String, VietPhraseRule>()
        var skipped = 0
        var duplicates = 0
        keys.indices.forEach { index ->
            val source = keys[index].removePrefix("\uFEFF").trim()
            val target = values[index].trim()
            if (source.isBlank() || target.isBlank() || source.length > MAX_SOURCE_CHARS || target.length > MAX_TARGET_CHARS || hasControl(source) || hasControl(target)) {
                skipped++
                return@forEach
            }
            val key = source.lowercase(Locale.ROOT)
            if (unique.containsKey(key)) duplicates++
            unique[key] = VietPhraseRule(
                id = "${kind.name.lowercase()}:${stableId(source)}",
                source = source,
                target = target,
                kind = kind,
                matchMode = if (kind == VietPhraseDictionaryKind.LUAT_NHAN || PLACEHOLDER.containsMatchIn(source)) VietPhraseMatchMode.TEMPLATE else VietPhraseMatchMode.LITERAL,
            )
        }
        require(unique.isNotEmpty()) { "DIC/DAT không có mục từ hợp lệ." }
        return DecodeResult(kind, unique.values.toList(), format, skipped, duplicates)
    }

    private fun readString(input: DataInputStream, codec: Codec): String = when (codec) {
        Codec.JAVA_UTF -> input.readUTF()
        Codec.DOTNET_UTF8 -> readUtf8(input, read7BitInt(input))
        Codec.U32_BE_UTF8 -> readUtf8(input, input.readInt())
        Codec.U32_LE_UTF8 -> readUtf8(input, Integer.reverseBytes(input.readInt()))
    }

    private fun read7BitInt(input: DataInputStream): Int {
        var value = 0
        var shift = 0
        repeat(5) {
            val byte = input.readUnsignedByte()
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        throw IllegalArgumentException("Độ dài chuỗi .NET DIC không hợp lệ.")
    }

    private fun readUtf8(input: DataInputStream, length: Int): String {
        require(length in 0..MAX_STRING_BYTES) { "Độ dài chuỗi DIC bất thường: $length" }
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (_: EOFException) {
            throw IllegalArgumentException("DIC kết thúc giữa chuỗi.")
        }
        return decodeStrictUtf8(bytes) ?: throw IllegalArgumentException("Chuỗi DIC không phải UTF-8 hợp lệ.")
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun plausibleTerms(terms: List<String>): Boolean {
        if (terms.isEmpty() || terms.any { !plausibleString(it) }) return false
        val sampled = terms.take(128)
        return sampled.size < 8 || sampled.count(::containsHan) * 100 / sampled.size >= 55
    }

    private fun plausibleString(value: String): Boolean = value.length <= MAX_STRING_CHARS && !hasControl(value)
    private fun hasControl(value: String): Boolean = value.any { char -> char.code < 32 && char != '\t' && char != '\n' && char != '\r' }
    private fun containsHan(value: String): Boolean = value.any { char ->
        val block = Character.UnicodeBlock.of(char)
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    private fun guessLayout(samples: List<String>): Layout {
        val odd = samples.filterIndexed { index, _ -> index % 2 == 0 }
        val even = samples.filterIndexed { index, _ -> index % 2 == 1 }
        val oddRatio = odd.count(::containsHan).toDouble() / odd.size.coerceAtLeast(1)
        val evenRatio = even.count(::containsHan).toDouble() / even.size.coerceAtLeast(1)
        return if (oddRatio >= 0.55 && evenRatio <= 0.45 && oddRatio - evenRatio >= 0.25) Layout.PAIRED else Layout.GROUPED
    }

    private fun utf16UnitsToString(units: IntArray, length: Int): String {
        val chars = CharArray(length) { units[it].toChar() }
        return String(chars)
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    private val PLACEHOLDER = Regex("\\{\\d+}")
    const val MAX_RECORDS = 1_000_000
    const val MAX_FILE_BYTES = 256 * 1024 * 1024
    const val MAX_STRING_BYTES = 16 * 1024 * 1024
    const val MAX_STRING_CHARS = 4_000_000
    const val MAX_SOURCE_CHARS = 2_000
    const val MAX_TARGET_CHARS = 4_000
    const val MAX_TERM_UTF16_UNITS = 4_096
}
