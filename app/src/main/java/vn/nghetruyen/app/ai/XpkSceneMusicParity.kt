package vn.nghetruyen.app.ai

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** XPK-compatible scene-music catalog, continuity, prompt and scene-boundary rules. */
object XpkSceneMusicParity {
    const val MAX_TRACKS = 500
    const val MAX_DESCRIPTION_CHARS = 300
    const val MODE = "ai_full_authority"
    const val SILENCE_TRACK_ID = "NONE"
    private const val MIN_MIDDLE_SCENE_UNITS = 2

    data class PromptTrack(
        val id: String,
        val name: String,
        val description: String,
    )

    data class PromptBlock(
        val instructions: String,
        val outputRules: String,
        val tracks: List<PromptTrack>,
        val incomingTrackId: String,
        val continuitySource: String,
    )

    data class RawScene(
        val startId: String,
        val endId: String,
        val trackId: String,
    )

    private val shuffleSerial = AtomicLong(0L)
    private val audioExtensions = listOf(
        ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus", ".wma", ".webm", ".aiff", ".aif",
    )

    fun promptBlock(
        title: String,
        firstUnitId: String,
        lastUnitId: String,
        tracks: List<SceneMusicTrackOption>,
        context: NarrationPlanContext,
    ): PromptBlock {
        val normalized = normalizeTracks(tracks)
        val shuffled = shuffleTracks(
            normalized,
            listOf(title, context.activeTrackId.orEmpty(), normalized.size.toString()).joinToString("\u0000"),
        )
        val validIds = normalized.map(PromptTrack::id).toHashSet()
        val incoming = context.activeTrackId.orEmpty().trim().takeIf { it in validIds } ?: SILENCE_TRACK_ID
        val source = context.incomingSource.trim().ifBlank { if (incoming == SILENCE_TRACK_ID) "none" else "provided" }
        val previousTail = context.previousChapterEnding.trim()
            .ifBlank { "Không có ngữ cảnh chương trước." }
            .let { utf8Tail(it, 3000) }
        val catalog = buildString {
            append("$SILENCE_TRACK_ID | IM LẶNG | Không phát nhạc nền; dùng khi im lặng phục vụ lời kể tốt hơn một bài nhạc gượng ép.")
            if (shuffled.isNotEmpty()) append('\n')
            append(
                shuffled.joinToString("\n") { track ->
                    buildString {
                        append(track.id).append(" | ").append(track.name)
                        if (track.description.isNotBlank()) append(" | ").append(track.description)
                    }
                },
            )
        }
        val instructions = """
            NHIỆM VỤ ĐẠO DIỄN NHẠC NỀN TRONG CÙNG PHẢN HỒI:

            Bạn đồng thời là đạo diễn nhạc nền cho truyện đọc. Hãy tạo một dòng nhạc hoặc khoảng im lặng có chủ ý từ cuối chương trước qua toàn bộ chương hiện tại, hỗ trợ diễn biến nhưng không lấn át lời đọc. Im lặng hoàn toàn hợp lệ khi nó tốt hơn việc ép một bài nhạc vào cảnh.

            DỮ LIỆU NỐI CHƯƠNG:

            INCOMING_TRACK_ID: $incoming
            NGUỒN XÁC ĐỊNH: $source

            INCOMING_TRACK_ID là bài dự kiến tiếp tục từ cảnh cuối chương trước. $SILENCE_TRACK_ID nghĩa là cuối chương trước đang im lặng hoặc không có bài hợp lệ để kế thừa.

            PREVIOUS_CHAPTER_TAIL chỉ dùng để hiểu sự tiếp nối. Không tạo assignment, music_scene hoặc sử dụng ID từ phần này:

            $previousTail

            QUY TẮC ĐẠO DIỄN:

            1. Đọc phần cuối chương trước và toàn bộ chương hiện tại trước khi chọn bài, chọn im lặng hoặc đặt bất kỳ ranh giới nào.
            2. INCOMING_TRACK_ID cho biết trạng thái nhạc ở cuối chương trước. Hãy đánh giá trạng thái đó cùng mọi bài trong TRACK_CATALOG và lựa chọn $SILENCE_TRACK_ID, không giữ hoặc đổi chỉ vì ranh giới chương.
            3. Ở đầu chương, giữ INCOMING_TRACK_ID khi nó vẫn phù hợp với chức năng kể chuyện và trạng thái thực tế của phần mở đầu; đổi ngay tại ID đầu khi một bài khác hoặc im lặng phù hợp hơn. Nếu INCOMING_TRACK_ID là $SILENCE_TRACK_ID và phần mở đầu vẫn nên im lặng thì tiếp tục $SILENCE_TRACK_ID.
            4. Nếu INCOMING_TRACK_ID là $SILENCE_TRACK_ID, không bắt buộc phải mở nhạc. So sánh im lặng với TRACK_CATALOG và chỉ chọn bài khi bài đó thực sự hỗ trợ cảnh tốt hơn.
            5. Trong toàn chương, tại mỗi chuyển biến đáng kể, đánh giá lại trạng thái đang dùng dựa trên ngữ cảnh trước sau, chức năng kể chuyện, hướng cảm xúc, nhịp kể, mức căng thẳng, không gian, thời gian, quy mô và tính chất của diễn biến. Trạng thái mới có thể là một track_id hoặc $SILENCE_TRACK_ID.
            6. Giữ bài hoặc giữ im lặng trong khoảng mà trạng thái đó còn phù hợp. Đổi tại đúng UNIT đầu tiên nơi trạng thái khác trở thành lựa chọn phù hợp hơn cho diễn biến đang bắt đầu.
            7. Ổn định quan trọng hơn phản ứng theo từng câu: không đổi giữa bài và im lặng vì một câu thoại, một cảm xúc thoáng qua, một động tác ngắn hoặc một từ khóa. Một cảnh nhạc/im lặng nằm giữa chương phải kéo dài ít nhất $MIN_MIDDLE_SCENE_UNITS UNIT; nếu thay đổi không đủ bền thì giữ trạng thái hiện tại.
            8. Không đặt mục tiêu về số lần đổi nhạc, số khoảng im lặng hoặc số lượng music_scene. Không ưu tiên một bài cho cả chương, không ưu tiên luôn có nhạc, không ưu tiên im lặng, không ưu tiên đổi ít và cũng không ưu tiên đổi nhiều. Số cảnh phải hoàn toàn là kết quả của nhu cầu thực tế trong nội dung.
            9. Một chuyển biến quan trọng chỉ tạo ranh giới khi nó thực sự mở ra một đơn vị kể chuyện mới có chức năng âm nhạc khác; không dùng BGM như SFX để nhấn một khoảnh khắc đơn lẻ.
            10. Không dựa riêng vào từ khóa, nhãn cảm xúc hoặc độ dài bài đã phát. Luôn xét diễn biến đầy đủ trước và sau ranh giới.
            11. Có thể giữ INCOMING_TRACK_ID qua một phần hoặc toàn bộ chương, đổi khỏi nó ngay đầu chương, dùng lại một bài sau khi đã chuyển qua bài khác, hoặc xen các khoảng $SILENCE_TRACK_ID khi lời kể nên đứng một mình, miễn mỗi quyết định phù hợp với nội dung.
            12. Hai cảnh liền nhau không được cùng track_id; nếu cùng bài hoặc cùng $SILENCE_TRACK_ID thì phải gộp thành một cảnh liên tục.
            13. Mỗi music_scene là một khoảng liên tục dùng cùng một track_id hoặc $SILENCE_TRACK_ID. start_id và end_id đều được tính bao gồm.
            14. Cảnh đầu bắt đầu tại ID $firstUnitId; cảnh cuối kết thúc tại ID $lastUnitId. Các cảnh phải đúng thứ tự TIMELINE, liên tục, không chồng lấn và không bỏ sót UNIT hoặc DIALOGUE.
            15. Với hai cảnh liên tiếp, start_id của cảnh sau phải là phần tử ngay sau end_id của cảnh trước. Mọi ID phải có thật trong chương; track_id phải có thật trong TRACK_CATALOG hoặc chính xác là $SILENCE_TRACK_ID.
            16. Phần mô tả sau tên bài, nếu có, chỉ là dữ liệu tham khảo về đặc tính của chính tệp nhạc. Hãy đối chiếu mô tả với toàn bộ ngữ cảnh và tự chọn bài phù hợp nhất; không coi một nhãn riêng lẻ là mệnh lệnh bắt buộc.
            17. Không trả tên bài, URI, đường dẫn, thời gian theo giây, cảm xúc, thể loại, cường độ, lý do lựa chọn hoặc trường phụ.

            KIỂM TRA ÂM THẦM TRƯỚC KHI TRẢ:

            18. Kiểm tra toàn chương được phủ kín, các ID và track_id/$SILENCE_TRACK_ID hợp lệ, không có hai cảnh liền nhau cùng trạng thái.
            19. Kiểm tra không có cảnh nhạc hoặc im lặng giữa chương chỉ tồn tại một UNIT; nếu có, hãy bỏ ranh giới phản ứng quá nhanh và gộp nó vào ngữ cảnh ổn định phù hợp hơn.
            20. Kiểm tra từng khoảng và từng ranh giới chỉ theo mức độ phù hợp với diễn biến, không theo mong muốn tăng hoặc giảm số lần đổi nhạc hay số đoạn im lặng.
            21. Kiểm tra riêng điểm đầu chương: INCOMING_TRACK_ID đã được giữ hoặc thay thế sau khi so sánh thực chất với phần mở đầu, TRACK_CATALOG và lựa chọn im lặng, không phải do thói quen.
            22. Không trình bày quá trình suy luận hoặc kết quả kiểm tra.

            TRACK_CATALOG, định dạng track_id | tên bài | mô tả tham khảo nếu có:

            $catalog
        """.trimIndent()
        val outputRules = """
            - Khi nhiệm vụ nhạc được bật, JSON phải có mảng music_scenes.
            - music_scenes phải giữ đúng thứ tự từ đầu đến cuối chương và phủ kín toàn bộ UNIT, kể cả khoảng chủ ý im lặng.
            - Mỗi phần tử music_scenes có đúng ba trường: start_id, end_id, track_id.
            - track_id phải khớp chính xác một mã trong TRACK_CATALOG hoặc là $SILENCE_TRACK_ID để biểu diễn im lặng.
            - Không có hai phần tử music_scenes liền nhau dùng cùng track_id.
            - Cảnh nhạc hoặc im lặng nằm giữa chương không được ngắn hơn $MIN_MIDDLE_SCENE_UNITS UNIT.
        """.trimIndent()
        return PromptBlock(instructions, outputRules, shuffled, incoming, source)
    }

