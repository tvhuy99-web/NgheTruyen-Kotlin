package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.ChapterDownloadFailureEntity
import vn.nghetruyen.app.data.local.ChapterNoteEntity
import vn.nghetruyen.app.data.local.DownloadJobEntity
import vn.nghetruyen.app.data.local.FollowedStoryEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.ui.LibrarySection
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceDivider
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceTabButton
import vn.nghetruyen.app.ui.components.ReferenceText

@Composable
fun LibraryScreen(
    state: MainUiState,
    onSectionSelected: (LibrarySection) -> Unit,
    onImportFile: () -> Unit,
    onStoryClick: (StoryEntity) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRetryFailedChapter: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveOffline: (String) -> Unit,
    onCheckFollowing: () -> Unit,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onNoteClick: (ChapterNoteEntity) -> Unit,
    onDeleteNote: (String) -> Unit,
    onFollowingClick: (FollowedStoryEntity) -> Unit,
) {
    val view = LocalView.current
    val itemCount = when (state.librarySection) {
        LibrarySection.READING -> state.readingStories.size
        LibrarySection.DOWNLOADED -> state.downloadedStories.size + state.downloads.size
        LibrarySection.BOOKMARKS -> state.bookmarks.size + state.notes.size
        LibrarySection.NOTES -> state.bookmarks.size + state.notes.size
        LibrarySection.FOLLOWING -> state.following.size
    }
    val sectionName = when (state.librarySection) {
        LibrarySection.READING -> "Đang đọc"
        LibrarySection.DOWNLOADED -> "Đã tải"
        LibrarySection.BOOKMARKS -> "Đánh dấu"
        LibrarySection.NOTES -> "Đánh dấu"
        LibrarySection.FOLLOWING -> "Theo dõi"
    }
    LaunchedEffect(state.librarySection, itemCount) {
        delay(120)
        view.announceForAccessibility("Tủ truyện, $sectionName, $itemCount mục")
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
                    accessibilityLabel = "Tủ truyện, ${label.lowercase()}",
                    minHeight = 58.dp,
                    unselectedColor = ReferenceDivider,
                    unselectedContentColor = ReferenceText,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }
        if (state.librarySection == LibrarySection.DOWNLOADED) {
            ReferenceActionButton(
                text = "NHẬP TỆP",
                onClick = onImportFile,
                accessibilityLabel = "Nhập tệp truyện từ thiết bị để đọc",
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            )
        }

        when (state.librarySection) {
            LibrarySection.READING -> StoryEntityList(state.readingStories, onStoryClick, "Chưa có truyện đang đọc.")
            LibrarySection.DOWNLOADED -> DownloadedSection(
                stories = state.downloadedStories,
                jobs = state.downloads,
                failures = state.downloadFailures,
                storage = state.offlineStorage,
                onStoryClick = onStoryClick,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onRetryDownload = onRetryDownload,
                onRetryFailedChapter = onRetryFailedChapter,
                onCancelDownload = onCancelDownload,
                onRemoveOffline = onRemoveOffline,
            )
            LibrarySection.BOOKMARKS, LibrarySection.NOTES -> BookmarkAndNoteList(
                bookmarks = state.bookmarks,
                notes = state.notes,
                onBookmarkOpen = onBookmarkClick,
                onBookmarkDelete = onDeleteBookmark,
                onNoteOpen = onNoteClick,
                onNoteDelete = onDeleteNote,
            )
            LibrarySection.FOLLOWING -> FollowingList(state.following, onFollowingClick, onCheckFollowing)
        }
    }
}


