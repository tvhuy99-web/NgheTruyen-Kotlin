package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.freesound.FreesoundAiAssistant
import vn.nghetruyen.app.freesound.FreesoundAiKeywordPlan
import vn.nghetruyen.app.freesound.FreesoundAiKeywordSuggestion
import vn.nghetruyen.app.freesound.FreesoundCategory
import vn.nghetruyen.app.freesound.FreesoundDuration
import vn.nghetruyen.app.freesound.FreesoundImporter
import vn.nghetruyen.app.freesound.FreesoundImportResult
import vn.nghetruyen.app.freesound.FreesoundLibraryAnalyzer
import vn.nghetruyen.app.freesound.FreesoundLibraryGap
import vn.nghetruyen.app.freesound.FreesoundNearDuplicate
import vn.nghetruyen.app.freesound.FreesoundPreviewPlayer
import vn.nghetruyen.app.freesound.FreesoundSearchPage
import vn.nghetruyen.app.freesound.FreesoundSearchRequest
import vn.nghetruyen.app.freesound.FreesoundSearchResult
import vn.nghetruyen.app.freesound.FreesoundSemanticPlan
import vn.nghetruyen.app.freesound.FreesoundSort
import vn.nghetruyen.app.freesound.FreesoundSound
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderPlaybackService

