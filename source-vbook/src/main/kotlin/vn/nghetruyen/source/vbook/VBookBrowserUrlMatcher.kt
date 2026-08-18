package vn.nghetruyen.source.vbook


internal object VBookBrowserUrlMatcher {
    fun matches(url: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if (url.contains(pattern, ignoreCase = true)) return true

        val explicitRegex = pattern.startsWith("regex:")
        val expression = if (explicitRegex) pattern.removePrefix("regex:") else pattern
        val looksLikeRegex = explicitRegex || ".*" in expression || expression.any(REGEX_META::contains)
        if (looksLikeRegex) {
            return runCatching { Regex(expression).containsMatchIn(url) }.getOrDefault(false)
        }

        if ('*' in expression) {
            val regex = expression.split('*').joinToString(".*") { Regex.escape(it) }
            return runCatching {
                Regex("^${regex}${'$'}", RegexOption.IGNORE_CASE).matches(url)
            }.getOrDefault(false)
        }
        return false
    }

    private val REGEX_META = setOf('+', '?', '^', '$', '(', ')', '|', '[', ']', '\\')
}