    fun continuityTailForPrompt(title: String, body: String, maxUnits: Int = 5): String {
        val units = XpkVoiceCastSplitter.buildUnits(title, body)
        val limit = maxUnits.coerceIn(1, 8)
        val first = (units.size - limit).coerceAtLeast(0)
        return units.drop(first).mapIndexedNotNull { offset, unit ->
            val compact = utf8Head(oneLine(unit.text), 420)
            if (compact.isBlank()) return@mapIndexedNotNull null
            val absoluteIndex = first + offset
            val kind = unit.unitKind.ifBlank { if (unit.fixedVoice != null) "narration" else "dialogue" }
            val attributes = mutableListOf(
                "offset=-${units.size - absoluteIndex}",
                "kind=$kind",
            )
            unit.speakerHint?.takeIf(String::isNotBlank)?.let { attributes += "speaker_hint=${oneLine(it)}" }
            "[PREVIOUS_UNIT ${attributes.joinToString(" | ")}] $compact"
        }.joinToString("\n")
    }

    fun validateScenes(
        rows: List<RawScene>,
        validUnitIds: List<String>,
        validTrackIds: List<String>,
    ): List<SceneMusicCue> {
        require(rows.isNotEmpty()) { "Kết quả không có music_scenes" }
        require(validUnitIds.isNotEmpty()) { "Danh sách UNIT hợp lệ đang trống" }
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val validTracks = validTrackIds.map(String::trim)
            .filter(String::isNotBlank)
            .toHashSet()
            .apply { add(SILENCE_TRACK_ID) }
        val out = mutableListOf<SceneMusicCue>()
        var cursor = 0
        rows.forEachIndexed { position, raw ->
            val startId = raw.startId.trim()
            val endId = raw.endId.trim()
            val trackId = raw.trackId.trim()
            val start = order[startId] ?: error("Cảnh nhạc thứ ${position + 1} dùng UNIT không tồn tại")
            val end = order[endId] ?: error("Cảnh nhạc thứ ${position + 1} dùng UNIT không tồn tại")
            require(trackId in validTracks) { "Cảnh nhạc thứ ${position + 1} dùng track_id không tồn tại" }
            require(start == cursor) { "Cảnh nhạc thứ ${position + 1} không nối tiếp cảnh trước" }
            require(end >= start) { "Cảnh nhạc thứ ${position + 1} có ranh giới đảo ngược" }
            val previous = out.lastOrNull()
            if (previous != null && previous.trackId == trackId) {
                out[out.lastIndex] = previous.copy(
                    endUnitId = endId,
                    endParagraph = paragraphIndexFromUnitId(endId),
                )
            } else {
                out += SceneMusicCue(
                    startParagraph = paragraphIndexFromUnitId(startId).coerceAtLeast(0),
                    trackId = trackId,
                    volume = 1f,
                    mood = "",
                    startUnitId = startId,
                    endUnitId = endId,
                    endParagraph = paragraphIndexFromUnitId(endId).coerceAtLeast(0),
                )
            }
            cursor = end + 1
        }
        require(cursor == validUnitIds.size) { "music_scenes chưa phủ kín toàn bộ UNIT" }
        if (out.size > 2) {
            out.subList(1, out.lastIndex).forEachIndexed { offset, scene ->
                val start = order.getValue(scene.startUnitId)
                val end = order.getValue(scene.endUnitId)
                require(end - start + 1 >= MIN_MIDDLE_SCENE_UNITS) {
                    "Cảnh nhạc/im lặng giữa chương thứ ${offset + 2} quá ngắn; tránh đổi BGM theo từng câu."
                }
            }
        }
        return out
    }