@Composable
fun FreesoundAdvancedToolsDialog(
    kind: AudioAssetKind,
    tracks: List<SceneMusicTrackEntity>,
    onUseQuery: (String) -> Unit,
    onStageRemoveTrack: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val application = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()
    val assistant = remember(application) {
        FreesoundAiAssistant(
            settingsRepository = application.container.settingsRepository,
            credentialStore = application.container.aiCredentialStore,
            requestGovernor = application.container.aiRequestGovernor,
            libraryRepository = application.container.libraryRepository,
        )
    }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var chapterPlan by remember { mutableStateOf<FreesoundAiKeywordPlan?>(null) }
    var semanticInput by remember { mutableStateOf("") }
    var semanticPlan by remember { mutableStateOf<FreesoundSemanticPlan?>(null) }
    var gaps by remember { mutableStateOf<List<FreesoundLibraryGap>>(emptyList()) }
    var duplicatePairs by remember { mutableStateOf<List<FreesoundNearDuplicate>>(emptyList()) }

    fun analyzeChapter() {
        if (busy) return
        val snapshot = PlaybackQueueStore.state.value
        if (snapshot.storyId.isBlank() || snapshot.chapterId.isBlank() || snapshot.paragraphs.isEmpty()) {
            status = "Không có chương đang đọc để gửi AI."
            return
        }
        val fullChapter = snapshot.paragraphs.joinToString("\n\n")
        busy = true
        status = "Đang gửi toàn bộ chương (${fullChapter.length} ký tự) đến AI đang dùng cho phân vai…"
        scope.launch {
            when (
                val result = assistant.analyzeWholeChapter(
                    storyId = snapshot.storyId,
                    chapterId = snapshot.chapterId,
                    chapterTitle = snapshot.chapterTitle,
                    rawText = fullChapter,
                    kind = kind,
                )
            ) {
                is AppResult.Success -> {
                    chapterPlan = result.value
                    status = "AI ${result.value.provider} / ${result.value.model}: ${result.value.suggestions.size} từ khóa từ toàn chương."
                }
                is AppResult.Failure -> status = result.message
            }
            busy = false
        }
    }

    fun semanticSearch() {
        if (busy || semanticInput.isBlank()) return
        val snapshot = PlaybackQueueStore.state.value
        busy = true
        status = "AI đang chuyển mô tả tiếng Việt thành truy vấn Freesound…"
        scope.launch {
            when (
                val result = assistant.expandVietnameseSearch(
                    storyId = snapshot.storyId,
                    naturalLanguageQuery = semanticInput,
                    kind = kind,
                )
            ) {
                is AppResult.Success -> {
                    semanticPlan = result.value
                    status = "AI ${result.value.provider} / ${result.value.model}: ${result.value.queries.size} truy vấn gợi ý."
                }
                is AppResult.Failure -> status = result.message
            }
            busy = false
        }
    }

    fun inspectCoverage() {
        gaps = FreesoundLibraryAnalyzer.findMissingTopics(kind, tracks)
        status = if (gaps.isEmpty()) {
            "Không thấy khoảng trống rõ ràng trong bộ chủ đề chuẩn của ${advancedKindLabel(kind).lowercase()}."
        } else {
            "Phát hiện ${gaps.size} nhóm âm thanh còn thiếu hoặc phủ yếu."
        }
    }

    fun inspectDuplicates() {
        duplicatePairs = FreesoundLibraryAnalyzer.findNearDuplicates(tracks)
        status = if (duplicatePairs.isEmpty()) {
            "Không phát hiện cặp âm thanh gần trùng đáng tin cậy."
        } else {
            "Phát hiện ${duplicatePairs.size} cặp gần trùng. Mọi thao tác bỏ tệp chỉ là bản nháp cho đến khi LƯU DANH SÁCH."
        }
    }

    LaunchedEffect(status) {
        status?.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("CÔNG CỤ FREESOUND NÂNG CAO — ${advancedKindLabel(kind).uppercase()}") },
        text = {
            Column(
                Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::analyzeChapter,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "ĐANG XỬ LÝ…" else "AI PHÂN TÍCH TOÀN CHƯƠNG") }
                Text(
                    "Chức năng này gửi toàn bộ chương đang đọc đến đúng provider/model AI mà cấu hình phân vai của truyện đang dùng.",
                    style = MaterialTheme.typography.bodySmall,
                )

                chapterPlan?.let { plan ->
                    Text("TỪ KHÓA TỪ TOÀN CHƯƠNG", fontWeight = FontWeight.SemiBold)
                    plan.suggestions.forEach { suggestion ->
                        AdvancedKeywordRow(
                            suggestion = suggestion,
                            coverage = FreesoundLibraryAnalyzer.coverageScore(suggestion.query, tracks),
                            onUseQuery = onUseQuery,
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("TÌM KIẾM NGỮ NGHĨA TIẾNG VIỆT", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = semanticInput,
                    onValueChange = { semanticInput = it.take(2_000) },
                    label = { Text("Mô tả âm thanh bằng tiếng Việt") },
                    placeholder = { Text("Ví dụ: tiếng sấm cực lớn rất gần, khô và đột ngột") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = ::semanticSearch,
                    enabled = !busy && semanticInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("AI TẠO TRUY VẤN FREESOUND") }
                semanticPlan?.queries?.forEach { query ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(query, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onUseQuery(query) }) { Text("TÌM") }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::inspectCoverage,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("KIỂM TRA THIẾU") }
                    Button(
                        onClick = ::inspectDuplicates,
                        enabled = !busy && tracks.size >= 2,
                        modifier = Modifier.weight(1f),
                    ) { Text("TÌM GẦN TRÙNG") }
                }

                if (gaps.isNotEmpty()) {
                    Text("THƯ VIỆN CÒN THIẾU", fontWeight = FontWeight.SemiBold)
                    gaps.forEach { gap ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${gap.query} • phủ ${(gap.coverageScore * 100).toInt()}%",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onUseQuery(gap.query) }) { Text("TÌM") }
                        }
                    }
                }

                if (duplicatePairs.isNotEmpty()) {
                    Text("CÁC CẶP GẦN TRÙNG", fontWeight = FontWeight.SemiBold)
                    duplicatePairs.forEach { pair ->
                        val first = tracks.firstOrNull { it.id == pair.firstTrackId }
                        val second = tracks.firstOrNull { it.id == pair.secondTrackId }
                        if (first != null && second != null) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${(pair.score * 100).toInt()}% • ${pair.reason}", fontWeight = FontWeight.SemiBold)
                                Text("A: ${first.title}")
                                Text("B: ${second.title}")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            onStageRemoveTrack(second.id)
                                            duplicatePairs = duplicatePairs.filterNot {
                                                it.firstTrackId == second.id || it.secondTrackId == second.id
                                            }
                                            status = "Đã đánh dấu bỏ ‘${second.title}’. Nhấn LƯU DANH SÁCH để xác nhận."
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("GIỮ A / BỎ B") }
                                    Button(
                                        onClick = {
                                            onStageRemoveTrack(first.id)
                                            duplicatePairs = duplicatePairs.filterNot {
                                                it.firstTrackId == first.id || it.secondTrackId == first.id
                                            }
                                            status = "Đã đánh dấu bỏ ‘${first.title}’. Nhấn LƯU DANH SÁCH để xác nhận."
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("GIỮ B / BỎ A") }
                                }
                            }
                        }
                    }
                }

                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("ĐÓNG") }
        },
    )
}

