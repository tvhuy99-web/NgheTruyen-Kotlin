from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text()


def write(path, text):
    (ROOT / path).write_text(text)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)


# 1) Keep music order persistent and expose clear-all for exact library behavior.
path = "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt"
text = read(path)
text = text.replace(
    '@Query("SELECT * FROM scene_music_tracks ORDER BY title COLLATE NOCASE")',
    '@Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, title COLLATE NOCASE")',
)
text = replace_once(
    text,
    '    @Query("DELETE FROM scene_music_tracks WHERE id = :id")\n    suspend fun delete(id: String)\n}',
    '    @Query("DELETE FROM scene_music_tracks WHERE id = :id")\n    suspend fun delete(id: String)\n\n    @Query("DELETE FROM scene_music_tracks")\n    suspend fun deleteAll()\n}',
    "scene music deleteAll",
)
write(path, text)


# 2) Real VietPhrase diagnostic ZIP exporter.
path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseDiagnosticExporter.kt"
write(path, r'''package vn.nghetruyen.app.ai.vietphrase

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class VietPhraseDiagnosticExport(
    val path: String,
    val summary: String,
    val preview: String,
    val traceCount: Int,
)

object VietPhraseDiagnosticExporter {
    private const val TRACE_LIMIT = 20_000

    fun export(
        context: Context,
        title: String,
        paragraphs: List<String>,
        rules: List<VietPhraseRule>,
        storyId: String?,
        fallbackHanViet: Boolean,
    ): Result<VietPhraseDiagnosticExport> = runCatching {
        val body = paragraphs.joinToString("\n\n").trim()
        require(body.isNotBlank()) { "Hãy mở một chương truyện trước khi tạo nhật ký VietPhrase." }
        val options = VietPhraseOptions(
            storyId = storyId,
            fallbackHanViet = fallbackHanViet,
            traceLimit = TRACE_LIMIT,
        )
        val engine = VietPhraseEngine(rules)
        val result = engine.translateWithTrace(body, options)
        val translatedTitle = title.takeIf(String::isNotBlank)?.let { engine.translate(it, options.copy(traceLimit = 0)) }.orEmpty()
        val now = Date()
        val summary = buildString {
            appendLine("NHẬT KÝ VIETPHRASE - NGHE TRUYỆN")
            appendLine("Thời gian: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(now)}")
            appendLine("Tiêu đề: ${title.ifBlank { "Không có tiêu đề" }}")
            appendLine("Độ dài nội dung gốc: ${body.toByteArray(Charsets.UTF_8).size} byte")
            appendLine("Số quyết định được ghi: ${result.trace.size}${if (result.traceTruncated) " (BỊ CẮT DO GIỚI HẠN)" else ""}")
            appendLine()
            appendLine("CÀI ĐẶT")
            appendLine("fallback_hanviet=$fallbackHanViet")
            appendLine()
            appendLine("THỐNG KÊ MATCH")
            VietPhraseDictionaryKind.entries.forEach { kind ->
                appendLine("${kind.fileName}: ${result.appliedByKind[kind] ?: 0}")
            }
        }.trimEnd()
        val traceLines = buildList {
            add("start\tend\tkind\tsource\treplacement\trule_id\tcaptures")
            result.trace.forEach { entry ->
                add(
                    listOf(
                        entry.inputStart,
                        entry.inputEnd,
                        entry.kind?.fileName.orEmpty(),
                        entry.source.tsvSafe(),
                        entry.replacement.tsvSafe(),
                        entry.ruleId.orEmpty().tsvSafe(),
                        entry.captures.entries.sortedBy(Map.Entry<Int, String>::key)
                            .joinToString(";") { (slot, value) -> "$slot=${value.tsvSafe()}" },
                    ).joinToString("\t"),
                )
            }
        }
        val preview = traceLines.drop(1).take(60).joinToString("\n")
        val outputDir = diagnosticDirectory(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(now)
        val target = File(outputDir, "vietphrase_diagnostic_$stamp.zip")
        val temp = File(outputDir, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        runCatching {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.addText(
                    "README.txt",
                    "Gói này được tạo bởi chức năng Chẩn đoán VietPhrase.\n" +
                        "Hãy gửi nguyên file ZIP để phân tích lỗi chất lượng dịch.\n" +
                        "trace.tsv ghi từng quyết định phân đoạn; summary.txt ghi thống kê tổng hợp.\n",
                )
                zip.addText("summary.txt", summary + "\n")
                zip.addText("source.txt", title + "\n\n" + body)
                zip.addText("translated.txt", translatedTitle + "\n\n" + result.text)
                zip.addText("trace.tsv", traceLines.joinToString("\n") + "\n")
            }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Không đổi tên được file ZIP tạm." }
        }.onFailure {
            temp.delete()
            throw it
        }
        VietPhraseDiagnosticExport(
            path = target.absolutePath,
            summary = summary,
            preview = preview,
            traceCount = result.trace.size,
        )
    }

    private fun diagnosticDirectory(context: Context): File {
        val candidates = buildList {
            add(File("/storage/emulated/0/NgheTruyen/diagnostics"))
            add(File("/storage/emulated/0/Download/NgheTruyen/diagnostics"))
            context.getExternalFilesDir(null)?.let { add(File(it, "diagnostics")) }
            add(File(context.filesDir, "diagnostics"))
        }
        return candidates.firstOrNull { candidate ->
            runCatching {
                if (!candidate.exists()) candidate.mkdirs()
                require(candidate.isDirectory)
                val probe = File(candidate, ".vp_probe_${System.nanoTime()}")
                probe.writeText("ok")
                probe.delete()
                true
            }.getOrDefault(false)
        } ?: error("Không tạo được thư mục nhật ký VietPhrase.")
    }

    private fun ZipOutputStream.addText(name: String, value: String) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun String.tsvSafe(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
}
''')


