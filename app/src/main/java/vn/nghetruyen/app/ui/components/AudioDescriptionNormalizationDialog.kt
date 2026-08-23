package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.AiAuxiliaryJsonResult
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.freesound.FreesoundImporter
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderPlaybackService

enum class AudioDescriptionNormalizationScope {
    MISSING_VIETNAMESE,
    ALL_LIBRARY,
}

data class AudioDescriptionPreview(
    val trackId: String,
    val title: String,
    val kind: AudioAssetKind,
    val sourceDescription: String,
    val convertedDescription: String,
    val sourceRefreshed: Boolean,
)

internal fun audioDescriptionText(tagsCsv: String): String = tagsCsv
    .replace(AUDIO_DESCRIPTION_TYPE_MARKER_REGEX, "")
    .replace(AUDIO_DESCRIPTION_LEGACY_PROVENANCE_REGEX, "")
    .trim().trim(',', ';').trim()

internal fun audioDescriptionIsVietnameseStructured(tagsCsv: String): Boolean {
    val value = audioDescriptionText(tagsCsv).lowercase()
    return ("sắc thái:" in value || "sac thai:" in value) &&
        ("dùng:" in value || "dung:" in value) &&
        ("tránh:" in value || "tranh:" in value)
}

internal fun audioDescriptionNeedsNormalization(tagsCsv: String): Boolean =
    !audioDescriptionIsVietnameseStructured(tagsCsv)

private fun audioDescriptionTypeMarker(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "type:music"
    AudioAssetKind.AMBIENCE -> "type:ambience"
    AudioAssetKind.SFX -> "type:sfx"
}

private fun audioDescriptionTags(kind: AudioAssetKind, description: String): String {
    val marker = audioDescriptionTypeMarker(kind)
    val clean = description.replace(Regex("\\s+"), " ").trim().take(300)
    return if (clean.isBlank()) marker else "$marker, $clean"
}

