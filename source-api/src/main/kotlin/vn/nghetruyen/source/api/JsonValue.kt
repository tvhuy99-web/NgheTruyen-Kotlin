package vn.nghetruyen.source.api

import java.lang.StringBuilder

sealed interface JsonValue {
    data class Obj(val values: LinkedHashMap<String, JsonValue> = linkedMapOf()) : JsonValue {
        operator fun get(name: String): JsonValue? = values[name]
        fun string(name: String): String? = (values[name] as? Str)?.value
        fun int(name: String): Int? = when (val value = values[name]) {
            is Num -> value.value.toInt()
            is Str -> value.value.toIntOrNull()
            else -> null
        }
        fun long(name: String): Long? = when (val value = values[name]) {
            is Num -> value.value.toLong()
            is Str -> value.value.toLongOrNull()
            else -> null
        }
        fun bool(name: String): Boolean? = when (val value = values[name]) {
            is Bool -> value.value
            is Str -> value.value.toBooleanStrictOrNull()
            else -> null
        }
        fun obj(name: String): Obj? = values[name] as? Obj
        fun array(name: String): Arr? = values[name] as? Arr
    }

    data class Arr(val values: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double, val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

object JsonCodec {
    fun parse(raw: String, maxDepth: Int = 64, maxNodes: Int = 200_000): JsonValue =
        Parser(raw, maxDepth, maxNodes).parse()

    fun stringify(value: JsonValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                append('{')
                value.values.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is JsonValue.Arr -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is JsonValue.Str -> appendString(value.value)
            is JsonValue.Num -> append(value.raw)
            is JsonValue.Bool -> append(if (value.value) "true" else "false")
            JsonValue.Null -> append("null")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private class Parser(
        private val input: String,
        private val maxDepth: Int,
        private val maxNodes: Int,
    ) {
        private var index = 0
        private var nodes = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            require(index == input.length) { "JSON còn dữ liệu thừa tại vị trí $index." }
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            require(depth <= maxDepth) { "JSON vượt quá độ sâu $maxDepth." }
            nodes += 1
            require(nodes <= maxNodes) { "JSON vượt quá số nút $maxNodes." }
            return when (peek()) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("JSON không hợp lệ tại vị trí $index.")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val values = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (consume('}')) return JsonValue.Obj(values)
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "Khóa JSON phải là chuỗi tại vị trí $index." }
                val key = parseString()
                require(key !in values) { "JSON có khóa trùng: $key" }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                values[key] = parseValue(depth)
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return JsonValue.Obj(values)
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (consume(']')) return JsonValue.Arr(values)
            while (true) {
                skipWhitespace()
                values += parseValue(depth)
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return JsonValue.Arr(values)
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < input.length) {
                val char = input[index++]
                when (char) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < input.length) { "Escape JSON bị cắt." }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= input.length) { "Unicode escape bị cắt." }
                                val first = input.substring(index, index + 4).toInt(16).toChar()
                                index += 4
                                if (first.isHighSurrogate()) {
                                    require(input.startsWith("\\u", index) && index + 6 <= input.length) {
                                        "Unicode surrogate không hợp lệ."
                                    }
                                    index += 2
                                    val second = input.substring(index, index + 4).toInt(16).toChar()
                                    index += 4
                                    require(second.isLowSurrogate()) { "Unicode surrogate không hợp lệ." }
                                    out.append(first).append(second)
                                } else {
                                    require(!first.isLowSurrogate()) { "Unicode surrogate không hợp lệ." }
                                    out.append(first)
                                }
                            }
                            else -> error("Escape JSON không hỗ trợ: $escaped")
                        }
                    }
                    else -> {
                        require(char.code >= 0x20) { "Chuỗi JSON chứa control character." }
                        out.append(char)
                    }
                }
            }
            error("Chuỗi JSON chưa đóng.")
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            consume('-')
            if (consume('0')) {
                require(peek() !in '0'..'9') { "Số JSON có số 0 đầu không hợp lệ." }
            } else {
                require(peek() in '1'..'9') { "Số JSON không hợp lệ." }
                while (peek() in '0'..'9') index += 1
            }
            if (consume('.')) {
                require(peek() in '0'..'9') { "Phần thập phân JSON không hợp lệ." }
                while (peek() in '0'..'9') index += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                index += 1
                if (peek() == '+' || peek() == '-') index += 1
                require(peek() in '0'..'9') { "Số mũ JSON không hợp lệ." }
                while (peek() in '0'..'9') index += 1
            }
            val raw = input.substring(start, index)
            val value = raw.toDoubleOrNull() ?: error("Số JSON không hợp lệ: $raw")
            require(value.isFinite()) { "Số JSON không hữu hạn." }
            return JsonValue.Num(value, raw)
        }

        private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
            require(input.startsWith(literal, index)) { "JSON không hợp lệ tại vị trí $index." }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index += 1
        }

        private fun consume(char: Char): Boolean {
            if (peek() != char) return false
            index += 1
            return true
        }

        private fun expect(char: Char) {
            require(consume(char)) { "Mong đợi '$char' tại vị trí $index." }
        }

        private fun peek(): Char = input.getOrElse(index) { '\u0000' }
    }
}
