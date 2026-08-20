package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.freesound.FreesoundCategory
import vn.nghetruyen.app.freesound.FreesoundDuplicateException
import vn.nghetruyen.app.freesound.FreesoundDuration
import vn.nghetruyen.app.freesound.FreesoundImporter
import vn.nghetruyen.app.freesound.FreesoundImportQueueStatus
import vn.nghetruyen.app.freesound.FreesoundImportResult
import vn.nghetruyen.app.freesound.FreesoundPreviewPlayer
import vn.nghetruyen.app.freesound.FreesoundSearchPage
import vn.nghetruyen.app.freesound.FreesoundSearchRequest
import vn.nghetruyen.app.freesound.FreesoundSearchResult
import vn.nghetruyen.app.freesound.FreesoundSort
import vn.nghetruyen.app.freesound.FreesoundSound
import vn.nghetruyen.app.freesound.summarizeFreesoundQueue
import vn.nghetruyen.app.playback.ReaderPlaybackService

@Composable
fun FreesoundSearchDialog(
    kind: AudioAssetKind,
    normalizationTargetLufs: Float,
    onImported: (FreesoundImportResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val application = context.applicationContext as NgheTruyenApplication
    val credentialStore = application.container.freesoundCredentialStore
    val client = application.container.freesoundClient
    val importer = remember(application) {
        FreesoundImporter(
            context = context,
            repository = application.container.libraryRepository,
            existingTracksProvider = {
                application.container.database.sceneMusicTrackDao().listAll()
            },
        )
    }
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { FreesoundPreviewPlayer() }
    val category = remember(kind) { kind.toFreesoundCategory() }

    var query by remember(kind) { mutableStateOf("") }
    var duration by remember(kind) { mutableStateOf(FreesoundDuration.RECOMMENDED) }
    var sort by remember(kind) { mutableStateOf(FreesoundSort.RELEVANCE) }
    var durationExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchPage by remember { mutableStateOf<FreesoundSearchPage?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var previewLoadingId by remember { mutableStateOf<Int?>(null) }
    var previewPlayingId by remember { mutableStateOf<Int?>(null) }
    var existingImportedIds by remember(kind) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSounds by remember(kind) { mutableStateOf<Map<Int, FreesoundSound>>(emptyMap()) }
    var queueSounds by remember(kind) { mutableStateOf<Map<Int, FreesoundSound>>(emptyMap()) }
    var queueStates by remember(kind) { mutableStateOf<Map<Int, FreesoundImportQueueStatus>>(emptyMap()) }
    var queueErrors by remember(kind) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var queueRunning by remember(kind) { mutableStateOf(false) }
    var stopQueueRequested by remember(kind) { mutableStateOf(false) }

    val hasApiKey = credentialStore.hasApiKey()
    val queueSummary = summarizeFreesoundQueue(queueStates.values)

    fun endPlaybackPreview() {
        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
    }

    fun stopPreview() {
        previewPlayer.stop()
        previewLoadingId = null
        previewPlayingId = null
        endPlaybackPreview()
    }

    fun close() {
        if (queueRunning) return
        stopPreview()
        onDismiss()
    }

    fun toggleSelection(sound: FreesoundSound) {
        if (queueRunning || searching || sound.preferredPreviewUrl == null) return
        if (sound.id in existingImportedIds) {
            status = "${sound.name} đã có trong thư viện."
            return
        }
        if (sound.id in selectedSounds) {
            selectedSounds = selectedSounds - sound.id
            return
        }
        if (selectedSounds.size >= FreesoundImporter.MAX_BATCH_SIZE) {
            status = "Mỗi lượt được chọn tối đa ${FreesoundImporter.MAX_BATCH_SIZE} âm thanh."
            return
        }
        selectedSounds = selectedSounds + (sound.id to sound)
    }

    fun selectPage(page: FreesoundSearchPage) {
        if (queueRunning || searching) return
        val candidates = page.results.filter {
            it.preferredPreviewUrl != null &&
                it.id !in existingImportedIds &&
                it.id !in selectedSounds
        }
        val remaining = (FreesoundImporter.MAX_BATCH_SIZE - selectedSounds.size).coerceAtLeast(0)
        val additions = candidates.take(remaining)
        selectedSounds = selectedSounds + additions.associateBy(FreesoundSound::id)
        status = when {
            additions.isEmpty() && candidates.isNotEmpty() ->
                "Đã đạt giới hạn ${FreesoundImporter.MAX_BATCH_SIZE} âm thanh mỗi lượt."
            additions.isEmpty() -> "Trang này không còn âm thanh khả dụng để chọn."
            additions.size < candidates.size ->
                "Đã chọn thêm ${additions.size} âm thanh; đạt giới hạn ${FreesoundImporter.MAX_BATCH_SIZE}."
            else -> "Đã chọn thêm ${additions.size} âm thanh trên trang này."
        }
    }

    fun runSearch(targetPage: Int) {
        if (!hasApiKey || searching || queueRunning || query.isBlank()) return
        stopPreview()
        searching = true
        status = "Đang tìm trên Freesound…"
        scope.launch {
            try {
                when (
                    val result = client.search(
                        FreesoundSearchRequest(
                            query = query,
                            category = category,
                            duration = duration,
                            sort = sort,
                            page = targetPage,
                        ),
                    )
                ) {
                    is FreesoundSearchResult.Success -> {
                        searchPage = result.page
                        status = if (result.page.results.isEmpty()) {
                            "Không tìm thấy âm thanh phù hợp."
                        } else {
                            "Tìm thấy ${result.page.count} kết quả. Trang ${result.page.page}."
                        }
                    }
                    is FreesoundSearchResult.Failure -> status = result.message
                }
            } finally {
                searching = false
            }
        }
    }

    fun startImportQueue(sounds: List<FreesoundSound>) {
        if (queueRunning || sounds.isEmpty()) return
        val candidates = sounds
            .distinctBy(FreesoundSound::id)
            .filter { it.preferredPreviewUrl != null }
            .take(FreesoundImporter.MAX_BATCH_SIZE)
        if (candidates.isEmpty()) {
            status = "Không có âm thanh khả dụng để nhập."
            return
        }

        stopPreview()
        stopQueueRequested = false
        queueRunning = true
        queueSounds = candidates.associateBy(FreesoundSound::id)
        queueStates = candidates.associate { sound ->
            sound.id to if (sound.id in existingImportedIds) {
                FreesoundImportQueueStatus.DUPLICATE
            } else {
                FreesoundImportQueueStatus.QUEUED
            }
        }
        queueErrors = emptyMap()

        scope.launch {
            try {
                for ((index, sound) in candidates.withIndex()) {
                    if (sound.id in existingImportedIds) {
                        queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.DUPLICATE)
                        selectedSounds = selectedSounds - sound.id
                        continue
                    }
                    if (stopQueueRequested) {
                        candidates.drop(index).forEach { pending ->
                            if (queueStates[pending.id] == FreesoundImportQueueStatus.QUEUED) {
                                queueStates = queueStates + (pending.id to FreesoundImportQueueStatus.CANCELLED)
                            }
                        }
                        break
                    }

                    queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.IMPORTING)
                    status = "Đang nhập ${index + 1}/${candidates.size}: ${sound.name}"
                    importer.importPreview(
                        sound = sound,
                        kind = kind,
                        normalizationTargetLufs = normalizationTargetLufs,
                    ).onSuccess { result ->
                        existingImportedIds = existingImportedIds + sound.id
                        selectedSounds = selectedSounds - sound.id
                        queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.IMPORTED)
                        queueErrors = queueErrors - sound.id
                        onImported(result)
                    }.onFailure { error ->
                        if (error is FreesoundDuplicateException) {
                            existingImportedIds = existingImportedIds + sound.id
                            selectedSounds = selectedSounds - sound.id
                            queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.DUPLICATE)
                            queueErrors = queueErrors - sound.id
                        } else {
                            queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.FAILED)
                            queueErrors = queueErrors + (
                                sound.id to (error.message ?: "Không nhập được âm thanh từ Freesound.")
                            )
                        }
                    }
                }
            } finally {
                queueRunning = false
                stopQueueRequested = false
                val summary = summarizeFreesoundQueue(queueStates.values)
                status = buildString {
                    append("Hàng đợi hoàn tất: ${summary.imported} đã nhập")
                    if (summary.duplicate > 0) append(" • ${summary.duplicate} đã có")
                    if (summary.failed > 0) append(" • ${summary.failed} lỗi")
                    if (summary.cancelled > 0) append(" • ${summary.cancelled} đã dừng")
                    append('.')
                }
            }
        }
    }

    fun retryFailed() {
        val failed = queueStates
            .filterValues { it == FreesoundImportQueueStatus.FAILED }
            .keys
            .mapNotNull(queueSounds::get)
        startImportQueue(failed)
    }

    LaunchedEffect(kind) {
        runCatching {
            application.container.database.sceneMusicTrackDao().listAll()
        }.onSuccess { tracks ->
            existingImportedIds = tracks.mapNotNull { track ->
                FreesoundImporter.soundIdFromManagedUri(track.uri)
            }.toSet()
        }.onFailure {
            status = "Không kiểm tra được các âm thanh Freesound đã có trong thư viện."
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
            endPlaybackPreview()
        }
    }

    LaunchedEffect(status) {
        status?.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
    }

    AlertDialog(
        onDismissRequest = { if (!queueRunning) close() },
        title = { Text("TÌM TRÊN FREESOUND — ${category.label.uppercase()}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!hasApiKey) {
                    Text(
                        "Chưa có khóa API Freesound. Hãy cấu hình khóa trong Cài đặt → Nguồn âm thanh trực tuyến → Freesound.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }

                Text("Loại: ${category.label}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(FreesoundSearchRequest.MAX_QUERY_LENGTH) },
                    label = { Text("Từ khóa") },
                    placeholder = { Text(searchHint(kind)) },
                    singleLine = true,
                    enabled = !searching && !queueRunning,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Thời lượng", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { durationExpanded = true },
                        enabled = !searching && !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${duration.label} ▼") }
                    DropdownMenu(
                        expanded = durationExpanded,
                        onDismissRequest = { durationExpanded = false },
                    ) {
                        FreesoundDuration.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text((if (duration == option) "✓ " else "") + option.label) },
                                onClick = {
                                    duration = option
                                    durationExpanded = false
                                },
                            )
                        }
                    }
                }

                Text("Thứ tự", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { sortExpanded = true },
                        enabled = !searching && !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${sort.label} ▼") }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                    ) {
                        FreesoundSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text((if (sort == option) "✓ " else "") + option.label) },
                                onClick = {
                                    sort = option
                                    sortExpanded = false
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = { runSearch(1) },
                    enabled = !searching && !queueRunning && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "ĐANG TÌM…" else "TÌM") }

                if (selectedSounds.isNotEmpty()) {
                    Button(
                        onClick = { startImportQueue(selectedSounds.values.toList()) },
                        enabled = !searching && !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("NHẬP ĐÃ CHỌN (${selectedSounds.size})") }
                    TextButton(
                        onClick = { selectedSounds = emptyMap() },
                        enabled = !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("BỎ CHỌN TẤT CẢ") }
                }

                if (queueStates.isNotEmpty()) {
                    Text(
                        buildString {
                            append("Hàng đợi: ${queueSummary.queued} chờ")
                            if (queueSummary.importing > 0) append(" • ${queueSummary.importing} đang nhập")
                            if (queueSummary.imported > 0) append(" • ${queueSummary.imported} xong")
                            if (queueSummary.failed > 0) append(" • ${queueSummary.failed} lỗi")
                            if (queueSummary.duplicate > 0) append(" • ${queueSummary.duplicate} đã có")
                            if (queueSummary.cancelled > 0) append(" • ${queueSummary.cancelled} đã dừng")
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (queueRunning) {
                        Button(
                            onClick = {
                                stopQueueRequested = true
                                status = "Sẽ dừng hàng đợi sau tệp đang nhập."
                            },
                            enabled = !stopQueueRequested,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (stopQueueRequested) "ĐANG DỪNG SAU TỆP HIỆN TẠI…" else "DỪNG HÀNG ĐỢI") }
                    } else if (queueSummary.failed > 0) {
                        Button(
                            onClick = ::retryFailed,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("THỬ LẠI ${queueSummary.failed} TỆP LỖI") }
                    }
                }

                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                searchPage?.let { page ->
                    val totalPages = if (page.count <= 0) 1 else (page.count + page.pageSize - 1) / page.pageSize
                    Text(
                        "${page.count} kết quả • trang ${page.page}/$totalPages",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { selectPage(page) },
                            enabled = !searching && !queueRunning,
                            modifier = Modifier.weight(1f),
                        ) { Text("CHỌN TRANG") }
                        Button(
                            onClick = {
                                val pageIds = page.results.mapTo(hashSetOf(), FreesoundSound::id)
                                selectedSounds = selectedSounds.filterKeys { it !in pageIds }
                            },
                            enabled = !searching && !queueRunning,
                            modifier = Modifier.weight(1f),
                        ) { Text("BỎ CHỌN TRANG") }
                    }
                    page.results.forEach { sound ->
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        FreesoundSearchResultRow(
                            sound = sound,
                            previewLoading = previewLoadingId == sound.id,
                            previewPlaying = previewPlayingId == sound.id,
                            queueStatus = queueStates[sound.id],
                            queueError = queueErrors[sound.id],
                            selected = sound.id in selectedSounds,
                            alreadyImported = sound.id in existingImportedIds,
                            busy = queueRunning || searching,
                            onPreview = {
                                val previewUrl = sound.preferredPreviewUrl
                                if (previewUrl == null) {
                                    status = "Âm thanh này không có preview HQ khả dụng."
                                } else if (previewLoadingId == sound.id || previewPlayingId == sound.id) {
                                    stopPreview()
                                    status = "Đã dừng nghe thử."
                                } else {
                                    stopPreview()
                                    ReaderPlaybackService.command(
                                        context,
                                        ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN,
                                    )
                                    previewLoadingId = sound.id
                                    status = "Đang tải nghe thử ${sound.name}."
                                    previewPlayer.play(
                                        soundId = sound.id,
                                        previewUrl = previewUrl,
                                        onStarted = {
                                            previewLoadingId = null
                                            previewPlayingId = sound.id
                                            status = "Đang nghe thử ${sound.name}."
                                        },
                                        onStopped = {
                                            previewLoadingId = null
                                            previewPlayingId = null
                                            endPlaybackPreview()
                                            status = "Đã phát xong ${sound.name}."
                                        },
                                        onError = {
                                            previewLoadingId = null
                                            previewPlayingId = null
                                            endPlaybackPreview()
                                            status = "Không thể phát preview của ${sound.name}."
                                        },
                                    )
                                }
                            },
                            onImport = { startImportQueue(listOf(sound)) },
                            onSelect = { toggleSelection(sound) },
                        )
                    }
                    if (page.results.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { runSearch(page.page - 1) },
                                enabled = !searching && !queueRunning && page.hasPrevious,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG TRƯỚC") }
                            Button(
                                onClick = { runSearch(page.page + 1) },
                                enabled = !searching && !queueRunning && page.hasNext,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG SAU") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = ::close,
                enabled = !queueRunning,
            ) { Text(if (queueRunning) "ĐANG XỬ LÝ HÀNG ĐỢI…" else "ĐÓNG") }
        },
    )
}