@Composable
fun AudioDescriptionNormalizationDialog(
    tracks: List<SceneMusicTrackEntity>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AudioDescriptionNormalizationScope.MISSING_VIETNAMESE) }
    var running by remember { mutableStateOf(false) }
    var processed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var previews by remember { mutableStateOf<List<AudioDescriptionPreview>>(emptyList()) }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    var providerModel by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    val missingCount = tracks.count { audioDescriptionNeedsNormalization(it.tagsCsv) }
    val blankCount = tracks.count { audioDescriptionText(it.tagsCsv).isBlank() }
    val normalizedCount = tracks.count(::trackHasNormalizedDescription)
    val targetCount = when (mode) {
        AudioDescriptionNormalizationScope.MISSING_VIETNAMESE -> missingCount
        AudioDescriptionNormalizationScope.ALL_LIBRARY -> tracks.size
    }

    fun start() {
        if (running) return
        running = true
        applied = false
        processed = 0
        total = targetCount
        previews = emptyList()
        failures = emptyList()
        providerModel = ""
        job = scope.launch {
            val targets = tracks.filter { track ->
                when (mode) {
                    AudioDescriptionNormalizationScope.MISSING_VIETNAMESE -> !audioDescriptionIsVietnameseStructured(track.tagsCsv)
                    AudioDescriptionNormalizationScope.ALL_LIBRARY -> true
                }
            }
            val converted = mutableListOf<AudioDescriptionPreview>()
            val errors = mutableListOf<String>()
            targets.chunked(DESCRIPTION_BATCH_SIZE).forEach { batch ->
                val prepared = mutableListOf<PreparedDescription>()
                for (track in batch) {
                    val current = audioDescriptionText(track.tagsCsv)
                    var source = current
                    var refreshed = false
                    if (
                        source.isBlank() ||
                        (mode == AudioDescriptionNormalizationScope.ALL_LIBRARY &&
                            audioDescriptionIsVietnameseStructured(track.tagsCsv))
                    ) {
                        val soundId = FreesoundImporter.soundIdFromManagedUri(track.uri)
                        if (soundId != null) {
                            val original = application.container.freesoundClient.sound(soundId)?.description.orEmpty().trim()
                            if (original.isNotBlank()) {
                                source = original
                                refreshed = true
                            }
                        }
                    }
                    if (source.isBlank()) {
                        errors += "${track.title}: không có mô tả nguồn để chuẩn hóa."
                    } else {
                        prepared += PreparedDescription(
                            track = track,
                            kind = AudioAssetClassifier.classify(track),
                            source = source,
                            refreshed = refreshed,
                        )
                    }
                }
                if (prepared.isNotEmpty()) {
                    when (val result = application.container.xpkNarrationAiServices.completeAuxiliaryJson(
                        storyId = GLOBAL_VOICE_PROFILE_STORY_ID,
                        prompt = descriptionBatchPrompt(prepared),
                    )) {
                        is AppResult.Success -> {
                            providerModel = "${result.value.provider} / ${result.value.model}"
                            val parsed = runCatching { parseDescriptionBatch(result.value, prepared) }
                                .getOrElse { error ->
                                    errors += "Lô ${processed + 1}-${processed + batch.size}: ${error.message ?: "AI trả JSON không hợp lệ."}"
                                    emptyList()
                                }
                            converted += parsed
                            val returnedIds = parsed.mapTo(hashSetOf(), AudioDescriptionPreview::trackId)
                            prepared.filter { it.track.id !in returnedIds }.forEach { missing ->
                                errors += "${missing.track.title}: AI không trả kết quả."
                            }
                        }
                        is AppResult.Failure -> errors += "AI: ${result.message}"
                    }
                }
                processed += batch.size
                previews = converted.toList()
                failures = errors.toList()
            }
            running = false
            job = null
        }
    }

    fun applyResults() {
        if (running || previews.isEmpty()) return
        running = true
        job = scope.launch {
            val now = System.currentTimeMillis()
            val previewById = previews.associateBy(AudioDescriptionPreview::trackId)
            val updated = tracks.mapNotNull { track ->
                val preview = previewById[track.id] ?: return@mapNotNull null
                val kind = AudioAssetClassifier.classify(track)
                track.copy(
                    tagsCsv = audioDescriptionTags(kind, preview.convertedDescription),
                    updatedAt = now,
                )
            }
            withContext(Dispatchers.IO) {
                application.container.database.sceneMusicTrackDao().upsertAll(updated)
            }
            if (PlaybackQueueStore.state.value.isPlaying) {
                ReaderPlaybackService.command(application, ReaderPlaybackService.ACTION_REFRESH)
            }
            applied = true
            running = false
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("CHUẨN HÓA MÔ TẢ TOÀN BỘ THƯ VIỆN") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Áp dụng cho cùng một kho MUSIC / AMBIENCE / SFX ở cả Mode 1, 2 và 3.")
                Text("Mô tả gốc được gửi cho cùng AI/provider/model đang dùng để phân vai. AI chỉ chuyển thành metadata tiếng Việt theo cấu trúc: Sắc thái / Dùng / Tránh; không được tự bịa nhạc cụ, vật liệu hay nguồn âm nếu mô tả gốc không nói.")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { if (!running) mode = AudioDescriptionNormalizationScope.MISSING_VIETNAMESE },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (mode == AudioDescriptionNormalizationScope.MISSING_VIETNAMESE) "✓ CHƯA CÓ TIẾNG VIỆT" else "CHƯA CÓ TIẾNG VIỆT") }
                    Button(
                        onClick = { if (!running) mode = AudioDescriptionNormalizationScope.ALL_LIBRARY },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (mode == AudioDescriptionNormalizationScope.ALL_LIBRARY) "✓ TOÀN BỘ" else "TOÀN BỘ") }
                }
                Text("Tổng thư viện: ${tracks.size} • Đã chuẩn: $normalizedCount • Cần chuẩn: $missingCount • Trống mô tả: $blankCount")
                if (mode == AudioDescriptionNormalizationScope.ALL_LIBRARY) {
                    Text("TOÀN BỘ: với file Freesound, ứng dụng cố lấy lại mô tả gốc bằng sound ID trước khi AI chuẩn hóa lại. File local không có mô tả nguồn sẽ được liệt kê để bổ sung, không cho AI đoán từ tên.")
                }
                if (running || processed > 0) {
                    Text("Đã xử lý: $processed / $total • Thành công: ${previews.size} • Cần kiểm tra: ${failures.size}")
                }
                if (providerModel.isNotBlank()) Text("AI: $providerModel")
                if (previews.isNotEmpty()) {
                    Text("KẾT QUẢ XEM TRƯỚC — chưa ghi vào thư viện:")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(previews, key = { it.trackId }) { preview ->
                            Text("${preview.kind.name} • ${preview.title}")
                            if (preview.sourceRefreshed) Text("Nguồn: mô tả gốc Freesound đã lấy lại")
                            Text(preview.convertedDescription)
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
                if (failures.isNotEmpty()) {
                    Text("CẦN KIỂM TRA (${failures.size}):")
                    failures.take(12).forEach { Text("• $it") }
                    if (failures.size > 12) Text("… và ${failures.size - 12} mục khác.")
                }
                if (applied) Text("Đã áp dụng ${previews.size} mô tả vào thư viện chung.")
            }
        },
        confirmButton = {
            Column {
                if (!running && previews.isEmpty() && targetCount > 0) {
                    TextButton(onClick = ::start) { Text("BẮT ĐẦU CHUẨN HÓA ($targetCount)") }
                }
                if (!running && previews.isNotEmpty() && !applied) {
                    TextButton(onClick = ::applyResults) { Text("ÁP DỤNG ${previews.size} MỤC") }
                }
                if (applied) TextButton(onClick = onDismiss) { Text("XONG") }
            }
        },
        dismissButton = {
            if (running) {
                TextButton(onClick = {
                    job?.cancel()
                    job = null
                    running = false
                }) { Text("DỪNG") }
            } else if (!applied) {
                TextButton(onClick = onDismiss) { Text("HỦY") }
            }
        },
    )
}

