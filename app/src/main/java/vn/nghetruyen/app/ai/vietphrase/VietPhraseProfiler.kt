package vn.nghetruyen.app.ai.vietphrase

/** Deterministic profiler used by diagnostics and release gates. */
object VietPhraseProfiler {
    data class Sample(
        val label: String,
        val inputChars: Int,
        val outputChars: Int,
        val elapsedNanos: Long,
        val replacements: Int,
    )

    data class Report(
        val ruleCount: Int,
        val literalRuleCount: Int,
        val templateRuleCount: Int,
        val storyRuleCount: Int,
        val buildNanos: Long,
        val samples: List<Sample>,
    ) {
        val totalInputChars: Int get() = samples.sumOf { it.inputChars }
        val totalElapsedNanos: Long get() = samples.sumOf { it.elapsedNanos }
        val charsPerSecond: Long get() = if (totalElapsedNanos <= 0) 0 else (totalInputChars * 1_000_000_000L) / totalElapsedNanos

        fun asText(): String = buildString {
            appendLine("VietPhrase profiler")
            appendLine("rules=$ruleCount literal=$literalRuleCount template=$templateRuleCount story=$storyRuleCount")
            appendLine("buildMs=${buildNanos / 1_000_000} charsPerSecond=$charsPerSecond")
            samples.forEach { sample ->
                appendLine("${sample.label}: input=${sample.inputChars} output=${sample.outputChars} replacements=${sample.replacements} elapsedMs=${sample.elapsedNanos / 1_000_000}")
            }
        }.trim()
    }

    fun profile(
        rules: List<VietPhraseRule>,
        samples: List<Pair<String, String>>,
        storyId: String? = null,
    ): Report {
        require(rules.size <= VietPhraseEngine.MAX_RULES) { "Bộ quy tắc vượt giới hạn profiler." }
        require(samples.size <= MAX_SAMPLES) { "Quá nhiều mẫu profiler." }
        val buildStarted = System.nanoTime()
        val engine = VietPhraseEngine(rules, maxCacheEntries = 0)
        val buildNanos = System.nanoTime() - buildStarted
        val options = VietPhraseOptions(storyId = storyId, traceLimit = 100_000, capitalizeSentences = false)
        val results = samples.map { (label, text) ->
            require(text.length <= VietPhraseEngine.MAX_INPUT_CHARS) { "Mẫu profiler quá dài." }
            val started = System.nanoTime()
            val result = engine.translateWithTrace(text, options)
            Sample(label.take(100), text.length, result.text.length, System.nanoTime() - started, result.trace.size)
        }
        return Report(
            ruleCount = rules.size,
            literalRuleCount = rules.count { it.matchMode == VietPhraseMatchMode.LITERAL },
            templateRuleCount = rules.count { it.matchMode == VietPhraseMatchMode.TEMPLATE },
            storyRuleCount = rules.count { it.scope == VietPhraseScope.STORY },
            buildNanos = buildNanos,
            samples = results,
        )
    }

    const val MAX_SAMPLES = 100
}