@Composable
private fun FreesoundSearchResultRow(
    sound: FreesoundSound,
    previewLoading: Boolean,
    previewPlaying: Boolean,
    queueStatus: FreesoundImportQueueStatus?,
    queueError: String?,
    selected: Boolean,
    alreadyImported: Boolean,
    busy: Boolean,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onSelect: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(sound.name, fontWeight = FontWeight.SemiBold)
        Text(formatDuration(sound.durationSeconds), style = MaterialTheme.typography.bodySmall)
        if (sound.description.isNotBlank()) {
            Text(sound.description, style = MaterialTheme.typography.bodySmall)
        }
        if (alreadyImported) {
            Text("Đã có trong thư viện.", style = MaterialTheme.typography.bodySmall)
        } else {
            queueStatus?.let { state ->
                Text(queueStatusLabel(state), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (queueStatus == FreesoundImportQueueStatus.FAILED && !queueError.isNullOrBlank()) {
            Text("Lỗi: $queueError", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPreview,
                enabled = !busy && sound.preferredPreviewUrl != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        previewLoading -> "ĐANG TẢI…"
                        previewPlaying -> "DỪNG NGHE"
                        sound.preferredPreviewUrl == null -> "KHÔNG PREVIEW"
                        else -> "NGHE THỬ"
                    },
                )
            }
            Button(
                onClick = onImport,
                enabled = !busy && !alreadyImported && sound.preferredPreviewUrl != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        alreadyImported -> "ĐÃ CÓ"
                        queueStatus == FreesoundImportQueueStatus.QUEUED -> "ĐANG CHỜ"
                        queueStatus == FreesoundImportQueueStatus.IMPORTING -> "ĐANG NHẬP…"
                        queueStatus == FreesoundImportQueueStatus.IMPORTED -> "ĐÃ NHẬP"
                        queueStatus == FreesoundImportQueueStatus.DUPLICATE -> "ĐÃ CÓ"
                        queueStatus == FreesoundImportQueueStatus.FAILED -> "THỬ LẠI"
                        else -> "NHẬP"
                    },
                )
            }
        }
        Button(
            onClick = onSelect,
            enabled = !busy && !alreadyImported && sound.preferredPreviewUrl != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selected) "BỎ CHỌN" else "CHỌN VÀO HÀNG ĐỢI")
        }
    }
}

private fun queueStatusLabel(status: FreesoundImportQueueStatus): String = when (status) {
    FreesoundImportQueueStatus.QUEUED -> "Đang chờ trong hàng đợi."
    FreesoundImportQueueStatus.IMPORTING -> "Đang tải và nhập."
    FreesoundImportQueueStatus.IMPORTED -> "Đã nhập thành công."
    FreesoundImportQueueStatus.FAILED -> "Nhập thất bại."
    FreesoundImportQueueStatus.DUPLICATE -> "Đã có trong thư viện."
    FreesoundImportQueueStatus.CANCELLED -> "Đã dừng trước khi nhập."
}

private fun AudioAssetKind.toFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}

private fun searchHint(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Ví dụ: fantasy music, battle music"
    AudioAssetKind.AMBIENCE -> "Ví dụ: forest ambience, thunderstorm"
    AudioAssetKind.SFX -> "Ví dụ: sword clash, magic spell"
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val minutes = total / 60
    val remaining = total % 60
    return if (minutes > 0) "$minutes:${remaining.toString().padStart(2, '0')}" else "$remaining giây"
}
