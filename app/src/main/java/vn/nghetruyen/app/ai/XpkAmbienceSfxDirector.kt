package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.audio.AmbienceScene
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.SoundEffectCue
import vn.nghetruyen.app.playback.PlaybackSnapshot

/**
 * AI contract for the two new sound layers.
 *
 * The existing XPK scene-music plan remains authoritative for music. Its selected scenes are passed
 * as read-only context so ambience/SFX can complement rather than fight the music. Every new cue is
 * anchored to a canonical UNIT/DIALOGUE id; absolute time is deliberately forbidden.
 */
object XpkAmbienceSfxDirector {
    const val ENGINE = "xpk-audio-direction-v1"
    const val MAX_ASSETS_PER_KIND = 200
    const val MAX_DESCRIPTION_CHARS = 300

    fun buildPrompt(
        snapshot: PlaybackSnapshot,
        ambienceAssets: List<AudioDirectionAsset>,
        soundEffectAssets: List<AudioDirectionAsset>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
        previousChapterTail: String,
        musicScenesContext: String,
        incomingAmbienceId: String?,
    ): String {
        val units = snapshot.speechChunks.filter { it.unitId.isNotBlank() }
        require(units.isNotEmpty()) { "Timeline XPK không có UNIT để đạo diễn âm thanh." }

        val ambience = if (ambienceEnabled) ambienceAssets.take(MAX_ASSETS_PER_KIND) else emptyList()
        val sfx = if (soundEffectsEnabled) soundEffectAssets.take(MAX_ASSETS_PER_KIND) else emptyList()
        val validIncoming = incomingAmbienceId?.trim()?.takeIf { id -> ambience.any { it.id == id } } ?: "NONE"
        val maxSfx = maxSfxForUnits(units.size)

        fun catalog(items: List<AudioDirectionAsset>): String = items.joinToString("\n") { asset ->
            val name = stripAudioExtension(oneLine(asset.title)).take(160)
            val description = oneLine(asset.description).take(MAX_DESCRIPTION_CHARS)
            buildString {
                append(asset.id).append(" | ").append(name)
                if (description.isNotBlank()) append(" | ").append(description)
            }
        }.ifBlank { "DISABLED_OR_EMPTY" }

        val timeline = units.joinToString("\n") { unit ->
            val kind = unit.unitKind.ifBlank { "unknown" }
            val text = oneLine(unit.text).let { value -> if (value.length <= 720) value else value.take(720) }
            "[UNIT id=${unit.unitId} | kind=$kind] $text"
        }

        return """
            Bạn là AI SOUND DIRECTOR cho truyện đọc. Hãy đọc TOÀN BỘ timeline trước khi quyết định âm thanh.

            MỤC TIÊU:
            - AMBIENCE là lớp môi trường kéo dài: mưa, rừng, quán trọ, chợ, gió, sóng, chiến trường xa... Chỉ dùng khi không gian thực sự cần một nền âm thanh môi trường.
            - SFX là hiệu ứng một lần: sấm, cửa, rút kiếm, va kiếm, nổ, bước chân, vó ngựa, vỡ kính... Chỉ dùng cho âm thanh đáng chú ý có giá trị kể chuyện.
            - Lời đọc TTS luôn có ưu tiên cao nhất. Không biến truyện thành chuỗi hiệu ứng liên tục.
            - Nhạc cảnh đã có hệ thống riêng. MUSIC_SCENES_CONTEXT chỉ để phối hợp; không được sửa hay trả lại music_scenes.

            TRẠNG THÁI LỚP ÂM THANH:
            AMBIENCE_ENABLED: $ambienceEnabled
            SFX_ENABLED: $soundEffectsEnabled
            INCOMING_AMBIENCE_ID: $validIncoming
            MAX_SFX_CUES_THIS_CHAPTER: $maxSfx

            QUY TẮC BẮT BUỘC:
            1. Không dùng thời gian theo giây/mili-giây. Mọi quyết định phải bám UNIT id.
            2. Chỉ chọn id có thật trong catalog tương ứng; không tạo tên file, URI, đường dẫn hoặc id mới.
            3. Không trả volume, mood, genre, intensity, confidence, reason hay trường phụ. Runtime tự xử lý âm lượng, LUFS, ducking và giới hạn đồng thời.
            4. Nếu một lớp đang tắt hoặc catalog rỗng, mảng đầu ra của lớp đó bắt buộc rỗng.
            5. ambience_scenes KHÔNG bắt buộc phủ kín chương. Có thể để khoảng im lặng. Các cảnh phải theo thứ tự timeline, không chồng lấn; start_id/end_id đều tính bao gồm.
            6. Chỉ mở ambience khi môi trường có tính liên tục đủ rõ. Không đổi ambience vì một từ khóa hoặc chi tiết thoáng qua.
            7. sfx_cues là one-shot tại ĐẦU UNIT được chỉ định. Tối đa một SFX cho một UNIT và tổng số không vượt MAX_SFX_CUES_THIS_CHAPTER.
            8. Không mô phỏng mọi hành động. Chỉ chọn SFX khi âm thanh đó đáng lẽ người nghe/nhân vật phải chú ý, giúp hiểu sự kiện, tạo không gian hoặc nhấn cao trào.
            9. Tránh lặp cùng effect_id ở các UNIT gần nhau nếu không có lý do âm thanh rõ ràng.
            10. Đọc ngữ cảnh trước và sau ranh giới; không quyết định chỉ bằng keyword.
            11. PREVIOUS_CHAPTER_TAIL chỉ là ngữ cảnh nối chương, tuyệt đối không dùng ID của nó trong đầu ra.
            12. INCOMING_AMBIENCE_ID chỉ là phương án kế thừa. Giữ nếu môi trường thực sự tiếp tục; thay hoặc ngừng ngay khi bối cảnh hiện tại yêu cầu.

            PREVIOUS_CHAPTER_TAIL:
            ${previousChapterTail.trim().ifBlank { "Không có ngữ cảnh chương trước." }.takeLast(3_500)}

            MUSIC_SCENES_CONTEXT:
            ${musicScenesContext.trim().ifBlank { "[]" }.take(8_000)}

            AMBIENCE_CATALOG, định dạng ambience_id | tên | mô tả:
            ${catalog(ambience)}

            SFX_CATALOG, định dạng effect_id | tên | mô tả:
            ${catalog(sfx)}

            TIMELINE CHƯƠNG HIỆN TẠI:
            $timeline

            ĐẦU RA BẮT BUỘC:
            - Chỉ trả đúng một JSON object hợp lệ, không markdown, không giải thích.
            - JSON có đúng hai mảng cấp cao: ambience_scenes và sfx_cues.
            - Mỗi ambience_scenes item có đúng ba trường: start_id, end_id, ambience_id.
            - Mỗi sfx_cues item có đúng hai trường: unit_id, effect_id.
            - Giữ đúng thứ tự timeline.

            Cấu trúc:
            {
              "ambience_scenes": [
                {"start_id":"UNIT_THAT","end_id":"UNIT_THAT","ambience_id":"ID_HOP_LE"}
              ],
              "sfx_cues": [
                {"unit_id":"UNIT_THAT","effect_id":"ID_HOP_LE"}
              ]
            }
        """.trimIndent()
    }

