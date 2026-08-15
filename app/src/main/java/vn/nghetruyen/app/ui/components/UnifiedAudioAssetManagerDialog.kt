package vn.nghetruyen.app.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.playback.ReaderPlaybackService

/**
 * Canonical asset-library dialog for MUSIC, AMBIENCE and SFX.
 * The old Reader music library is the UX reference, so all three kinds expose the same controls.
 */
@Composable
fun UnifiedAudioAssetManagerDialog(
    kind: AudioAssetKind,
    tracks: List<SceneMusicTrackEntity>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val repository = application.container.libraryRepository
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val initialRows = remember(kind) {
        tracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
            .mapIndexed { index, row -> row.copy(orderIndex = index) }
    }
    var draft by remember(kind) { mutableStateOf(initialRows) }
    val baselineIds = remember(kind) { initialRows.mapTo(linkedSetOf()) { it.id } }
    var search by remember(kind) { mutableStateOf("") }
    var selectedTrackId by remember(kind) { mutableStateOf<String?>(null) }
    var editingTrack by remember(kind) { mutableStateOf<SceneMusicTrackEntity?>(null) }
    var deleteTrack by remember(kind) { mutableStateOf<SceneMusicTrackEntity?>(null) }
    var showBulkDialog by remember(kind) { mutableStateOf(false) }
    var bulkText by remember(kind) { mutableStateOf("") }
    var showBulkResult by remember(kind) { mutableStateOf(false) }
    var bulkUpdates by remember(kind) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var bulkErrors by remember(kind) { mutableStateOf<List<String>>(emptyList()) }
    var showClearAllConfirm by remember(kind) { mutableStateOf(false) }
    var previewPlayer by remember(kind) { mutableStateOf<MediaPlayer?>(null) }

    fun notify(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun stopPreview() {
        runCatching { previewPlayer?.stop() }
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
    }

    fun cancelLibrary() {
        stopPreview()
        val app = application
        val initialIds = baselineIds.toSet()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val dao = app.container.database.sceneMusicTrackDao()
            dao.listAll()
                .filter { AudioAssetClassifier.classify(it) == kind && it.id !in initialIds }
                .forEach { dao.delete(it.id) }
        }
        onDismiss()
    }

    DisposableEffect(kind) {
        onDispose { stopPreview() }
    }

    LaunchedEffect(tracks, kind) {
        val draftIds = draft.mapTo(linkedSetOf()) { it.id }
        val added = tracks.filter { it.id !in baselineIds && it.id !in draftIds }
        if (added.isNotEmpty()) {
            draft = (draft + added)
                .take(500)
                .mapIndexed { index, row -> row.copy(orderIndex = index) }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.distinct().forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val displayName = displayName(context, uri)
                repository.saveSceneMusicTrack(
                    title = displayName.substringBeforeLast('.', displayName).ifBlank { defaultAssetName(kind) },
                    uri = uri.toString(),
                    tagsCsv = typeMarker(kind),
                ).onSuccess { trackId ->
                    SceneMusicAnalysisWorker.enqueue(context, trackId, normalizationTarget(kind))
                }
            }
        }
    }

    val normalizedSearch = search.trim().lowercase()
    val visibleTracks = draft.filter {
        normalizedSearch.isBlank() || it.title.lowercase().contains(normalizedSearch)
    }

    AlertDialog(
        onDismissRequest = ::cancelLibrary,
        title = { Text(libraryTitle(kind)) },
        text = {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(120) },
                    placeholder = { Text("Tìm theo tên tệp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visibleTracks.isEmpty()) {
                    Text(
                        if (draft.isEmpty()) "Chưa có tệp âm thanh nào." else "Không có tệp phù hợp với nội dung tìm kiếm.",
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                visibleTracks.forEach { track ->
                    val index = draft.indexOfFirst { it.id == track.id }
                    ReferenceActionButton(
                        text = "${index + 1}. ${track.title}",
                        onClick = { selectedTrackId = track.id },
                        normalColor = ReferenceGray,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { launcher.launch(arrayOf("audio/*")) },
                        enabled = draft.size < 500,
                        modifier = Modifier.weight(1f),
                    ) { Text("THÊM TỆP") }
                    TextButton(
                        onClick = { bulkText = ""; showBulkDialog = true },
                        enabled = draft.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("MÔ TẢ HÀNG LOẠT") }
                    TextButton(
                        onClick = { showClearAllConfirm = true },
                        enabled = draft.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("XÓA TẤT CẢ") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (previewPlayer != null) {
                        TextButton(onClick = ::stopPreview, modifier = Modifier.weight(1f)) { Text("DỪNG NGHE") }
                    }
                    TextButton(
                        onClick = {
                            if (draft.size > 500) {
                                notify("Danh sách vượt giới hạn 500 tệp.")
                            } else {
                                stopPreview()
                                scope.launch {
                                    val dao = application.container.database.sceneMusicTrackDao()
                                    val existing = dao.listAll()
                                    val existingKind = existing.filter { AudioAssetClassifier.classify(it) == kind }
                                    val now = System.currentTimeMillis()
                                    val normalized = draft.mapIndexed { index, row ->
                                        row.copy(orderIndex = index, updatedAt = now)
                                    }
                                    val keepIds = normalized.mapTo(hashSetOf()) { it.id }
                                    existingKind.filter { it.id !in keepIds }.forEach { dao.delete(it.id) }
                                    dao.upsertAll(normalized)
                                    notify("Đã lưu ${kindDisplayName(kind).lowercase()}.")
                                    onDismiss()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("LƯU DANH SÁCH") }
                    TextButton(onClick = ::cancelLibrary, modifier = Modifier.weight(1f)) { Text("HỦY") }
                }
            }
        },
    )

    selectedTrackId?.let { selectedId ->
        draft.firstOrNull { it.id == selectedId }?.let { track ->
            val index = draft.indexOfFirst { it.id == track.id }
            val description = assetDescription(kind, track.tagsCsv)
            AlertDialog(
                onDismissRequest = { selectedTrackId = null },
                title = { Text(track.title) },
                text = { Text("Mô tả: ${description.ifBlank { "—" }}") },
                confirmButton = {
                    Column(Modifier.fillMaxWidth()) {
                        UnifiedAssetActionButton("NGHE THỬ") {
                            stopPreview()
                            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN)
                            val gainDb = if (
                                track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
                                track.normalizationError.isBlank() &&
                                track.loudnessLufsEstimate.isFinite() &&
                                track.peakDbfs.isFinite()
                            ) {
                                PcmLoudnessEstimator.calculateNormalization(
                                    track.loudnessLufsEstimate,
                                    track.peakDbfs,
                                    normalizationTarget(kind),
                                ).gainDb
                            } else 0f
                            val previewLevel = (
                                track.volume * PcmLoudnessEstimator.gainDbToLinear(gainDb)
                            ).coerceIn(0f, 1f)
                            previewPlayer = runCatching {
                                MediaPlayer.create(context, Uri.parse(track.uri))
                            }.getOrNull()?.also { player ->
                                player.setVolume(previewLevel, previewLevel)
                                player.setOnCompletionListener { completed ->
                                    runCatching { completed.release() }
                                    if (previewPlayer === completed) {
                                        previewPlayer = null
                                        ReaderPlaybackService.command(
                                            context,
                                            ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END,
                                        )
                                    }
                                }
                                player.start()
                                scope.launch {
                                    delay(15_000)
                                    if (previewPlayer === player) {
                                        runCatching { player.stop() }
                                        runCatching { player.release() }
                                        previewPlayer = null
                                        ReaderPlaybackService.command(
                                            context,
                                            ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END,
                                        )
                                    }
                                }
                            }
                            if (previewPlayer == null) {
                                ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                                notify("Không nghe thử được tệp này.")
                            }
                        }
                        UnifiedAssetActionButton("CHUẨN HÓA") {
                            SceneMusicAnalysisWorker.enqueue(context, track.id, normalizationTarget(kind))
                            notify("Đã đưa ‘${track.title}’ vào hàng đợi chuẩn hóa.")
                        }
                        UnifiedAssetActionButton("SỬA TÊN / MÔ TẢ") {
                            editingTrack = track
                            selectedTrackId = null
                        }
                        UnifiedAssetActionButton("SAO CHÉP TÊN") {
                            clipboard.setText(AnnotatedString(track.title))
                            notify("Đã sao chép tên.")
                        }
                        UnifiedAssetActionButton("SAO CHÉP MÔ TẢ") {
                            clipboard.setText(AnnotatedString(description))
                            notify(if (description.isBlank()) "Mô tả đang trống." else "Đã sao chép mô tả.")
                        }
                        UnifiedAssetActionButton(if (track.enabled) "TẮT TỆP NÀY" else "BẬT TỆP NÀY") {
                            draft = draft.map {
                                if (it.id == track.id) it.copy(enabled = !track.enabled) else it
                            }
                            selectedTrackId = null
                        }
                        if (index > 0) {
                            UnifiedAssetActionButton("DI CHUYỂN LÊN") {
                                val rows = draft.toMutableList()
                                val previous = rows[index - 1]
                                rows[index - 1] = rows[index]
                                rows[index] = previous
                                draft = rows.mapIndexed { position, row -> row.copy(orderIndex = position) }
                                selectedTrackId = null
                            }
                        }
                        if (index in 0 until draft.lastIndex) {
                            UnifiedAssetActionButton("DI CHUYỂN XUỐNG") {
                                val rows = draft.toMutableList()
                                val next = rows[index + 1]
                                rows[index + 1] = rows[index]
                                rows[index] = next
                                draft = rows.mapIndexed { position, row -> row.copy(orderIndex = position) }
                                selectedTrackId = null
                            }
                        }
                        UnifiedAssetActionButton("XÓA KHỎI DANH SÁCH") {
                            deleteTrack = track
                            selectedTrackId = null
                        }
                        TextButton(
                            onClick = { selectedTrackId = null },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("ĐÓNG") }
                    }
                },
            )
        }
    }

    editingTrack?.let { track ->
        var title by remember(track.id, track.title) { mutableStateOf(track.title) }
        var description by remember(track.id, track.tagsCsv) {
            mutableStateOf(assetDescription(kind, track.tagsCsv))
        }
        AlertDialog(
            onDismissRequest = { editingTrack = null },
            title = { Text("CHỈNH SỬA TỆP ÂM THANH") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(120) },
                        label = { Text("Tên") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(300) },
                        label = { Text("Mô tả") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(title))
                                notify("Đã sao chép tên.")
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("SAO CHÉP TÊN") }
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(description))
                                notify(if (description.isBlank()) "Mô tả đang trống." else "Đã sao chép mô tả.")
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("SAO CHÉP MÔ TẢ") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleanTitle = title.trim().ifBlank { track.title }
                    draft = draft.map {
                        if (it.id == track.id) {
                            it.copy(title = cleanTitle, tagsCsv = tagsWithDescription(kind, description))
                        } else it
                    }
                    editingTrack = null
                }) { Text("LƯU") }
            },
            dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("HỦY") } },
        )
    }

    deleteTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { deleteTrack = null },
            title = { Text("XÓA TỆP ÂM THANH") },
            text = { Text("Xóa ‘${track.title}’ khỏi danh sách?") },
            confirmButton = {
                TextButton(onClick = {
                    stopPreview()
                    draft = draft.filterNot { it.id == track.id }
                        .mapIndexed { index, row -> row.copy(orderIndex = index) }
                    deleteTrack = null
                }) { Text("XÓA") }
            },
            dismissButton = { TextButton(onClick = { deleteTrack = null }) { Text("HỦY") } },
        )
    }

    if (showBulkDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDialog = false },
            title = { Text("MÔ TẢ HÀNG LOẠT") },
            text = {
                OutlinedTextField(
                    value = bulkText,
                    onValueChange = { bulkText = it.take(120_000) },
                    placeholder = { Text("Tên tệp || Mô tả    •    [XÓA] để xóa mô tả") },
                    minLines = 12,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val tracksByName = draft.associateBy { it.title.trim().lowercase() }
                    val updates = linkedMapOf<String, String>()
                    val errors = mutableListOf<String>()
                    bulkText.lineSequence().forEachIndexed { index, raw ->
                        val line = raw.trim()
                        if (line.isBlank()) return@forEachIndexed
                        val separator = line.indexOf("||")
                        if (separator < 0) {
                            errors += "Dòng ${index + 1}: thiếu dấu ||."
                            return@forEachIndexed
                        }
                        val name = line.substring(0, separator).trim()
                        val description = line.substring(separator + 2).trim()
                        val track = tracksByName[name.lowercase()]
                        when {
                            name.isBlank() -> errors += "Dòng ${index + 1}: thiếu tên tệp."
                            track == null -> errors += "Dòng ${index + 1}: không tìm thấy tệp “$name”."
                            description.isBlank() -> Unit
                            description != "[XÓA]" && description.length > 300 -> {
                                errors += "Dòng ${index + 1}: mô tả có ${description.length} ký tự, vượt giới hạn 300."
                            }
                            else -> updates[track.id] = if (description == "[XÓA]") "" else description
                        }
                    }
                    bulkUpdates = updates
                    bulkErrors = errors
                    showBulkResult = true
                }) { Text("KIỂM TRA") }
            },
            dismissButton = { TextButton(onClick = { showBulkDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showBulkResult) {
        val message = buildString {
            append("Dòng hợp lệ: ${bulkUpdates.size}\nDòng cần kiểm tra: ${bulkErrors.size}")
            if (bulkErrors.isNotEmpty()) {
                append("\n\nCÁC DÒNG CẦN KIỂM TRA:\n")
                append(bulkErrors.take(20).joinToString("\n"))
                if (bulkErrors.size > 20) append("\n... và ${bulkErrors.size - 20} lỗi khác.")
            }
        }
        AlertDialog(
            onDismissRequest = { showBulkResult = false },
            title = { Text("KẾT QUẢ KIỂM TRA") },
            text = { Text(message) },
            confirmButton = {
                if (bulkUpdates.isNotEmpty()) {
                    TextButton(onClick = {
                        draft = draft.map { row ->
                            bulkUpdates[row.id]?.let {
                                row.copy(tagsCsv = tagsWithDescription(kind, it))
                            } ?: row
                        }
                        showBulkResult = false
                        showBulkDialog = false
                        notify("Đã cập nhật ${bulkUpdates.size} tệp. ${bulkErrors.size} dòng cần kiểm tra.")
                    }) { Text("ÁP DỤNG DÒNG HỢP LỆ") }
                }
            },
            dismissButton = {
                Row {
                    if (bulkErrors.isNotEmpty()) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(bulkErrors.joinToString("\n")))
                            notify("Đã sao chép danh sách lỗi.")
                        }) { Text("SAO CHÉP LỖI") }
                    }
                    TextButton(onClick = { showBulkResult = false }) { Text("SỬA") }
                }
            },
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("XÓA TOÀN BỘ DANH SÁCH") },
            text = { Text("Xóa tất cả tệp khỏi bản nháp?") },
            confirmButton = {
                TextButton(onClick = {
                    stopPreview()
                    draft = emptyList()
                    showClearAllConfirm = false
                }) { Text("XÓA") }
            },
            dismissButton = { TextButton(onClick = { showClearAllConfirm = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun UnifiedAssetActionButton(text: String, onClick: () -> Unit) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        normalColor = ReferenceGray,
        minHeight = 52.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

private fun libraryTitle(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "DANH SÁCH NHẠC NỀN"
    AudioAssetKind.AMBIENCE -> "DANH SÁCH ÂM THANH MÔI TRƯỜNG"
    AudioAssetKind.SFX -> "DANH SÁCH HIỆU ỨNG ÂM THANH"
}

private fun kindDisplayName(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Danh sách nhạc nền"
    AudioAssetKind.AMBIENCE -> "Danh sách âm thanh môi trường"
    AudioAssetKind.SFX -> "Danh sách hiệu ứng âm thanh"
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
    return fromProvider?.trim()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "audio"
}

private fun defaultAssetName(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Nhạc"
    AudioAssetKind.AMBIENCE -> "Âm thanh môi trường"
    AudioAssetKind.SFX -> "Hiệu ứng âm thanh"
}

private fun typeMarker(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "type:music"
    AudioAssetKind.AMBIENCE -> "type:ambience"
    AudioAssetKind.SFX -> "type:sfx"
}

private fun assetDescription(kind: AudioAssetKind, tagsCsv: String): String {
    val marker = typeMarker(kind)
    return tagsCsv.replace(marker, "", ignoreCase = true)
        .trim()
        .trimStart(',', ';')
        .trim()
}

private fun tagsWithDescription(kind: AudioAssetKind, description: String): String {
    val marker = typeMarker(kind)
    val cleanDescription = description.trim().take(300)
    return if (cleanDescription.isBlank()) marker else "$marker, $cleanDescription"
}

private fun normalizationTarget(kind: AudioAssetKind): Float = when (kind) {
    AudioAssetKind.MUSIC -> -24f
    AudioAssetKind.AMBIENCE -> -27f
    AudioAssetKind.SFX -> -20f
}
