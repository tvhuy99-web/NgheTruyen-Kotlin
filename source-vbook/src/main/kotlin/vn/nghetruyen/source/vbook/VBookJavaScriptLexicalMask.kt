package vn.nghetruyen.source.vbook

/**
 * Lightweight lexical masking for corpus feature detection only; it never transforms code that is
 * executed. Keeping character positions/newlines stable makes evidence deterministic while avoiding
 * host-API false positives inside comments and generated HTML/JavaScript string payloads.
 */
internal object VBookJavaScriptLexicalMask {
    fun executable(source: String): String = mask(source, hideStrings = true)
    fun withoutComments(source: String): String = mask(source, hideStrings = false)

    private fun mask(source: String, hideStrings: Boolean): String {
        val output = StringBuilder(source.length)
        var index = 0
        var state = State.CODE
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                State.CODE -> when {
                    char == '/' && next == '/' -> {
                        output.append("  ")
                        index += 2
                        state = State.LINE_COMMENT
                        continue
                    }
                    char == '/' && next == '*' -> {
                        output.append("  ")
                        index += 2
                        state = State.BLOCK_COMMENT
                        continue
                    }
                    char == '\'' -> state = State.SINGLE_QUOTE
                    char == '"' -> state = State.DOUBLE_QUOTE
                    char == '`' -> state = State.TEMPLATE
                }
                State.LINE_COMMENT -> {
                    if (char == '\n' || char == '\r') state = State.CODE
                    output.append(if (char == '\n' || char == '\r') char else ' ')
                    index++
                    continue
                }
                State.BLOCK_COMMENT -> {
                    if (char == '*' && next == '/') {
                        output.append("  ")
                        index += 2
                        state = State.CODE
                        continue
                    }
                    output.append(if (char == '\n' || char == '\r') char else ' ')
                    index++
                    continue
                }
                State.SINGLE_QUOTE, State.DOUBLE_QUOTE, State.TEMPLATE -> {
                    val terminator = when (state) {
                        State.SINGLE_QUOTE -> '\''
                        State.DOUBLE_QUOTE -> '"'
                        else -> '`'
                    }
                    if (char == '\\' && next != null) {
                        appendStringChar(output, char, hideStrings)
                        appendStringChar(output, next, hideStrings)
                        index += 2
                        continue
                    }
                    appendStringChar(output, char, hideStrings)
                    index++
                    if (char == terminator) state = State.CODE
                    continue
                }
            }
            appendStringChar(output, char, hideStrings && state != State.CODE)
            index++
        }
        return output.toString()
    }

    private fun appendStringChar(output: StringBuilder, char: Char, hidden: Boolean) {
        output.append(if (hidden && char != '\n' && char != '\r') ' ' else char)
    }

    private enum class State { CODE, SINGLE_QUOTE, DOUBLE_QUOTE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT }
}