@Composable
private fun AdvancedKeywordRow(
    suggestion: FreesoundAiKeywordSuggestion,
    coverage: Double,
    onUseQuery: (String) -> Unit,
) {
    val missing = coverage < 0.56
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "${if (missing) "THIẾU" else "ĐÃ CÓ"} • ${suggestion.query}",
            fontWeight = FontWeight.SemiBold,
        )
        if (suggestion.reason.isNotBlank()) Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
        Text("Mức phủ thư viện: ${(coverage * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onUseQuery(suggestion.query) }) { Text("TÌM TRÊN FREESOUND") }
    }
}

@Composable
fun FreesoundSimilarAssetDialog(
    kind: AudioAssetKind,
    track: SceneMusicTrackEntity,
    normalizationTargetLufs: Float,
    onImported: (FreesoundImportResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val application = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { FreesoundPreviewPlayer() }
    val importer = remember(application) {
        FreesoundImporter(
            context = context,
            repository = application.container.libraryRepository,
            existingTracksProvider = { application.container.database.sceneMusicTrackDao().listAll() },
        )
    }
    val assistant = remember(application) {
        FreesoundAiAssistant(
            settingsRepository = application.container.settingsRepository,
            credentialStore = application.container.aiCredentialStore,
            requestGovernor = application.container.aiRequestGovernor,
            libraryRepository = application.container.libraryRepository,
        )
    }
    val remoteSoundId = remember(track.uri) { FreesoundImporter.soundIdFromManagedUri(track.uri) }
    val category = kind.toAdvancedFreesoundCategory()
    val chapterStoryId = PlaybackQueueStore.state.value.storyId

    var loading by remember(track.id) { mutableStateOf(true) }
    var importingId by remember(track.id) { mutableStateOf<Int?>(null) }
    var previewId by remember(track.id) { mutableStateOf<Int?>(null) }
    var page by remember(track.id) { mutableStateOf<FreesoundSearchPage?>(null) }
    var status by remember(track.id) { mutableStateOf("Đang tìm âm thanh tương tự…") }

    fun endPreview() {
        previewPlayer.stop()
        previewId = null
        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
    }

    suspend fun semanticSimilar(): FreesoundSearchPage? {
        val localDescription = FreesoundLibraryAnalyzer.description(track.tagsCsv)
        return when (
            val semantic = assistant.expandVietnameseSearch(
                storyId = chapterStoryId,
                naturalLanguageQuery = "${track.title}. $localDescription",
                kind = kind,
            )
        ) {
            is AppResult.Failure -> {
                status = semantic.message
                null
            }
            is AppResult.Success -> {
                val merged = linkedMapOf<Int, FreesoundSound>()
                semantic.value.queries.take(4).forEach { query ->
                    when (
                        val result = application.container.freesoundClient.search(
                            FreesoundSearchRequest(
                                query = query,
                                category = category,
                                duration = FreesoundDuration.RECOMMENDED,
                                sort = FreesoundSort.RELEVANCE,
                                page = 1,
                                pageSize = 12,
                            ),
                        )
                    ) {
                        is FreesoundSearchResult.Success -> result.page.results.forEach { sound -> merged.putIfAbsent(sound.id, sound) }
                        is FreesoundSearchResult.Failure -> Unit
                    }
                }
                FreesoundSearchPage(
                    count = merged.size,
                    page = 1,
                    pageSize = merged.size.coerceAtLeast(1),
                    results = merged.values.take(24),
                    hasNext = false,
                    hasPrevious = false,
                )
            }
        }
    }

    suspend fun loadSimilar(targetPage: Int) {
        loading = true
        endPreview()
        if (remoteSoundId != null) {
            when (
                val result = application.container.freesoundClient.similar(
                    remoteSoundId,
                    FreesoundSearchRequest(
                        query = "",
                        category = category,
                        duration = FreesoundDuration.RECOMMENDED,
                        page = targetPage,
                        pageSize = 20,
                    ),
                )
            ) {
                is FreesoundSearchResult.Success -> {
                    page = result.page
                    status = "Âm thanh tương tự theo Freesound ID #$remoteSoundId • trang ${result.page.page}."
                }
                is FreesoundSearchResult.Failure -> status = result.message
            }
        } else {
            page = semanticSimilar()
            if (page?.results?.isNotEmpty() == true) {
                status = "Đã dùng AI tạo truy vấn tương tự từ tên và mô tả của tệp local."
            }
        }
        loading = false
    }

    LaunchedEffect(track.id) { loadSimilar(1) }
    LaunchedEffect(status) { status.takeIf(String::isNotBlank)?.let(view::announceForAccessibility) }
    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading && importingId == null) onDismiss() },
        title = { Text("TÌM ÂM THANH TƯƠNG TỰ") },
        text = {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Tệp gốc: ${track.title}", fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (loading) Text("Đang tải kết quả…")
                page?.results?.forEach { sound ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(sound.name, fontWeight = FontWeight.SemiBold)
                    Text(formatAdvancedDuration(sound.durationSeconds), style = MaterialTheme.typography.bodySmall)
                    if (sound.description.isNotBlank()) Text(sound.description.take(300), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (previewId == sound.id) {
                                    endPreview()
                                    return@Button
                                }
                                val url = sound.preferredPreviewUrl ?: return@Button
                                endPreview()
                                ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN)
                                previewId = sound.id
                                previewPlayer.play(
                                    soundId = sound.id,
                                    previewUrl = url,
                                    onStarted = { previewId = sound.id },
                                    onStopped = { endPreview() },
                                    onError = {
                                        endPreview()
                                        status = "Không nghe thử được ${sound.name}."
                                    },
                                )
                            },
                            enabled = importingId == null && sound.preferredPreviewUrl != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (previewId == sound.id) "DỪNG NGHE" else "NGHE THỬ") }
                        Button(
                            onClick = {
                                if (importingId != null) return@Button
                                importingId = sound.id
                                scope.launch {
                                    importer.importPreview(sound, kind, normalizationTargetLufs)
                                        .onSuccess {
                                            onImported(it)
                                            status = "Đã nhập ${it.title}."
                                        }
                                        .onFailure { status = it.message ?: "Không nhập được âm thanh." }
                                    importingId = null
                                }
                            },
                            enabled = importingId == null && sound.preferredPreviewUrl != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (importingId == sound.id) "ĐANG NHẬP…" else "NHẬP") }
                    }
                }
                if (remoteSoundId != null && page != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { loadSimilar((page?.page ?: 1) - 1) } },
                            enabled = !loading && importingId == null && page?.hasPrevious == true,
                            modifier = Modifier.weight(1f),
                        ) { Text("TRANG TRƯỚC") }
                        Button(
                            onClick = { scope.launch { loadSimilar((page?.page ?: 1) + 1) } },
                            enabled = !loading && importingId == null && page?.hasNext == true,
                            modifier = Modifier.weight(1f),
                        ) { Text("TRANG SAU") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading && importingId == null) { Text("ĐÓNG") }
        },
    )
}

private fun AudioAssetKind.toAdvancedFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}

private fun advancedKindLabel(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Nhạc nền"
    AudioAssetKind.AMBIENCE -> "Âm thanh môi trường"
    AudioAssetKind.SFX -> "Hiệu ứng âm thanh"
}

private fun formatAdvancedDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val minutes = total / 60
    val remaining = total % 60
    return if (minutes > 0) "$minutes:${remaining.toString().padStart(2, '0')}" else "$remaining giây"
}
