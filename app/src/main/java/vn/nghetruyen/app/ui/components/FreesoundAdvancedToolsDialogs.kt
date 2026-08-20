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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import vn.nghetruyen.app.freesound.FreesoundExactDuplicateAnalyzer
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
import vn.nghetruyen.app.freesound.FreesoundSemanticSearchEngine
import vn.nghetruyen.app.freesound.FreesoundSemanticSearchHit
import vn.nghetruyen.app.freesound.FreesoundSemanticSearchResult
import vn.nghetruyen.app.freesound.FreesoundSound
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderPlaybackService

@Composable
fun FreesoundAdvancedToolsDialog(
    kind: AudioAssetKind,
    tracks: List<SceneMusicTrackEntity>,
    normalizationTargetLufs: Float,
    onUseQuery: (String) -> Unit,
    onImported: (FreesoundImportResult) -> Unit,
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
    val semanticEngine = remember(application) {
        FreesoundSemanticSearchEngine(application.container.freesoundClient)
    }
    val previewPlayer = remember { FreesoundPreviewPlayer() }
    val importer = remember(application) {
        FreesoundImporter(
            context = context,
            repository = application.container.libraryRepository,
            existingTracksProvider = { application.container.database.sceneMusicTrackDao().listAll() },
        )
    }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var chapterPlan by remember { mutableStateOf<FreesoundAiKeywordPlan?>(null) }
    var semanticInput by remember { mutableStateOf("") }
    var semanticPlan by remember { mutableStateOf<FreesoundSemanticPlan?>(null) }
    var semanticHits by remember { mutableStateOf<List<FreesoundSemanticSearchHit>>(emptyList()) }
    var semanticPreviewId by remember { mutableStateOf<Int?>(null) }
    var semanticImportingId by remember { mutableStateOf<Int?>(null) }
    var gaps by remember { mutableStateOf<List<FreesoundLibraryGap>>(emptyList()) }
    var duplicatePairs by remember { mutableStateOf<List<FreesoundNearDuplicate>>(emptyList()) }
    var duplicateTracks by remember { mutableStateOf<Map<String, SceneMusicTrackEntity>>(emptyMap()) }

    fun endPreview() {
        previewPlayer.stop()
        semanticPreviewId = null
        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
    }

    fun analyzeChapter() {
        if (busy) return
        val snapshot = PlaybackQueueStore.state.value
        if (snapshot.storyId.isBlank() || snapshot.chapterId.isBlank() || snapshot.paragraphs.isEmpty()) {
            status = "Không có chương đang đọc để gửi AI."
            return
        }
        val fullChapter = snapshot.paragraphs.joinToString("\n\n")
        busy = true
        status = "Đang gửi toàn bộ chương (${fullChapter.length} ký tự) để tìm ${advancedKindLabel(kind).lowercase()}…"
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
                    status = "AI ${result.value.provider} / ${result.value.model}: ${result.value.suggestions.size} từ khóa ${advancedKindLabel(kind).lowercase()} từ toàn chương."
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
        endPreview()
        semanticHits = emptyList()
        status = "Đang phân tích mô tả tiếng Việt và tìm gộp trên Freesound…"
        scope.launch {
            val aiResult = assistant.expandVietnameseSearch(
                storyId = snapshot.storyId,
                naturalLanguageQuery = semanticInput,
                kind = kind,
            )
            val queries = when (aiResult) {
                is AppResult.Success -> {
                    semanticPlan = aiResult.value
                    aiResult.value.queries
                }
                is AppResult.Failure -> {
                    val fallback = FreesoundSemanticSearchEngine.fallbackQueries(semanticInput, kind)
                    semanticPlan = FreesoundSemanticPlan("LOCAL_FALLBACK", "", fallback)
                    fallback
                }
            }
            if (queries.isEmpty()) {
                status = (aiResult as? AppResult.Failure)?.message ?: "Không tạo được truy vấn tìm kiếm."
                busy = false
                return@launch
            }
            when (
                val merged = semanticEngine.search(
                    queries = queries,
                    category = kind.toAdvancedFreesoundCategory(),
                    duration = FreesoundDuration.RECOMMENDED,
                    maxResults = 40,
                )
            ) {
                is FreesoundSemanticSearchResult.Failure -> status = merged.message
                is FreesoundSemanticSearchResult.Success -> {
                    semanticHits = merged.hits
                    val source = if (aiResult is AppResult.Success) {
                        "AI ${aiResult.value.provider} / ${aiResult.value.model}"
                    } else {
                        "fallback cục bộ vì AI không khả dụng"
                    }
                    status = "Đã hợp nhất ${merged.queries.size} truy vấn từ $source: ${merged.hits.size} kết quả${if (merged.failedQueries > 0) ", ${merged.failedQueries} truy vấn lỗi" else ""}."
                }
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
        if (busy) return
        busy = true
        status = "Đang quét toàn bộ thư viện và tính SHA-256 cho các tệp đọc được…"
        scope.launch {
            val databaseTracks = application.container.database.sceneMusicTrackDao().listAll()
            val effectiveAll = databaseTracks
                .filter { AudioAssetClassifier.classify(it) != kind } + tracks
            duplicateTracks = effectiveAll.associateBy(SceneMusicTrackEntity::id)

            val exactGroups = FreesoundExactDuplicateAnalyzer.find(context, effectiveAll)
            val exactPairs = buildList {
                exactGroups.forEach { group ->
                    val ids = group.trackIds
                    for (firstIndex in 0 until ids.lastIndex) {
                        for (secondIndex in firstIndex + 1 until ids.size) {
                            add(
                                FreesoundNearDuplicate(
                                    firstTrackId = ids[firstIndex],
                                    secondTrackId = ids[secondIndex],
                                    score = 1.0,
                                    reason = "Nội dung file giống hệt (SHA-256)",
                                ),
                            )
                        }
                    }
                }
            }
            val exactKeys = exactPairs.mapTo(hashSetOf()) {
                setOf(it.firstTrackId, it.secondTrackId)
            }
            val nearPairs = FreesoundLibraryAnalyzer.findNearDuplicates(effectiveAll, maxResults = 80)
                .filter { setOf(it.firstTrackId, it.secondTrackId) !in exactKeys }
            val currentIds = tracks.mapTo(hashSetOf(), SceneMusicTrackEntity::id)
            duplicatePairs = (exactPairs + nearPairs)
                .filter { it.firstTrackId in currentIds || it.secondTrackId in currentIds }
                .take(80)
            status = if (duplicatePairs.isEmpty()) {
                "Không phát hiện tệp trùng chính xác hoặc cặp gần trùng liên quan đến ${advancedKindLabel(kind).lowercase()}."
            } else {
                "Phát hiện ${duplicatePairs.size} cặp. SHA-256 = trùng chính xác; các cặp còn lại chỉ là gợi ý gần trùng. Bỏ tệp vẫn chỉ là bản nháp đến khi LƯU DANH SÁCH."
            }
            busy = false
        }
    }

    LaunchedEffect(status, busy) {
        status?.takeIf(String::isNotBlank)?.let { message ->
            if (!busy || !message.startsWith("Đang quét toàn bộ")) view.announceForAccessibility(message)
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && semanticImportingId == null) onDismiss() },
        title = { Text("CÔNG CỤ FREESOUND NÂNG CAO — ${advancedKindLabel(kind).uppercase()}") },
        text = {
            Column(
                Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::analyzeChapter,
                    enabled = !busy && semanticImportingId == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "ĐANG XỬ LÝ…" else "AI PHÂN TÍCH TOÀN CHƯƠNG") }
                Text(
                    "Gửi toàn bộ chương đến đúng provider/model AI của truyện, nhưng chỉ yêu cầu từ khóa cho ${advancedKindLabel(kind).lowercase()} đang quản lý; không quét hai danh mục còn lại.",
                    style = MaterialTheme.typography.bodySmall,
                )

                chapterPlan?.let { plan ->
                    Text("TỪ KHÓA ${advancedKindLabel(kind).uppercase()} TỪ TOÀN CHƯƠNG", fontWeight = FontWeight.SemiBold)
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
                    enabled = !busy && semanticImportingId == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = ::semanticSearch,
                    enabled = !busy && semanticImportingId == null && semanticInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("TÌM NGỮ NGHĨA TRÊN FREESOUND") }
                semanticPlan?.queries?.takeIf { it.isNotEmpty() }?.let { queries ->
                    Text("Truy vấn đã hợp nhất: ${queries.joinToString(" • ")}", style = MaterialTheme.typography.bodySmall)
                }
                semanticHits.forEach { hit ->
                    val sound = hit.sound
                    HorizontalDivider(Modifier.padding(vertical = 3.dp))
                    Column(
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = "Kết quả ngữ nghĩa ${sound.name}, khớp ${hit.matchedQueries} truy vấn"
                        },
                    ) {
                        Text(sound.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatAdvancedDuration(sound.durationSeconds)} • khớp ${hit.matchedQueries} truy vấn",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (sound.description.isNotBlank()) Text(sound.description.take(300), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (semanticPreviewId == sound.id) {
                                        endPreview()
                                        return@Button
                                    }
                                    val url = sound.preferredPreviewUrl ?: return@Button
                                    endPreview()
                                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN)
                                    semanticPreviewId = sound.id
                                    previewPlayer.play(
                                        soundId = sound.id,
                                        previewUrl = url,
                                        onStarted = { semanticPreviewId = sound.id },
                                        onStopped = { endPreview() },
                                        onError = {
                                            endPreview()
                                            status = "Không nghe thử được ${sound.name}."
                                        },
                                    )
                                },
                                enabled = !busy && semanticImportingId == null && sound.preferredPreviewUrl != null,
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = if (semanticPreviewId == sound.id) "Dừng nghe ${sound.name}" else "Nghe thử ${sound.name}"
                                },
                            ) { Text(if (semanticPreviewId == sound.id) "DỪNG NGHE" else "NGHE THỬ") }
                            Button(
                                onClick = {
                                    if (semanticImportingId != null) return@Button
                                    endPreview()
                                    semanticImportingId = sound.id
                                    scope.launch {
                                        importer.importPreview(sound, kind, normalizationTargetLufs)
                                            .onSuccess {
                                                onImported(it)
                                                status = "Đã nhập và chuẩn hóa ${it.title}."
                                            }
                                            .onFailure { status = it.message ?: "Không nhập/chuẩn hóa được âm thanh." }
                                        semanticImportingId = null
                                    }
                                },
                                enabled = !busy && semanticImportingId == null && sound.preferredPreviewUrl != null,
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = "Nhập và chuẩn hóa ${sound.name}"
                                },
                            ) { Text(if (semanticImportingId == sound.id) "ĐANG NHẬP / CHUẨN HÓA…" else "NHẬP") }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::inspectCoverage,
                        enabled = !busy && semanticImportingId == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("KIỂM TRA THIẾU") }
                    Button(
                        onClick = ::inspectDuplicates,
                        enabled = !busy && semanticImportingId == null,
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
                    Text("TRÙNG CHÍNH XÁC / GẦN TRÙNG", fontWeight = FontWeight.SemiBold)
                    val currentIds = tracks.mapTo(hashSetOf(), SceneMusicTrackEntity::id)
                    duplicatePairs.forEach { pair ->
                        val first = duplicateTracks[pair.firstTrackId]
                        val second = duplicateTracks[pair.secondTrackId]
                        if (first != null && second != null) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${(pair.score * 100).toInt()}% • ${pair.reason}", fontWeight = FontWeight.SemiBold)
                                Text("A: ${first.title} (${advancedKindLabel(AudioAssetClassifier.classify(first))})")
                                Text("B: ${second.title} (${advancedKindLabel(AudioAssetClassifier.classify(second))})")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (second.id in currentIds) {
                                        Button(
                                            onClick = {
                                                onStageRemoveTrack(second.id)
                                                duplicatePairs = duplicatePairs.filterNot {
                                                    it.firstTrackId == second.id || it.secondTrackId == second.id
                                                }
                                                status = "Đã đánh dấu bỏ ‘${second.title}’. Nhấn LƯU DANH SÁCH để xác nhận."
                                            },
                                            modifier = Modifier.weight(1f).semantics {
                                                contentDescription = "Giữ ${first.title}, đánh dấu bỏ ${second.title}"
                                            },
                                        ) { Text("GIỮ A / BỎ B") }
                                    }
                                    if (first.id in currentIds) {
                                        Button(
                                            onClick = {
                                                onStageRemoveTrack(first.id)
                                                duplicatePairs = duplicatePairs.filterNot {
                                                    it.firstTrackId == first.id || it.secondTrackId == first.id
                                                }
                                                status = "Đã đánh dấu bỏ ‘${first.title}’. Nhấn LƯU DANH SÁCH để xác nhận."
                                            },
                                            modifier = Modifier.weight(1f).semantics {
                                                contentDescription = "Giữ ${second.title}, đánh dấu bỏ ${first.title}"
                                            },
                                        ) { Text("GIỮ B / BỎ A") }
                                    }
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
            TextButton(onClick = onDismiss, enabled = !busy && semanticImportingId == null) { Text("ĐÓNG") }
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
        TextButton(
            onClick = { onUseQuery(suggestion.query) },
            modifier = Modifier.semantics { contentDescription = "Tìm ${suggestion.query} trên Freesound" },
        ) { Text("TÌM TRÊN FREESOUND") }
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
    val semanticEngine = remember(application) {
        FreesoundSemanticSearchEngine(application.container.freesoundClient)
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
        val sourceText = "${track.title}. $localDescription".trim()
        val aiResult = assistant.expandVietnameseSearch(
            storyId = chapterStoryId,
            naturalLanguageQuery = sourceText,
            kind = kind,
        )
        val queries = when (aiResult) {
            is AppResult.Success -> aiResult.value.queries
            is AppResult.Failure -> FreesoundSemanticSearchEngine.fallbackQueries(sourceText, kind)
        }
        if (queries.isEmpty()) {
            status = (aiResult as? AppResult.Failure)?.message ?: "Không tạo được truy vấn tương tự."
            return null
        }
        return when (
            val merged = semanticEngine.search(
                queries = queries,
                category = category,
                duration = FreesoundDuration.RECOMMENDED,
                maxResults = 24,
            )
        ) {
            is FreesoundSemanticSearchResult.Failure -> {
                status = merged.message
                null
            }
            is FreesoundSemanticSearchResult.Success -> {
                status = if (aiResult is AppResult.Success) {
                    "Đã dùng AI tạo ${merged.queries.size} truy vấn và hợp nhất ${merged.hits.size} kết quả."
                } else {
                    "AI không khả dụng; đã fallback từ tên + mô tả và hợp nhất ${merged.hits.size} kết quả."
                }
                FreesoundSearchPage(
                    count = merged.hits.size,
                    page = 1,
                    pageSize = merged.hits.size.coerceAtLeast(1),
                    results = merged.hits.map(FreesoundSemanticSearchHit::sound),
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
        }
        loading = false
    }

    LaunchedEffect(track.id) { loadSimilar(1) }
    LaunchedEffect(status, loading) {
        if (!loading) status.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
    }
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
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = if (previewId == sound.id) "Dừng nghe ${sound.name}" else "Nghe thử ${sound.name}"
                            },
                        ) { Text(if (previewId == sound.id) "DỪNG NGHE" else "NGHE THỬ") }
                        Button(
                            onClick = {
                                if (importingId != null) return@Button
                                importingId = sound.id
                                scope.launch {
                                    importer.importPreview(sound, kind, normalizationTargetLufs)
                                        .onSuccess {
                                            onImported(it)
                                            status = "Đã nhập và chuẩn hóa ${it.title}."
                                        }
                                        .onFailure { status = it.message ?: "Không nhập/chuẩn hóa được âm thanh." }
                                    importingId = null
                                }
                            },
                            enabled = importingId == null && sound.preferredPreviewUrl != null,
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = "Nhập và chuẩn hóa ${sound.name}"
                            },
                        ) { Text(if (importingId == sound.id) "ĐANG NHẬP / CHUẨN HÓA…" else "NHẬP") }
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