private data class PreparedDescription(
    val track: SceneMusicTrackEntity,
    val kind: AudioAssetKind,
    val source: String,
    val refreshed: Boolean,
)

private fun trackHasNormalizedDescription(track: SceneMusicTrackEntity): Boolean =
    audioDescriptionIsVietnameseStructured(track.tagsCsv)

private fun descriptionBatchPrompt(items: List<PreparedDescription>): String {
    val payload = JSONArray().also { array ->
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.track.id)
                    .put("kind", item.kind.name)
                    .put("title", item.track.title.take(160))
                    .put("original_description", item.source.take(4_000)),
            )
        }
    }
    return """
Bạn là bộ chuẩn hóa metadata âm thanh cho ứng dụng đọc truyện.

ĐẦU VÀO là JSON chứa id, kind, title và original_description. original_description là nguồn thông tin chính về âm thanh. title chỉ là tín hiệu hỗ trợ.

Với MỖI mục, hãy mô tả bằng tiếng Việt đúng âm thanh thực sự nghe được, không mô tả cốt truyện. Không được tự khẳng định nhạc cụ, vật liệu, hành động, không gian hay nguồn âm nếu original_description không hỗ trợ. Nếu title và original_description mâu thuẫn, ưu tiên original_description.

Mỗi description phải ngắn gọn, tối đa 300 ký tự và đúng một dòng theo mẫu:
Sắc thái: <nguồn âm/vật liệu/hành động/cường độ/nhịp/không gian nghe được>; Dùng: <cảnh/tình huống phù hợp>; Tránh: <những âm hoặc tình huống gần giống nhưng không đúng>

Đặc biệt:
- MUSIC: nêu nhạc cụ/texture chỉ khi nguồn nói rõ; ưu tiên cảm xúc, nhịp, cường độ và kiểu phối khí.
- AMBIENCE: phân biệt chính xác gió/mưa/nước/biển/đám đông/giao thông/rừng/phòng trong nhà...
- SFX: phân biệt nguồn + hành động + vật liệu, ví dụ đấm/đá/kiếm/nổ/vỡ/quần áo/rơi/ngã/va chạm.
- Không thêm tên truyện, nhân vật, ID hay tên file vào description.

Chỉ trả JSON hợp lệ, không markdown:
{"items":[{"id":"...","description":"Sắc thái: ...; Dùng: ...; Tránh: ..."}]}

INPUT:
${payload}
""".trimIndent()
}

private fun parseDescriptionBatch(
    result: AiAuxiliaryJsonResult,
    prepared: List<PreparedDescription>,
): List<AudioDescriptionPreview> {
    val clean = result.content.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    val root = JSONObject(clean)
    val array = root.optJSONArray("items") ?: error("Thiếu mảng items.")
    val preparedById = prepared.associateBy { it.track.id }
    val output = mutableListOf<AudioDescriptionPreview>()
    for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val id = row.optString("id").trim()
        val source = preparedById[id] ?: continue
        val description = row.optString("description").replace(Regex("\\s+"), " ").trim().take(300)
        val lower = description.lowercase()
        val valid = ("sắc thái:" in lower || "sac thai:" in lower) &&
            ("dùng:" in lower || "dung:" in lower) &&
            ("tránh:" in lower || "tranh:" in lower)
        if (!valid) continue
        output += AudioDescriptionPreview(
            trackId = id,
            title = source.track.title,
            kind = source.kind,
            sourceDescription = source.source,
            convertedDescription = description,
            sourceRefreshed = source.refreshed,
        )
    }
    return output.distinctBy(AudioDescriptionPreview::trackId)
}

private const val DESCRIPTION_BATCH_SIZE = 12

private val AUDIO_DESCRIPTION_TYPE_MARKER_REGEX = Regex(
    """(?i)(?:type\s*[:=]\s*(?:sfx[_-]?continuous|continuous|sfx|sound[_-]?effect|ambience|environment|music)|\[(?:continuous|sfx[_-]?continuous|sfx|ambience|environment|music)])""",
)
private val AUDIO_DESCRIPTION_LEGACY_PROVENANCE_REGEX = Regex(
    """(?i)(?:^|[,;]\s*)freesound_(?:id|user|license|url)\s*:[^,;]*""",
)
