package vn.nghetruyen.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.ChapterDownloadFailureEntity
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.ChapterNoteEntity
import vn.nghetruyen.app.data.local.DownloadJobEntity
import vn.nghetruyen.app.data.local.FollowedStoryEntity
import vn.nghetruyen.app.data.local.ReadingHistoryEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.ui.DownloadedLibraryCallbacks
import vn.nghetruyen.app.ui.LibrarySection
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceDivider
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceTabButton
import vn.nghetruyen.app.ui.components.ReferenceText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    state: MainUiState,
    onSectionSelected: (LibrarySection) -> Unit,
    onImportFile: () -> Unit,
    onStoryClick: (StoryEntity) -> Unit,
    onUpdateDownloadedStory: (StoryEntity) -> Unit,
    onRemoveFromReading: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onPrioritizeDownload: (String) -> Unit,
    onRetryFailedChapter: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveOffline: (String) -> Unit,
    onCheckFollowing: () -> Unit,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onNoteClick: (ChapterNoteEntity) -> Unit,
    onDeleteNote: (String) -> Unit,
    onHistoryClick: (ReadingHistoryEntity) -> Unit,
    onClearReadingHistory: () -> Unit,
    onFollowingClick: (FollowedStoryEntity) -> Unit,
    onUnfollow: (String) -> Unit,
) {
    val view = LocalView.current
    var query by remember { mutableStateOf("") }
    var querySection by remember { mutableStateOf(state.librarySection) }
    var showSearch by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showDownloadQueue by remember { mutableStateOf(false) }
    var showReadingHistory by remember { mutableStateOf(false) }
    var showClearReadingHistory by remember { mutableStateOf(false) }
    var historyQuery by remember { mutableStateOf("") }
    var readingSort by remember { mutableStateOf("recent") }
    var downloadedSort by remember { mutableStateOf("recent") }
    var bookmarkSort by remember { mutableStateOf("recent") }

    val itemCount = when (state.librarySection) {
        LibrarySection.READING -> state.readingStories.size
        LibrarySection.DOWNLOADED -> state.downloadedStories.size
        LibrarySection.BOOKMARKS -> state.bookmarks.map(BookmarkEntity::storyId).distinct().size
        LibrarySection.NOTES -> state.notes.size
        LibrarySection.FOLLOWING -> state.following.size
    }
    val sectionName = when (state.librarySection) {
        LibrarySection.READING -> "Đang đọc"
        LibrarySection.DOWNLOADED -> "Đã tải"
        LibrarySection.BOOKMARKS -> "Đánh dấu"
        LibrarySection.NOTES -> "Ghi chú"
        LibrarySection.FOLLOWING -> "Theo dõi"
    }

    LaunchedEffect(state.librarySection, itemCount) {
        if (querySection != state.librarySection) {
            query = ""
            querySection = state.librarySection
        }
        delay(120)
        view.announceForAccessibility("Tủ truyện, $sectionName, $itemCount mục")
    }

    val needle = query.trim().lowercase()
    val readingVisible = state.readingStories
        .filter { story -> needle.isBlank() || listOf(story.title, story.author, story.description).any { it.lowercase().contains(needle) } }
        .let { stories ->
            when (readingSort) {
                "title" -> stories.sortedBy { it.title.lowercase() }
                "progress" -> stories.sortedWith(
                    compareByDescending<StoryEntity> { story ->
                        state.readingProgress[story.id]?.let { progress ->
                            if (progress.totalParagraphs > 0)
                                (progress.paragraphIndex + 1).toDouble() / progress.totalParagraphs.toDouble()
                            else 0.0
                        } ?: 0.0
                    }.thenByDescending { story -> state.readingProgress[story.id]?.updatedAt ?: 0L },
                )
                else -> stories.sortedByDescending { it.updatedAt }
            }
        }
    val downloadedVisible = state.downloadedStories
        .filter { story -> needle.isBlank() || listOf(story.title, story.author).any { it.lowercase().contains(needle) } }
        .let { stories ->
            when (downloadedSort) {
                "title" -> stories.sortedBy { it.title.lowercase() }
                "size" -> stories.sortedByDescending { state.offlineStorage[it.id]?.bytes ?: 0L }
                "chapters" -> stories.sortedByDescending { state.offlineStorage[it.id]?.chapterCount ?: 0 }
                else -> stories.sortedByDescending { it.updatedAt }
            }
        }
    val visibleBookmarks = state.bookmarks
        .groupBy(BookmarkEntity::storyId)
        .mapNotNull { (_, values) ->
            values.sortedWith(
                compareByDescending<BookmarkEntity> { !it.label.startsWith("Truyện:") }
                    .thenByDescending(BookmarkEntity::createdAt),
            ).firstOrNull()
        }
        .filter { needle.isBlank() || it.label.lowercase().contains(needle) || it.storyId.lowercase().contains(needle) }
        .let { values -> if (bookmarkSort == "title") values.sortedBy { it.label.lowercase() } else values.sortedByDescending { it.createdAt } }
    val visibleNotes = state.notes
        .filter { needle.isBlank() || it.text.lowercase().contains(needle) || it.storyId.lowercase().contains(needle) || it.chapterId.lowercase().contains(needle) }
        .let { values -> if (bookmarkSort == "title") values.sortedBy { it.text.lowercase() } else values.sortedByDescending { it.updatedAt } }
    val followingVisible = state.following.filter {
        needle.isBlank() || it.title.lowercase().contains(needle) ||
            it.sourceId.lowercase().contains(needle) || it.latestKnownChapter.lowercase().contains(needle)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReferenceScreenBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ReferenceDivider)
                .padding(2.dp),
        ) {
            listOf(
                LibrarySection.READING to "ĐANG ĐỌC",
                LibrarySection.DOWNLOADED to "ĐÃ TẢI",
                LibrarySection.BOOKMARKS to "ĐÁNH DẤU",
                LibrarySection.FOLLOWING to "THEO DÕI",
            ).forEach { (section, label) ->
                val selected = if (section == LibrarySection.BOOKMARKS) {
                    state.librarySection == LibrarySection.BOOKMARKS || state.librarySection == LibrarySection.NOTES
                } else {
                    state.librarySection == section
                }
                ReferenceTabButton(
                    text = label,
                    selected = selected,
                    onClick = { onSectionSelected(section) },
                    accessibilityLabel = label,
                    minHeight = 50.dp,
                    unselectedColor = ReferenceDivider,
                    unselectedContentColor = ReferenceText,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }

        when (state.librarySection) {
            LibrarySection.READING -> {
                LibraryControl("TÌM TRUYỆN") { showSearch = true }
                LibraryControl(
                    "SẮP XẾP: " + when (readingSort) {
                        "title" -> "TÊN A-Z"
                        "progress" -> "TIẾN ĐỘ ĐỌC"
                        else -> "ĐỌC GẦN ĐÂY"
                    },
                ) { showSort = true }
                LibraryControl("HÀNG ĐỢI TẢI") { showDownloadQueue = true }
                LibraryControl("LỊCH SỬ ĐỌC") { showReadingHistory = true }
                LibraryControl("NHẬP TRUYỆN", onImportFile)
                StoryEntityList(readingVisible, onStoryClick, onRemoveFromReading, "Chưa có truyện đang đọc.")
            }

            LibrarySection.DOWNLOADED -> {
                LibraryControl("TÌM TRUYỆN") { showSearch = true }
                LibraryControl(
                    "SẮP XẾP: " + when (downloadedSort) {
                        "title" -> "TÊN A-Z"
                        "size" -> "DUNG LƯỢNG LỚN NHẤT"
                        "chapters" -> "NHIỀU CHƯƠNG NHẤT"
                        else -> "MỚI TẢI"
                    },
                ) { showSort = true }
                LibraryControl("NHẬP TỆP", onImportFile)
                DownloadedSection(
                    stories = downloadedVisible,
                    jobs = emptyList(),
                    failures = emptyList(),
                    storage = state.offlineStorage,
                    onStoryClick = onStoryClick,
                    onUpdateDownloadedStory = onUpdateDownloadedStory,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onPrioritizeDownload = onPrioritizeDownload,
                    onRetryFailedChapter = onRetryFailedChapter,
                    onCancelDownload = onCancelDownload,
                    onRemoveOffline = onRemoveOffline,
                )
            }

            LibrarySection.BOOKMARKS -> {
                LibraryControl("TÌM TRUYỆN") { showSearch = true }
                LibraryControl("SẮP XẾP: ${if (bookmarkSort == "title") "TÊN A-Z" else "MỚI ĐÁNH DẤU"}") { showSort = true }
                LibraryControl("XEM GHI CHÚ") { onSectionSelected(LibrarySection.NOTES) }
                BookmarkList(
                    bookmarks = visibleBookmarks,
                    onBookmarkOpen = onBookmarkClick,
                    onBookmarkDelete = onDeleteBookmark,
                )
            }

            LibrarySection.NOTES -> {
                LibraryControl("TÌM GHI CHÚ") { showSearch = true }
                LibraryControl("SẮP XẾP: ${if (bookmarkSort == "title") "NỘI DUNG A-Z" else "MỚI CẬP NHẬT"}") { showSort = true }
                LibraryControl("XEM ĐÁNH DẤU") { onSectionSelected(LibrarySection.BOOKMARKS) }
                NoteList(visibleNotes, onNoteClick, onDeleteNote)
            }

            LibrarySection.FOLLOWING -> {
                LibraryControl("TÌM TRUYỆN") { showSearch = true }
                LibraryControl("KIỂM TRA CẬP NHẬT", onCheckFollowing)
                FollowingList(followingVisible, onFollowingClick, onUnfollow)
            }
        }
    }

    if (showSearch) {
        val meta = when (state.librarySection) {
            LibrarySection.READING -> "TÌM TRONG ĐANG ĐỌC" to "Tên truyện hoặc chương"
            LibrarySection.DOWNLOADED -> "TÌM TRUYỆN ĐÃ TẢI" to "Tên truyện"
            LibrarySection.BOOKMARKS -> "TÌM TRUYỆN ĐÃ ĐÁNH DẤU" to "Tên truyện hoặc chương"
            LibrarySection.NOTES -> "TÌM GHI CHÚ" to "Nhập nội dung ghi chú"
            LibrarySection.FOLLOWING -> "TÌM TRUYỆN ĐANG THEO DÕI" to "Tên truyện hoặc nguồn"
        }
        var draft by remember(showSearch) { mutableStateOf(query) }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text(meta.first) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(180) },
                    placeholder = { Text(meta.second) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { query = draft; showSearch = false }) { Text("TÌM") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { query = ""; showSearch = false }) { Text("HIỆN TẤT CẢ") }
                    TextButton(onClick = { showSearch = false }) { Text("HỦY") }
                }
            },
        )
    }

    if (showSort) {
        val title: String
        val options: List<Pair<String, String>>
        when (state.librarySection) {
            LibrarySection.READING -> {
                title = "SẮP XẾP ĐANG ĐỌC"
                options = listOf("recent" to "ĐỌC GẦN ĐÂY", "title" to "TÊN A-Z", "progress" to "TIẾN ĐỘ ĐỌC")
            }
            LibrarySection.DOWNLOADED -> {
                title = "SẮP XẾP TRUYỆN ĐÃ TẢI"
                options = listOf("recent" to "MỚI TẢI", "title" to "TÊN A-Z", "size" to "DUNG LƯỢNG LỚN NHẤT", "chapters" to "NHIỀU CHƯƠNG NHẤT")
            }
            LibrarySection.BOOKMARKS, LibrarySection.NOTES -> {
                title = if (state.librarySection == LibrarySection.NOTES) "SẮP XẾP GHI CHÚ" else "SẮP XẾP ĐÁNH DẤU"
                options = if (state.librarySection == LibrarySection.NOTES) listOf("recent" to "MỚI CẬP NHẬT", "title" to "NỘI DUNG A-Z") else listOf("recent" to "MỚI ĐÁNH DẤU", "title" to "TÊN A-Z")
            }
            LibrarySection.FOLLOWING -> { title = ""; options = emptyList() }
        }
        if (options.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showSort = false }, title = { Text(title) }, text = { Column {
                    options.forEach { (value, label) ->
                        ReferenceActionButton(text = label, onClick = {
                            when (state.librarySection) {
                                LibrarySection.READING -> readingSort = value
                                LibrarySection.DOWNLOADED -> downloadedSort = value
                                LibrarySection.BOOKMARKS, LibrarySection.NOTES -> bookmarkSort = value
                                LibrarySection.FOLLOWING -> Unit
                            }
                            showSort = false
                        }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                    }
                } }, confirmButton = { TextButton(onClick = { showSort = false }) { Text("ĐÓNG") } },
            )
        } else showSort = false
    }

    if (showDownloadQueue) {
        AlertDialog(
            onDismissRequest = { showDownloadQueue = false }, title = { Text("HÀNG ĐỢI TẢI") }, text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (state.downloads.isEmpty()) Text("Hàng đợi tải đang trống.")
                state.downloads.forEach { job ->
                    DownloadJobControls(job, state.downloadFailures.filter { it.jobId == job.id }, onPauseDownload, onResumeDownload, onRetryDownload, onPrioritizeDownload, onRetryFailedChapter, onCancelDownload)
                }
            } }, confirmButton = { TextButton(onClick = { showDownloadQueue = false }) { Text("ĐÓNG") } },
        )
    }

    if (showReadingHistory) {
        AlertDialog(
            onDismissRequest = { showReadingHistory = false }, title = { Text("LỊCH SỬ ĐỌC") }, text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = historyQuery, onValueChange = { historyQuery = it.take(160) }, placeholder = { Text("Tìm truyện hoặc chương") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val historyNeedle = historyQuery.trim().lowercase()
                val history = state.readingHistory.filter { historyNeedle.isBlank() || it.storyTitle.lowercase().contains(historyNeedle) || it.chapterTitle.lowercase().contains(historyNeedle) || it.sourceId.lowercase().contains(historyNeedle) }
                if (history.isEmpty()) Text("Chưa có lịch sử đọc.", modifier = Modifier.padding(top = 10.dp))
                val dayFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
                val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                history.groupBy { dayFormat.format(Date(it.visitedAt)) }.forEach { (day, entries) ->
                    Text(day, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    entries.forEach { item -> ReferenceActionButton(text = "${timeFormat.format(Date(item.visitedAt))} • ${item.storyTitle}\n${item.chapterTitle} • đoạn ${item.paragraphIndex + 1}/${item.totalParagraphs.coerceAtLeast(1)} • ${item.sourceId}", onClick = { showReadingHistory = false; onHistoryClick(item) }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) }
                }
            } }, confirmButton = { TextButton(onClick = { showReadingHistory = false }) { Text("ĐÓNG") } }, dismissButton = { if (state.readingHistory.isNotEmpty()) TextButton(onClick = { showReadingHistory = false; showClearReadingHistory = true }) { Text("XÓA LỊCH SỬ") } },
        )
    }

    if (showClearReadingHistory) {
        AlertDialog(onDismissRequest = { showClearReadingHistory = false }, title = { Text("XÓA LỊCH SỬ ĐỌC") }, text = { Text("Xóa toàn bộ lịch sử đọc? Tiến độ đọc vẫn được giữ.") }, confirmButton = { TextButton(onClick = { onClearReadingHistory(); historyQuery = ""; showClearReadingHistory = false }) { Text("XÓA") } }, dismissButton = { TextButton(onClick = { showClearReadingHistory = false }) { Text("HỦY") } })
    }
}

