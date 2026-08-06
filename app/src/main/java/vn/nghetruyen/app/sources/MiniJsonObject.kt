package vn.nghetruyen.app.sources

internal class MiniJsonObject private constructor(
    private val values: Map<String, Any?>,
) {
    fun string(name: String): String? = when (val value = values[name]) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    fun int(name: String): Int? = when (val value = values[name]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    companion object {
        fun parse(raw: String): MiniJsonObject = Parser(raw).parseObject()
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parseObject(): MiniJsonObject {
            skipWhitespace()
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return MiniJsonObject(result)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return MiniJsonObject(result)
                    }
                    else -> error("JSON không hợp lệ tại vị trí $index: $next")
                }
            }
        }

        private fun parseValue(): Any? = when (peek()) {
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '{' -> skipNested('{', '}')
            '[' -> skipNested('[', ']')
            else -> parseNumberOrBare()
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
                                require(index + 4 <= input.length) { "Unicode escape JSON bị cắt." }
                                val hex = input.substring(index, index + 4)
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Escape JSON không hỗ trợ: $escaped")
                        }
                    }
                    else -> out.append(char)
                }
            }
            error("Chuỗi JSON chưa đóng.")
        }

        private fun parseNumberOrBare(): Any? {
            val start = index
            while (index < input.length && input[index] !in charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t')) index += 1
            val token = input.substring(start, index)
            return token.toLongOrNull() ?: token.toDoubleOrNull() ?: token
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            require(input.startsWith(literal, index)) { "JSON không hợp lệ tại vị trí $index." }
            index += literal.length
            return value
        }

        private fun skipNested(open: Char, close: Char): String {
            val start = index
            var depth = 0
            var quoted = false
            var escaped = false
            while (index < input.length) {
                val char = input[index++]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                    continue
                }
                if (char == '"') quoted = true
                else if (char == open) depth += 1
                else if (char == close) {
                    depth -= 1
                    if (depth == 0) return input.substring(start, index)
                }
            }
            error("JSON lồng nhau chưa đóng.")
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index += 1
        }

        private fun expect(expected: Char) {
            require(peek() == expected) { "Mong đợi '$expected' tại vị trí $index." }
            index += 1
        }

        private fun peek(): Char = input.getOrElse(index) { '\u0000' }
    }
}