# 3) Reader surface: exact music library controls + real VietPhrase diagnostic flow.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
text = read(path)
text = replace_once(text, "import android.content.Context\n", "import android.content.Context\nimport android.media.MediaPlayer\nimport android.net.Uri\n", "Reader media imports")
text = replace_once(text, "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch\n", "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n", "Reader coroutine imports")
text = replace_once(
    text,
    "import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind\n",
    "import vn.nghetruyen.app.ai.vietphrase.VietPhraseDiagnosticExport\nimport vn.nghetruyen.app.ai.vietphrase.VietPhraseDiagnosticExporter\nimport vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind\n",
    "Reader VietPhrase diagnostic imports",
)
text = replace_once(
    text,
    "    var showMusicLibrary by remember { mutableStateOf(false) }\n    var editingTrack by remember { mutableStateOf<SceneMusicTrackEntity?>(null) }\n",
    "    var showMusicLibrary by remember { mutableStateOf(false) }\n    var musicLibraryDraft by remember { mutableStateOf<List<SceneMusicTrackEntity>>(emptyList()) }\n    var musicLibraryBaselineIds by remember { mutableStateOf<Set<String>>(emptySet()) }\n    var musicSearch by remember { mutableStateOf(\"\") }\n    var selectedMusicTrackId by remember { mutableStateOf<String?>(null) }\n    var editingTrack by remember { mutableStateOf<SceneMusicTrackEntity?>(null) }\n    var showMusicBulkDialog by remember { mutableStateOf(false) }\n    var musicBulkText by remember { mutableStateOf(\"\") }\n    var showMusicBulkResult by remember { mutableStateOf(false) }\n    var musicBulkUpdates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }\n    var musicBulkErrors by remember { mutableStateOf<List<String>>(emptyList()) }\n    var showMusicClearAllConfirm by remember { mutableStateOf(false) }\n    var musicPreviewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }\n",
    "Reader music library state",
)
text = replace_once(
    text,
    "    var showDiagnosticLogDialog by remember { mutableStateOf(false) }\n    var showVietPhraseLogDialog by remember { mutableStateOf(false) }\n",
    "    var showDiagnosticLogDialog by remember { mutableStateOf(false) }\n    var vietPhraseDiagnosticBusy by remember(content.chapter.id) { mutableStateOf(false) }\n    var vietPhraseDiagnosticResult by remember(content.chapter.id) { mutableStateOf<VietPhraseDiagnosticExport?>(null) }\n",
    "Reader VietPhrase diagnostic state",
)