@Composable
private fun LibraryControl(text: String, onClick: () -> Unit) {
    ReferenceActionButton(text = text, onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
}

@Composable
private fun DownloadJobControls(job: DownloadJobEntity, failures: List<ChapterDownloadFailureEntity>, onPauseDownload: (String) -> Unit, onResumeDownload: (String) -> Unit, onRetryDownload: (String) -> Unit, onPrioritizeDownload: (String) -> Unit, onRetryFailedChapter: (String) -> Unit, onCancelDownload: (String) -> Unit) {
    Text(job.currentChapterTitle.ifBlank { "Tải truyện" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Text("${job.completedChapters}/${job.totalChapters} chương • ${job.state}", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth()) {
        when (job.state) {
            "QUEUED", "RUNNING" -> Button({ onPauseDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("TẠM DỪNG") }
            "PAUSED" -> Button({ onResumeDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("TIẾP TỤC") }
            "FAILED", "CANCELLED" -> Button({ onRetryDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("THỬ LẠI") }
        }
        if (job.state in setOf("QUEUED", "PAUSED", "FAILED")) Button({ onPrioritizeDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("ƯU TIÊN") }
        if (job.state !in setOf("COMPLETED", "CANCELLED")) Button({ onCancelDownload(job.storyId) }, Modifier.weight(1f).padding(1.dp)) { Text("HỦY") }
    }
    failures.forEach { failure ->
        Text(failure.chapterTitle.ifBlank { "Chương ${failure.chapterIndex + 1}" }, fontWeight = FontWeight.SemiBold)
        Text(failure.errorMessage, style = MaterialTheme.typography.bodySmall)
        Button({ onRetryFailedChapter(failure.id) }, Modifier.fillMaxWidth()) { Text("THỬ LẠI RIÊNG CHƯƠNG") }
    }
}

@Composable
private fun BookmarkList(bookmarks: List<BookmarkEntity>, onBookmarkOpen: (BookmarkEntity) -> Unit, onBookmarkDelete: (String) -> Unit) {
    var selected by remember { mutableStateOf<BookmarkEntity?>(null) }
    var deleteConfirm by remember { mutableStateOf<BookmarkEntity?>(null) }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.label.removePrefix("Truyện: ").ifBlank { "ĐÁNH DẤU" }) }, text = { Column {
        ReferenceActionButton("MỞ TRUYỆN", { selected = null; onBookmarkOpen(item) }, modifier = Modifier.fillMaxWidth())
        ReferenceActionButton("BỎ ĐÁNH DẤU", { selected = null; deleteConfirm = item }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } }) }
    deleteConfirm?.let { item -> AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("BỎ ĐÁNH DẤU") }, text = { Text("Bỏ đánh dấu truyện này?") }, confirmButton = { TextButton(onClick = { onBookmarkDelete(item.id); deleteConfirm = null }) { Text("BỎ ĐÁNH DẤU") } }, dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("HỦY") } }) }
    if (bookmarks.isEmpty()) { Text("Chưa có truyện đã đánh dấu.", modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(bookmarks, key = { "bookmark:${it.id}" }) { item -> Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { selected = item }) { Column(modifier = Modifier.padding(14.dp)) {
        Text(item.label.removePrefix("Truyện: ").ifBlank { "Đánh dấu đoạn ${item.paragraphIndex + 1}" }, fontWeight = FontWeight.SemiBold)
        if (!item.label.startsWith("Truyện:")) Text("Đoạn ${item.paragraphIndex + 1}")
    } } } }
}

