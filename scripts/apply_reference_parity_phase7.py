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


# 1) Make downloadedAt the chapter-level offline marker everywhere.
path = "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt"
text = read(path)
text = replace_once(
    text,
    '    @Query("SELECT id FROM chapters WHERE storyId = :storyId AND content IS NOT NULL AND TRIM(content) != \'\'")\n    suspend fun listDownloadedIds(storyId: String): List<String>\n',
    '    @Query("SELECT id FROM chapters WHERE storyId = :storyId AND downloadedAt IS NOT NULL AND content IS NOT NULL AND TRIM(content) != \'\'")\n    suspend fun listDownloadedIds(storyId: String): List<String>\n\n    @Query("SELECT id FROM chapters WHERE downloadedAt IS NOT NULL AND content IS NOT NULL AND TRIM(content) != \'\'")\n    fun observeDownloadedIds(): Flow<List<String>>\n',
    "chapter downloaded ids marker",
)
text = replace_once(
    text,
    '    @Query("UPDATE chapters SET content = NULL, downloadedAt = NULL WHERE storyId IN (SELECT id FROM stories WHERE isOffline = 0)")\n    suspend fun clearTransientCache()\n',
    '    @Query("UPDATE chapters SET content = NULL, downloadedAt = NULL WHERE downloadedAt IS NULL")\n    suspend fun clearTransientCache()\n',
    "clear only transient cache",
)
old_offline_storage = '''        WHERE s.isOffline = 1 AND c.content IS NOT NULL AND TRIM(c.content) != ''
        GROUP BY c.storyId
'''
new_offline_storage = '''        WHERE c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != ''
        GROUP BY c.storyId
'''
text = replace_once(text, old_offline_storage, new_offline_storage, "offline storage by chapter marker")
old_usage = '''          COALESCE(SUM(CASE WHEN s.isOffline = 1 AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS downloadedChapters,
          COALESCE(SUM(CASE WHEN s.isOffline = 1 THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS downloadedBytes,
          COALESCE(SUM(CASE WHEN s.isOffline = 0 AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS cachedChapters,
          COALESCE(SUM(CASE WHEN s.isOffline = 0 THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS cachedBytes
'''
new_usage = '''          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS downloadedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS downloadedBytes,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS cachedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS cachedBytes
'''
text = replace_once(text, old_usage, new_usage, "storage usage chapter marker")
text = replace_once(
    text,
    "        WHERE s.isOffline = 0 AND c.content IS NOT NULL AND TRIM(c.content) != ''\n        ORDER BY COALESCE(c.downloadedAt, 0) ASC, c.chapterIndex ASC\n",
    "        WHERE c.downloadedAt IS NULL AND c.content IS NOT NULL AND TRIM(c.content) != ''\n        ORDER BY c.chapterIndex ASC\n",
    "transient cache entries marker",
)
text = replace_once(text, "    version = 20,\n", "    version = 21,\n", "database version 21")
text = replace_once(
    text,
    '''        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN totalParagraphs INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase''',
    '''        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN totalParagraphs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Older builds stamped downloadedAt for ordinary reader cache too. Reset online
                // chapters, then reconstruct the chapters that download jobs actually made local.
                db.execSQL(
                    "UPDATE chapters SET downloadedAt = NULL " +
                        "WHERE storyId IN (SELECT id FROM stories WHERE sourceId <> 'offline')",
                )
                db.execSQL(
                    """
                    UPDATE chapters
                    SET downloadedAt = (
                        SELECT MAX(j.updatedAt)
                        FROM download_jobs j
                        WHERE j.storyId = chapters.storyId
                          AND j.completedChapters > 0
                          AND chapters.chapterIndex >= j.startChapterIndex
                          AND chapters.chapterIndex <= CASE
                              WHEN j.state = 'COMPLETED' THEN j.endChapterIndex
                              ELSE j.startChapterIndex + j.completedChapters - 1
                          END
                    )
                    WHERE storyId IN (SELECT id FROM stories WHERE sourceId <> 'offline')
                      AND EXISTS (
                        SELECT 1
                        FROM download_jobs j
                        WHERE j.storyId = chapters.storyId
                          AND j.completedChapters > 0
                          AND chapters.chapterIndex >= j.startChapterIndex
                          AND chapters.chapterIndex <= CASE
                              WHEN j.state = 'COMPLETED' THEN j.endChapterIndex
                              ELSE j.startChapterIndex + j.completedChapters - 1
                          END
                      )
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): AppDatabase''',
    "migration 20 to 21 offline markers",
)
text = replace_once(
    text,
    "            MIGRATION_18_19,\n            MIGRATION_19_20,\n        ).build()",
    "            MIGRATION_18_19,\n            MIGRATION_19_20,\n            MIGRATION_20_21,\n        ).build()",
    "register migration 20 to 21",
)
write(path, text)


