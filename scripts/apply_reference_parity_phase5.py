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


# Room: persist the denominator required by the XPK progress ratio.
path = "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt"
text = read(path)
text = replace_once(
    text,
    "data class ReadingProgressEntity(\n    @PrimaryKey val storyId: String,\n    val chapterId: String,\n    val paragraphIndex: Int,\n    val updatedAt: Long,\n)",
    "data class ReadingProgressEntity(\n    @PrimaryKey val storyId: String,\n    val chapterId: String,\n    val paragraphIndex: Int,\n    @ColumnInfo(defaultValue = \"0\") val totalParagraphs: Int = 0,\n    val updatedAt: Long,\n)",
    "ReadingProgress total paragraphs",
)
text = replace_once(
    text,
    "    @Query(\"SELECT * FROM reading_progress ORDER BY updatedAt DESC\")\n    suspend fun listAll(): List<ReadingProgressEntity>\n",
    "    @Query(\"SELECT * FROM reading_progress ORDER BY updatedAt DESC\")\n    fun observeAll(): Flow<List<ReadingProgressEntity>>\n\n    @Query(\"SELECT * FROM reading_progress ORDER BY updatedAt DESC\")\n    suspend fun listAll(): List<ReadingProgressEntity>\n",
    "ProgressDao observeAll",
)
text = replace_once(text, "    version = 19,\n", "    version = 20,\n", "database version 20")
text = replace_once(
    text,
    "        val MIGRATION_18_19 = object : Migration(18, 19) {\n            override fun migrate(db: SupportSQLiteDatabase) {\n                db.execSQL(\"ALTER TABLE voice_roles ADD COLUMN description TEXT NOT NULL DEFAULT ''\")\n            }\n        }\n\n        fun create(context: Context): AppDatabase",
    "        val MIGRATION_18_19 = object : Migration(18, 19) {\n            override fun migrate(db: SupportSQLiteDatabase) {\n                db.execSQL(\"ALTER TABLE voice_roles ADD COLUMN description TEXT NOT NULL DEFAULT ''\")\n            }\n        }\n\n        val MIGRATION_19_20 = object : Migration(19, 20) {\n            override fun migrate(db: SupportSQLiteDatabase) {\n                db.execSQL(\"ALTER TABLE reading_progress ADD COLUMN totalParagraphs INTEGER NOT NULL DEFAULT 0\")\n            }\n        }\n\n        fun create(context: Context): AppDatabase",
    "migration 19 to 20",
)
text = replace_once(
    text,
    "            MIGRATION_17_18,\n            MIGRATION_18_19,\n        ).build()",
    "            MIGRATION_17_18,\n            MIGRATION_18_19,\n            MIGRATION_19_20,\n        ).build()",
    "register migration 19 to 20",
)
write(path, text)


# Repository: expose progress to UI and preserve denominator on legacy save calls.
path = "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt"
text = read(path)
text = replace_once(
    text,
    "    fun observeReading(): Flow<List<StoryEntity>> = db.storyDao().observeReading()\n",
    "    fun observeReading(): Flow<List<StoryEntity>> = db.storyDao().observeReading()\n    fun observeReadingProgress(): Flow<List<ReadingProgressEntity>> = db.progressDao().observeAll()\n",
    "observe reading progress",
)
text = replace_once(
    text,
    "    suspend fun saveProgress(storyId: String, chapterId: String, paragraphIndex: Int) {\n        db.progressDao().save(\n            ReadingProgressEntity(\n                storyId = storyId,\n                chapterId = chapterId,\n                paragraphIndex = paragraphIndex.coerceAtLeast(0),\n                updatedAt = System.currentTimeMillis(),\n            ),\n        )\n    }",
    "    suspend fun saveProgress(storyId: String, chapterId: String, paragraphIndex: Int, totalParagraphs: Int = 0) {\n        val previous = db.progressDao().get(storyId)\n        db.progressDao().save(\n            ReadingProgressEntity(\n                storyId = storyId,\n                chapterId = chapterId,\n                paragraphIndex = paragraphIndex.coerceAtLeast(0),\n                totalParagraphs = totalParagraphs.takeIf { it > 0 } ?: previous?.totalParagraphs ?: 0,\n                updatedAt = System.currentTimeMillis(),\n            ),\n        )\n    }",
    "save real reading progress ratio",
)
write(path, text)