@Composable
private fun NoteList(notes: List<ChapterNoteEntity>, onOpen: (ChapterNoteEntity) -> Unit, onDelete: (String) -> Unit) {
    if (notes.isEmpty()) { Text("Chưa có ghi chú.", modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(notes, key = { "note:${it.id}" }) { note -> Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { onOpen(note) }) { Column(Modifier.padding(12.dp)) {
        Text(note.text, fontWeight = FontWeight.SemiBold, maxLines = 3); Text("Đoạn ${note.paragraphIndex + 1}", style = MaterialTheme.typography.bodySmall)
        ReferenceActionButton(text = "XÓA GHI CHÚ", onClick = { onDelete(note.id) }, minHeight = 44.dp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    } } } }
}

@Composable
private fun FollowingList(items: List<FollowedStoryEntity>, onOpen: (FollowedStoryEntity) -> Unit, onUnfollow: (String) -> Unit) {
    var selected by remember { mutableStateOf<FollowedStoryEntity?>(null) }; var unfollowConfirm by remember { mutableStateOf<FollowedStoryEntity?>(null) }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title) }, text = { Column {
        ReferenceActionButton("MỞ TRUYỆN", { selected = null; onOpen(item) }, modifier = Modifier.fillMaxWidth()); ReferenceActionButton("BỎ THEO DÕI", { selected = null; unfollowConfirm = item }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } }) }
    unfollowConfirm?.let { item -> AlertDialog(onDismissRequest = { unfollowConfirm = null }, title = { Text("BỎ THEO DÕI") }, text = { Text("Bỏ theo dõi “${item.title}”?") }, confirmButton = { TextButton(onClick = { onUnfollow(item.storyId); unfollowConfirm = null }) { Text("BỎ THEO DÕI") } }, dismissButton = { TextButton(onClick = { unfollowConfirm = null }) { Text("HỦY") } }) }
    if (items.isEmpty()) { Text("Chưa theo dõi truyện nào.", modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(items, key = { it.storyId }) { item -> Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { selected = item }) { Column(modifier = Modifier.padding(14.dp)) {
        Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.latestKnownChapter.ifBlank { "Chạm để mở tùy chọn" }); if (item.newChapterCount > 0) Text("${item.newChapterCount} chương mới chưa xem", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(item.sourceId, style = MaterialTheme.typography.labelSmall)
    } } } }
}