    fun parseAndValidate(
        raw: String,
        validUnitIds: List<String>,
        validAmbienceIds: Set<String>,
        validSfxIds: Set<String>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
    ): AmbienceSfxPlan {
        require(validUnitIds.isNotEmpty()) { "Timeline UNIT hợp lệ đang trống." }
        val root = JSONObject(raw.trim())
        val keys = root.keys().asSequence().toSet()
        require(keys == setOf("ambience_scenes", "sfx_cues")) {
            "Kết quả audio direction phải có đúng ambience_scenes và sfx_cues."
        }
        val order = validUnitIds.withIndex().associate { it.value to it.index }

        val ambienceArray = root.optJSONArray("ambience_scenes") ?: JSONArray()
        val ambienceScenes = mutableListOf<AmbienceScene>()
        var previousEnd = -1
        for (index in 0 until ambienceArray.length()) {
            val row = ambienceArray.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("start_id", "end_id", "ambience_id")) {
                "ambience_scenes[$index] có trường không hợp lệ."
            }
            val startId = row.getString("start_id").trim()
            val endId = row.getString("end_id").trim()
            val ambienceId = row.getString("ambience_id").trim()
            val start = order[startId] ?: error("ambience_scenes[$index] dùng start_id không tồn tại.")
            val end = order[endId] ?: error("ambience_scenes[$index] dùng end_id không tồn tại.")
            require(ambienceEnabled) { "AI trả ambience trong khi lớp ambience đang tắt." }
            require(ambienceId in validAmbienceIds) { "ambience_scenes[$index] dùng ambience_id không tồn tại." }
            require(end >= start) { "ambience_scenes[$index] có ranh giới đảo ngược." }
            require(start > previousEnd) { "ambience_scenes bị chồng lấn hoặc sai thứ tự." }
            val previous = ambienceScenes.lastOrNull()
            if (previous != null && previous.ambienceId == ambienceId && order[previous.endUnitId]?.plus(1) == start) {
                ambienceScenes[ambienceScenes.lastIndex] = previous.copy(endUnitId = endId)
            } else {
                ambienceScenes += AmbienceScene(startId, endId, ambienceId)
            }
            previousEnd = end
        }