# ViewModel: keep live progress map and save exact total paragraph count.
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
text = replace_once(
    text,
    "import vn.nghetruyen.app.data.local.PronunciationEntity\n",
    "import vn.nghetruyen.app.data.local.PronunciationEntity\nimport vn.nghetruyen.app.data.local.ReadingProgressEntity\n",
    "ReadingProgressEntity import",
)
text = replace_once(
    text,
    "    val readingStories: List<StoryEntity> = emptyList(),\n    val downloadedStories: List<StoryEntity> = emptyList(),\n",
    "    val readingStories: List<StoryEntity> = emptyList(),\n    val readingProgress: Map<String, ReadingProgressEntity> = emptyMap(),\n    val downloadedStories: List<StoryEntity> = emptyList(),\n",
    "MainUiState reading progress map",
)
text = replace_once(
    text,
    "        viewModelScope.launch {\n            container.libraryRepository.observeOffline().collect { items ->\n",
    "        viewModelScope.launch {\n            container.libraryRepository.observeReadingProgress().collect { items ->\n                mutableState.update { it.copy(readingProgress = items.associateBy(ReadingProgressEntity::storyId)) }\n            }\n        }\n        viewModelScope.launch {\n            container.libraryRepository.observeOffline().collect { items ->\n",
    "observe reading progress in ViewModel",
)
text = replace_once(
    text,
    "                snapshot.playback.paragraphIndex,\n            )",
    "                snapshot.playback.paragraphIndex,\n                content.paragraphs.size,\n            )",
    "save reading progress total paragraphs",
)
write(path, text)


# Library reference surface: exact progress sorting and bookmarks-only shelf.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt"
text = read(path)
text = replace_once(
    text,
    "        LibrarySection.BOOKMARKS, LibrarySection.NOTES -> state.bookmarks.size + state.notes.size\n",
    "        LibrarySection.BOOKMARKS, LibrarySection.NOTES -> state.bookmarks.map(BookmarkEntity::storyId).distinct().size\n",
    "bookmark item count excludes notes",
)
text = replace_once(
    text,
    "                \"progress\" -> stories.sortedByDescending { it.updatedAt }\n                else -> stories.sortedByDescending { it.updatedAt }",
    "                \"progress\" -> stories.sortedWith(\n                    compareByDescending<StoryEntity> { story ->\n                        state.readingProgress[story.id]?.let { progress ->\n                            if (progress.totalParagraphs > 0)\n                                (progress.paragraphIndex + 1).toDouble() / progress.totalParagraphs.toDouble()\n                            else 0.0\n                        } ?: 0.0\n                    }.thenByDescending { story -> state.readingProgress[story.id]?.updatedAt ?: 0L },\n                )\n                else -> stories.sortedByDescending { it.updatedAt }",
    "real reading progress sort",
)
old_bookmarks = "    val visibleBookmarks = state.bookmarks\n        .filter { needle.isBlank() || it.label.lowercase().contains(needle) || it.storyId.lowercase().contains(needle) }\n        .let { values -> if (bookmarkSort == \"title\") values.sortedBy { it.label.lowercase() } else values.sortedByDescending { it.createdAt } }\n    val visibleNotes = state.notes\n        .filter { needle.isBlank() || it.text.lowercase().contains(needle) || it.storyId.lowercase().contains(needle) }\n        .let { values -> if (bookmarkSort == \"title\") values.sortedBy { it.text.lowercase() } else values.sortedByDescending { it.updatedAt } }\n"
new_bookmarks = "    val visibleBookmarks = state.bookmarks\n        .groupBy(BookmarkEntity::storyId)\n        .mapNotNull { (_, values) ->\n            values.sortedWith(\n                compareByDescending<BookmarkEntity> { !it.label.startsWith(\"Truyện:\") }\n                    .thenByDescending(BookmarkEntity::createdAt),\n            ).firstOrNull()\n        }\n        .filter { needle.isBlank() || it.label.lowercase().contains(needle) || it.storyId.lowercase().contains(needle) }\n        .let { values -> if (bookmarkSort == \"title\") values.sortedBy { it.label.lowercase() } else values.sortedByDescending { it.createdAt } }\n"
text = replace_once(text, old_bookmarks, new_bookmarks, "one bookmark row per story")
text = replace_once(
    text,
    "                BookmarkAndNoteList(\n                    bookmarks = visibleBookmarks,\n                    notes = visibleNotes,\n                    onBookmarkOpen = onBookmarkClick,\n                    onBookmarkDelete = onDeleteBookmark,\n                    onNoteOpen = onNoteClick,\n                    onNoteDelete = onDeleteNote,\n                )",
    "                BookmarkList(\n                    bookmarks = visibleBookmarks,\n                    onBookmarkOpen = onBookmarkClick,\n                    onBookmarkDelete = onDeleteBookmark,\n                )",
    "bookmarks-only reference shelf",
)
start = text.index("@Composable\nprivate fun BookmarkAndNoteList(")
end = text.index("@Composable\nprivate fun FollowingList(", start)
replacement = '''@Composable
private fun BookmarkList(
    bookmarks: List<BookmarkEntity>,
    onBookmarkOpen: (BookmarkEntity) -> Unit,
    onBookmarkDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Text("Chưa có truyện đã đánh dấu.", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(bookmarks, key = { "bookmark:${it.id}" }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { onBookmarkOpen(item) },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(item.label.removePrefix("Truyện: ").ifBlank { "Đánh dấu đoạn ${item.paragraphIndex + 1}" }, fontWeight = FontWeight.SemiBold)
                    if (!item.label.startsWith("Truyện:")) Text("Đoạn ${item.paragraphIndex + 1}")
                    ReferenceActionButton(
                        text = "XÓA ĐÁNH DẤU",
                        onClick = { onBookmarkDelete(item.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

'''
text = text[:start] + replacement + text[end:]
write(path, text)