# 2) Repository: ordinary reader caching preserves a real download marker but never creates one.
path = "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt"
text = read(path)
text = replace_once(
    text,
    "    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>> = db.chapterDao().observeOfflineStorage()\n",
    "    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>> = db.chapterDao().observeOfflineStorage()\n    fun observeDownloadedChapterIds(): Flow<List<String>> = db.chapterDao().observeDownloadedIds()\n",
    "observe downloaded chapter ids",
)
text = replace_once(
    text,
    '''    suspend fun cacheChapter(content: ChapterContent) {
        db.chapterDao().upsert(content.toEntity())
    }

    suspend fun saveDownloadedChapter(content: ChapterContent) = cacheChapter(content)
''',
    '''    suspend fun cacheChapter(content: ChapterContent) {
        val existingDownloadedAt = db.chapterDao().get(content.chapter.id)?.downloadedAt
        db.chapterDao().upsert(content.toEntity(downloadedAt = existingDownloadedAt))
    }

    suspend fun saveDownloadedChapter(content: ChapterContent) {
        db.chapterDao().upsert(content.toEntity(downloadedAt = System.currentTimeMillis()))
    }
''',
    "separate cache from download marker",
)
text = replace_once(
    text,
    '''    suspend fun hasDownloadedChapter(chapterId: String): Boolean =
        !db.chapterDao().get(chapterId)?.content.isNullOrBlank()
''',
    '''    suspend fun hasDownloadedChapter(chapterId: String): Boolean =
        db.chapterDao().get(chapterId)?.let { it.downloadedAt != null && !it.content.isNullOrBlank() } == true
''',
    "has downloaded chapter marker",
)
text = replace_once(
    text,
    '''    private fun ChapterContent.toEntity() = ChapterEntity(
        id = chapter.id,
        storyId = chapter.storyId,
        chapterIndex = chapter.index,
        title = chapter.title,
        remoteUrl = chapter.url.ifBlank { chapter.id },
        content = paragraphs.joinToString(PARAGRAPH_SEPARATOR),
        downloadedAt = System.currentTimeMillis(),
    )
''',
    '''    private fun ChapterContent.toEntity(downloadedAt: Long? = null) = ChapterEntity(
        id = chapter.id,
        storyId = chapter.storyId,
        chapterIndex = chapter.index,
        title = chapter.title,
        remoteUrl = chapter.url.ifBlank { chapter.id },
        content = paragraphs.joinToString(PARAGRAPH_SEPARATOR),
        downloadedAt = downloadedAt,
    )
''',
    "chapter entity download marker",
)
write(path, text)


# 3) Surface current downloaded chapter IDs to Reader UI.
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
text = replace_once(
    text,
    "    val offlineStorage: Map<String, OfflineStoryStorage> = emptyMap(),\n    val storageUsage: StorageUsage = StorageUsage(0, 0, 0, 0),\n",
    "    val offlineStorage: Map<String, OfflineStoryStorage> = emptyMap(),\n    val downloadedChapterIds: Set<String> = emptySet(),\n    val storageUsage: StorageUsage = StorageUsage(0, 0, 0, 0),\n",
    "MainUiState downloaded chapter ids",
)
text = replace_once(
    text,
    '''        viewModelScope.launch {
            container.libraryRepository.observeOfflineStorage().collect { items ->
                mutableState.update { it.copy(offlineStorage = items.associateBy(OfflineStoryStorage::storyId)) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeStorageUsage().collect { usage ->
''',
    '''        viewModelScope.launch {
            container.libraryRepository.observeOfflineStorage().collect { items ->
                mutableState.update { it.copy(offlineStorage = items.associateBy(OfflineStoryStorage::storyId)) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeDownloadedChapterIds().collect { ids ->
                mutableState.update { it.copy(downloadedChapterIds = ids.toSet()) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeStorageUsage().collect { usage ->
''',
    "observe downloaded chapter ids in ViewModel",
)
write(path, text)


# 4) Reader info checks the exact current chapter, matching readFromOffline(current_reading_url).
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
text = read(path)
text = replace_once(
    text,
    '        val offline = state.offlineStorage[storyId]?.chapterCount?.let { it > 0 } == true\n',
    '        val offline = content.chapter.id in state.downloadedChapterIds\n',
    "chapter info exact offline marker",
)
write(path, text)

print("REFERENCE_PARITY_PHASE7_PATCH_OK")
