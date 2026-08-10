package vn.nghetruyen.app.transfer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.settings.SettingsRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LegacyXpkBackupImporterTest {
    private lateinit var context: Context
    private lateinit var currentDatabase: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var root: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        currentDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepository = SettingsRepository(context)
        root = File(context.cacheDir, "legacy-xpk-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        currentDatabase.close()
        root.deleteRecursively()
    }

    @Test
    fun format7AllBackupIsDetectedAndMergedIntoCurrentRoomSchema() = runBlocking {
        val legacyDb = File(root, "accessible_reader.db")
        createLegacyLibraryDatabase(legacyDb)
        val vietPhraseDb = File(root, "vietphrase_dictionary.db")
        createLegacyVietPhraseDatabase(vietPhraseDb)
        val archive = File(root, "legacy-all.zip")
        createArchive(archive, legacyDb, vietPhraseDb)

        val importer = LegacyXpkBackupImporter(context, currentDatabase, settingsRepository)
        val inspection = importer.inspect(Uri.fromFile(archive))
        assertTrue(inspection is AppResult.Success)
        inspection as AppResult.Success
        assertTrue(inspection.value.isLegacyXpk)
        assertEquals(7, inspection.value.preview?.formatVersion)
        assertEquals(11, inspection.value.preview?.databaseSchema)

        val result = importer.restoreFrom(Uri.fromFile(archive), BackupComponent.entries.toSet())
        assertTrue(result is AppResult.Success)
        result as AppResult.Success
        assertEquals(1, result.value.stories)
        assertEquals(1, result.value.chapters)
        assertEquals(1, result.value.progress)
        assertEquals(1, result.value.readingHistory)
        assertEquals(1, result.value.bookmarks)
        assertEquals(1, result.value.following)
        assertEquals(1, result.value.pronunciations)
        assertEquals(1, result.value.storyTtsProfiles)
        assertEquals(1, result.value.storyAiProfiles)
        assertEquals(2, result.value.vietPhraseRules)
        assertTrue(result.value.settingsRestored)

        val story = currentDatabase.storyDao().listAll().single()
        assertEquals("truyenfull", story.sourceId)
        assertEquals("https://truyenfull.vision/test-story/", story.remoteUrl)
        assertEquals("Truyện thử", story.title)

        val chapter = currentDatabase.chapterDao().listForStory(story.id).single()
        assertEquals(1, chapter.chapterIndex)
        assertEquals("Chương 2", chapter.title)
        assertEquals("https://truyenfull.vision/test-story/chuong-2/", chapter.remoteUrl)

        val progress = currentDatabase.progressDao().get(story.id)!!
        assertEquals(chapter.id, progress.chapterId)
        assertEquals(2, progress.paragraphIndex)
        assertEquals(10, progress.totalParagraphs)

        val history = currentDatabase.readingHistoryDao().listRecent().single()
        assertEquals(story.id, history.storyId)
        assertEquals(chapter.id, history.chapterId)
        assertEquals(3, history.paragraphIndex)

        val bookmark = currentDatabase.bookmarkDao().listAll().single()
        assertEquals(story.id, bookmark.storyId)
        assertEquals(chapter.id, bookmark.chapterId)
        assertEquals(4, bookmark.paragraphIndex)

        val followed = currentDatabase.followingDao().listAll().single()
        assertEquals(story.id, followed.storyId)
        assertEquals(3, followed.latestKnownChapterIndex)
        assertEquals(2, followed.newChapterCount)

        val pronunciation = currentDatabase.pronunciationDao().listAll().single()
        assertEquals("AI", pronunciation.original)
        assertEquals("ây ai", pronunciation.replacement)

        val ttsProfile = currentDatabase.storyTtsProfileDao().listAll().single()
        assertEquals(story.id, ttsProfile.storyId)
        assertEquals(1.15f, ttsProfile.rate, 0.001f)
        assertEquals("vi-VN", ttsProfile.languageTag)

        val aiProfile = currentDatabase.storyAiProfileDao().listAll().single()
        assertEquals(story.id, aiProfile.storyId)
        assertEquals("TRANSLATE", aiProfile.mode)
        assertTrue(aiProfile.useCustomPrompts)

        val vietPhrase = currentDatabase.vietPhraseDao().listAll()
        assertEquals(2, vietPhrase.size)
        assertTrue(vietPhrase.any { it.kind == "NAMES" && it.source == "张三" && it.target == "Trương Tam" })
        assertTrue(vietPhrase.any { it.kind == "VIET_PHRASE" && it.source == "修炼" && it.target == "tu luyện" })

        val settings = settingsRepository.snapshot()
        assertEquals(1.2f, settings.ttsRate, 0.001f)
        assertEquals(0.95f, settings.ttsPitch, 0.001f)
        assertEquals("vi-VN", settings.ttsLanguageTag)
        assertEquals(20, settings.readerDisplay.fontSizeSp)
    }

    private fun createLegacyLibraryDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE stories (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL DEFAULT '', story_url TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '', current_chapter TEXT DEFAULT '', current_url TEXT DEFAULT '', para INTEGER DEFAULT 1, total_para INTEGER DEFAULT 0, progress REAL DEFAULT 0, last_read_at INTEGER DEFAULT 0, bookmarked INTEGER DEFAULT 0, UNIQUE(source, story_url))")
            db.execSQL("CREATE TABLE chapters (url TEXT PRIMARY KEY, story_url TEXT DEFAULT '', source TEXT DEFAULT '', story_title TEXT DEFAULT '', title TEXT DEFAULT '', offline_path TEXT DEFAULT '', order_index INTEGER DEFAULT 0, is_read INTEGER DEFAULT 0, read_at INTEGER DEFAULT 0, content_size INTEGER DEFAULT 0, downloaded_at INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE reading_history (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT DEFAULT '', story_url TEXT DEFAULT '', chapter_url TEXT DEFAULT '', title TEXT DEFAULT '', para INTEGER DEFAULT 1, read_at INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT DEFAULT '', story_url TEXT DEFAULT '', chapter_url TEXT DEFAULT '', title TEXT DEFAULT '', para INTEGER DEFAULT 1, created_at INTEGER DEFAULT 0, UNIQUE(source, story_url, chapter_url, para))")
            db.execSQL("CREATE TABLE followed_stories (source TEXT NOT NULL DEFAULT '', story_url TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '', last_chapter_url TEXT DEFAULT '', last_chapter_title TEXT DEFAULT '', last_chapter_number INTEGER DEFAULT 0, known_chapter_count INTEGER DEFAULT 0, new_chapter_count INTEGER DEFAULT 0, last_checked_at INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0, PRIMARY KEY(source, story_url))")
            db.execSQL("CREATE TABLE tts_pronunciations (id INTEGER PRIMARY KEY AUTOINCREMENT, original TEXT NOT NULL UNIQUE, replacement TEXT NOT NULL DEFAULT '', enabled INTEGER DEFAULT 1, created_at INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE story_tts_profiles (source TEXT NOT NULL DEFAULT '', story_url TEXT NOT NULL DEFAULT '', engine TEXT DEFAULT '', language TEXT DEFAULT 'vi-VN', voice TEXT DEFAULT '', processing_method TEXT DEFAULT 'system', sonic_quality INTEGER DEFAULT 0, speed REAL DEFAULT 1.0, pitch REAL DEFAULT 1.0, volume REAL DEFAULT 1.0, enabled INTEGER DEFAULT 1, updated_at INTEGER DEFAULT 0, PRIMARY KEY(source, story_url))")
            db.execSQL("CREATE TABLE story_ai_profiles (source TEXT NOT NULL DEFAULT '', story_url TEXT NOT NULL DEFAULT '', mode TEXT DEFAULT 'inherit', use_custom_prompt INTEGER DEFAULT 0, translate_prompt TEXT DEFAULT '', improve_prompt TEXT DEFAULT '', updated_at INTEGER DEFAULT 0, PRIMARY KEY(source, story_url))")

            val storyUrl = "https://truyenfull.vision/test-story/"
            val chapterUrl = "https://truyenfull.vision/test-story/chuong-2/"
            db.execSQL(
                "INSERT INTO stories(source,story_url,title,current_chapter,current_url,para,total_para,progress,last_read_at,bookmarked) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf("truyenfull", storyUrl, "Truyện thử", "Chương 2", chapterUrl, 3, 10, 0.3, 1_700_000_000_000L, 1),
            )
            db.execSQL(
                "INSERT INTO chapters(url,story_url,source,story_title,title,offline_path,order_index,is_read,read_at,content_size,downloaded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(chapterUrl, storyUrl, "truyenfull", "Truyện thử", "Chương 2", "/legacy/chapter.txt", 2, 1, 1_700_000_000_100L, 1234, 1_700_000_000_100L),
            )
            db.execSQL(
                "INSERT INTO reading_history(source,story_url,chapter_url,title,para,read_at) VALUES(?,?,?,?,?,?)",
                arrayOf("truyenfull", storyUrl, chapterUrl, "Chương 2", 4, 1_700_000_000_200L),
            )
            db.execSQL(
                "INSERT INTO bookmarks(source,story_url,chapter_url,title,para,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf("truyenfull", storyUrl, chapterUrl, "Đoạn hay", 5, 1_700_000_000_300L),
            )
            db.execSQL(
                "INSERT INTO followed_stories(source,story_url,title,last_chapter_url,last_chapter_title,last_chapter_number,known_chapter_count,new_chapter_count,last_checked_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf("truyenfull", storyUrl, "Truyện thử", "https://truyenfull.vision/test-story/chuong-4/", "Chương 4", 4, 4, 2, 1_700_000_000_400L, 1_700_000_000_400L),
            )
            db.execSQL(
                "INSERT INTO tts_pronunciations(original,replacement,enabled,created_at,updated_at) VALUES(?,?,?,?,?)",
                arrayOf("AI", "ây ai", 1, 1_700_000_000_500L, 1_700_000_000_500L),
            )
            db.execSQL(
                "INSERT INTO story_tts_profiles(source,story_url,engine,language,voice,processing_method,sonic_quality,speed,pitch,volume,enabled,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("truyenfull", storyUrl, "engine.pkg", "vi-VN", "voice-1", "system", 0, 1.15, 0.9, 0.8, 1, 1_700_000_000_600L),
            )
            db.execSQL(
                "INSERT INTO story_ai_profiles(source,story_url,mode,use_custom_prompt,translate_prompt,improve_prompt,updated_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf("truyenfull", storyUrl, "translate", 1, "Dịch thử", "Cải thiện thử", 1_700_000_000_700L),
            )
        }
    }

    private fun createLegacyVietPhraseDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE vp_entries (kind TEXT NOT NULL, term TEXT NOT NULL, replacement TEXT NOT NULL DEFAULT '', first_char TEXT DEFAULT '', prefix_key TEXT DEFAULT '', char_length INTEGER DEFAULT 0, byte_length INTEGER DEFAULT 0, PRIMARY KEY(kind,term))")
            db.execSQL("CREATE TABLE vp_dictionary_meta (kind TEXT PRIMARY KEY, file_name TEXT DEFAULT '', entry_count INTEGER DEFAULT 0, imported_at INTEGER DEFAULT 0, source_size INTEGER DEFAULT 0, source_path TEXT DEFAULT '', parser_revision INTEGER DEFAULT 0)")
            db.execSQL("INSERT INTO vp_entries(kind,term,replacement) VALUES('names','张三','Trương Tam')")
            db.execSQL("INSERT INTO vp_entries(kind,term,replacement) VALUES('vietphrase','修炼','tu luyện')")
            db.execSQL("INSERT INTO vp_dictionary_meta(kind,file_name,entry_count,imported_at) VALUES('names','Names.txt',1,1700000000800)")
            db.execSQL("INSERT INTO vp_dictionary_meta(kind,file_name,entry_count,imported_at) VALUES('vietphrase','VietPhrase.txt',1,1700000000800)")
        }
    }

    private fun createArchive(archive: File, legacyDb: File, vietPhraseDb: File) {
        val manifest = JSONObject()
            .put("format_version", 7)
            .put("created_at", 1_700_000_001_000L)
            .put("database_schema", 11)
            .put("app", "NgheTruyen Modular")
            .put("scope", "all")
            .put("components", JSONArray(listOf("settings", "library", "vietphrase")))
            .toString()
        val settings = JSONObject()
            .put("tts_speed", 1.2)
            .put("tts_pitch", 0.95)
            .put("tts_volume", 0.85)
            .put("tts_language", "vi-VN")
            .put("reader_mode", "tts")
            .put("font_size", 20)
            .put("reader_line_spacing", 1.3)
            .put("reader_dark_mode", true)
            .put("reader_keep_screen_on", true)
            .put("ai_enabled", false)
            .put("ai_gemini_key", "must-not-be-migrated")
            .toString()

        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.writeTextEntry("manifest.json", manifest)
            zip.writeTextEntry("settings.json", settings)
            zip.writeTextEntry("preferences.json", "{}")
            zip.writeFileEntry("database/accessible_reader.db", legacyDb)
            zip.writeFileEntry("database/vietphrase_dictionary.db", vietPhraseDb)
        }
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(this) }
        closeEntry()
    }
}
