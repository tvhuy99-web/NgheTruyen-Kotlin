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
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.AiAuxiliaryJsonResult
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderPlaybackService
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

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
    var diagnosticTraceId by remember { mutableStateOf("") }

    fun diagnostic(
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val traceId = diagnosticTraceId.ifBlank { "audio-description:${UUID.randomUUID()}" }
        val operationAttributes = when (name) {
            "AUDIO_DESCRIPTION_NORMALIZATION_START" -> DiagnosticOperationContract.attributes(
                id = traceId,
                kind = "AUDIO_DESCRIPTION_NORMALIZATION",
                flow = "runtime",
                state = DiagnosticOperationState.STARTED,
                stage = name,
            )
            "AUDIO_DESCRIPTION_NORMALIZATION_DONE" -> DiagnosticOperationContract.attributes(
                id = traceId,
                kind = "AUDIO_DESCRIPTION_NORMALIZATION",
                flow = "runtime",
                state = DiagnosticOperationState.COMPLETED,
                stage = name,
            )
            "AUDIO_DESCRIPTION_NORMALIZATION_CANCELLED" -> DiagnosticOperationContract.attributes(
                id = traceId,
                kind = "AUDIO_DESCRIPTION_NORMALIZATION",
                flow = "runtime",
                state = DiagnosticOperationState.FAILED,
                stage = name,
            )
            "AUDIO_DESCRIPTION_APPLY_START" -> DiagnosticOperationContract.attributes(
                id = traceId,
                kind = "AUDIO_DESCRIPTION_APPLY",
                flow = "runtime",
                state = DiagnosticOperationState.STARTED,
                stage = name,
            )
            "AUDIO_DESCRIPTION_APPLY_DONE" -> DiagnosticOperationContract.attributes(
                id = traceId,
                kind = "AUDIO_DESCRIPTION_APPLY",
                flow = "runtime",
                state = DiagnosticOperationState.COMPLETED,
                stage = name,
            )
            else -> emptyMap()
        }
        runCatching {
            application.container.sourceDiagnostics.mark(
                name = name,
                category = DiagnosticCategory.RUNTIME,
                severity = severity,
                sourceId = "audio-description",
                traceId = traceId,
                attributes = operationAttributes + attributes,
            )
        }
    }

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
        diagnosticTraceId = "audio-description:${UUID.randomUUID()}"
        processed = 0
        total = targetCount
        previews = emptyList()
        failures = emptyList()
        providerModel = ""
        diagnostic(
            "AUDIO_DESCRIPTION_NORMALIZATION_START",
            attributes = mapOf(
                "scope" to mode.name,
                "targets" to targetCount.toString(),
                "music" to tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }.toString(),
                "ambience" to tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }.toString(),
                "sfx" to tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }.toString(),
                "blankSourceDescriptions" to blankCount.toString(),
                "inputPolicy" to "TITLE_PLUS_STORED_DESCRIPTION_OFFLINE",
            ),
        )
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
                val prepared = batch.map { track ->
                    PreparedDescription(
                        track = track,
                        kind = AudioAssetClassifier.classify(track),
                        source = audioDescriptionText(track.tagsCsv),
                        refreshed = false,
                    )
                }
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
                            errors += "${missing.track.title}: AI không trả kết quả hợp lệ."
                        }
                    }
                    is AppResult.Failure -> errors += "AI: ${result.message}"
                }
                processed += batch.size
                previews = converted.toList()
                failures = errors.toList()
                diagnostic(
                    "AUDIO_DESCRIPTION_BATCH_PARSED",
                    attributes = mapOf(
                        "batchSize" to batch.size.toString(),
                        "processed" to processed.toString(),
                        "total" to total.toString(),
                        "prepared" to prepared.size.toString(),
                        "convertedTotal" to converted.size.toString(),
                        "failuresTotal" to errors.size.toString(),
                        "sourceRefetchedTotal" to "0",
                    ),
                )
            }
            diagnostic(
                "AUDIO_DESCRIPTION_PREVIEW_READY",
                attributes = mapOf(
                    "scope" to mode.name,
                    "processed" to processed.toString(),
                    "converted" to converted.size.toString(),
                    "failures" to errors.size.toString(),
                    "sourceRefetched" to "0",
                ),
            )
            diagnostic(
                "AUDIO_DESCRIPTION_NORMALIZATION_DONE",
                attributes = mapOf(
                    "scope" to mode.name,
                    "processed" to processed.toString(),
                    "converted" to converted.size.toString(),
                    "failures" to errors.size.toString(),
                ),
            )
            running = false
            job = null
        }
    }

    fun applyResults() {
        if (running || previews.isEmpty()) return
        running = true
        diagnostic(
            "AUDIO_DESCRIPTION_APPLY_START",
            attributes = mapOf("items" to previews.size.toString()),
        )
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
            val applyAttributes = mapOf(
                "items" to updated.size.toString(),
                "music" to updated.count { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }.toString(),
                "ambience" to updated.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }.toString(),
                "sfx" to updated.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }.toString(),
                "sourceRefetched" to "0",
            )
            diagnostic("AUDIO_DESCRIPTION_APPLIED", attributes = applyAttributes)
            diagnostic("AUDIO_DESCRIPTION_APPLY_DONE", attributes = applyAttributes)
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
                Text("AI dùng tên + mô tả hiện có để viết lại theo Sắc thái / Dùng / Tránh, ưu tiên điểm phân biệt thật giữa các âm gần nhau. Chức năng này không tìm Internet và không tự bịa chi tiết khi cả tên lẫn mô tả đều không hỗ trợ.")
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
                    Text("TOÀN BỘ: AI xem lại cả tên và mô tả đang lưu của từng file. Mục mô tả trống vẫn được xử lý thận trọng từ tên; nếu tên cũng mơ hồ, mô tả phải giữ mức khái quát và không khẳng định chi tiết chưa biết.")
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
                    diagnostic(
                        "AUDIO_DESCRIPTION_NORMALIZATION_CANCELLED",
                        attributes = mapOf(
                            "processed" to processed.toString(),
                            "total" to total.toString(),
                            "converted" to previews.size.toString(),
                        ),
                    )
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
                    .put("existing_description", item.source.take(4_000)),
            )
        }
    }
    return """
Bạn là biên tập viên metadata âm thanh cho bộ chọn semantic của ứng dụng đọc truyện.

Bạn KHÔNG có Internet và KHÔNG được giả vờ đã nghe file. Với mỗi mục, chỉ dùng hai bằng chứng được cung cấp: title và existing_description. Cả hai đều là manh mối. Mô tả hiện có có thể đúng, quá chung, trùng mẫu hoặc sai một phần, vì vậy không được mặc định tin tuyệt đối một trường.

MỤC TIÊU QUAN TRỌNG: description sau khi viết phải tự đứng độc lập vì bộ chọn LOCAL KHÔNG dùng title để matching. Description phải giúp phân biệt file này với các file gần giống cùng nhóm, không chỉ lặp một mẫu chung.

Cách làm cho MỖI mục:
1. Xác định loại âm cần mô tả từ kind.
2. Từ title + existing_description, rút ra các đặc điểm đáng tin: nguồn âm/nhạc cụ/vật liệu/hành động, cường độ, nhịp, khoảng cách, môi trường hoặc cấu trúc thời gian.
3. Giữ 1-3 đặc điểm PHÂN BIỆT nhất so với các âm gần loại. Ví dụ: gần/xa, nhẹ/mạnh, một phát/chuỗi/loop, gió thường/gió qua cáp, đấm vật lý/đấm ma thuật, orchestral heroic/orchestral dark, mưa trên kính/mưa ngoài rừng.
4. Dùng trường Dùng cho tình huống phù hợp thật sự; không viết quá rộng tới mức mọi file cùng loại đều giống nhau.
5. Dùng trường Tránh để nêu các biến thể dễ bị chọn nhầm nhưng khác file này. Không nhồi từ khóa và không mô tả cốt truyện.
6. Nếu existing_description đã tốt và cụ thể, giữ thông tin đúng rồi chỉ làm gọn/chuẩn hơn; không thay đổi chỉ để khác câu chữ.
7. Nếu title rất cụ thể còn mô tả cũ chung chung, được dùng title để làm mô tả chính xác hơn. Nếu title chung chung còn mô tả cụ thể, ưu tiên chi tiết cụ thể của mô tả.
8. Nếu title và existing_description mâu thuẫn, không chọn máy móc một bên. Chỉ giữ phần có cơ sở mạnh hơn; nếu chưa đủ chắc chắn thì mô tả bảo thủ hơn.
9. Nếu cả title và mô tả đều không xác định được một chi tiết, tuyệt đối không tự khẳng định chi tiết đó.

Quy tắc theo kind:
- MUSIC: ưu tiên cảm xúc, nhịp/cường độ, hướng phát triển và kiểu phối khí; chỉ nêu nhạc cụ khi title hoặc mô tả hỗ trợ. Phân biệt rõ heroic/dark/sad/romantic/tension/action/fantasy/chill và buildup/loop/đột ngột khi có bằng chứng.
- AMBIENCE: ưu tiên nguồn môi trường vật lý + nơi chốn + thời điểm/thời tiết + khoảng cách/mật độ. Phân biệt gió/mưa/nước/biển/rừng/côn trùng/đám đông/giao thông/nội thất và field-recording với sound-design khi có bằng chứng.
- SFX: ưu tiên nguồn + hành động + vật liệu + cường độ/cấu trúc. Phân biệt đấm/đá/ngã/rơi, kiếm vung/va/chém trúng, điện hum/zap/arc, nổ thường/cinematic/phép, whoosh nhanh/dài/nặng, kính nứt/vỡ, đá va/lăn/sạt.

Mỗi description phải là MỘT DÒNG, tối đa 280 ký tự để luôn an toàn dưới giới hạn lưu 300 ký tự, đúng cấu trúc:
Sắc thái: <đặc điểm nghe được và điểm phân biệt>; Dùng: <tình huống phù hợp>; Tránh: <biến thể gần giống nhưng không đúng>

Không thêm ID, tên file, tên truyện, nhân vật, lời quảng cáo, nguồn tải hay giải thích ngoài ba trường. Không dùng markdown.

Chỉ trả JSON hợp lệ:
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
        val description = row.optString("description").replace(Regex("\\s+"), " ").trim()
        if (description.length > 300) continue
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
            sourceRefreshed = false,
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