# Add preview cleanup.
anchor = "    DisposableEffect(view, display.keepScreenOn) {\n"
idx = text.index(anchor)
preview_effect = '''    DisposableEffect(Unit) {
        onDispose {
            runCatching { musicPreviewPlayer?.stop() }
            runCatching { musicPreviewPlayer?.release() }
            musicPreviewPlayer = null
        }
    }

'''
text = text[:idx] + preview_effect + text[idx:]

# Sync newly picked files into the draft while the library dialog remains open.
anchor = "    LaunchedEffect(activeIndex, textMode) {\n"
idx = text.index(anchor)
sync_effect = '''    LaunchedEffect(state.sceneMusicTracks, showMusicLibrary) {
        if (showMusicLibrary) {
            val draftIds = musicLibraryDraft.mapTo(linkedSetOf()) { it.id }
            val added = state.sceneMusicTracks.filter { it.id !in musicLibraryBaselineIds && it.id !in draftIds }
            if (added.isNotEmpty()) {
                musicLibraryDraft = (musicLibraryDraft + added)
                    .take(500)
                    .mapIndexed { index, row -> row.copy(orderIndex = index) }
            }
        }
    }

'''
text = text[:idx] + sync_effect + text[idx:]

# Real diagnostic worker helper local to Reader.
anchor = "    val palette = readerPalette(display.theme)\n"
idx = text.index(anchor)
diag_helper = '''    fun createVietPhraseDiagnostic() {
        if (vietPhraseDiagnosticBusy) {
            onMessage("Đang tạo một nhật ký VietPhrase khác.")
            return
        }
        val rawParagraphs = state.originalChapterContent?.paragraphs ?: content.paragraphs
        if (rawParagraphs.isEmpty()) {
            onMessage("Hãy mở một chương truyện trước khi tạo nhật ký VietPhrase.")
            return
        }
        val rules = state.vietPhraseRules.mapNotNull { item ->
            runCatching {
                VietPhraseRule(
                    id = item.id.toString(),
                    source = item.source,
                    target = item.target,
                    kind = VietPhraseDictionaryKind.valueOf(item.kind),
                    priority = item.priority,
                    enabled = item.enabled,
                    scope = VietPhraseScope.valueOf(item.scope),
                    storyId = item.storyId.takeIf(String::isNotBlank),
                    matchMode = VietPhraseMatchMode.valueOf(item.matchMode),
                    ignoreCase = item.ignoreCase,
                    updatedAt = item.updatedAt,
                )
            }.getOrNull()
        }
        vietPhraseDiagnosticBusy = true
        scope.launch {
            val exported = withContext(Dispatchers.IO) {
                VietPhraseDiagnosticExporter.export(
                    context = context,
                    title = content.chapter.title,
                    paragraphs = rawParagraphs,
                    rules = rules,
                    storyId = storyId,
                    fallbackHanViet = state.vietPhraseFallbackHanViet,
                )
            }
            vietPhraseDiagnosticBusy = false
            exported.onSuccess { vietPhraseDiagnosticResult = it }
                .onFailure { onMessage(it.message ?: "Lỗi tạo nhật ký VietPhrase.") }
        }
    }

'''
text = text[:idx] + diag_helper + text[idx:]

text = replace_once(
    text,
    '                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") { showReaderOptions = false; showVietPhraseLogDialog = true }',
    '                    ReaderMenuButton("TẠO NHẬT KÝ VIETPHRASE") { showReaderOptions = false; createVietPhraseDiagnostic() }',
    "Reader real VietPhrase diagnostic action",
)
text = replace_once(
    text,
    '                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") { showMusicLibrary = true }',
    '''                ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC") {
                    val rows = state.sceneMusicTracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                    musicLibraryDraft = rows.mapIndexed { index, row -> row.copy(orderIndex = index) }
                    musicLibraryBaselineIds = rows.mapTo(linkedSetOf()) { it.id }
                    musicSearch = ""
                    showMusicLibrary = true
                }''',
    "open staged music library",
)