# Story chapter dialogs: exact hint/blank guard and single-choice sort semantics.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
text = read(path)
text = replace_once(
    text,
    "import androidx.compose.material3.OutlinedTextField\n",
    "import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.RadioButton\n",
    "Story RadioButton import",
)
text = replace_once(
    text,
    "    var showChapterSearchDialog by remember(detail.story.id) { mutableStateOf(false) }\n    var showChapterSortDialog by remember(detail.story.id) { mutableStateOf(false) }\n",
    "    var showChapterSearchDialog by remember(detail.story.id) { mutableStateOf(false) }\n    var chapterSearchError by remember(detail.story.id) { mutableStateOf(false) }\n    var showChapterSortDialog by remember(detail.story.id) { mutableStateOf(false) }\n",
    "chapter search validation state",
)
old_search = '''            text = { OutlinedTextField(draft, { draft = it.take(120) }, placeholder = { Text("Nhập tên chương hoặc số chương") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { chapterQuery = draft; showChapterSearchDialog = false }) { Text("TÌM") } },'''
new_search = '''            text = { Column {
                OutlinedTextField(
                    draft,
                    { draft = it.take(120); chapterSearchError = false },
                    placeholder = { Text("Nhập tên, số chương hoặc vài ký tự liên quan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chapterSearchError) Text("Hãy nhập tên hoặc số chương.", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                if (draft.trim().isBlank()) chapterSearchError = true
                else { chapterQuery = draft; chapterSearchError = false; showChapterSearchDialog = false }
            }) { Text("TÌM") } },'''
text = replace_once(text, old_search, new_search, "exact chapter search behavior")
old_sort = '''            text = { Column {
                ReferenceActionButton("CŨ NHẤT TRƯỚC", { onChapterSortDescendingChange(false); showChapterSortDialog = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                ReferenceActionButton("MỚI NHẤT TRƯỚC", { onChapterSortDescendingChange(true); showChapterSortDialog = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
            } },'''
new_sort = '''            text = { Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onChapterSortDescendingChange(false); showChapterSortDialog = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !state.chapterSortDescending, onClick = { onChapterSortDescendingChange(false); showChapterSortDialog = false })
                    Text("CŨ NHẤT TRƯỚC")
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onChapterSortDescendingChange(true); showChapterSortDialog = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.chapterSortDescending, onClick = { onChapterSortDescendingChange(true); showChapterSortDialog = false })
                    Text("MỚI NHẤT TRƯỚC")
                }
            } },'''
text = replace_once(text, old_sort, new_sort, "single choice chapter sort")
write(path, text)

print("REFERENCE_PARITY_PHASE5_PATCH_OK")