// NAVIGATION_AUDIT_V3_LIBRARY: the reference tool exposes exactly four library tabs.
// Notes remain available inside the ĐÁNH DẤU tab instead of creating a fifth navigation level.
@Composable
private fun BookmarkAndNoteList(
    bookmarks: List<BookmarkEntity>,
    notes: List<ChapterNoteEntity>,
    onBookmarkOpen: (BookmarkEntity) -> Unit,
    onBookmarkDelete: (String) -> Unit,
    onNoteOpen: (ChapterNoteEntity) -> Unit,
    onNoteDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty() && notes.isEmpty()) {
        Text("Chưa có đánh dấu hoặc ghi chú.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (bookmarks.isNotEmpty()) {
            item(key = "bookmark-heading") {
                Text(
                    "ĐÁNH DẤU • ${bookmarks.size}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            items(bookmarks, key = { "bookmark:${it.id}" }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onBookmarkOpen(item) },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(item.label.ifBlank { "Đánh dấu đoạn ${item.paragraphIndex + 1}" }, fontWeight = FontWeight.SemiBold)
                        Text("Đoạn ${item.paragraphIndex + 1}")
                        ReferenceActionButton(
                            text = "XÓA ĐÁNH DẤU",
                            onClick = { onBookmarkDelete(item.id) },
                            accessibilityLabel = "Xóa ${item.label.ifBlank { "đánh dấu đoạn ${item.paragraphIndex + 1}" }}",
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (notes.isNotEmpty()) {
            item(key = "note-heading") {
                Text(
                    "GHI CHÚ • ${notes.size}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            items(notes, key = { "note:${it.id}" }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onNoteOpen(item) },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Ghi chú đoạn ${item.paragraphIndex + 1}", fontWeight = FontWeight.SemiBold)
                        Text(item.text)
                        ReferenceActionButton(
                            text = "XÓA GHI CHÚ",
                            onClick = { onNoteDelete(item.id) },
                            accessibilityLabel = "Xóa ghi chú đoạn ${item.paragraphIndex + 1}",
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkList(
    items: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (items.isEmpty()) {
        Text("Chưa có đánh dấu.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(item.label.ifBlank { "Đánh dấu đoạn ${item.paragraphIndex + 1}" }, fontWeight = FontWeight.SemiBold)
                    Text("Đoạn ${item.paragraphIndex + 1}")
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Button(onClick = { onOpen(item) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("MỞ") }
                        Button(onClick = { onDelete(item.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("XÓA") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteList(
    items: List<ChapterNoteEntity>,
    onOpen: (ChapterNoteEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (items.isEmpty()) {
        Text("Chưa có ghi chú.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Đoạn ${item.paragraphIndex + 1}", fontWeight = FontWeight.SemiBold)
                    Text(item.text)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Button(onClick = { onOpen(item) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("MỞ") }
                        Button(onClick = { onDelete(item.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("XÓA") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowingList(
    items: List<FollowedStoryEntity>,
    onOpen: (FollowedStoryEntity) -> Unit,
    onCheckNow: () -> Unit,
) {
    if (items.isEmpty()) {
        Text("Chưa theo dõi truyện nào.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Button(onClick = onCheckNow, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("KIỂM TRA CHƯƠNG MỚI") }
        }
        items(items, key = { it.storyId }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { onOpen(item) },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Text(item.latestKnownChapter.ifBlank { "Chạm để kiểm tra truyện" })
                    if (item.newChapterCount > 0) {
                        Text(
                            "${item.newChapterCount} chương mới chưa xem",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(item.sourceId, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DownloadedSection(
    stories: List<StoryEntity>,
    jobs: List<DownloadJobEntity>,
    failures: List<ChapterDownloadFailureEntity>,
    storage: Map<String, vn.nghetruyen.app.data.local.OfflineStoryStorage>,
    onStoryClick: (StoryEntity) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRetryFailedChapter: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveOffline: (String) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<StoryEntity?>(null) }
    pendingRemoval?.let { story ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Xóa bản ngoại tuyến?") },
            text = {
                Text(
                    if (story.sourceId == "offline") {
                        "Truyện nhập từ tệp sẽ bị xóa khỏi ứng dụng cùng toàn bộ chương và tiến độ."
                    } else {
                        "Nội dung chương đã tải của ${story.title} sẽ bị xóa; lịch sử đọc vẫn được giữ."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    onRemoveOffline(story.id)
                }) { Text("XÓA") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("HỦY") } },
        )
    }
    if (stories.isEmpty() && jobs.isEmpty() && failures.isEmpty()) {
        Text("Chưa có truyện ngoại tuyến.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(jobs, key = { "job:${it.id}" }) { job ->
            val jobFailures = failures.filter { it.jobId == job.id }
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Tải truyện • ${job.state}", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (job.selectionMode) {
                            "SINGLE" -> "Một chương"
                            "RANGE" -> "Chương ${job.startChapterIndex + 1}–${job.endChapterIndex + 1}"
                            "UNREAD" -> "Các chương chưa đọc"
                            else -> "Toàn bộ truyện"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val progress = if (job.totalChapters > 0) {
                        "${job.completedChapters}/${job.totalChapters} chương"
                    } else {
                        "Đang chuẩn bị mục lục"
                    }
                    Text(progress)
                    if (job.currentChapterTitle.isNotBlank()) Text("Đang xử lý: ${job.currentChapterTitle}")
                    if (job.retryCount > 0) Text("Đã thử lại ${job.retryCount} lần")
                    if (!job.errorMessage.isNullOrBlank()) Text(job.errorMessage)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        when (job.state) {
                            "QUEUED", "RUNNING" -> Button(
                                onClick = { onPauseDownload(job.id) },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text("TẠM DỪNG") }
                            "PAUSED" -> Button(
                                onClick = { onResumeDownload(job.id) },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text("TIẾP TỤC") }
                            "FAILED", "CANCELLED" -> Button(
                                onClick = { onRetryDownload(job.id) },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text("THỬ LẠI JOB") }
                        }
                        if (job.state !in setOf("COMPLETED", "CANCELLED")) {
                            Button(
                                onClick = { onCancelDownload(job.storyId) },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text("HỦY") }
                        }
                    }
                    jobFailures.forEach { failure ->
                        Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    failure.chapterTitle.ifBlank { "Chương ${failure.chapterIndex + 1}" },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(failure.errorMessage, style = MaterialTheme.typography.labelSmall)
                                Button(
                                    onClick = { onRetryFailedChapter(failure.id) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) { Text("THỬ LẠI RIÊNG CHƯƠNG") }
                            }
                        }
                    }
                }
            }
        }
        val orphanFailures = failures.filter { failure -> jobs.none { it.id == failure.jobId } }
        items(orphanFailures, key = { "failure:${it.id}" }) { failure ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(failure.chapterTitle.ifBlank { "Chương ${failure.chapterIndex + 1}" }, fontWeight = FontWeight.SemiBold)
                    Text(failure.errorMessage)
                    Button(
                        onClick = { onRetryFailedChapter(failure.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("THỬ LẠI RIÊNG CHƯƠNG") }
                }
            }
        }
        items(stories, key = { "story:${it.id}" }) { story ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { onStoryClick(story) },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(story.title, fontWeight = FontWeight.SemiBold)
                    if (story.author.isNotBlank()) Text(story.author)
                    val usage = storage[story.id]
                    Text(
                        if (usage == null) "Có thể đọc ngoại tuyến"
                        else "${usage.chapterCount} chương • ${formatStorageBytes(usage.bytes)}",
                    )
                    Button(
                        onClick = { pendingRemoval = story },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("XÓA BẢN NGOẠI TUYẾN") }
                }
            }
        }
    }
}

@Composable
private fun StoryEntityList(
    items: List<StoryEntity>,
    onStoryClick: (StoryEntity) -> Unit,
    emptyText: String,
) {
    if (items.isEmpty()) {
        Text(emptyText, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { story ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { onStoryClick(story) },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(story.title, fontWeight = FontWeight.SemiBold)
                    if (story.author.isNotBlank()) Text(story.author)
                    Text(if (story.isOffline) "Có thể đọc ngoại tuyến" else story.sourceId)
                }
            }
        }
    }
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