start = text.index("    if (showMusicLibrary) {")
end = text.index("    if (showExportDialog) {", start)
new_music_block = r'''    if (showMusicLibrary) {
        val normalizedSearch = musicSearch.trim().lowercase()
        val visibleTracks = musicLibraryDraft.filter { normalizedSearch.isBlank() || it.title.lowercase().contains(normalizedSearch) }
        val enabledCount = musicLibraryDraft.count(SceneMusicTrackEntity::enabled)
        val normalizedCount = musicLibraryDraft.count { kotlin.math.abs(it.loudnessLufsEstimate + 18f) > 0.05f }
        val describedCount = musicLibraryDraft.count { it.tagsCsv.isNotBlank() }
        val estimatedTokens = musicLibraryDraft.sumOf { it.title.length + it.tagsCsv.length }.div(4).coerceAtLeast(0)
        fun stopPreview() {
            runCatching { musicPreviewPlayer?.stop() }
            runCatching { musicPreviewPlayer?.release() }
            musicPreviewPlayer = null
        }
        fun cancelLibrary() {
            stopPreview()
            val transientIds = state.sceneMusicTracks.mapTo(linkedSetOf()) { it.id } - musicLibraryBaselineIds
            scope.launch { transientIds.forEach { app.container.database.sceneMusicTrackDao().delete(it) } }
            showMusicLibrary = false
        }
        AlertDialog(
            onDismissRequest = ::cancelLibrary,
            title = { Text("DANH SÁCH NHẠC NỀN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Kho nhạc: ${musicLibraryDraft.size} bài\n" +
                        "Bài đang bật: $enabledCount bài\n" +
                        "Đã chuẩn hóa: $normalizedCount bài\n" +
                        "Đã có mô tả: $describedCount bài\n" +
                        "Ước tính khi gửi danh mục AI: khoảng $estimatedTokens token",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    musicSearch,
                    { musicSearch = it.take(120) },
                    placeholder = { Text("Tìm theo tên bài") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (visibleTracks.isEmpty()) {
                    Text(if (musicLibraryDraft.isEmpty()) "Chưa có bài nhạc nào." else "Không có bài phù hợp với nội dung tìm kiếm.", modifier = Modifier.padding(vertical = 8.dp))
                }
                visibleTracks.forEach { track ->
                    val index = musicLibraryDraft.indexOfFirst { it.id == track.id }
                    val status = if (track.enabled) "đang bật" else "đang tắt"
                    val described = if (track.tagsCsv.isNotBlank()) "đã có mô tả" else "chưa có mô tả"
                    ReferenceActionButton(
                        "${index + 1}. ${track.title}, $status, $described",
                        { selectedMusicTrackId = track.id },
                        normalColor = ReferenceGray,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Button(onClick = onSelectSceneMusic, enabled = musicLibraryDraft.size < 500, modifier = Modifier.weight(1f).padding(2.dp)) { Text("THÊM BÀI") }
                    Button(onClick = { musicBulkText = ""; showMusicBulkDialog = true }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("DÁN MÔ TẢ") }
                }
                Row(Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(musicLibraryDraft.joinToString("\n") { it.title }))
                        onMessage("Đã sao chép tên của ${musicLibraryDraft.size} bài.")
                    }, enabled = musicLibraryDraft.isNotEmpty(), modifier = Modifier.weight(1f).padding(2.dp)) { Text("SAO CHÉP TÊN") }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(musicLibraryDraft.joinToString("\n") { "${it.title} || ${it.tagsCsv}" }))
                        onMessage("Đã sao chép tên và mô tả của ${musicLibraryDraft.size} bài.")
                    }, enabled = musicLibraryDraft.isNotEmpty(), modifier = Modifier.weight(1f).padding(2.dp)) { Text("SAO CHÉP MÔ TẢ") }
                }
                Row(Modifier.fillMaxWidth()) {
                    Button(onClick = ::stopPreview, enabled = musicPreviewPlayer != null, modifier = Modifier.weight(1f).padding(2.dp)) { Text("DỪNG NGHE THỬ") }
                    Button(onClick = { showMusicClearAllConfirm = true }, enabled = musicLibraryDraft.isNotEmpty(), modifier = Modifier.weight(1f).padding(2.dp)) { Text("XÓA TẤT CẢ") }
                }
            } },
            confirmButton = { TextButton(onClick = {
                val invalid = musicLibraryDraft.firstOrNull { it.tagsCsv.length > 300 }
                if (invalid != null) {
                    onMessage("Mô tả của “${invalid.title}” vượt giới hạn 300 ký tự.")
                } else if (musicLibraryDraft.size > 500) {
                    onMessage("Danh sách vượt giới hạn 500 bài.")
                } else {
                    stopPreview()
                    scope.launch {
                        val dao = app.container.database.sceneMusicTrackDao()
                        val existing = dao.listAll()
                        val now = System.currentTimeMillis()
                        val normalized = musicLibraryDraft.mapIndexed { index, row -> row.copy(orderIndex = index, updatedAt = now) }
                        val keepIds = normalized.mapTo(hashSetOf()) { it.id }
                        existing.filter { it.id !in keepIds }.forEach { dao.delete(it.id) }
                        dao.upsertAll(normalized)
                        onMessage("Đã lưu danh sách nhạc nền.")
                        showMusicLibrary = false
                    }
                }
            }) { Text("LƯU DANH SÁCH") } },
            dismissButton = { TextButton(onClick = ::cancelLibrary) { Text("HỦY") } },
        )
    }

    selectedMusicTrackId?.let { selectedId ->
        musicLibraryDraft.firstOrNull { it.id == selectedId }?.let { track ->
            val index = musicLibraryDraft.indexOfFirst { it.id == track.id }
            AlertDialog(
                onDismissRequest = { selectedMusicTrackId = null },
                title = { Text(track.title) },
                text = { Column {
                    ReaderMenuButton("NGHE THỬ") {
                        runCatching { musicPreviewPlayer?.stop() }
                        runCatching { musicPreviewPlayer?.release() }
                        musicPreviewPlayer = runCatching { MediaPlayer.create(context, Uri.parse(track.uri)) }.getOrNull()?.also { player ->
                            player.setOnCompletionListener { completed ->
                                runCatching { completed.release() }
                                if (musicPreviewPlayer === completed) musicPreviewPlayer = null
                            }
                            player.start()
                        }
                        if (musicPreviewPlayer == null) onMessage("Không nghe thử được bài nhạc này.")
                        selectedMusicTrackId = null
                    }
                    ReaderMenuButton("SỬA TÊN VÀ MÔ TẢ") { editingTrack = track; selectedMusicTrackId = null }
                    ReaderMenuButton(if (track.enabled) "TẮT BÀI NÀY" else "BẬT BÀI NÀY") {
                        musicLibraryDraft = musicLibraryDraft.map { if (it.id == track.id) it.copy(enabled = !track.enabled) else it }
                        selectedMusicTrackId = null
                    }
                    if (index > 0) ReaderMenuButton("DI CHUYỂN LÊN") {
                        val rows = musicLibraryDraft.toMutableList()
                        val previous = rows[index - 1]
                        rows[index - 1] = rows[index]
                        rows[index] = previous
                        musicLibraryDraft = rows.mapIndexed { position, row -> row.copy(orderIndex = position) }
                        selectedMusicTrackId = null
                    }
                    if (index in 0 until musicLibraryDraft.lastIndex) ReaderMenuButton("DI CHUYỂN XUỐNG") {
                        val rows = musicLibraryDraft.toMutableList()
                        val next = rows[index + 1]
                        rows[index + 1] = rows[index]
                        rows[index] = next
                        musicLibraryDraft = rows.mapIndexed { position, row -> row.copy(orderIndex = position) }
                        selectedMusicTrackId = null
                    }
                    ReaderMenuButton("XÓA KHỎI DANH SÁCH") {
                        selectedMusicTrackId = null
                        editingTrack = track.copy(title = "__DELETE_CONFIRM__${track.title}")
                    }
                } },
                confirmButton = { TextButton(onClick = { selectedMusicTrackId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    editingTrack?.let { track ->
        if (track.title.startsWith("__DELETE_CONFIRM__")) {
            val realTitle = track.title.removePrefix("__DELETE_CONFIRM__")
            AlertDialog(
                onDismissRequest = { editingTrack = null },
                title = { Text("XÓA BÀI NHẠC") },
                text = { Text("Xóa ‘$realTitle’ khỏi danh sách?") },
                confirmButton = { TextButton(onClick = {
                    musicPreviewPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
                    musicPreviewPlayer = null
                    musicLibraryDraft = musicLibraryDraft.filterNot { it.id == track.id }
                        .mapIndexed { index, row -> row.copy(orderIndex = index) }
                    editingTrack = null
                }) { Text("XÓA") } },
                dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("HỦY") } },
            )
        } else {
            var title by remember(track.id, track.title) { mutableStateOf(track.title) }
            var description by remember(track.id, track.tagsCsv) { mutableStateOf(track.tagsCsv) }
            AlertDialog(
                onDismissRequest = { editingTrack = null },
                title = { Text("SỬA THÔNG TIN AI") },
                text = { Column {
                    Text("Tên bài gửi cho AI")
                    OutlinedTextField(title, { title = it.take(120) }, placeholder = { Text("Tên bài") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Mô tả tham khảo cho AI, không bắt buộc AI làm theo", modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        description,
                        { description = it.take(301) },
                        placeholder = { Text("Tông/diễn biến: ...; Dùng: ...; Tránh: ...") },
                        minLines = 4,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Tối đa 300 ký tự. Chỉ ghi thông tin thực sự giúp AI phân biệt và chọn bài.", style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(onClick = {
                    if (description.length > 300) onMessage("Mô tả có ${description.length} ký tự, vượt giới hạn 300.")
                    else {
                        val cleanTitle = title.trim().ifBlank { track.title }
                        musicLibraryDraft = musicLibraryDraft.map {
                            if (it.id == track.id) it.copy(title = cleanTitle, tagsCsv = description.trim()) else it
                        }
                        editingTrack = null
                    }
                }) { Text("LƯU") } },
                dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("HỦY") } },
            )
        }
    }

    if (showMusicBulkDialog) {
        AlertDialog(
            onDismissRequest = { showMusicBulkDialog = false },
            title = { Text("DÁN MÔ TẢ HÀNG LOẠT") },
            text = { Column {
                Text(
                    "Mỗi bài một dòng theo định dạng:\nTên bài || Tông/diễn biến: ...; Dùng: ...; Tránh: ...\n\n" +
                        "Tên phải khớp với danh sách. Dòng có mô tả trống được bỏ qua. Ghi [XÓA] để xóa mô tả hiện có.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    musicBulkText,
                    { musicBulkText = it.take(120_000) },
                    placeholder = { Text("Dán toàn bộ tên và mô tả tại đây") },
                    minLines = 12,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            } },
            confirmButton = { TextButton(onClick = {
                val tracksByName = musicLibraryDraft.associateBy { it.title.trim().lowercase() }
                val updates = linkedMapOf<String, String>()
                val errors = mutableListOf<String>()
                musicBulkText.lineSequence().forEachIndexed { index, raw ->
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
                        name.isBlank() -> errors += "Dòng ${index + 1}: thiếu tên bài."
                        track == null -> errors += "Dòng ${index + 1}: không tìm thấy bài “$name”."
                        description.isBlank() -> Unit
                        description != "[XÓA]" && description.length > 300 -> errors += "Dòng ${index + 1}: mô tả có ${description.length} ký tự, vượt giới hạn 300."
                        else -> updates[track.id] = if (description == "[XÓA]") "" else description
                    }
                }
                musicBulkUpdates = updates
                musicBulkErrors = errors
                showMusicBulkResult = true
            }) { Text("KIỂM TRA") } },
            dismissButton = { TextButton(onClick = { showMusicBulkDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMusicBulkResult) {
        val message = buildString {
            append("Dòng hợp lệ: ${musicBulkUpdates.size}\nDòng cần kiểm tra: ${musicBulkErrors.size}")
            if (musicBulkErrors.isNotEmpty()) {
                append("\n\nCÁC DÒNG CẦN KIỂM TRA:\n")
                append(musicBulkErrors.take(20).joinToString("\n"))
                if (musicBulkErrors.size > 20) append("\n... và ${musicBulkErrors.size - 20} lỗi khác.")
            }
        }
        AlertDialog(
            onDismissRequest = { showMusicBulkResult = false },
            title = { Text("KẾT QUẢ KIỂM TRA") },
            text = { Text(message) },
            confirmButton = {
                if (musicBulkUpdates.isNotEmpty()) TextButton(onClick = {
                    musicLibraryDraft = musicLibraryDraft.map { row ->
                        musicBulkUpdates[row.id]?.let { row.copy(tagsCsv = it) } ?: row
                    }
                    showMusicBulkResult = false
                    showMusicBulkDialog = false
                    onMessage("Đã cập nhật ${musicBulkUpdates.size} bài. ${musicBulkErrors.size} dòng cần kiểm tra.")
                }) { Text("ÁP DỤNG DÒNG HỢP LỆ") }
            },
            dismissButton = { Row {
                if (musicBulkErrors.isNotEmpty()) TextButton(onClick = {
                    clipboard.setText(AnnotatedString(musicBulkErrors.joinToString("\n")))
                    onMessage("Đã sao chép danh sách lỗi.")
                }) { Text("SAO CHÉP LỖI") }
                TextButton(onClick = { showMusicBulkResult = false }) { Text("QUAY LẠI") }
            } },
        )
    }

    if (showMusicClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showMusicClearAllConfirm = false },
            title = { Text("XÓA TOÀN BỘ DANH SÁCH") },
            text = { Text("Xóa tất cả bài nhạc khỏi bản nháp?") },
            confirmButton = { TextButton(onClick = {
                runCatching { musicPreviewPlayer?.stop() }
                runCatching { musicPreviewPlayer?.release() }
                musicPreviewPlayer = null
                musicLibraryDraft = emptyList()
                showMusicClearAllConfirm = false
            }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { showMusicClearAllConfirm = false }) { Text("HỦY") } },
        )
    }

'''
text = text[:start] + new_music_block + text[end:]