        val sfxArray = root.optJSONArray("sfx_cues") ?: JSONArray()
        val maxSfx = maxSfxForUnits(validUnitIds.size)
        require(sfxArray.length() <= maxSfx) { "AI trả quá nhiều SFX cho một chương." }
        val soundEffectCues = mutableListOf<SoundEffectCue>()
        val usedUnits = hashSetOf<String>()
        var previousSfxIndex = -1
        for (index in 0 until sfxArray.length()) {
            val row = sfxArray.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("unit_id", "effect_id")) {
                "sfx_cues[$index] có trường không hợp lệ."
            }
            val unitId = row.getString("unit_id").trim()
            val effectId = row.getString("effect_id").trim()
            val unitIndex = order[unitId] ?: error("sfx_cues[$index] dùng unit_id không tồn tại.")
            require(soundEffectsEnabled) { "AI trả SFX trong khi lớp SFX đang tắt." }
            require(effectId in validSfxIds) { "sfx_cues[$index] dùng effect_id không tồn tại." }
            require(unitIndex >= previousSfxIndex) { "sfx_cues sai thứ tự timeline." }
            require(usedUnits.add(unitId)) { "Mỗi UNIT chỉ được có tối đa một SFX." }
            soundEffectCues += SoundEffectCue(unitId, effectId)
            previousSfxIndex = unitIndex
        }

        if (!ambienceEnabled) require(ambienceScenes.isEmpty())
        if (!soundEffectsEnabled) require(soundEffectCues.isEmpty())
        return AmbienceSfxPlan(ambienceScenes, soundEffectCues)
    }

    fun encode(plan: AmbienceSfxPlan): String = JSONObject()
        .put("engine", ENGINE)
        .put(
            "ambience_scenes",
            JSONArray().also { array ->
                plan.ambienceScenes.forEach { cue ->
                    array.put(
                        JSONObject()
                            .put("start_id", cue.startUnitId)
                            .put("end_id", cue.endUnitId)
                            .put("ambience_id", cue.ambienceId),
                    )
                }
            },
        )
        .put(
            "sfx_cues",
            JSONArray().also { array ->
                plan.soundEffectCues.forEach { cue ->
                    array.put(JSONObject().put("unit_id", cue.unitId).put("effect_id", cue.effectId))
                }
            },
        )
        .toString()

    fun decodePersisted(
        text: String,
        validUnitIds: List<String>,
        validAmbienceIds: Set<String>,
        validSfxIds: Set<String>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
    ): AmbienceSfxPlan {
        val root = JSONObject(text)
        require(root.optString("engine") == ENGINE) { "Audio direction cache dùng engine cũ." }
        val raw = JSONObject()
            .put("ambience_scenes", root.optJSONArray("ambience_scenes") ?: JSONArray())
            .put("sfx_cues", root.optJSONArray("sfx_cues") ?: JSONArray())
            .toString()
        return parseAndValidate(
            raw,
            validUnitIds,
            validAmbienceIds,
            validSfxIds,
            ambienceEnabled,
            soundEffectsEnabled,
        )
    }

    private fun maxSfxForUnits(unitCount: Int): Int = ((unitCount + 3) / 4).coerceIn(4, 48)

    private fun oneLine(value: String): String = value
        .replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripAudioExtension(value: String): String = value.replace(
        Regex("(?i)\\.(mp3|m4a|aac|wav|ogg|flac|opus|wma|webm|aiff|aif)$"),
        "",
    )
}
