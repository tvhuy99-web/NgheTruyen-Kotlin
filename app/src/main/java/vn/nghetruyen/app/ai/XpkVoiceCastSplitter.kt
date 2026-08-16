package vn.nghetruyen.app.ai

import java.nio.charset.StandardCharsets








object XpkVoiceCastSplitter {
    const val NARRATOR_ID: String = "voice_narrator"
    const val ENGINE_VERSION: Int = 8

    data class Unit(
        val id: String,
        var text: String,
        var fixedVoice: String? = null,
        var unitKind: String = "narration",
        var directDialogue: Boolean = false,
        var dialogueGroupId: String? = null,
        var speakerHint: String? = null,
        var contextBefore: String? = null,
        var contextAfter: String? = null,
        var unclosedQuote: Boolean = false,
        var parsingNote: String? = null,
        var dashDialogue: Boolean = false,
        var colonDialogue: Boolean = false,
        var colonSpeakerLabel: Boolean = false,
    ) {
        val isDialogue: Boolean get() = fixedVoice == null
    }

    private data class Metadata(
        val forceDialogue: Boolean = false,
        val forceNarration: Boolean = false,
        val fixedVoice: String? = null,
        val unitKind: String? = null,
        val directDialogue: Boolean = false,
        val dialogueGroupId: String? = null,
        val speakerHint: String? = null,
        val contextBefore: String? = null,
        val contextAfter: String? = null,
        val unclosedQuote: Boolean = false,
        val parsingNote: String? = null,
        val dashDialogue: Boolean = false,
        val colonDialogue: Boolean = false,
        val colonSpeakerLabel: Boolean = false,
    )

    private data class QuotePair(val open: String, val close: String)
    private data class ColonMarker(
        val prefixStart: Int,
        val colonPos: Int,
        val colonEndExclusive: Int,
        val prefix: String,
    )

    private val quotes = listOf(
        QuotePair("“", "”"),
        QuotePair("「", "」"),
        QuotePair("『", "』"),
        QuotePair("‘", "’"),
        QuotePair("\"", "\""),
    )

    private val sentenceEndings = listOf("……", "...", "…", "。", "！", "？", ".", "!", "?")
    private val colonBoundaries = listOf("。", "！", "？", ".", "!", "?", ";", "；")
    private val colonCueWords = listOf(
        "nói", "hỏi", "đáp", "trả lời", "quát", "hét", "thì thầm", "lẩm bẩm",
        "lên tiếng", "cất tiếng", "thốt lên", "gọi", "kêu", "hô", "mắng", "rít",
        "gầm", "ra lệnh", "tiếp lời", "nói tiếp", "nhắc", "truyền âm",
        "cất giọng", "mở miệng", "buông lời", "thốt lời", "một tiếng",
    )
    private val thoughtCueWords = listOf(
        "nghĩ", "thầm nghĩ", "nghĩ thầm", "tự nhủ", "nhủ thầm", "trong lòng",
        "trong bụng", "ý nghĩ", "suy nghĩ", "tâm niệm", "thầm tự hỏi", "tự hỏi trong lòng",
    )
    private val quotedNarrationCueWords = listOf(
        "phát ra tiếng", "bật ra tiếng", "vang lên tiếng", "nghe thấy tiếng",
        "tiếng kêu", "tiếng cười", "tiếng khóc", "tiếng thở", "âm thanh",
        "nhắc lại câu", "nhắc lại lời", "lặp lại câu", "lặp lại lời",
        "trích dẫn", "dẫn lại câu", "dẫn lại lời", "câu nói", "cụm từ",
        "dòng chữ", "trên đó viết", "trên đó ghi", "được viết", "được ghi",
        "gọi là", "mang tên", "đọc thấy", "đọc được", "ký hiệu", "mật khẩu",
        "viết", "ghi", "đọc", "đánh vần",
    )
    private val colonHeadingPrefixes = listOf(
        "chương", "phần", "mục", "quyển", "tập", "hồi", "trang", "thời gian",
        "ngày", "giờ", "tỷ lệ", "tỉ lệ", "tọa độ", "phiên bản", "http", "https",
        "kết quả", "lý do", "nguyên nhân", "nội dung", "ghi chú", "chú ý", "cảnh báo",
        "thông báo", "trạng thái", "mô tả", "ví dụ", "đáp án", "kết luận", "mục tiêu",
        "yêu cầu", "thông tin", "dữ liệu", "địa chỉ", "đường dẫn", "url", "email",
        "số điện thoại", "tổng", "điểm", "giá", "mức", "loại", "thuộc tính", "kỹ năng",
        "hiệu ứng", "nhiệm vụ", "phần thưởng",
    )

