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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.freesound.FreesoundCategory
import vn.nghetruyen.app.freesound.FreesoundDuplicateException
import vn.nghetruyen.app.freesound.FreesoundDuration
import vn.nghetruyen.app.freesound.FreesoundImporter
import vn.nghetruyen.app.freesound.FreesoundImportQueueStatus
import vn.nghetruyen.app.freesound.FreesoundImportResult
import vn.nghetruyen.app.freesound.FreesoundPendingQueueStore
import vn.nghetruyen.app.freesound.FreesoundPreviewPlayer
import vn.nghetruyen.app.freesound.FreesoundSearchPage
import vn.nghetruyen.app.freesound.FreesoundSearchPreferences
import vn.nghetruyen.app.freesound.FreesoundSearchRequest
import vn.nghetruyen.app.freesound.FreesoundSearchResult
import vn.nghetruyen.app.freesound.FreesoundSort
import vn.nghetruyen.app.freesound.FreesoundSound
import vn.nghetruyen.app.freesound.summarizeFreesoundQueue
import vn.nghetruyen.app.playback.ReaderPlaybackService

private data class ExistingFreesoundAsset(
    val trackId: String,
    val title: String,
    val description: String,
    val kind: AudioAssetKind,
)

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
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { FreesoundPreviewPlayer() }
    val category = remember(kind) { kind.toFreesoundCategory() }
    val searchPreferences = remember(context) { FreesoundSearchPreferences(context) }
    val pendingQueueStore = remember(context) { FreesoundPendingQueueStore(context) }
    val savedSearch = remember(kind) { searchPreferences.snapshot(category) }
    val importer = remember(application) {
        FreesoundImporter(
            context = context,
            repository = application.container.libraryRepository,
            existingTracksProvider = {
                application.container.database.sceneMusicTrackDao().listAll()
            },
        )
    }

    var query by remember(kind) { mutableStateOf(savedSearch.query) }
    var duration by remember(kind) { mutableStateOf(savedSearch.duration) }
    var sort by remember(kind) { mutableStateOf(savedSearch.sort) }
    var recentQueries by remember(kind) { mutableStateOf(savedSearch.recentQueries) }
    var durationExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchPage by remember { mutableStateOf<FreesoundSearchPage?>(null) }
    var similarSource by remember { mutableStateOf<FreesoundSound?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var previewLoadingId by remember { mutableStateOf<Int?>(null) }
    var previewPlayingId by remember { mutableStateOf<Int?>(null) }
    var existingAssets by remember(kind) { mutableStateOf<Map<Int, ExistingFreesoundAsset>>(emptyMap()) }
    var existingDetail by remember { mutableStateOf<ExistingFreesoundAsset?>(null) }
    var selectedSounds by remember(kind) { mutableStateOf<Map<Int, FreesoundSound>>(emptyMap()) }
    var queueSounds by remember(kind) { mutableStateOf<Map<Int, FreesoundSound>>(emptyMap()) }
    var queueStates by remember(kind) { mutableStateOf<Map<Int, FreesoundImportQueueStatus>>(emptyMap()) }
    var queueErrors by remember(kind) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var queueRunning by remember(kind) { mutableStateOf(false) }
    var stopQueueRequested by remember(kind) { mutableStateOf(false) }

    val hasApiKey = credentialStore.hasApiKey()
    val queueSummary = summarizeFreesoundQueue(queueStates.values)
    val presets = remember(category) { FreesoundSearchPreferences.presets(category) }

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
        if (sound.id in existingAssets) {
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
            it.preferredPreviewUrl != null && it.id !in existingAssets && it.id !in selectedSounds
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

    suspend fun loadExistingAssets(): Map<Int, ExistingFreesoundAsset> =
        application.container.database.sceneMusicTrackDao().listAll().mapNotNull { track ->
            val soundId = FreesoundImporter.soundIdFromManagedUri(track.uri) ?: return@mapNotNull null
            if (!FreesoundImporter.managedFileExists(context, track.uri)) return@mapNotNull null
            soundId to ExistingFreesoundAsset(
                trackId = track.id,
                title = track.title,
                description = localAssetDescription(track),
                kind = AudioAssetClassifier.classify(track),
            )
        }.toMap()

    fun rememberKeywordSearch() {
        searchPreferences.rememberSearch(category, query, duration, sort)
        recentQueries = searchPreferences.snapshot(category).recentQueries
    }

    fun runKeywordSearch(targetPage: Int) {
        if (!hasApiKey || searching || queueRunning || query.isBlank()) return
        stopPreview()
        similarSource = null
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
                        rememberKeywordSearch()
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

    fun runSimilarSearch(source: FreesoundSound, targetPage: Int) {
        if (!hasApiKey || searching || queueRunning) return
        stopPreview()
        similarSource = source
        searching = true
        status = "Đang tìm âm thanh tương tự ${source.name}…"
        scope.launch {
            try {
                val request = FreesoundSearchRequest(
                    query = "",
                    category = category,
                    duration = duration,
                    sort = FreesoundSort.RELEVANCE,
                    page = targetPage,
                )
                when (val result = client.similar(source.id, request)) {
                    is FreesoundSearchResult.Success -> {
                        searchPage = result.page
                        status = if (result.page.results.isEmpty()) {
                            "Không tìm thấy âm thanh tương tự phù hợp."
                        } else {
                            "${result.page.count} âm thanh tương tự • trang ${result.page.page}."
                        }
                    }
                    is FreesoundSearchResult.Failure -> status = result.message
                }
            } finally {
                searching = false
            }
        }
    }

    fun loadPage(targetPage: Int) {
        similarSource?.let { runSimilarSearch(it, targetPage) } ?: runKeywordSearch(targetPage)
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
            sound.id to if (sound.id in existingAssets) {
                FreesoundImportQueueStatus.DUPLICATE
            } else {
                FreesoundImportQueueStatus.QUEUED
            }
        }
        queueErrors = emptyMap()

        scope.launch {
            pendingQueueStore.save(kind, candidates, autoResume = true)
            try {
                for ((index, sound) in candidates.withIndex()) {
                    if (sound.id in existingAssets) {
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
                    status = "Đang tải và chuẩn hóa ${index + 1}/${candidates.size}: ${sound.name}"
                    importer.importPreview(
                        sound = sound,
                        kind = kind,
                        normalizationTargetLufs = normalizationTargetLufs,
                    ).onSuccess { result ->
                        existingAssets = existingAssets + (
                            sound.id to ExistingFreesoundAsset(
                                trackId = result.trackId,
                                title = result.title,
                                description = sound.description.trim().take(300),
                                kind = kind,
                            )
                        )
                        selectedSounds = selectedSounds - sound.id
                        queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.IMPORTED)
                        queueErrors = queueErrors - sound.id
                        onImported(result)
                    }.onFailure { error ->
                        if (error is FreesoundDuplicateException) {
                            existingAssets = runCatching { loadExistingAssets() }.getOrDefault(existingAssets)
                            selectedSounds = selectedSounds - sound.id
                            queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.DUPLICATE)
                            queueErrors = queueErrors - sound.id
                        } else {
                            queueStates = queueStates + (sound.id to FreesoundImportQueueStatus.FAILED)
                            queueErrors = queueErrors + (
                                sound.id to (error.message ?: "Không nhập hoặc chuẩn hóa được âm thanh từ Freesound.")
                            )
                        }
                    }
                }
            } finally {
                queueRunning = false
                val summary = summarizeFreesoundQueue(queueStates.values)
                val pendingIds = queueStates.filterValues {
                    it == FreesoundImportQueueStatus.FAILED || it == FreesoundImportQueueStatus.CANCELLED
                }.keys
                val pending = pendingIds.mapNotNull(queueSounds::get)
                if (pending.isEmpty()) {
                    pendingQueueStore.clear(kind)
                } else {
                    pendingQueueStore.save(kind, pending, autoResume = false)
                }
                stopQueueRequested = false
                status = buildString {
                    append("Hàng đợi hoàn tất: ${summary.imported} đã nhập và chuẩn hóa")
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
            .filterValues { it == FreesoundImportQueueStatus.FAILED || it == FreesoundImportQueueStatus.CANCELLED }
            .keys
            .mapNotNull(queueSounds::get)
        startImportQueue(failed)
    }

    LaunchedEffect(kind) {
        FreesoundImporter.cleanupStalePartFiles(context)
        runCatching { loadExistingAssets() }
            .onSuccess { existingAssets = it }
            .onFailure { status = "Không kiểm tra được các âm thanh Freesound đã có trong thư viện." }
        pendingQueueStore.load(kind)?.let { pending ->
            val remaining = pending.sounds.filter { it.id !in existingAssets }
            if (remaining.isEmpty()) {
                pendingQueueStore.clear(kind)
            } else if (pending.autoResume) {
                selectedSounds = remaining.associateBy(FreesoundSound::id)
                status = "Đã khôi phục hàng đợi Freesound bị gián đoạn; đang tiếp tục ${remaining.size} tệp."
                startImportQueue(remaining)
            } else {
                selectedSounds = remaining.associateBy(FreesoundSound::id)
                queueSounds = remaining.associateBy(FreesoundSound::id)
                queueStates = remaining.associate { it.id to FreesoundImportQueueStatus.CANCELLED }
                status = "Đã khôi phục ${remaining.size} tệp chưa hoàn tất. Nhấn NHẬP ĐÃ CHỌN để tiếp tục."
            }
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
            endPlaybackPreview()
        }
    }

    LaunchedEffect(status, queueRunning) {
        status?.takeIf(String::isNotBlank)?.let { message ->
            val noisyQueueProgress = queueRunning && message.startsWith("Đang tải và chuẩn hóa ")
            if (!noisyQueueProgress) view.announceForAccessibility(message)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!queueRunning) close() },
        title = { Text("TÌM TRÊN FREESOUND — ${category.label.uppercase()}") },
        text = {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
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

                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { suggestionsExpanded = true },
                        enabled = !searching && !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("GỢI Ý / GẦN ĐÂY ▼") }
                    DropdownMenu(
                        expanded = suggestionsExpanded,
                        onDismissRequest = { suggestionsExpanded = false },
                    ) {
                        presets.forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text("Gợi ý: $suggestion") },
                                onClick = {
                                    query = suggestion
                                    suggestionsExpanded = false
                                },
                            )
                        }
                        recentQueries.forEach { recent ->
                            DropdownMenuItem(
                                text = { Text("Gần đây: $recent") },
                                onClick = {
                                    query = recent
                                    suggestionsExpanded = false
                                },
                            )
                        }
                    }
                }

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

                if (similarSource == null) {
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
                        onClick = { runKeywordSearch(1) },
                        enabled = !searching && !queueRunning && query.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (searching) "ĐANG TÌM…" else "TÌM") }
                } else {
                    Text(
                        "Đang xem âm thanh tương tự: ${similarSource?.name}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Button(
                        onClick = {
                            similarSource = null
                            searchPage = null
                            if (query.isNotBlank()) runKeywordSearch(1)
                        },
                        enabled = !searching && !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("TRỞ LẠI TÌM TỪ KHÓA") }
                }

                if (selectedSounds.isNotEmpty()) {
                    Button(
                        onClick = { startImportQueue(selectedSounds.values.toList()) },
                        enabled = !searching && !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("NHẬP ĐÃ CHỌN (${selectedSounds.size})") }
                    TextButton(
                        onClick = {
                            selectedSounds = emptyMap()
                            scope.launch { pendingQueueStore.clear(kind) }
                        },
                        enabled = !queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("BỎ CHỌN TẤT CẢ") }
                }

                if (queueStates.isNotEmpty()) {
                    Text(
                        buildString {
                            append("Hàng đợi: ${queueSummary.queued} chờ")
                            if (queueSummary.importing > 0) append(" • ${queueSummary.importing} tải/chuẩn hóa")
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
                                scope.launch {
                                    pendingQueueStore.save(kind, queueSounds.values, autoResume = false)
                                }
                                status = "Sẽ dừng hàng đợi sau tệp đang tải/chuẩn hóa."
                            },
                            enabled = !stopQueueRequested,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (stopQueueRequested) "ĐANG DỪNG SAU TỆP HIỆN TẠI…" else "DỪNG HÀNG ĐỢI") }
                    } else if (queueSummary.failed + queueSummary.cancelled > 0) {
                        Button(
                            onClick = ::retryFailed,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("THỬ LẠI ${queueSummary.failed + queueSummary.cancelled} TỆP") }
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
                        val existing = existingAssets[sound.id]
                        FreesoundSearchResultRow(
                            sound = sound,
                            previewLoading = previewLoadingId == sound.id,
                            previewPlaying = previewPlayingId == sound.id,
                            queueStatus = queueStates[sound.id],
                            queueError = queueErrors[sound.id],
                            selected = sound.id in selectedSounds,
                            existingAsset = existing,
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
                            onSimilar = { runSimilarSearch(sound, 1) },
                            onOpenExisting = { existing?.let { existingDetail = it } },
                        )
                    }
                    if (page.results.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { loadPage(page.page - 1) },
                                enabled = !searching && !queueRunning && page.hasPrevious,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG TRƯỚC") }
                            Button(
                                onClick = { loadPage(page.page + 1) },
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

    existingDetail?.let { asset ->
        AlertDialog(
            onDismissRequest = { existingDetail = null },
            title = { Text(asset.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Danh mục: ${kindLabel(asset.kind)}")
                    Text("Mô tả: ${asset.description.ifBlank { "—" }}")
                    Text("Tệp này đã có trong thư viện cục bộ.")
                }
            },
            confirmButton = {
                TextButton(onClick = { existingDetail = null }) { Text("ĐÓNG") }
            },
        )
    }
}

@Composable
private fun FreesoundSearchResultRow(
    sound: FreesoundSound,
    previewLoading: Boolean,
    previewPlaying: Boolean,
    queueStatus: FreesoundImportQueueStatus?,
    queueError: String?,
    selected: Boolean,
    existingAsset: ExistingFreesoundAsset?,
    busy: Boolean,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onSelect: () -> Unit,
    onSimilar: () -> Unit,
    onOpenExisting: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().semantics {
            contentDescription = "Kết quả ${sound.name}, ${formatDuration(sound.durationSeconds)}"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(sound.name, fontWeight = FontWeight.SemiBold)
        Text(formatDuration(sound.durationSeconds), style = MaterialTheme.typography.bodySmall)
        if (sound.description.isNotBlank()) {
            Text(sound.description.take(300), style = MaterialTheme.typography.bodySmall)
        }
        existingAsset?.let {
            Text(
                "Đã có trong ${kindLabel(it.kind)}.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        queueStatus?.let {
            Text("Trạng thái: ${queueStatusLabel(it)}", style = MaterialTheme.typography.bodySmall)
        }
        queueError?.takeIf(String::isNotBlank)?.let {
            Text("Lỗi: ${it.take(220)}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPreview,
                enabled = !busy && sound.preferredPreviewUrl != null,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = if (previewPlaying) "Dừng nghe ${sound.name}" else "Nghe thử ${sound.name}"
                },
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
                onClick = if (existingAsset != null) onOpenExisting else onImport,
                enabled = !busy && (existingAsset != null || sound.preferredPreviewUrl != null),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = if (existingAsset != null) {
                        "Mở âm thanh đã có ${sound.name}"
                    } else {
                        "Nhập và chuẩn hóa ${sound.name}"
                    }
                },
            ) {
                Text(
                    if (existingAsset != null) {
                        "MỞ ĐÃ CÓ"
                    } else {
                        when (queueStatus) {
                            FreesoundImportQueueStatus.QUEUED -> "ĐANG CHỜ"
                            FreesoundImportQueueStatus.IMPORTING -> "ĐANG NHẬP / CHUẨN HÓA…"
                            FreesoundImportQueueStatus.IMPORTED -> "ĐÃ NHẬP"
                            FreesoundImportQueueStatus.FAILED -> "NHẬP LẠI"
                            FreesoundImportQueueStatus.DUPLICATE -> "ĐÃ CÓ"
                            FreesoundImportQueueStatus.CANCELLED -> "NHẬP"
                            null -> "NHẬP"
                        }
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSelect,
                enabled = !busy && existingAsset == null && sound.preferredPreviewUrl != null,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = if (selected) "Bỏ chọn ${sound.name}" else "Chọn ${sound.name}"
                },
            ) { Text(if (selected) "BỎ CHỌN" else "CHỌN") }
            Button(
                onClick = onSimilar,
                enabled = !busy,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Tìm âm thanh tương tự ${sound.name}"
                },
            ) { Text("TÌM TƯƠNG TỰ") }
        }
    }
}