@Composable
private fun DownloadedSection(
    stories: List<StoryEntity>,
    jobs: List<DownloadJobEntity>,
    failures: List<ChapterDownloadFailureEntity>,
    storage: Map<String, vn.nghetruyen.app.data.local.OfflineStoryStorage>,
    onStoryClick: (StoryEntity) -> Unit,
    onUpdateDownloadedStory: (StoryEntity) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onPrioritizeDownload: (String) -> Unit,
    onRetryFailedChapter: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveOffline: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as NgheTruyenApplication
    val scope = rememberCoroutineScope()
    var selectedOptions by remember { mutableStateOf<StoryEntity?>(null) }
    var pendingRemoval by remember { mutableStateOf<StoryEntity?>(null) }
    var selectedChapterStory by remember { mutableStateOf<StoryEntity?>(null) }
    var downloadedChapters by remember { mutableStateOf<List<ChapterEntity>>(emptyList()) }
    var chapterQuery by remember { mutableStateOf("") }
    var showChapterSearch by remember { mutableStateOf(false) }

    selectedOptions?.let { story ->
        AlertDialog(
            onDismissRequest = { selectedOptions = null },
            title = { Text(story.title.ifBlank { "Truyện" }) },
            text = { Column {
                ReferenceActionButton("CẬP NHẬT / TẢI TIẾP", { selectedOptions = null; onUpdateDownloadedStory(story) }, modifier = Modifier.fillMaxWidth())
                ReferenceActionButton("XÓA DỮ LIỆU ĐÃ TẢI", { selectedOptions = null; pendingRemoval = story }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            } },
            confirmButton = { TextButton(onClick = { selectedOptions = null }) { Text("ĐÓNG") } },
        )
    }
    pendingRemoval?.let { story ->
        val chapterCount = storage[story.id]?.chapterCount ?: 0
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("XÓA DỮ LIỆU ĐÃ TẢI") },
            text = { Text(if (story.sourceId == "offline") "Truyện nhập từ tệp sẽ bị xóa khỏi ứng dụng cùng toàn bộ chương và tiến độ." else "Xóa $chapterCount chương đã tải của ${story.title}? Tiến độ đọc và dấu trang sẽ được giữ lại.") },
            confirmButton = { TextButton(onClick = { pendingRemoval = null; onRemoveOffline(story.id) }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("HỦY") } },
        )
    }

    selectedChapterStory?.let { story ->
        val visible = rankDownloadedChapters(downloadedChapters, chapterQuery)
        AlertDialog(
            onDismissRequest = { selectedChapterStory = null },
            title = { Text("CHƯƠNG: ${story.title.ifBlank { "Truyện" }}${if (chapterQuery.isNotBlank()) " - TÌM: ${chapterQuery.trim()}" else ""}") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    if (visible.isEmpty()) {
                        Text("Không tìm thấy chương phù hợp với “${chapterQuery.trim()}”.")
                    } else {
                        visible.forEach { chapter ->
                            ReferenceActionButton(
                                text = chapter.title.ifBlank { "Chương ${chapter.chapterIndex + 1}" },
                                onClick = {
                                    DownloadedLibraryCallbacks.selectChapter(chapter)
                                    selectedChapterStory = null
                                    onStoryClick(story)
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedChapterStory = null }) { Text("ĐÓNG") } },
            dismissButton = {
                TextButton(onClick = { showChapterSearch = true }) {
                    Text(if (chapterQuery.isBlank()) "TÌM KIẾM" else "TÌM: ${chapterQuery.trim()}")
                }
            },
        )
    }

    if (showChapterSearch) {
        var draft by remember(showChapterSearch, selectedChapterStory?.id) { mutableStateOf(chapterQuery) }
        AlertDialog(
            onDismissRequest = { showChapterSearch = false },
            title = { Text("TÌM CHƯƠNG ĐÃ TẢI") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(180) },
                    placeholder = { Text("Nhập tên, số chương hoặc vài ký tự liên quan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chapterQuery = draft.trim()
                    showChapterSearch = false
                }) { Text("TÌM") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        chapterQuery = ""
                        showChapterSearch = false
                    }) { Text("HIỆN TẤT CẢ") }
                    TextButton(onClick = { showChapterSearch = false }) { Text("HỦY") }
                }
            },
        )
    }

    if (stories.isEmpty() && jobs.isEmpty() && failures.isEmpty()) { Text("Chưa có truyện ngoại tuyến.", modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(jobs, key = { "job:${it.id}" }) { job -> Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { Column(modifier = Modifier.padding(14.dp)) {
            DownloadJobControls(job, failures.filter { it.jobId == job.id }, onPauseDownload, onResumeDownload, onRetryDownload, onPrioritizeDownload, onRetryFailedChapter, onCancelDownload)
        } } }
        items(stories, key = { "story:${it.id}" }) { story ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable {
                        scope.launch {
                            val chapters = DownloadedLibraryCallbacks.chapters(app, story)
                            if (chapters.isEmpty()) {
                                Toast.makeText(context, "Truyện này chưa có chương nào còn tồn tại trên thiết bị.", Toast.LENGTH_LONG).show()
                            } else {
                                downloadedChapters = chapters
                                chapterQuery = ""
                                selectedChapterStory = story
                            }
                        }
                    },
            ) { Column(modifier = Modifier.padding(14.dp)) {
                Text(story.title, fontWeight = FontWeight.SemiBold); if (story.author.isNotBlank()) Text(story.author)
                val usage = storage[story.id]
                Text(if (usage == null) "Có thể đọc ngoại tuyến" else "${usage.chapterCount} chương • ${formatStorageBytes(usage.bytes)}")
                Text("Chạm để mở danh sách chương đã tải", style = MaterialTheme.typography.bodySmall)
                ReferenceActionButton("TÙY CHỌN", { selectedOptions = story }, minHeight = 44.dp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            } }
        }
    }
}