    fun buildUnits(title: String, body: String): List<Unit> {
        val units = mutableListOf<Unit>()
        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty()) {
            units += Unit(
                id = "TITLE-U01",
                text = "Bạn đang nghe: $cleanTitle",
                fixedVoice = NARRATOR_ID,
                unitKind = "title",
            )
        }
        var paragraphIndex = 0
        body.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { raw ->
            val paragraph = raw.trim()
            if (paragraph.isNotEmpty()) {
                paragraphIndex += 1
                splitParagraph(units, paragraph, paragraphIndex)
            }
        }
        return coalesceNarrationUnits(units)
    }

    private fun splitParagraph(out: MutableList<Unit>, paragraph: String, paragraphIndex: Int) {
        if (splitDashDialogue(out, paragraph, paragraphIndex)) return
        if (splitColonDialogue(out, paragraph, paragraphIndex)) return
        splitQuoteAware(out, paragraph, paragraphIndex, 0, Metadata())
    }

    private fun splitQuoteAware(
        out: MutableList<Unit>,
        rawValue: String,
        paragraphIndex: Int,
        initialCounter: Int,
        metadata: Metadata,
    ): Int {
        val value = rawValue.trim()
        if (value.isEmpty()) return initialCounter
        var unitCounter = initialCounter
        if (metadata.forceDialogue) {
            val narrationReason = quoteNarrationReason(value, metadata.contextBefore, metadata.contextAfter)
            if (narrationReason != null) {
                return appendSized(
                    out, value, paragraphIndex, unitCounter, 1200,
                    narrationMetadata(metadata, "quoted_narration").copy(parsingNote = narrationReason),
                )
            }
            val dialogueMetadata = dialogueGroupMetadata(
                dialogueContextMetadata(metadata, metadata.contextBefore.orEmpty(), metadata.contextAfter.orEmpty()),
                paragraphIndex,
                unitCounter,
            )
            return appendSized(out, value, paragraphIndex, unitCounter, 1200, dialogueMetadata)
        }
        if (metadata.forceNarration) {
            return appendSized(
                out, value, paragraphIndex, unitCounter, 1200,
                narrationMetadata(metadata, metadata.unitKind ?: "narration"),
            )
        }

        var pos = 0
        while (pos < value.length) {
            var bestStart = -1
            var bestPair: QuotePair? = null
            for (pair in quotes) {
                val found = value.indexOf(pair.open, pos)
                if (found >= 0 && (bestStart < 0 || found < bestStart)) {
                    bestStart = found
                    bestPair = pair
                }
            }
            if (bestStart < 0 || bestPair == null) {
                unitCounter = appendSized(
                    out, value.substring(pos), paragraphIndex, unitCounter, 1200,
                    narrationMetadata(metadata, "narration"),
                )
                break
            }

            val beforeText = if (bestStart > pos) value.substring(pos, bestStart) else ""
            if (beforeText.isNotBlank()) {
                unitCounter = appendSized(
                    out, beforeText, paragraphIndex, unitCounter, 1200,
                    narrationMetadata(metadata, "narration"),
                )
            }

            val closeStart = value.indexOf(bestPair.close, bestStart + bestPair.open.length)
            if (closeStart < 0) {
                val fallbackEndExclusive = findUnclosedQuoteEndExclusive(value, bestStart, bestPair.open)
                val quoteText = value.substring(bestStart, fallbackEndExclusive)
                val afterText = value.substring(fallbackEndExclusive)
                val thought = containsThoughtCue(beforeText) || containsThoughtCue(afterText)
                val narrationReason = quoteNarrationReason(quoteText, beforeText, afterText)
                var quoteMetadata = when {
                    thought -> narrationMetadata(metadata, "inner_thought").copy(
                        unclosedQuote = true,
                        parsingNote = "Dấu ngoặc mở nhưng thiếu dấu đóng; nội dung có dấu hiệu là nội tâm.",
                        contextBefore = utf8Tail(beforeText, 260).ifBlank { null },
                        contextAfter = utf8Head(afterText, 260).ifBlank { null },
                    )
                    narrationReason != null -> narrationMetadata(metadata, "quoted_narration").copy(
                        unclosedQuote = true,
                        parsingNote = narrationReason,
                        contextBefore = utf8Tail(beforeText, 260).ifBlank { null },
                        contextAfter = utf8Head(afterText, 260).ifBlank { null },
                    )
                    else -> dialogueContextMetadata(metadata, beforeText, afterText).copy(
                        unclosedQuote = true,
                        parsingNote = "Dấu ngoặc thoại mở nhưng thiếu dấu đóng; chỉ dùng phạm vi đơn vị này để suy luận người nói.",
                    )
                }
                if (!thought && narrationReason == null) {
                    quoteMetadata = dialogueGroupMetadata(quoteMetadata, paragraphIndex, unitCounter)
                }
                unitCounter = appendSized(out, quoteText, paragraphIndex, unitCounter, 640, quoteMetadata)
                pos = fallbackEndExclusive
                while (pos < value.length && value[pos].isWhitespace()) pos += 1
                if (pos >= value.length) break
            } else {
                val closeEndExclusive = closeStart + bestPair.close.length
                val quoteText = value.substring(bestStart, closeEndExclusive)
                val afterText = value.substring(closeEndExclusive)
                val thought = containsThoughtCue(beforeText) || containsThoughtCue(utf8Head(afterText, 180))
                val narrationReason = quoteNarrationReason(quoteText, beforeText, afterText)
                var quoteMetadata = when {
                    thought -> narrationMetadata(metadata, "inner_thought").copy(
                        contextBefore = utf8Tail(beforeText, 260).ifBlank { null },
                        contextAfter = utf8Head(afterText, 260).ifBlank { null },
                    )
                    narrationReason != null -> narrationMetadata(metadata, "quoted_narration").copy(
                        parsingNote = narrationReason,
                        contextBefore = utf8Tail(beforeText, 260).ifBlank { null },
                        contextAfter = utf8Head(afterText, 260).ifBlank { null },
                    )
                    else -> dialogueContextMetadata(metadata, beforeText, afterText)
                }
                if (!thought && narrationReason == null) {
                    quoteMetadata = dialogueGroupMetadata(quoteMetadata, paragraphIndex, unitCounter)
                }
                unitCounter = appendSized(out, quoteText, paragraphIndex, unitCounter, 1200, quoteMetadata)
                pos = closeEndExclusive
            }
        }
        return unitCounter
    }

    private fun splitDashDialogue(out: MutableList<Unit>, paragraph: String, paragraphIndex: Int): Boolean {
        if (!Regex("^\\s*[—–-]\\s+").containsMatchIn(paragraph)) return false
        val delimiters = listOf(" — ", " – ", " - ")
        val parts = mutableListOf<String>()
        var start = 0
        while (true) {
            var bestStart = -1
            var bestEnd = -1
            for (delimiter in delimiters) {
                val found = paragraph.indexOf(delimiter, start)
                if (found >= 0 && (bestStart < 0 || found < bestStart)) {
                    bestStart = found
                    bestEnd = found + delimiter.length
                }
            }
            if (bestStart < 0) {
                parts += paragraph.substring(start)
                break
            }
            parts += paragraph.substring(start, bestStart)
            start = bestEnd
        }
        var counter = 0
        parts.forEachIndexed { index, part ->
            counter = if (index % 2 == 0) {
                splitQuoteAware(
                    out, part, paragraphIndex, counter,
                    Metadata(
                        forceDialogue = true,
                        contextBefore = parts.getOrNull(index - 1),
                        contextAfter = parts.getOrNull(index + 1),
                        dashDialogue = true,
                    ),
                )
            } else {
                splitQuoteAware(
                    out, part, paragraphIndex, counter,
                    Metadata(forceNarration = true, unitKind = "narration"),
                )
            }
        }
        return true
    }

    private fun splitColonDialogue(out: MutableList<Unit>, paragraph: String, paragraphIndex: Int): Boolean {
        val markers = findColonDialogueMarkers(paragraph)
        if (markers.isEmpty()) return false
        var counter = 0
        var cursor = 0
        markers.forEachIndexed { index, marker ->
            if (marker.prefixStart > cursor) {
                counter = splitQuoteAware(out, paragraph.substring(cursor, marker.prefixStart), paragraphIndex, counter, Metadata())
            }
            val labelText = paragraph.substring(marker.prefixStart, marker.colonEndExclusive).trim()
            val nextStart = markers.getOrNull(index + 1)?.prefixStart ?: paragraph.length
            val dialogueText = paragraph.substring(marker.colonEndExclusive, nextStart).trim()
            val labelHasQuote = quotes.any { labelText.contains(it.open) }
            counter = if (labelHasQuote) {
                splitQuoteAware(
                    out, labelText, paragraphIndex, counter,
                    Metadata(
                        speakerHint = marker.prefix,
                        contextAfter = dialogueText,
                        colonSpeakerLabel = true,
                    ),
                )
            } else {
                appendSized(
                    out, labelText, paragraphIndex, counter, 1200,
                    Metadata(
                        fixedVoice = NARRATOR_ID,
                        unitKind = "speaker_label",
                        colonSpeakerLabel = true,
                    ),
                )
            }
            if (dialogueText.isNotEmpty()) {
                val pair = leadingQuotePair(dialogueText)
                val dialogueMetadata = Metadata(
                    forceDialogue = pair == null,
                    speakerHint = marker.prefix,
                    colonDialogue = true,
                    contextBefore = labelText,
                )
                counter = if (pair != null) {
                    splitRecoveredColonQuote(out, dialogueText, paragraphIndex, counter, dialogueMetadata, pair)
                } else {
                    splitQuoteAware(out, dialogueText, paragraphIndex, counter, dialogueMetadata)
                }
            }
            cursor = nextStart
        }
        if (cursor < paragraph.length) {
            splitQuoteAware(out, paragraph.substring(cursor), paragraphIndex, counter, Metadata())
        }
        return true
    }

    private fun splitRecoveredColonQuote(
        out: MutableList<Unit>,
        dialogueText: String,
        paragraphIndex: Int,
        initialCounter: Int,
        metadata: Metadata,
        pair: QuotePair,
    ): Int {
        var counter = initialCounter
        val openCount = countToken(dialogueText, pair.open)
        val closeCount = countToken(dialogueText, pair.close)
        val symmetric = pair.open == pair.close
        val needsRecovery = if (symmetric) openCount % 2 == 1 else openCount != closeCount
        if (!needsRecovery) return splitQuoteAware(out, dialogueText, paragraphIndex, counter, metadata)

        var spanEndExclusive = dialogueText.length
        var unclosed = false
        if (symmetric) {
            if (openCount >= 3) {
                spanEndExclusive = lastTokenEndExclusive(dialogueText, pair.close) ?: dialogueText.length
            } else {
                unclosed = true
            }
        } else {
            val lastClose = lastTokenEndExclusive(dialogueText, pair.close)
            if (lastClose != null) spanEndExclusive = lastClose else unclosed = true
        }

        val quoteText = dialogueText.substring(0, spanEndExclusive).trim()
        val afterText = dialogueText.substring(spanEndExclusive).trim()
        val narrationReason = quoteNarrationReason(quoteText, metadata.contextBefore, afterText)
        var quoteMetadata = if (narrationReason != null) {
            narrationMetadata(metadata, "quoted_narration").copy(parsingNote = narrationReason)
        } else {
            dialogueGroupMetadata(
                dialogueContextMetadata(metadata, metadata.contextBefore.orEmpty(), afterText),
                paragraphIndex,
                counter,
            ).copy(
                parsingNote = if (unclosed) {
                    "Dấu ngoặc thoại mở nhưng thiếu dấu đóng; đã giữ toàn bộ phần còn lại sau nhãn người nói làm một lời thoại."
                } else {
                    "Phát hiện số dấu ngoặc không cân bằng; đã dùng dấu đầu và dấu cuối làm biên ngoài của lời thoại."
                },
            )
        }
        if (unclosed) quoteMetadata = quoteMetadata.copy(unclosedQuote = true)
        counter = appendSized(out, quoteText, paragraphIndex, counter, 1200, quoteMetadata)
        if (afterText.isNotEmpty()) {
            counter = splitQuoteAware(
                out, afterText, paragraphIndex, counter,
                Metadata(forceNarration = true, unitKind = "narration"),
            )
        }
        return counter
    }

    private fun findColonDialogueMarkers(value: String): List<ColonMarker> {
        val markers = mutableListOf<ColonMarker>()
        var search = 0
        while (search < value.length) {
            val ascii = value.indexOf(':', search).takeIf { it >= 0 }
            val full = value.indexOf('：', search).takeIf { it >= 0 }
            val bestPos = listOfNotNull(ascii, full).minOrNull() ?: break
            val token = value.substring(bestPos, bestPos + 1)
            val marker = colonCandidateAt(value, bestPos, token)
            if (marker != null && (markers.isEmpty() || marker.prefixStart > markers.last().colonEndExclusive - 1)) {
                markers += marker
            }
            search = bestPos + 1
        }
        return markers
    }

    private fun colonCandidateAt(value: String, colonPos: Int, colonToken: String): ColonMarker? {
        if (isInsideQuote(value, colonPos)) return null
        val boundaryEnd = lastPlainBeforeEndExclusive(value, colonBoundaries, colonPos)
        var prefixStart = boundaryEnd ?: 0
        while (prefixStart < colonPos) {
            if (value[prefixStart].isWhitespace()) {
                prefixStart += 1
                continue
            }
            val closer = quotes.firstOrNull { value.startsWith(it.close, prefixStart) }?.close
            if (closer != null) {
                prefixStart += closer.length
                continue
            }
            break
        }
        val colonEndExclusive = colonPos + colonToken.length
        val prefix = value.substring(prefixStart, colonPos).trim()
        val right = value.substring(colonEndExclusive).trim()
        if (prefix.isEmpty() || right.isEmpty() || utf8Bytes(prefix) > 180) return null
        if (prefix.contains(':') || prefix.contains('：') || prefix.contains("://")) return null
        if (right.startsWith("//")) return null
        if (prefix.firstOrNull()?.isDigit() == true && right.firstOrNull()?.isDigit() == true) return null
        if (prefix.lastOrNull()?.isDigit() == true && right.firstOrNull()?.isDigit() == true) return null

        val lower = normalizeSpaces(prefix.lowercase())
        if (startsWithHeading(lower)) return null
        if (containsThoughtCue(lower)) return null
        val cue = containsCueWord(lower)
        val words = prefix.split(Regex("\\s+")).count { it.isNotEmpty() }
        val simpleLabel = words in 1..8 &&
            utf8Bytes(prefix) <= 180 &&
            listOf(",", ";", ".", "!", "?", "。", "！", "？", "；").none(prefix::contains) &&
            !prefix.all(Char::isDigit)
        if (!cue && !simpleLabel) return null
        val numericOnly = right.replace(Regex("[\\d\\s.,:/%\\-]"), "").isEmpty()
        if (numericOnly) return null
        return ColonMarker(prefixStart, colonPos, colonEndExclusive, prefix)
    }

    private fun isInsideQuote(value: String, position: Int): Boolean {
        for (pair in quotes) {
            if (pair.open == pair.close) {
                var count = 0
                var search = 0
                while (true) {
                    val found = value.indexOf(pair.open, search)
                    if (found < 0 || found >= position) break
                    count += 1
                    search = found + pair.open.length
                }
                if (count % 2 == 1) return true
            } else {
                val lastOpen = value.lastIndexOf(pair.open, position - 1)
                val lastClose = value.lastIndexOf(pair.close, position - 1)
                if (lastOpen >= 0 && (lastClose < 0 || lastOpen > lastClose)) return true
            }
        }
        return false
    }

    private fun startsWithHeading(prefixLower: String): Boolean = colonHeadingPrefixes.any {
        prefixLower == it || prefixLower.startsWith("$it ")
    }

    private fun containsCueWord(prefixLower: String): Boolean {
        val tail = utf8Tail(prefixLower, 120)
        return colonCueWords.any { containsBoundedPhrase(tail, it) }
    }

    private fun containsThoughtCue(value: String): Boolean {
        val normalized = normalizeSpaces(value.trim().lowercase())
        if (normalized.isEmpty()) return false
        val tail = utf8Tail(normalized, 220)
        val thought = lastBoundedPhrase(tail, thoughtCueWords) ?: return false
        val speech = lastBoundedPhrase(tail, colonCueWords) ?: return true
        if (speech.first >= thought.first && speech.first <= thought.second) return true
        return thought.first > speech.first
    }

    private fun quoteNarrationReason(value: String, beforeText: String?, afterText: String?): String? {
        if (quoteContent(value).isEmpty()) {
            return "Đoạn trong ngoặc không có nội dung có nghĩa nên dùng giọng Người kể chuyện."
        }
        if (containsQuotedNarrationCue(beforeText.orEmpty(), afterText.orEmpty())) {
            return "Đoạn trong ngoặc có dấu hiệu là âm thanh, thuật ngữ hoặc lời được trích dẫn nên dùng giọng Người kể chuyện."
        }
        return null
    }

    private fun containsQuotedNarrationCue(beforeText: String, afterText: String): Boolean {
        val before = utf8Tail(normalizeSpaces(beforeText.trim().lowercase()), 240)
        val narration = lastBoundedPhrase(before, quotedNarrationCueWords)
        if (narration != null) {
            val speech = lastBoundedPhrase(before, colonCueWords)
            val closeEnough = utf8Bytes(before.substring(narration.second.coerceAtMost(before.length))) <= 90
            val supersededBySpeech = speech != null && speech.first > narration.second
            if (closeEnough && !supersededBySpeech) return true
        }

        @Suppress("UNUSED_VARIABLE") val reservedAfterText = afterText
        return false
    }

    private fun quoteContent(value: String): String {
        var normalized = value.trim()
        var changed = true
        while (changed && normalized.isNotEmpty()) {
            changed = false
            for (pair in quotes) {
                if (normalized.startsWith(pair.open)) {
                    normalized = normalized.removePrefix(pair.open).trim()
                    changed = true
                }
                if (normalized.endsWith(pair.close)) {
                    normalized = normalized.removeSuffix(pair.close).trim()
                    changed = true
                }
            }
        }
        listOf("“", "”", "「", "」", "『", "』", "‘", "’", "…", "\"").forEach {
            normalized = normalized.replace(it, " ")
        }
        normalized = normalized.replace(Regex("[\\p{Punct}\\p{P}]"), " ")
        return normalizeSpaces(normalized).trim()
    }

    private fun dialogueContextMetadata(metadata: Metadata, beforeText: String, afterText: String): Metadata =
        metadataForUnit(metadata, "dialogue", null).copy(
            contextBefore = utf8Tail(beforeText, 260).ifBlank { null },
            contextAfter = utf8Head(afterText, 260).ifBlank { null },
        )

    private fun dialogueGroupMetadata(metadata: Metadata, paragraphIndex: Int, unitCounter: Int): Metadata =
        if (metadata.dialogueGroupId.isNullOrBlank()) {
            metadata.copy(dialogueGroupId = "P%04d-D%02d".format(paragraphIndex, unitCounter + 1))
        } else metadata

    private fun narrationMetadata(metadata: Metadata, kind: String): Metadata = metadataForUnit(metadata, kind, NARRATOR_ID)

    private fun metadataForUnit(metadata: Metadata, kind: String, fixedVoice: String?): Metadata = metadata.copy(
        forceDialogue = false,
        forceNarration = false,
        fixedVoice = fixedVoice,
        unitKind = kind,
        directDialogue = fixedVoice == null,
    )

    private fun appendSized(
        out: MutableList<Unit>,
        rawValue: String,
        paragraphIndex: Int,
        initialCounter: Int,
        requestedMaxBytes: Int,
        metadata: Metadata,
    ): Int {
        val value = rawValue.trim()
        if (value.isEmpty()) return initialCounter
        val maxBytes = requestedMaxBytes.coerceAtLeast(300)
        var counter = initialCounter
        var start = 0
        while (start < value.length) {
            var cutExclusive = value.length
            if (utf8Bytes(value.substring(start)) > maxBytes) {
                cutExclusive = safeCharCutExclusive(value, start, maxBytes, 0.45)
            }
            if (cutExclusive <= start) cutExclusive = nextCharBoundary(value, start)
            val part = value.substring(start, cutExclusive).trim()
            if (part.isNotEmpty()) {
                counter += 1
                out += Unit(
                    id = "P%04d-U%02d".format(paragraphIndex, counter),
                    text = part,
                    fixedVoice = metadata.fixedVoice,
                    unitKind = metadata.unitKind ?: if (metadata.fixedVoice != null) "narration" else "dialogue",
                    directDialogue = metadata.directDialogue,
                    dialogueGroupId = metadata.dialogueGroupId,
                    speakerHint = metadata.speakerHint,
                    contextBefore = metadata.contextBefore,
                    contextAfter = metadata.contextAfter,
                    unclosedQuote = metadata.unclosedQuote,
                    parsingNote = metadata.parsingNote,
                    dashDialogue = metadata.dashDialogue,
                    colonDialogue = metadata.colonDialogue,
                    colonSpeakerLabel = metadata.colonSpeakerLabel,
                )
            }
            start = cutExclusive
            while (start < value.length && value[start].isWhitespace()) start += 1
        }
        return counter
    }

    private fun safeCharCutExclusive(value: String, start: Int, maxBytes: Int, breakRatio: Double): Int {
        var index = start
        var bytes = 0
        var lastSafe = start
        var lastBreak: Int? = null
        while (index < value.length) {
            val cp = value.codePointAt(index)
            val chars = Character.charCount(cp)
            val cpBytes = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
            if (bytes + cpBytes > maxBytes) break
            bytes += cpBytes
            index += chars
            lastSafe = index
            val ch = String(Character.toChars(cp))
            if (ch in listOf(" ", "\t", ",", ";", ".", "!", "?", "。", "！", "？")) lastBreak = index
        }
        val minimumBreakBytes = (maxBytes * breakRatio).toInt()
        if (lastBreak != null && utf8Bytes(value.substring(start, lastBreak)) >= minimumBreakBytes) return lastBreak
        return lastSafe.coerceAtLeast(nextCharBoundary(value, start))
    }

    private fun findUnclosedQuoteEndExclusive(value: String, openStart: Int, openToken: String): Int {
        val contentStart = (openStart + openToken.length).coerceAtMost(value.length)
        val hardEndExclusive = if (utf8Bytes(value.substring(openStart)) <= 640) {
            value.length
        } else {
            safeCharCutExclusive(value, openStart, 640, 0.40)
        }
        val sentenceEnd = findSentenceBoundaryExclusive(value, contentStart, hardEndExclusive)
        return sentenceEnd ?: hardEndExclusive
    }

    private fun findSentenceBoundaryExclusive(value: String, from: Int, toExclusive: Int): Int? {
        var best: Int? = null
        for (token in sentenceEndings) {
            var search = from
            while (search < toExclusive) {
                val found = value.indexOf(token, search)
                if (found < 0 || found >= toExclusive) break
                val endExclusive = (found + token.length).coerceAtMost(value.length)
                val previous = value.getOrNull(found - 1)
                val next = value.getOrNull(endExclusive)
                val decimalPoint = token == "." && previous?.isDigit() == true && next?.isDigit() == true
                val repeatedPoint = token == "." && (previous == '.' || next == '.')
                if (!decimalPoint && !repeatedPoint && (best == null || endExclusive < best)) best = endExclusive
                search = endExclusive
            }
        }
        return best
    }

    private fun coalesceNarrationUnits(units: MutableList<Unit>): List<Unit> {
        val out = mutableListOf<Unit>()
        for (unit in units) {
            val previous = out.lastOrNull()
            val mergeableKind = unit.unitKind == "narration" || unit.unitKind == "quoted_narration"
            val previousMergeable = previous != null && (previous.unitKind == "narration" || previous.unitKind == "quoted_narration")
            if (
                previous != null &&
                previous.fixedVoice == NARRATOR_ID && unit.fixedVoice == NARRATOR_ID &&
                previousMergeable && mergeableKind &&
                paragraphKey(previous.id) != null && paragraphKey(previous.id) == paragraphKey(unit.id) &&
                utf8Bytes(previous.text) + utf8Bytes(unit.text) <= 1200
            ) {
                previous.text = mergeNarrationText(previous.text, unit.text)
                previous.unitKind = "narration"
                if (!unit.parsingNote.isNullOrBlank()) {
                    previous.parsingNote = listOfNotNull(previous.parsingNote?.takeIf(String::isNotBlank), unit.parsingNote)
                        .joinToString(" ")
                }
            } else {
                out += unit
            }
        }
        return out
    }

    private fun mergeNarrationText(leftRaw: String, rightRaw: String): String {
        val left = leftRaw.trim()
        val right = rightRaw.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val directJoinPrefixes = listOf(".", ",", ";", ":", "!", "?", "%", ")", "]", "}", "…", "。", "！", "？", "；")
        return if (directJoinPrefixes.any(right::startsWith)) left + right else "$left $right"
    }

    private fun paragraphKey(id: String): String? = Regex("^(P\\d+)-").find(id)?.groupValues?.get(1)

    private fun leadingQuotePair(value: String): QuotePair? {
        val trimmed = value.trim()
        return quotes.firstOrNull { trimmed.startsWith(it.open) }
    }

    private fun countToken(value: String, token: String): Int {
        var count = 0
        var search = 0
        while (true) {
            val found = value.indexOf(token, search)
            if (found < 0) break
            count += 1
            search = found + token.length
        }
        return count
    }

    private fun lastTokenEndExclusive(value: String, token: String): Int? {
        val start = value.lastIndexOf(token)
        return if (start >= 0) start + token.length else null
    }

    private fun lastPlainBeforeEndExclusive(value: String, tokens: List<String>, beforePos: Int): Int? {
        var bestStart = -1
        var bestEnd = -1
        for (token in tokens) {
            var search = 0
            while (search < beforePos) {
                val found = value.indexOf(token, search)
                if (found < 0 || found >= beforePos) break
                if (found > bestStart) {
                    bestStart = found
                    bestEnd = found + token.length
                }
                search = found + token.length
            }
        }
        return bestEnd.takeIf { it >= 0 }
    }

    private fun containsBoundedPhrase(value: String, phrase: String): Boolean = lastBoundedPhrase(value, listOf(phrase)) != null

    private fun lastBoundedPhrase(value: String, phrases: List<String>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (phrase in phrases) {
            var search = 0
            while (search < value.length) {
                val found = value.indexOf(phrase, search)
                if (found < 0) break
                val endExclusive = found + phrase.length
                val before = value.getOrNull(found - 1) ?: ' '
                val after = value.getOrNull(endExclusive) ?: ' '
                if (isPhraseBoundary(before) && isPhraseBoundary(after) && (best == null || found > best.first)) {
                    best = found to endExclusive
                }
                search = endExclusive
            }
        }
        return best
    }

    private fun isPhraseBoundary(ch: Char): Boolean = ch.isWhitespace() || ch in listOf(',', ';', ':', '.', '!', '?', '-')

    private fun utf8Head(value: String, maxBytes: Int): String {
        val trimmed = value.trim()
        if (utf8Bytes(trimmed) <= maxBytes) return trimmed
        return trimmed.substring(0, safeCharCutExclusive(trimmed, 0, maxBytes, 0.40)).trim()
    }

    private fun utf8Tail(value: String, maxBytes: Int): String {
        val trimmed = value.trim()
        if (utf8Bytes(trimmed) <= maxBytes) return trimmed
        var index = trimmed.length
        var bytes = 0
        while (index > 0) {
            val cp = trimmed.codePointBefore(index)
            val chars = Character.charCount(cp)
            val cpBytes = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
            if (bytes + cpBytes > maxBytes) break
            bytes += cpBytes
            index -= chars
        }
        return trimmed.substring(index).trim()
    }

    private fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun nextCharBoundary(value: String, index: Int): Int {
        if (index >= value.length) return value.length
        return index + Character.charCount(value.codePointAt(index))
    }

    private fun normalizeSpaces(value: String): String = value.replace(Regex("\\s+"), " ")
}