    fun fallbackScene(
        validUnitIds: List<String>,
        validTrackIds: List<String>,
        incomingTrackId: String?,
    ): List<SceneMusicCue> {
        if (validUnitIds.isEmpty()) return emptyList()
        val tracks = validTrackIds.map(String::trim).filter(String::isNotBlank).distinct()
        val selected = incomingTrackId.orEmpty().trim().takeIf { it in tracks } ?: SILENCE_TRACK_ID
        val start = validUnitIds.first()
        val end = validUnitIds.last()
        return listOf(
            SceneMusicCue(
                startParagraph = paragraphIndexFromUnitId(start).coerceAtLeast(0),
                trackId = selected,
                volume = 1f,
                mood = "",
                startUnitId = start,
                endUnitId = end,
                endParagraph = paragraphIndexFromUnitId(end).coerceAtLeast(0),
            ),
        )
    }

    fun normalizeTracks(tracks: List<SceneMusicTrackOption>): List<PromptTrack> {
        val seen = hashSetOf<String>()
        return buildList {
            tracks.take(MAX_TRACKS).forEach { track ->
                val id = track.id.trim()
                val name = stripAudioFileExtension(oneLine(track.title))
                var description = oneLine(track.description.ifBlank { track.tags.joinToString(" ") })
                description = takeCodePoints(description, MAX_DESCRIPTION_CHARS)
                description = utf8Head(description, 1200)
                if (id.isNotBlank() && id != SILENCE_TRACK_ID && name.isNotBlank() && seen.add(id)) {
                    add(PromptTrack(id, name, description))
                }
            }
        }
    }