start = text.index("    if (showVietPhraseLogDialog) {")
end = text.index("    if (showDiagnosticLogDialog) {", start)
new_diag_block = r'''    if (vietPhraseDiagnosticBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("ĐANG TẠO NHẬT KÝ VIETPHRASE") },
            text = { Text("Đang phân tích chương và đóng gói file ZIP…") },
            confirmButton = {},
        )
    }

    vietPhraseDiagnosticResult?.let { result ->
        val output = buildString {
            append(result.summary)
            append("\n\nFILE ZIP:\n")
            append(result.path)
            if (result.preview.isNotBlank()) {
                append("\n\n60 QUYẾT ĐỊNH ĐẦU TIÊN:\n")
                append(result.preview)
            }
        }
        AlertDialog(
            onDismissRequest = { vietPhraseDiagnosticResult = null },
            title = { Text("NHẬT KÝ VIETPHRASE") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { Text(output) } },
            confirmButton = { TextButton(onClick = { vietPhraseDiagnosticResult = null }) { Text("ĐÓNG") } },
            dismissButton = { TextButton(onClick = {
                clipboard.setText(AnnotatedString(result.path))
                onMessage("Đã sao chép đường dẫn file ZIP.")
            }) { Text("SAO CHÉP ĐƯỜNG DẪN") } },
        )
    }

'''
text = text[:start] + new_diag_block + text[end:]
write(path, text)

print("REFERENCE_PARITY_PHASE6_PATCH_OK")
