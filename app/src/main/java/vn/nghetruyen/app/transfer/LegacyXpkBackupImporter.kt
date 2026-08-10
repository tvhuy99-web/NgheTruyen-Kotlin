package vn.nghetruyen.app.transfer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.FollowedStoryEntity
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.ReadingHistoryEntity
import vn.nghetruyen.app.data.local.ReadingProgressEntity
import vn.nghetruyen.app.data.local.StoryAiProfileEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.data.local.StoryTtsProfileEntity
import vn.nghetruyen.app.data.local.VietPhraseDictionaryStateEntity
import vn.nghetruyen.app.data.local.VietPhraseEntity
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Compatibility reader for backups produced by the legacy XPK/Lua application.
 *
 * This importer is deliberately isolated from [BackupTransferManager]: current Kotlin backups keep
 * their normal restore path. Legacy archives are only read after explicit confirmation. The old
 * SQLite files are opened read-only and records are mapped into current Room entities; they are
 * never copied over the application's Room database.
 *
 * Executable legacy extensions, login/session state, API keys and opaque download payloads are not
 * executed or restored. Unknown archive entries are rejected before any database write.
 */
class LegacyXpkBackupImporter(
    context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class Inspection(
        val isLegacyXpk: Boolean,
        val preview: Preview? = null,
    )

    data class Preview(
        val formatVersion: Int,
        val databaseSchema: Int,
        val scope: String,
        val components: Set<String>,
        val hasLibraryDatabase: Boolean,
        val hasVietPhraseDatabase: Boolean,
        val hasSettings: Boolean,
        val hasExtensions: Boolean,
        val hasMusic: Boolean,
        val hasDownloadedFiles: Boolean,
    ) {
        fun confirmationMessage(): String = buildString {
            append("Đây là bản sao lưu của công cụ Nghe Truyện XPK cũ.\n\n")
            append("Format: ").append(formatVersion)
            if (databaseSchema > 0) append(" • Database schema: ").append(databaseSchema)
            append("\n")
            if (components.isNotEmpty()) {
                append("Thành phần: ").append(components.sorted().joinToString(", ")).append("\n")
            }
            append("\nỨng dụng sẽ chuyển dữ liệu tương thích và GỘP vào dữ liệu hiện tại. ")
            append("Database cũ không được chép đè lên database Kotlin.")
            if (hasExtensions || hasDownloadedFiles || hasMusic) {
                append("\n\nLưu ý: mã extension cũ, session/API key và payload tải xuống cũ không được tự chạy hoặc chép thẳng vì lý do an toàn.")
            } else {
                append("\n\nAPI key, cookie và session đăng nhập không được nhập.")
            }
        }
    }

    data class RestoreSummary(
        val stories: Int = 0,
        val chapters: Int = 0,
        val progress: Int = 0,
        val readingHistory: Int = 0,
        val bookmarks: Int = 0,
        val following: Int = 0,
        val pronunciations: Int = 0,
        val storyTtsProfiles: Int = 0,
        val storyAiProfiles: Int = 0,
        val vietPhraseRules: Int = 0,
        val settingsRestored: Boolean = false,
        val skippedLegacyFiles: Int = 0,
        val components: Set<BackupComponent> = emptySet(),
    ) {
        fun userMessage(): String = buildString {
            append("Đã chuyển đổi bản sao lưu XPK: ")
            append(stories).append(" truyện, ")
            append(chapters).append(" chương, ")
            append(progress).append(" tiến độ, ")
            append(readingHistory).append(" lịch sử, ")
            append(bookmarks).append(" đánh dấu")
            if (vietPhraseRules > 0) append(", ").append(vietPhraseRules).append(" mục VietPhrase")
            append(".")
            if (skippedLegacyFiles > 0) {
                append(" Đã bỏ qua ").append(skippedLegacyFiles)
                    .append(" tệp legacy không thể nhập an toàn trực tiếp.")
            }
        }
    }

    suspend fun inspect(source: Uri): AppResult<Inspection> = withContext(Dispatchers.IO) {
        try {
            val input = resolver.openInputStream(source)
                ?: return@withContext AppResult.Failure("RESTORE_OPEN_FAILED", "Không mở được tệp sao lưu.")
            val scan = input.use(::scanArchive)
            val manifest = scan.manifest
                ?: return@withContext AppResult.Success(Inspection(isLegacyXpk = false))

            // Native Kotlin backups continue through BackupTransferManager unchanged.
            if (manifest.optString("format") == CURRENT_FORMAT_NAME) {
                return@withContext AppResult.Success(Inspection(isLegacyXpk = false))
            }

            val version = manifest.optInt("format_version", -1)
            val app = manifest.optString("app")
            val legacyShape = scan.entries.contains(LEGACY_DB_ENTRY) ||
                scan.entries.contains(LEGACY_VP_DB_ENTRY) ||
                scan.entries.contains("settings.json") ||
                scan.entries.contains("library_preferences.json")
            if (
                version !in 1..MAX_LEGACY_FORMAT_VERSION ||
                !legacyShape ||
                (app.isNotBlank() &&
                    !app.contains("NgheTruyen", ignoreCase = true) &&
                    !app.contains("Nghe Truyen", ignoreCase = true))
            ) {
                return@withContext AppResult.Success(Inspection(isLegacyXpk = false))
            }

            val components = manifest.optJSONArray("components").toStringSet().ifEmpty {
                buildSet {
                    if (scan.entries.contains("settings.json")) add("settings")
                    if (scan.entries.contains(LEGACY_DB_ENTRY)) add("library")
                    if (scan.entries.contains(LEGACY_VP_DB_ENTRY)) add("vietphrase")
                    if (scan.entries.any { it.startsWith("extensions/") || it.startsWith("extension_packages/") }) add("extensions")
                    if (scan.entries.any { it.startsWith("background_music/") }) add("music")
                }
            }
            AppResult.Success(
                Inspection(
                    isLegacyXpk = true,
                    preview = Preview(
                        formatVersion = version,
                        databaseSchema = manifest.optInt("database_schema", 0),
                        scope = manifest.optString("scope", "all").ifBlank { "all" },
                        components = components,
                        hasLibraryDatabase = LEGACY_DB_ENTRY in scan.entries,
                        hasVietPhraseDatabase = LEGACY_VP_DB_ENTRY in scan.entries,
                        hasSettings = "settings.json" in scan.entries,
                        hasExtensions = scan.entries.any { it.startsWith("extensions/") || it.startsWith("extension_packages/") },
                        hasMusic = scan.entries.any { it.startsWith("background_music/") },
                        hasDownloadedFiles = scan.entries.any { it.startsWith("downloads/") },
                    ),
                ),
            )
        } catch (error: Exception) {
            AppResult.Failure(
                "LEGACY_XPK_INSPECT_FAILED",
                error.message ?: "Không kiểm tra được bản sao lưu XPK.",
                error,
            )
        }
    }

    suspend fun restoreFrom(
        source: Uri,
        requestedComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<RestoreSummary> = withContext(Dispatchers.IO) {
        val requested = requestedComponents.ifEmpty { BackupComponent.entries.toSet() }
        val stageRoot = File(appContext.cacheDir, "legacy_xpk_restore_${System.nanoTime()}")
        try {
            if (!stageRoot.mkdirs()) throw IOException("Không tạo được vùng tạm để chuyển đổi XPK.")
            val input = resolver.openInputStream(source)
                ?: return@withContext AppResult.Failure("RESTORE_OPEN_FAILED", "Không mở được tệp sao lưu.")
            val extraction = input.use { extractLegacyArchive(it, stageRoot, requested) }
            val manifest = extraction.manifest
            val version = manifest.optInt("format_version", -1)
            val schema = manifest.optInt("database_schema", 0)
            require(version in 1..MAX_LEGACY_FORMAT_VERSION) {
                "Phiên bản sao lưu XPK không được hỗ trợ: $version."
            }
            require(schema <= MAX_LEGACY_DATABASE_SCHEMA || schema <= 0) {
                "Database XPK schema $schema mới hơn bộ chuyển đổi hiện tại ($MAX_LEGACY_DATABASE_SCHEMA)."
            }

            var summary = RestoreSummary(components = requested)
            val legacyDb = File(stageRoot, LEGACY_DB_ENTRY)
            if (legacyDb.isFile && requested.any { it in LEGACY_DATABASE_COMPONENTS }) {
                summary = mergeLegacyDatabase(legacyDb, requested, summary)
            }
            val legacyVpDb = File(stageRoot, LEGACY_VP_DB_ENTRY)
            if (legacyVpDb.isFile && BackupComponent.VIETPHRASE in requested) {
                summary = mergeLegacyVietPhrase(legacyVpDb, summary)
            }
            val settingsFile = File(stageRoot, "settings.json")
            if (settingsFile.isFile && BackupComponent.SETTINGS in requested) {
                restoreSafeSettings(settingsFile)
                summary = summary.copy(settingsRestored = true)
            }
            AppResult.Success(summary.copy(skippedLegacyFiles = extraction.skippedLegacyFiles))
        } catch (error: Exception) {
            AppResult.Failure(
                "LEGACY_XPK_RESTORE_FAILED",
                error.message ?: "Không chuyển đổi được bản sao lưu XPK.",
                error,
            )
        } finally {
            stageRoot.deleteRecursively()
        }
    }

    private data class ArchiveScan(val manifest: JSONObject?, val entries: Set<String>)

    private fun scanArchive(input: InputStream): ArchiveScan {
        var manifest: JSONObject? = null
        var count = 0
        var total = 0L
        val names = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRY_COUNT) throw IOException("Bản sao lưu có quá nhiều mục.")
                val name = entry.name
                validateEntryName(name)
                if (!names.add(name)) throw IOException("Bản sao lưu có mục trùng lặp: $name")
                if (!entry.isDirectory) {
                    if (name == "manifest.json") {
                        val bytes = zip.readBounded(MAX_MANIFEST_BYTES)
                        total += bytes.size
                        manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                    } else {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_INSPECTION_BYTES) {
                                throw IOException("Tệp quá lớn để kiểm tra an toàn.")
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return ArchiveScan(manifest, names)
    }

    private data class Extraction(val manifest: JSONObject, val skippedLegacyFiles: Int)

    private fun extractLegacyArchive(
        input: InputStream,
        stageRoot: File,
        requested: Set<BackupComponent>,
    ): Extraction {
        var manifest: JSONObject? = null
        var count = 0
        var total = 0L
        var skipped = 0
        val seen = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRY_COUNT) throw IOException("Bản sao lưu có quá nhiều mục.")
                val name = entry.name
                validateEntryName(name)
                if (!seen.add(name)) throw IOException("Bản sao lưu có mục trùng lặp: $name")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (!isRecognizedLegacyEntry(name)) {
                    throw IOException("Bản sao lưu XPK chứa mục không được hỗ trợ: $name")
                }

                val shouldExtract = when {
                    name == "manifest.json" -> true
                    name == LEGACY_DB_ENTRY -> requested.any { it in LEGACY_DATABASE_COMPONENTS }
                    name == LEGACY_VP_DB_ENTRY -> BackupComponent.VIETPHRASE in requested
                    name == "settings.json" -> BackupComponent.SETTINGS in requested
                    else -> false
                }
                val limit = entryLimit(name)
                if (shouldExtract) {
                    val target = File(stageRoot, name).canonicalFile
                    require(target.path.startsWith(stageRoot.canonicalPath + File.separator)) {
                        "Đường dẫn tệp XPK không an toàn."
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        copyEntryBounded(zip, output, name, limit) { bytes ->
                            total += bytes
                            if (total > MAX_TOTAL_EXTRACT_BYTES) {
                                throw IOException("Tổng dữ liệu XPK vượt giới hạn an toàn.")
                            }
                        }
                    }
                    if (name == "manifest.json") {
                        manifest = JSONObject(target.readText(Charsets.UTF_8))
                    }
                } else {
                    skipped += 1
                    drainEntryBounded(zip, name, limit) { bytes ->
                        total += bytes
                        if (total > MAX_TOTAL_EXTRACT_BYTES) {
                            throw IOException("Tổng dữ liệu XPK vượt giới hạn an toàn.")
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return Extraction(
            manifest = manifest ?: throw IOException("Bản sao lưu XPK thiếu manifest.json."),
            skippedLegacyFiles = skipped,
        )
    }

    private suspend fun mergeLegacyDatabase(
        file: File,
        requested: Set<BackupComponent>,
        initial: RestoreSummary,
    ): RestoreSummary {
        validateSqlite(file)
        var summary = initial
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            database.withTransaction {
                if (BackupComponent.LIBRARY in requested) {
                    summary = importStories(legacy, summary)
                    summary = importChapters(legacy, summary)
                    summary = importFollowing(legacy, summary)
                }
                if (BackupComponent.READING in requested) {
                    // These are link targets. Ensure their metadata exists even when only READING is selected.
                    if (BackupComponent.LIBRARY !in requested) {
                        summary = importStories(legacy, summary)
                        summary = importChapters(legacy, summary)
                    }
                    summary = importProgress(legacy, summary)
                    summary = importReadingHistory(legacy, summary)
                    summary = importBookmarks(legacy, summary)
                    summary = importPronunciations(legacy, summary)
                }
                if (BackupComponent.AI_VOICE in requested) {
                    if (BackupComponent.LIBRARY !in requested && BackupComponent.READING !in requested) {
                        summary = importStories(legacy, summary)
                    }
                    summary = importStoryTtsProfiles(legacy, summary)
                    summary = importStoryAiProfiles(legacy, summary)
                }
            }
        } finally {
            legacy.close()
        }
        return summary
    }

    private suspend fun importStories(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("stories")) return summary
        var count = 0
        val batch = ArrayList<StoryEntity>(BATCH_SIZE)
        db.rawQuery("SELECT source,story_url,title,last_read_at FROM stories", null).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.string("source")
                val url = cursor.string("story_url")
                if (url.isBlank()) continue
                batch += StoryEntity(
                    id = stableStoryId(url),
                    sourceId = normalizeSourceId(source),
                    title = cursor.string("title").ifBlank { "Truyện" },
                    author = "",
                    description = "",
                    coverUrl = null,
                    remoteUrl = url,
                    isOffline = false,
                    updatedAt = cursor.long("last_read_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.storyDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.storyDao().upsertAll(batch)
        return summary.copy(stories = maxOf(summary.stories, count))
    }

    private suspend fun importChapters(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("chapters")) return summary
        var count = 0
        val stories = ArrayList<StoryEntity>(BATCH_SIZE)
        val chapters = ArrayList<ChapterEntity>(BATCH_SIZE)
        db.rawQuery(
            "SELECT url,story_url,source,story_title,title,order_index,downloaded_at FROM chapters",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val url = cursor.string("url")
                val storyUrl = cursor.string("story_url").ifBlank { url.substringBeforeLast('/', url) }
                if (url.isBlank() || storyUrl.isBlank()) continue
                val source = cursor.string("source")
                val storyId = stableStoryId(storyUrl)
                stories += StoryEntity(
                    id = storyId,
                    sourceId = normalizeSourceId(source),
                    title = cursor.string("story_title").ifBlank { "Truyện" },
                    author = "",
                    description = "",
                    coverUrl = null,
                    remoteUrl = storyUrl,
                    isOffline = false,
                    updatedAt = cursor.long("downloaded_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                chapters += ChapterEntity(
                    id = stableChapterId(url),
                    storyId = storyId,
                    chapterIndex = (cursor.int("order_index") - 1).coerceAtLeast(0),
                    title = cursor.string("title").ifBlank { "Chương truyện" },
                    remoteUrl = url,
                    content = null,
                    downloadedAt = null,
                )
                count += 1
                if (chapters.size >= BATCH_SIZE) {
                    database.storyDao().upsertAll(stories.toList())
                    database.chapterDao().upsertAll(chapters.toList())
                    stories.clear()
                    chapters.clear()
                }
            }
        }
        if (chapters.isNotEmpty()) {
            database.storyDao().upsertAll(stories)
            database.chapterDao().upsertAll(chapters)
        }
        return summary.copy(chapters = maxOf(summary.chapters, count))
    }

    private suspend fun importProgress(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("stories")) return summary
        var count = 0
        val batch = ArrayList<ReadingProgressEntity>(BATCH_SIZE)
        db.rawQuery(
            "SELECT source,story_url,current_url,para,total_para,last_read_at FROM stories WHERE current_url<>''",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                val chapterUrl = cursor.string("current_url")
                if (storyUrl.isBlank() || chapterUrl.isBlank()) continue
                batch += ReadingProgressEntity(
                    storyId = stableStoryId(storyUrl),
                    chapterId = stableChapterId(chapterUrl),
                    paragraphIndex = legacyParagraphIndex(cursor.int("para")),
                    totalParagraphs = cursor.int("total_para").coerceAtLeast(0),
                    updatedAt = cursor.long("last_read_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.progressDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.progressDao().upsertAll(batch)
        return summary.copy(progress = count)
    }

    private suspend fun importReadingHistory(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("reading_history")) return summary
        var count = 0
        db.rawQuery(
            "SELECT source,story_url,chapter_url,title,para,read_at FROM reading_history ORDER BY read_at DESC LIMIT 500",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                val chapterUrl = cursor.string("chapter_url")
                if (storyUrl.isBlank() || chapterUrl.isBlank()) continue
                val source = cursor.string("source")
                val storyId = stableStoryId(storyUrl)
                val chapterId = stableChapterId(chapterUrl)
                database.readingHistoryDao().upsert(
                    ReadingHistoryEntity(
                        id = stableId("history", "$storyId\u0000$chapterId"),
                        storyId = storyId,
                        sourceId = normalizeSourceId(source),
                        storyTitle = legacyStoryTitle(db, source, storyUrl),
                        chapterId = chapterId,
                        chapterTitle = cursor.string("title").ifBlank { legacyChapterTitle(db, chapterUrl) },
                        paragraphIndex = legacyParagraphIndex(cursor.int("para")),
                        totalParagraphs = 0,
                        visitedAt = cursor.long("read_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                )
                count += 1
            }
        }
        database.readingHistoryDao().prune(500)
        return summary.copy(readingHistory = count)
    }

    private suspend fun importBookmarks(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("bookmarks")) return summary
        var count = 0
        val batch = ArrayList<BookmarkEntity>(BATCH_SIZE)
        db.rawQuery("SELECT source,story_url,chapter_url,title,para,created_at FROM bookmarks", null).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                val chapterUrl = cursor.string("chapter_url")
                if (storyUrl.isBlank() || chapterUrl.isBlank()) continue
                val paragraph = legacyParagraphIndex(cursor.int("para"))
                val storyId = stableStoryId(storyUrl)
                val chapterId = stableChapterId(chapterUrl)
                batch += BookmarkEntity(
                    id = stableId("bookmark", "$storyId\u0000$chapterId\u0000$paragraph"),
                    storyId = storyId,
                    chapterId = chapterId,
                    paragraphIndex = paragraph,
                    label = cursor.string("title").ifBlank { "Đánh dấu từ XPK" },
                    createdAt = cursor.long("created_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.bookmarkDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.bookmarkDao().upsertAll(batch)
        return summary.copy(bookmarks = count)
    }

    private suspend fun importFollowing(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("followed_stories")) return summary
        var count = 0
        val batch = ArrayList<FollowedStoryEntity>(BATCH_SIZE)
        db.rawQuery(
            "SELECT source,story_url,title,last_chapter_title,last_chapter_number,new_chapter_count,last_checked_at,updated_at FROM followed_stories",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.string("source")
                val url = cursor.string("story_url")
                if (url.isBlank()) continue
                batch += FollowedStoryEntity(
                    storyId = stableStoryId(url),
                    sourceId = normalizeSourceId(source),
                    remoteUrl = url,
                    title = cursor.string("title").ifBlank { "Truyện" },
                    latestKnownChapter = cursor.string("last_chapter_title"),
                    latestKnownChapterIndex = (cursor.int("last_chapter_number") - 1).coerceAtLeast(-1),
                    newChapterCount = cursor.int("new_chapter_count").coerceAtLeast(0),
                    checkedAt = cursor.long("last_checked_at").takeIf { it > 0 }
                        ?: cursor.long("updated_at").takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.followingDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.followingDao().upsertAll(batch)
        return summary.copy(following = count)
    }

    private suspend fun importPronunciations(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("tts_pronunciations")) return summary
        var count = 0
        val batch = ArrayList<PronunciationEntity>(BATCH_SIZE)
        db.rawQuery(
            "SELECT original,replacement,enabled,created_at,updated_at FROM tts_pronunciations",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val original = cursor.string("original").trim()
                val replacement = cursor.string("replacement").trim()
                if (original.isBlank() || replacement.isBlank()) continue
                val now = System.currentTimeMillis()
                batch += PronunciationEntity(
                    original = original,
                    replacement = replacement,
                    enabled = cursor.int("enabled", 1) != 0,
                    createdAt = cursor.long("created_at").takeIf { it > 0 } ?: now,
                    updatedAt = cursor.long("updated_at").takeIf { it > 0 } ?: now,
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.pronunciationDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.pronunciationDao().upsertAll(batch)
        return summary.copy(pronunciations = count)
    }

    private suspend fun importStoryTtsProfiles(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("story_tts_profiles")) return summary
        var count = 0
        val batch = ArrayList<StoryTtsProfileEntity>(BATCH_SIZE)
        db.rawQuery(
            "SELECT source,story_url,engine,language,voice,speed,pitch,volume,enabled,updated_at FROM story_tts_profiles",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.int("enabled", 1) == 0) continue
                val storyUrl = cursor.string("story_url")
                if (storyUrl.isBlank()) continue
                batch += StoryTtsProfileEntity(
                    storyId = stableStoryId(storyUrl),
                    rate = cursor.float("speed", 1f),
                    pitch = cursor.float("pitch", 1f),
                    volume = cursor.float("volume", 1f),
                    enginePackage = cursor.string("engine").ifBlank { null },
                    voiceName = cursor.string("voice").ifBlank { null },
                    languageTag = cursor.string("language").ifBlank { "vi-VN" },
                    updatedAt = cursor.long("updated_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.storyTtsProfileDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.storyTtsProfileDao().upsertAll(batch)
        return summary.copy(storyTtsProfiles = count)
    }

    private suspend fun importStoryAiProfiles(db: SQLiteDatabase, summary: RestoreSummary): RestoreSummary {
        if (!db.hasTable("story_ai_profiles")) return summary
        var count = 0
        val batch = ArrayList<StoryAiProfileEntity>(BATCH_SIZE)
        db.rawQuery(
            "SELECT source,story_url,mode,use_custom_prompt,translate_prompt,improve_prompt,updated_at FROM story_ai_profiles",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                if (storyUrl.isBlank()) continue
                val mode = cursor.string("mode").uppercase(Locale.ROOT)
                    .takeIf { it in setOf("INHERIT", "TRANSLATE", "IMPROVE") }
                    ?: "INHERIT"
                batch += StoryAiProfileEntity(
                    storyId = stableStoryId(storyUrl),
                    mode = mode,
                    useCustomPrompts = cursor.int("use_custom_prompt") != 0,
                    translationPrompt = cursor.string("translate_prompt").take(16_000),
                    improvePrompt = cursor.string("improve_prompt").take(16_000),
                    updatedAt = cursor.long("updated_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                count += 1
                if (batch.size >= BATCH_SIZE) {
                    database.storyAiProfileDao().upsertAll(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) database.storyAiProfileDao().upsertAll(batch)
        return summary.copy(storyAiProfiles = count)
    }

    private suspend fun mergeLegacyVietPhrase(file: File, initial: RestoreSummary): RestoreSummary {
        validateSqlite(file)
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        var count = 0
        try {
            if (!legacy.hasTable("vp_entries")) return initial
            val meta = legacyVietPhraseMeta(legacy)
            database.withTransaction {
                val batch = ArrayList<VietPhraseEntity>(BATCH_SIZE)
                legacy.rawQuery("SELECT kind,term,replacement FROM vp_entries", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val source = cursor.string("term").trim()
                        val target = cursor.string("replacement").trim()
                        if (source.isBlank() || target.isBlank()) continue
                        val legacyKind = cursor.string("kind").lowercase(Locale.ROOT)
                        val mappedKind = mapVietPhraseKind(legacyKind) ?: continue
                        val now = meta[legacyKind]?.importedAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
                        batch += VietPhraseEntity(
                            source = source,
                            target = target,
                            priority = legacyVietPhrasePriority(legacyKind),
                            enabled = true,
                            kind = mappedKind,
                            scope = "GLOBAL",
                            storyId = "",
                            matchMode = "LITERAL",
                            ignoreCase = false,
                            createdAt = now,
                            updatedAt = now,
                        )
                        count += 1
                        if (batch.size >= BATCH_SIZE) {
                            database.vietPhraseDao().upsertAll(batch.toList())
                            batch.clear()
                        }
                    }
                }
                if (batch.isNotEmpty()) database.vietPhraseDao().upsertAll(batch)
                val states = meta.mapNotNull { (legacyKind, item) ->
                    val mapped = mapVietPhraseKind(legacyKind) ?: return@mapNotNull null
                    VietPhraseDictionaryStateEntity(
                        id = stableId("legacy-vp", mapped),
                        kind = mapped,
                        scope = "GLOBAL",
                        storyId = "",
                        enabled = true,
                        sourceName = item.fileName.ifBlank { "XPK $legacyKind" },
                        sourceFormat = "LEGACY_XPK_SQLITE",
                        checksum = "",
                        entryCount = item.entryCount,
                        revision = item.importedAt,
                        importedAt = item.importedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                }
                if (states.isNotEmpty()) database.vietPhraseDictionaryStateDao().upsertAll(states)
            }
        } finally {
            legacy.close()
        }
        return initial.copy(vietPhraseRules = count)
    }

    private data class LegacyVpMeta(
        val fileName: String,
        val entryCount: Int,
        val importedAt: Long,
    )

    private fun legacyVietPhraseMeta(db: SQLiteDatabase): Map<String, LegacyVpMeta> {
        if (!db.hasTable("vp_dictionary_meta")) return emptyMap()
        val result = linkedMapOf<String, LegacyVpMeta>()
        db.rawQuery("SELECT kind,file_name,entry_count,imported_at FROM vp_dictionary_meta", null).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = cursor.string("kind").lowercase(Locale.ROOT)
                result[kind] = LegacyVpMeta(
                    fileName = cursor.string("file_name"),
                    entryCount = cursor.int("entry_count"),
                    importedAt = cursor.long("imported_at"),
                )
            }
        }
        return result
    }

    private suspend fun restoreSafeSettings(file: File) {
        require(file.length() <= MAX_SETTINGS_BYTES) { "settings.json của XPK vượt giới hạn." }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val current = settingsRepository.snapshot()
        val provider = when (json.optString("ai_provider").lowercase(Locale.ROOT)) {
            "gemini" -> AiProvider.GEMINI
            else -> AiProvider.OPENAI_COMPATIBLE
        }
        val endpoint = json.optString("ai_proxy_url").trim().take(500)
        val geminiModel = json.optString("ai_gemini_model").trim().take(200)
        val openAiModel = json.optString("ai_proxy_model").trim().take(200)
        val mode = if (json.optString("ai_default_mode").equals("improve", ignoreCase = true)) {
            "improve"
        } else {
            "translate"
        }
        val ai = current.aiOnline.copy(
            provider = provider,
            enabled = json.optBoolean("ai_enabled", current.aiOnline.enabled),
            consentGranted = json.optBoolean("ai_enabled", current.aiOnline.enabled),
            endpoint = endpoint.ifBlank { current.aiOnline.endpoint },
            model = if (provider == AiProvider.GEMINI) {
                geminiModel.ifBlank { current.aiOnline.geminiModel }
            } else {
                openAiModel
            },
            geminiModel = geminiModel.ifBlank { current.aiOnline.geminiModel },
            openAiModel = openAiModel,
            mode = mode,
            translationPrompt = json.optString("ai_prompt_translate")
                .takeIf(String::isNotBlank)
                ?.take(16_000)
                ?: current.aiOnline.translationPrompt,
            improvePrompt = json.optString("ai_prompt_improve")
                .takeIf(String::isNotBlank)
                ?.take(16_000)
                ?: current.aiOnline.improvePrompt,
            timeoutMillis = json.optInt("ai_timeout_ms", current.aiOnline.timeoutMillis).coerceAtLeast(10_000),
            temperature = json.optDouble("ai_temperature", current.aiOnline.temperature.toDouble())
                .toFloat()
                .coerceIn(0f, 2f),
        )
        val readerMode = if (json.optString("reader_mode").equals("tts", ignoreCase = true)) {
            ReaderMode.TTS
        } else {
            ReaderMode.TEXT
        }
        val dark = json.optBoolean(
            "reader_dark_mode",
            current.readerDisplay.theme == ReaderThemeMode.DARK,
        )
        val lineHeight = (
            json.optDouble("reader_line_spacing", current.readerDisplay.lineHeightPercent / 100.0) * 100.0
            ).toInt()
        val processing = json.optString("tts_processing_method")
        val interruption = if (json.optBoolean("continue_reading_during_interruptions", false)) {
            AudioInterruptionMode.CONTINUE_DUCKED
        } else {
            AudioInterruptionMode.PAUSE
        }
        settingsRepository.restore(
            current.copy(
                ttsRate = json.optDouble("tts_speed", current.ttsRate.toDouble()).toFloat(),
                ttsPitch = json.optDouble("tts_pitch", current.ttsPitch.toDouble()).toFloat(),
                ttsVolume = json.optDouble("tts_volume", current.ttsVolume.toDouble()).toFloat(),
                ttsEnginePackage = json.optString("tts_engine").takeIf(String::isNotBlank)
                    ?: current.ttsEnginePackage,
                ttsVoiceName = json.optString("tts_voice").takeIf(String::isNotBlank)
                    ?: current.ttsVoiceName,
                ttsLanguageTag = json.optString("tts_language").ifBlank { current.ttsLanguageTag },
                audioInterruptionMode = interruption,
                readerMode = readerMode,
                readerDisplay = current.readerDisplay.copy(
                    theme = if (dark) ReaderThemeMode.DARK else current.readerDisplay.theme,
                    fontSizeSp = json.optInt("font_size", current.readerDisplay.fontSizeSp),
                    lineHeightPercent = lineHeight,
                    keepScreenOn = json.optBoolean("reader_keep_screen_on", current.readerDisplay.keepScreenOn),
                ),
                sonicProcessingEnabled = processing.equals("sonic", ignoreCase = true) || current.sonicProcessingEnabled,
                sonicAccurateMode = json.optInt(
                    "tts_sonic_quality",
                    if (current.sonicAccurateMode) 1 else 0,
                ) > 0,
                autoVoiceCastEnabled = json.optBoolean("ai_voice_cast_enabled", current.autoVoiceCastEnabled),
                aiOnline = ai,
            ),
        )
        // Deliberately ignore ai_gemini_key / ai_proxy_key. Credentials stay device-local.
    }

    private fun validateSqlite(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Database trong bản sao lưu XPK không toàn vẹn."
                }
            }
        } finally {
            db.close()
        }
    }

    private fun legacyStoryTitle(db: SQLiteDatabase, source: String, storyUrl: String): String {
        db.rawQuery(
            "SELECT title FROM stories WHERE source=? AND story_url=? LIMIT 1",
            arrayOf(source, storyUrl),
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.string("title").ifBlank { "Truyện" }
        }
        return "Truyện"
    }

    private fun legacyChapterTitle(db: SQLiteDatabase, chapterUrl: String): String {
        db.rawQuery("SELECT title FROM chapters WHERE url=? LIMIT 1", arrayOf(chapterUrl)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.string("title").ifBlank { "Chương truyện" }
        }
        return "Chương truyện"
    }

    private fun normalizeSourceId(value: String): String = value.trim()
        .lowercase(Locale.ROOT)
        .replace("truyen_full", "truyenfull")
        .replace("truyen_cv", "truyencv")
        .ifBlank { "legacy" }

    /** Matches the stable URL IDs used by the Kotlin source adapters where possible. */
    private fun stableStoryId(url: String): String = stableUrlId("story", url)

    private fun stableChapterId(url: String): String = stableUrlId("chapter", url)

    private fun stableUrlId(prefix: String, rawUrl: String): String {
        val value = runCatching {
            val uri = java.net.URI(rawUrl.trim())
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            if (host.isBlank()) rawUrl.trim().trimEnd('/') else "$host$path"
        }.getOrElse { rawUrl.trim().trimEnd('/') }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$prefix:$digest"
    }

    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$prefix:$digest"
    }

    private fun legacyParagraphIndex(value: Int): Int = (value - 1).coerceAtLeast(0)

    private fun mapVietPhraseKind(value: String): String? = when (value.lowercase(Locale.ROOT)) {
        "luatnhan" -> "LUAT_NHAN"
        "pronouns" -> "PRONOUNS"
        "phienam" -> "PHIEN_AM"
        "lacviet" -> "LAC_VIET"
        "vietphrase" -> "VIET_PHRASE"
        "names" -> "NAMES"
        "aireplace" -> "AI_REPLACE"
        else -> null
    }

    private fun legacyVietPhrasePriority(value: String): Int = when (value.lowercase(Locale.ROOT)) {
        "luatnhan" -> 50
        "pronouns" -> 45
        "phienam" -> 10
        "lacviet" -> 20
        "vietphrase" -> 30
        "names" -> 40
        "aireplace" -> 70
        else -> 0
    }

    private fun SQLiteDatabase.hasTable(name: String): Boolean {
        rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun Cursor.string(column: String): String {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) "" else getString(index).orEmpty()
    }

    private fun Cursor.int(column: String, fallback: Int = 0): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getInt(index)
    }

    private fun Cursor.long(column: String, fallback: Long = 0L): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getLong(index)
    }

    private fun Cursor.float(column: String, fallback: Float = 0f): Float {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getFloat(index)
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun validateEntryName(name: String) {
        if (
            name.isBlank() ||
            name.length > 1024 ||
            name.contains("..") ||
            name.contains('\\') ||
            name.contains('\u0000') ||
            name.startsWith('/')
        ) {
            throw IOException("Tên mục sao lưu không an toàn: $name")
        }
    }

    private fun isRecognizedLegacyEntry(name: String): Boolean = when {
        name in LEGACY_FIXED_ENTRIES -> true
        name.startsWith("downloads/") && safeLeaf(name.substringAfter("downloads/")) -> true
        name.startsWith("extensions/") && safeLeaf(name.substringAfter("extensions/")) -> true
        name.startsWith("extension_packages/") && safeLeaf(name.substringAfter("extension_packages/")) -> true
        name.startsWith("background_music/files/") && safeLeaf(name.substringAfter("background_music/files/")) -> true
        else -> false
    }

    private fun safeLeaf(value: String): Boolean = value.isNotBlank() &&
        !value.contains('/') &&
        !value.contains('\\') &&
        value != "." &&
        value != ".." &&
        value.all { it.isLetterOrDigit() || it in "._-" }

    private fun entryLimit(name: String): Long = when (name) {
        LEGACY_DB_ENTRY -> 256L * 1024L * 1024L
        LEGACY_VP_DB_ENTRY -> 1024L * 1024L * 1024L
        "manifest.json", "settings.json", "background_music/manifest.json" -> 1024L * 1024L
        "preferences.json", "library_preferences.json", "vietphrase_preferences.json", "extension_preferences.json" -> 16L * 1024L * 1024L
        "ai/vietphrase_suggestions.jsonl" -> 32L * 1024L * 1024L
        else -> when {
            name.startsWith("background_music/files/") -> 512L * 1024L * 1024L
            name.startsWith("extension_packages/") -> 128L * 1024L * 1024L
            name.startsWith("downloads/") -> 128L * 1024L * 1024L
            name.startsWith("extensions/") -> 32L * 1024L * 1024L
            else -> 1024L * 1024L
        }
    }

    private fun copyEntryBounded(
        input: InputStream,
        output: FileOutputStream,
        name: String,
        maxBytes: Long,
        onBytes: (Long) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var itemBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            itemBytes += read
            if (itemBytes > maxBytes) throw IOException("Mục sao lưu vượt giới hạn: $name")
            onBytes(read.toLong())
            output.write(buffer, 0, read)
        }
    }

    private fun drainEntryBounded(
        input: InputStream,
        name: String,
        maxBytes: Long,
        onBytes: (Long) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var itemBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            itemBytes += read
            if (itemBytes > maxBytes) throw IOException("Mục sao lưu vượt giới hạn: $name")
            onBytes(read.toLong())
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (output.size() + read > maxBytes) throw IOException("Mục sao lưu vượt giới hạn an toàn.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val CURRENT_FORMAT_NAME = "vn.nghetruyen.backup"
        private const val MAX_LEGACY_FORMAT_VERSION = 7
        private const val MAX_LEGACY_DATABASE_SCHEMA = 11
        private const val LEGACY_DB_ENTRY = "database/accessible_reader.db"
        private const val LEGACY_VP_DB_ENTRY = "database/vietphrase_dictionary.db"
        private const val MAX_ENTRY_COUNT = 10_000
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_SETTINGS_BYTES = 1024L * 1024L
        private const val MAX_INSPECTION_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_TOTAL_EXTRACT_BYTES = 2L * 1024L * 1024L * 1024L
        private const val BATCH_SIZE = 250

        private val LEGACY_DATABASE_COMPONENTS = setOf(
            BackupComponent.LIBRARY,
            BackupComponent.READING,
            BackupComponent.AI_VOICE,
        )

        private val LEGACY_FIXED_ENTRIES = setOf(
            "manifest.json",
            "settings.json",
            "preferences.json",
            "library_preferences.json",
            "vietphrase_preferences.json",
            "extension_preferences.json",
            LEGACY_DB_ENTRY,
            LEGACY_VP_DB_ENTRY,
            "background_music/manifest.json",
            "ai/vietphrase_suggestions.jsonl",
        )
    }
}