    private fun shuffleTracks(rows: List<PromptTrack>, salt: String): List<PromptTrack> {
        if (rows.size < 2) return rows
        val out = rows.toMutableList()
        var seed = System.currentTimeMillis() / 1000L + shuffleSerial.incrementAndGet() * 104729L
        seed += Math.floorMod(System.nanoTime(), 2147483647L)
        salt.toByteArray(Charsets.UTF_8).forEach { byte ->
            seed = (seed * 131L + (byte.toInt() and 0xff)) % 2147483647L
        }
        seed = abs(seed) % 2147483647L
        if (seed == 0L) seed = 1L
        var state = seed
        for (index in out.lastIndex downTo 1) {
            state = (state * 48271L) % 2147483647L
            val swap = (state % (index + 1)).toInt()
            val temp = out[index]
            out[index] = out[swap]
            out[swap] = temp
        }
        return out
    }

    private fun stripAudioFileExtension(value: String): String {
        val clean = value.trim()
        val lower = clean.lowercase()
        val extension = audioExtensions.firstOrNull { clean.length > it.length && lower.endsWith(it) }
        return if (extension == null) clean else clean.dropLast(extension.length).trim()
    }

    private fun paragraphIndexFromUnitId(unitId: String): Int {
        if (unitId == "TITLE-U01") return 0
        val paragraph = Regex("^P(\\d{4})-U\\d{2}$").matchEntire(unitId)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return -1
        return (paragraph - 1).coerceAtLeast(0)
    }

    private fun takeCodePoints(value: String, maxCodePoints: Int): String {
        if (value.codePointCount(0, value.length) <= maxCodePoints) return value
        val end = value.offsetByCodePoints(0, maxCodePoints)
        return value.substring(0, end).trim()
    }

    private fun oneLine(value: String): String = value
        .replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun utf8Head(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.append(piece)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return out.toString().trim()
    }

    private fun utf8Tail(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val points = value.codePoints().toArray()
        val out = ArrayDeque<String>()
        var bytes = 0
        for (index in points.indices.reversed()) {
            val piece = String(Character.toChars(points[index]))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.addFirst(piece)
            bytes += size
        }
        return out.joinToString("").trim()
    }
}