private fun AudioAssetKind.toFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}

private fun kindLabel(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Nhạc nền"
    AudioAssetKind.AMBIENCE -> "Âm thanh môi trường"
    AudioAssetKind.SFX -> "Hiệu ứng âm thanh"
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

private fun queueStatusLabel(status: FreesoundImportQueueStatus): String = when (status) {
    FreesoundImportQueueStatus.QUEUED -> "Chờ nhập"
    FreesoundImportQueueStatus.IMPORTING -> "Đang tải và chuẩn hóa"
    FreesoundImportQueueStatus.IMPORTED -> "Đã nhập và chuẩn hóa"
    FreesoundImportQueueStatus.FAILED -> "Lỗi"
    FreesoundImportQueueStatus.DUPLICATE -> "Đã có trong thư viện"
    FreesoundImportQueueStatus.CANCELLED -> "Đã dừng"
}

private fun localAssetDescription(track: SceneMusicTrackEntity): String {
    val markerRegex = Regex(
        """(?i)(?:type\s*[:=]\s*(?:sfx[_-]?continuous|continuous|sfx|sound[_-]?effect|ambience|environment|music)|\[(?:continuous|sfx[_-]?continuous|sfx|ambience|environment|music)])""",
    )
    return track.tagsCsv.replace(markerRegex, "").trim().trim(',', ';').take(300)
}
