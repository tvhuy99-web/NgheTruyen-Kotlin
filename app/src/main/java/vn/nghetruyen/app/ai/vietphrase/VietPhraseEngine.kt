package vn.nghetruyen.app.ai.vietphrase

import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale







class VietPhraseEngine(
    rules: List<VietPhraseRule>,
    private val maxCacheEntries: Int = 32,
) {
    private data class LiteralMatch(val rule: VietPhraseRule, val end: Int, val replacement: String)
    private data class TemplatePart(val literal: String? = null, val slot: Int? = null)
    private data class CompiledTemplate(val rule: VietPhraseRule, val parts: List<TemplatePart>)
    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        val terminalRules = mutableListOf<VietPhraseRule>()
    }
    private data class TemplateMatch(
        val rule: VietPhraseRule,
        val end: Int,
        val replacement: String,
        val captures: Map<Int, String>,
    )

    private class MutableEngineDiagnostics(private val probeLimit: Int) {
        var cursorPositions = 0
        var literalLookups = 0
        var literalCandidates = 0
        var directSelections = 0
        var templateCandidates = 0
        var templateAttempts = 0
        var templateMatches = 0
        var templateSelections = 0
        var templateBudgetExceeded = 0
        var captureCandidates = 0
        var fallbackSelections = 0
        var unmatchedCodePoints = 0
        var aiReplaceSelections = 0
        var multiMeaningSelections = 0
        private val probes = ArrayList<VietPhraseProbeEntry>()
        private var probesTruncated = false

        fun probe(position: Int, phase: String, rule: VietPhraseRule?, outcome: String, detail: String = "") {
            if (probeLimit <= 0) return
            if (probes.size >= probeLimit) {
                probesTruncated = true
                return
            }
            probes += VietPhraseProbeEntry(position, phase, rule?.kind, rule?.id, outcome, detail.take(300))
        }

        fun snapshot() = VietPhraseEngineDiagnostics(
            cursorPositions = cursorPositions,
            literalLookups = literalLookups,
            literalCandidates = literalCandidates,
            directSelections = directSelections,
            templateCandidates = templateCandidates,
            templateAttempts = templateAttempts,
            templateMatches = templateMatches,
            templateSelections = templateSelections,
            templateBudgetExceeded = templateBudgetExceeded,
            captureCandidates = captureCandidates,
            fallbackSelections = fallbackSelections,
            unmatchedCodePoints = unmatchedCodePoints,
            aiReplaceSelections = aiReplaceSelections,
            multiMeaningSelections = multiMeaningSelections,
            probes = probes.toList(),
            probesTruncated = probesTruncated,
        )
    }

    init {
        require(rules.size <= MAX_RULES) { "Bộ VietPhrase vượt giới hạn an toàn." }
    }

    private val normalizedRules = rules
        .asSequence()
        .filter { it.enabled && it.source.isNotBlank() }
        .distinctBy { canonicalKey(it) }
        .toList()

    private val baseLiteralRules = normalizedRules
        .filter {
            it.kind != VietPhraseDictionaryKind.AI_REPLACE &&
                it.kind != VietPhraseDictionaryKind.PHIEN_AM &&
                it.matchMode == VietPhraseMatchMode.LITERAL
        }
        .sortedWith(RULE_ORDER)
    private val baseLiteralTrie = buildTrie(baseLiteralRules)

    
    
    private val fallbackHanVietRules = normalizedRules
        .filter { it.kind == VietPhraseDictionaryKind.PHIEN_AM && it.matchMode == VietPhraseMatchMode.LITERAL }
        .sortedWith(RULE_ORDER)
    private val fallbackHanVietTrie = buildTrie(fallbackHanVietRules)

    private val captureRules = baseLiteralRules
        .filter { it.kind == VietPhraseDictionaryKind.NAMES || it.kind == VietPhraseDictionaryKind.PRONOUNS }
        .sortedWith(RULE_ORDER)
    private val captureTrie = buildTrie(captureRules)

    private val templateRules = normalizedRules
        .filter { it.kind == VietPhraseDictionaryKind.LUAT_NHAN || it.matchMode == VietPhraseMatchMode.TEMPLATE }
        .mapNotNull { rule -> compileTemplate(rule)?.let { CompiledTemplate(rule, it) } }
        .sortedWith(compareByDescending<CompiledTemplate> { it.rule.effectivePriority }.thenByDescending { it.rule.source.length }.thenBy { it.rule.id })
    private val templateBuckets = templateRules.filter { it.parts.firstOrNull()?.literal?.isNotEmpty() == true }
        .groupBy { bucketKey(it.parts.first().literal!!.first()) }
    private val wildcardTemplates = templateRules.filter { it.parts.firstOrNull()?.slot != null }

    private val aiReplaceRules = normalizedRules
        .filter { it.kind == VietPhraseDictionaryKind.AI_REPLACE && it.matchMode == VietPhraseMatchMode.LITERAL }
        .sortedWith(RULE_ORDER)
    private val aiReplaceTrie = buildTrie(aiReplaceRules)

    private val rulesFingerprint = fingerprint(normalizedRules)
    private val cache = object : LinkedHashMap<String, VietPhraseResult>(maxCacheEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VietPhraseResult>?): Boolean = size > maxCacheEntries.coerceAtLeast(0)
    }

    @Synchronized
    fun translate(text: String, options: VietPhraseOptions = VietPhraseOptions()): String = translateWithTrace(text, options.copy(traceLimit = 0)).text

    @Synchronized
    fun translateWithTrace(text: String, options: VietPhraseOptions = VietPhraseOptions()): VietPhraseResult {
        if (text.isEmpty()) return VietPhraseResult(text, emptyList(), false, emptyMap())
        require(text.length <= MAX_INPUT_CHARS) { "Nội dung VietPhrase vượt giới hạn an toàn." }
        val cacheable = options.traceLimit <= 0 && options.diagnosticProbeLimit <= 0 && maxCacheEntries > 0
        val key = cacheKey(text, options)
        if (cacheable) cache[key]?.let { return it }

        val diagnostics = MutableEngineDiagnostics(options.diagnosticProbeLimit)
        val trace = ArrayList<VietPhraseTraceEntry>()
        val counts = linkedMapOf<VietPhraseDictionaryKind, Int>()
        var truncated = false
        val base = StringBuilder(text.length + text.length / 8)
        var cursor = 0
        while (cursor < text.length) {
            diagnostics.cursorPositions += 1
            val chLength = Character.charCount(Character.codePointAt(text, cursor))
            val direct = bestLiteral(text, cursor, baseLiteralTrie, options, diagnostics, "base_literal")
            val template = if (options.useRules) bestTemplate(text, cursor, options, diagnostics) else null
            val choiceIsTemplate = when {
                template == null -> false
                direct == null -> true
                template.end > direct.end -> true
                template.end < direct.end -> false
                template.rule.effectivePriority > direct.rule.effectivePriority -> true
                template.rule.effectivePriority < direct.rule.effectivePriority -> false
                else -> template.rule.source.length >= direct.rule.source.length
            }
            if (choiceIsTemplate) {
                val match = requireNotNull(template)
                diagnostics.templateSelections += 1
                diagnostics.probe(cursor, "selection", match.rule, "template_selected", "end=${match.end}")
                appendSmart(base, resolveMeaning(match.replacement, match.rule.kind, options.oneMeaning))
                counts[match.rule.kind] = (counts[match.rule.kind] ?: 0) + 1
                if (options.traceLimit > 0 && trace.size < options.traceLimit) {
                    trace += VietPhraseTraceEntry(cursor, match.end, text.substring(cursor, match.end), match.replacement, match.rule.kind, match.rule.id, match.captures)
                } else if (options.traceLimit > 0) truncated = true
                cursor = match.end
            } else if (direct != null) {
                diagnostics.directSelections += 1
                diagnostics.probe(cursor, "selection", direct.rule, "direct_selected", "end=${direct.end}")
                if (meaningCount(direct.replacement, direct.rule.kind) > 1) diagnostics.multiMeaningSelections += 1
                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)
                appendSmart(base, replacement)
                counts[direct.rule.kind] = (counts[direct.rule.kind] ?: 0) + 1
                if (options.traceLimit > 0 && trace.size < options.traceLimit) {
                    trace += VietPhraseTraceEntry(cursor, direct.end, text.substring(cursor, direct.end), replacement, direct.rule.kind, direct.rule.id)
                } else if (options.traceLimit > 0) truncated = true
                cursor = direct.end
            } else {
                val fallback = if (options.fallbackHanViet) bestLiteral(text, cursor, fallbackHanVietTrie, options, diagnostics, "hanviet_fallback") else null
                if (fallback != null) {
                    diagnostics.fallbackSelections += 1
                    diagnostics.probe(cursor, "selection", fallback.rule, "fallback_selected", "end=${fallback.end}")
                    if (meaningCount(fallback.replacement, fallback.rule.kind) > 1) diagnostics.multiMeaningSelections += 1
                    val replacement = resolveMeaning(fallback.replacement, fallback.rule.kind, options.oneMeaning)
                    appendSmart(base, replacement)
                    counts[fallback.rule.kind] = (counts[fallback.rule.kind] ?: 0) + 1
                    if (options.traceLimit > 0 && trace.size < options.traceLimit) {
                        trace += VietPhraseTraceEntry(cursor, fallback.end, text.substring(cursor, fallback.end), replacement, fallback.rule.kind, fallback.rule.id)
                    } else if (options.traceLimit > 0) truncated = true
                    cursor = fallback.end
                } else {
                    diagnostics.unmatchedCodePoints += 1
                    diagnostics.probe(cursor, "selection", null, "unmatched_codepoint", "codePoint=${Character.codePointAt(text, cursor)}")
                    val raw = text.substring(cursor, cursor + chLength)
                    base.append(if (options.normalizePunctuation) PUNCTUATION[raw] ?: raw else raw)
                    cursor += chLength
                }
            }
        }

        var output = normalizeSpacing(base.toString())
        if (aiReplaceRules.isNotEmpty()) {
            val finalPass = applyFinalReplacement(output, options, trace, counts, diagnostics)
            output = finalPass.first
            truncated = truncated || finalPass.second
        }
        if (options.capitalizeSentences) output = capitalizeSentenceStarts(output)
        val result = VietPhraseResult(output, trace.toList(), truncated, counts.toMap(), diagnostics.snapshot())
        if (cacheable) cache[key] = result
        return result
    }

    @Synchronized
    fun clearCache() = cache.clear()

    private fun ruleVisible(rule: VietPhraseRule, options: VietPhraseOptions): Boolean = when (rule.scope) {
        VietPhraseScope.GLOBAL -> true
        VietPhraseScope.STORY -> !options.storyId.isNullOrBlank() && options.storyId == rule.storyId
    }

    private fun literalMatches(
        text: String,
        start: Int,
        trie: TrieNode,
        options: VietPhraseOptions,
        diagnostics: MutableEngineDiagnostics,
        phase: String,
    ): List<LiteralMatch> {
        diagnostics.literalLookups += 1
        val matches = mutableListOf<LiteralMatch>()
        var node: TrieNode? = trie
        var cursor = start
        while (cursor < text.length) {
            node = node?.children?.get(bucketKey(text[cursor])) ?: break
            cursor += 1
            for (rule in node.terminalRules) {
                diagnostics.literalCandidates += 1
                if (!ruleVisible(rule, options)) {
                    diagnostics.probe(start, phase, rule, "scope_hidden")
                    continue
                }
                if (!matchesAt(text, start, rule)) {
                    diagnostics.probe(start, phase, rule, "case_or_text_mismatch")
                    continue
                }
                val end = start + rule.source.length
                if (end != cursor) continue
                if (!safeBoundaries(text, start, end, rule.source)) {
                    diagnostics.probe(start, phase, rule, "boundary_rejected")
                    continue
                }
                diagnostics.probe(start, phase, rule, "candidate_matched", "end=$end")
                matches += LiteralMatch(rule, end, rule.target)
            }
        }
        return matches
    }

    private fun bestLiteral(
        text: String,
        start: Int,
        trie: TrieNode,
        options: VietPhraseOptions,
        diagnostics: MutableEngineDiagnostics,
        phase: String,
    ): LiteralMatch? =
        literalMatches(text, start, trie, options, diagnostics, phase).maxWithOrNull(
            compareBy<LiteralMatch> { it.end }
                .thenBy { it.rule.effectivePriority }
                .thenBy { it.rule.updatedAt }
                .thenByDescending { it.rule.id },
        )

    private fun bestTemplate(text: String, start: Int, options: VietPhraseOptions, diagnostics: MutableEngineDiagnostics): TemplateMatch? {
        var best: TemplateMatch? = null
        val candidates = templateBuckets[bucketKey(text[start])].orEmpty() + wildcardTemplates
        diagnostics.templateCandidates += candidates.size
        for (compiled in candidates) {
            if (!ruleVisible(compiled.rule, options)) {
                diagnostics.probe(start, "template", compiled.rule, "scope_hidden")
                continue
            }
            diagnostics.templateAttempts += 1
            val match = matchTemplate(text, start, compiled, options, diagnostics)
            if (match == null) {
                diagnostics.probe(start, "template", compiled.rule, "no_match")
                continue
            }
            diagnostics.templateMatches += 1
            diagnostics.probe(start, "template", compiled.rule, "candidate_matched", "end=${match.end}")
            if (best == null || match.end > best.end ||
                (match.end == best.end && match.rule.effectivePriority > best.rule.effectivePriority) ||
                (match.end == best.end && match.rule.effectivePriority == best.rule.effectivePriority && match.rule.source.length > best.rule.source.length)
            ) best = match
        }
        return best
    }

    private fun matchTemplate(text: String, start: Int, compiled: CompiledTemplate, options: VietPhraseOptions, diagnostics: MutableEngineDiagnostics): TemplateMatch? {
        var budget = 0
        fun walk(partIndex: Int, cursor: Int, captures: Map<Int, LiteralMatch>): Pair<Int, Map<Int, LiteralMatch>>? {
            budget += 1
            if (budget > MAX_TEMPLATE_STEPS) {
                diagnostics.templateBudgetExceeded += 1
                diagnostics.probe(start, "template", compiled.rule, "budget_exceeded", "steps=$budget")
                return null
            }
            if (partIndex >= compiled.parts.size) return cursor to captures
            val part = compiled.parts[partIndex]
            part.literal?.let { literal ->
                if (!text.regionMatches(cursor, literal, 0, literal.length, ignoreCase = compiled.rule.ignoreCase)) return null
                return walk(partIndex + 1, cursor + literal.length, captures)
            }
            val slot = part.slot ?: return null
            val candidates = literalMatches(text, cursor, captureTrie, options, diagnostics, "template_capture").asSequence()
                .filter { !containsBoundary(text.substring(cursor, it.end)) }
                .sortedWith(compareByDescending<LiteralMatch> { it.end }.thenByDescending { it.rule.effectivePriority }.thenBy { it.rule.id })
                .take(MAX_CAPTURE_CANDIDATES)
                .toList()
            diagnostics.captureCandidates += candidates.size
            var best: Pair<Int, Map<Int, LiteralMatch>>? = null
            for (capture in candidates) {
                val next = walk(partIndex + 1, capture.end, captures + (slot to capture)) ?: continue
                if (best == null || next.first > best.first) best = next
            }
            return best
        }

        val resolved = walk(0, start, emptyMap()) ?: return null
        if (resolved.first <= start) return null
        val stringCaptures = resolved.second.mapValues { (_, match) -> resolveMeaning(match.replacement, match.rule.kind, true) }
        val replacement = PLACEHOLDER.replace(compiled.rule.target) { token -> stringCaptures[token.groupValues[1].toInt()] ?: "" }
        return TemplateMatch(compiled.rule, resolved.first, replacement, stringCaptures)
    }

    private fun applyFinalReplacement(
        input: String,
        options: VietPhraseOptions,
        trace: MutableList<VietPhraseTraceEntry>,
        counts: MutableMap<VietPhraseDictionaryKind, Int>,
        diagnostics: MutableEngineDiagnostics,
    ): Pair<String, Boolean> {
        val out = StringBuilder(input.length)
        var cursor = 0
        var traceTruncated = false
        while (cursor < input.length) {
            val match = bestLiteral(input, cursor, aiReplaceTrie, options, diagnostics, "ai_replace")
            if (match == null) {
                val length = Character.charCount(Character.codePointAt(input, cursor))
                out.append(input, cursor, cursor + length)
                cursor += length
            } else {
                diagnostics.aiReplaceSelections += 1
                diagnostics.probe(cursor, "selection", match.rule, "ai_replace_selected", "end=${match.end}")
                out.append(match.replacement)
                counts[VietPhraseDictionaryKind.AI_REPLACE] = (counts[VietPhraseDictionaryKind.AI_REPLACE] ?: 0) + 1
                if (options.traceLimit > 0 && trace.size < options.traceLimit) {
                    trace += VietPhraseTraceEntry(cursor, match.end, input.substring(cursor, match.end), match.replacement, match.rule.kind, match.rule.id)
                } else if (options.traceLimit > 0) {
                    traceTruncated = true
                }
                cursor = match.end
            }
        }
        return out.toString() to traceTruncated
    }

    private fun matchesAt(text: String, start: Int, rule: VietPhraseRule): Boolean =
        start + rule.source.length <= text.length && text.regionMatches(start, rule.source, 0, rule.source.length, ignoreCase = rule.ignoreCase)

    private fun safeBoundaries(text: String, start: Int, end: Int, phrase: String): Boolean {
        val first = phrase.codePointAt(0)
        val last = phrase.codePointBefore(phrase.length)
        val leftWord = Character.isLetterOrDigit(first) && !isHan(first)
        val rightWord = Character.isLetterOrDigit(last) && !isHan(last)
        val leftSafe = !leftWord || start == 0 || !Character.isLetterOrDigit(text.codePointBefore(start))
        val rightSafe = !rightWord || end >= text.length || !Character.isLetterOrDigit(text.codePointAt(end))
        return leftSafe && rightSafe
    }

    private fun better(candidate: LiteralMatch, current: LiteralMatch?): Boolean = current == null ||
        candidate.end > current.end ||
        (candidate.end == current.end && candidate.rule.effectivePriority > current.rule.effectivePriority) ||
        (candidate.end == current.end && candidate.rule.effectivePriority == current.rule.effectivePriority && candidate.rule.id < current.rule.id)

    private fun compileTemplate(rule: VietPhraseRule): List<TemplatePart>? {
        val parts = mutableListOf<TemplatePart>()
        var cursor = 0
        for (match in PLACEHOLDER.findAll(rule.source)) {
            if (match.range.first > cursor) parts += TemplatePart(literal = rule.source.substring(cursor, match.range.first))
            parts += TemplatePart(slot = match.groupValues[1].toIntOrNull() ?: return null)
            cursor = match.range.last + 1
        }
        if (cursor < rule.source.length) parts += TemplatePart(literal = rule.source.substring(cursor))
        return parts.takeIf { it.isNotEmpty() && it.any { part -> part.slot != null } }
    }

    private fun resolveMeaning(raw: String, kind: VietPhraseDictionaryKind, oneMeaning: Boolean): String {
        val meanings = meaningCandidates(raw, kind)
        if (meanings.isEmpty()) return cleanMeaning(decodeMeaningText(raw))
        return if (oneMeaning) meanings.first() else meanings.take(4).joinToString(" / ")
    }

    private fun meaningCount(raw: String, kind: VietPhraseDictionaryKind): Int = meaningCandidates(raw, kind).size

    private fun meaningCandidates(raw: String, kind: VietPhraseDictionaryKind): List<String> {
        val decoded = decodeMeaningText(raw)
        val hasDictionaryMetadata = decoded.contains("Hán Việt:") || decoded.contains("✚[")
        val numberedLines = decoded.lineSequence().mapNotNull { line ->
            NUMBERED_MEANING.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.let(::cleanMeaning)
        }.filter(String::isNotBlank).toList()
        return when {
            numberedLines.isNotEmpty() -> numberedLines
            hasDictionaryMetadata -> decoded.lineSequence().map { cleanMeaning(it) }.filter { it.isNotBlank() && !it.contains("Hán Việt:") && !it.contains("✚[") }.toList()
            kind == VietPhraseDictionaryKind.LAC_VIET && decoded.contains('\n') -> decoded.lineSequence().map(::cleanMeaning).filter(String::isNotBlank).toList()
            else -> decoded.split('/', '|').map(::cleanMeaning).filter(String::isNotBlank)
        }
    }

    private fun decodeMeaningText(raw: String): String = raw
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .replace("\\t", "\t")
        .replace(Regex("<[bB][rR]\\s*/?>"), "\n")
        .replace("&nbsp;", " ")

    private fun cleanMeaning(value: String): String = value.trim()
        .replace(Regex("^[\\-*•]+\\s*"), "")
        .replace(Regex("^\\d+[.)]\\s*"), "")
        .trim()

    private fun appendSmart(out: StringBuilder, piece: String) {
        if (piece.isEmpty()) return
        if (out.isNotEmpty()) {
            val previous = out.last()
            val next = piece.first()
            if (!previous.isWhitespace() && !next.isWhitespace() && next !in CLOSE_PUNCT && previous !in OPEN_PUNCT) out.append(' ')
        }
        out.append(piece)
    }

    private fun normalizeSpacing(text: String): String = text
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex(" +([,\\.!?;:)\\]}])"), "$1")
        .replace(Regex("([(\\[{]) +"), "$1")

    private fun capitalizeSentenceStarts(text: String): String {
        val out = StringBuilder(text.length)
        var shouldCapitalize = true
        var cursor = 0
        while (cursor < text.length) {
            val codePoint = Character.codePointAt(text, cursor)
            val length = Character.charCount(codePoint)
            val token = text.substring(cursor, cursor + length)
            if (shouldCapitalize && Character.isLetter(codePoint)) {
                out.append(token.uppercase(Locale.forLanguageTag("vi")))
                shouldCapitalize = false
            } else {
                out.append(token)
                if (!Character.isWhitespace(codePoint) && token !in OPEN_QUOTES) shouldCapitalize = false
            }
            if (token == "." || token == "!" || token == "?" || token == "…" || token == "\n") shouldCapitalize = true
            cursor += length
        }
        return out.toString()
    }

    private fun containsBoundary(value: String): Boolean = value.any { it.isWhitespace() || it in RULE_BOUNDARY }
    private fun isHan(codePoint: Int): Boolean = codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F

    private fun cacheKey(text: String, options: VietPhraseOptions): String = listOf(
        rulesFingerprint,
        options.storyId.orEmpty(),
        options.useRules,
        options.oneMeaning,
        options.normalizePunctuation,
        options.capitalizeSentences,
        text,
    ).joinToString("\u0000").let(::sha256)

    companion object {
        private val PLACEHOLDER = Regex("\\{(\\d+)}")
        private val RULE_ORDER = compareByDescending<VietPhraseRule> { it.source.length }
            .thenByDescending { it.effectivePriority }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id }
        private val RULE_BOUNDARY = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':', '\n', '\r')
        private val PUNCTUATION = mapOf("，" to ",", "。" to ".", "！" to "!", "？" to "?", "：" to ":", "；" to ";", "、" to ",", "（" to "(", "）" to ")", "【" to "[", "】" to "]", "《" to "«", "》" to "»", "「" to "“", "」" to "”", "『" to "‘", "』" to "’", "～" to "~")
        private val CLOSE_PUNCT = setOf('.', ',', '!', '?', ':', ';', ')', ']', '}', '»', '›', '”', '’', '…')
        private val OPEN_PUNCT = setOf('(', '[', '{', '«', '‹', '“', '‘')
        private val OPEN_QUOTES = setOf("“", "‘", "\"", "'", "(", "[")
        private val NUMBERED_MEANING = Regex("^\\d+[.)]\\s*(.+)$")
        private const val MAX_TEMPLATE_STEPS = 160
        private const val MAX_CAPTURE_CANDIDATES = 24
        const val MAX_RULES = 1_000_000
        const val MAX_INPUT_CHARS = 8 * 1024 * 1024

        private fun bucketKey(value: Char): Char = value.lowercaseChar()
        private fun buildTrie(rules: List<VietPhraseRule>): TrieNode {
            val root = TrieNode()
            for (rule in rules) {
                var node = root
                for (character in rule.source) node = node.children.getOrPut(bucketKey(character)) { TrieNode() }
                node.terminalRules += rule
            }
            fun sort(node: TrieNode) {
                node.terminalRules.sortWith(RULE_ORDER)
                node.children.values.forEach(::sort)
            }
            sort(root)
            return root
        }
        private fun canonicalKey(rule: VietPhraseRule): String = listOf(rule.kind, rule.scope, rule.storyId.orEmpty(), rule.source.lowercase(Locale.ROOT), rule.matchMode).joinToString("|")
        private fun fingerprint(rules: List<VietPhraseRule>): String = rules.sortedBy { it.id }.joinToString("\n") { listOf(it.id, it.kind, it.scope, it.storyId, it.source, it.target, it.priority, it.enabled, it.matchMode, it.updatedAt).joinToString("\u0001") }.let(::sha256)
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