private fun rankDownloadedChapters(chapters: List<ChapterEntity>, query: String): List<ChapterEntity> {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return chapters
    return chapters.mapNotNull { chapter ->
        val title = chapter.title.lowercase()
        val indexText = (chapter.chapterIndex + 1).toString()
        val haystack = "$title ${chapter.remoteUrl.lowercase()} $indexText"
        val score = when {
            title == needle || indexText == needle -> 0
            title.startsWith(needle) -> 1
            title.contains(needle) -> 2
            haystack.contains(needle) -> 3
            else -> return@mapNotNull null
        }
        score to chapter
    }.sortedWith(compareBy<Pair<Int, ChapterEntity>> { it.first }.thenBy { it.second.chapterIndex })
        .map { it.second }
}

@Composable
private fun StoryEntityList(items: List<StoryEntity>, onStoryClick: (StoryEntity) -> Unit, onRemoveFromReading: (String) -> Unit, emptyText: String) {
    var selected by remember { mutableStateOf<StoryEntity?>(null) }; var removeConfirm by remember { mutableStateOf<StoryEntity?>(null) }
    selected?.let { story -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(story.title) }, text = { Column {
        ReferenceActionButton("ĐỌC TIẾP", { selected = null; onStoryClick(story) }, modifier = Modifier.fillMaxWidth()); ReferenceActionButton("XÓA KHỎI ĐANG ĐỌC", { selected = null; removeConfirm = story }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("ĐÓNG") } }) }
    removeConfirm?.let { story -> AlertDialog(onDismissRequest = { removeConfirm = null }, title = { Text("XÓA KHỎI ĐANG ĐỌC") }, text = { Text("Xóa “${story.title}” khỏi Đang đọc? Truyện đã tải, dấu trang và lịch sử đọc vẫn được giữ.") }, confirmButton = { TextButton(onClick = { onRemoveFromReading(story.id); removeConfirm = null }) { Text("XÓA") } }, dismissButton = { TextButton(onClick = { removeConfirm = null }) { Text("HỦY") } }) }
    if (items.isEmpty()) { Text(emptyText, modifier = Modifier.padding(16.dp)); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) { items(items, key = { it.id }) { story -> Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { selected = story }) { Column(modifier = Modifier.padding(14.dp)) {
        Text(story.title, fontWeight = FontWeight.SemiBold); if (story.author.isNotBlank()) Text(story.author); Text(if (story.isOffline) "Có thể đọc ngoại tuyến" else story.sourceId)
    } } } }
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
