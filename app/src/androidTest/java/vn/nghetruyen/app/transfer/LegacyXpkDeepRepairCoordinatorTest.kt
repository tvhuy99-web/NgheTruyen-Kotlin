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
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.StoryVoiceCastMode
import vn.nghetruyen.app.ai.StoryVoiceCastReferenceCodec
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LegacyXpkDeepRepairCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var root: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context)
        root = File(context.cacheDir, "legacy-xpk-deep-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun zeroOrderChaptersAndVoiceProfilesSurviveDeepRepair() = runBlocking {
        val legacyDb = File(root, "accessible_reader.db")
        createLegacyDatabase(legacyDb)
        val archive = File(root, "legacy-deep.zip")
        createArchive(archive, legacyDb)

        val app = context.applicationContext as NgheTruyenApplication
        val sourcePlatform = app.container.sourcePlatformManager
        val sourceRegistry = app.container.sourceRegistry
        val complete = LegacyXpkCompleteRestoreCoordinator(
            context = context,
            legacyImporter = LegacyXpkBackupImporter(context, database, settings),
            database = database,
            settingsRepository = settings,
            sourcePlatformManager = sourcePlatform,
            onSourcesChanged = {},
        )
        val everything = LegacyXpkEverythingRestoreCoordinator(context, complete, database)
        val verified = LegacyXpkVerifiedRestoreCoordinator(
            context = context,
            delegate = everything,
            database = database,
            sourceRegistry = sourceRegistry,
            sourcePlatformManager = sourcePlatform,
        )
        val deep = LegacyXpkDeepRepairCoordinator(
            context = context,
            delegate = verified,
            database = database,
            settingsRepository = settings,
            sourcePlatformManager = sourcePlatform,
            onSourcesChanged = {},
        )

        val result = deep.restoreFrom(
            Uri.fromFile(archive),
            setOf(BackupComponent.SETTINGS, BackupComponent.LIBRARY, BackupComponent.READING, BackupComponent.AI_VOICE),
        )
        assertTrue(result is AppResult.Success)
        result as AppResult.Success
        assertEquals(6, result.value.repair.legacyChaptersExpected)
        assertEquals(6, result.value.repair.legacyChaptersPersisted)
        assertEquals(6, result.value.repair.chapterIndicesRebuilt)

        val stories = database.storyDao().listAll()
        val story = stories.single()
        val chapters = database.chapterDao().listForStory(story.id)
        assertEquals(6, chapters.size)
        assertEquals(6, chapters.map { it.chapterIndex }.distinct().size)
        assertEquals(
            (1..6).map { "https://truyenfull.vision/deep-story/chuong-$it/" }.toSet(),
            chapters.map { it.remoteUrl }.toSet(),
        )

        val progress = database.progressDao().get(story.id)!!
        assertTrue(chapters.any { it.id == progress.chapterId && it.remoteUrl.endsWith("/chuong-5/") })

        val globalRoles = database.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID)
        assertEquals(2, globalRoles.size)
        assertTrue(globalRoles.any { it.isNarrator && it.roleName == "Người kể chuyện" })
        val male = globalRoles.single { it.roleName == "Nam thiếu niên" }
        assertEquals("voice-male-global", male.voiceName)
        assertEquals("sonic", ReferenceVoiceRoleExtras.load(context, male.id).processingMethod)
        assertTrue(ReferenceVoiceRoleExtras.load(context, male.id).sonicAccurate)

        val privateRoles = database.voiceRoleDao().listForStory(story.id)
        assertEquals(2, privateRoles.size)
        assertTrue(privateRoles.any { it.roleName == "Nhân vật A" && it.voiceName == "voice-a" })

        val aiProfile = database.storyAiProfileDao().get(story.id)!!
        val voiceSettings = StoryVoiceCastReferenceCodec.decode(aiProfile.voiceCastNote)
        assertEquals(StoryVoiceCastMode.PRIVATE, voiceSettings.mode)
        assertTrue(voiceSettings.autoRunOnOpenTts)
        assertEquals("Ghi chú riêng", voiceSettings.note)
        assertTrue(aiProfile.expressiveAdjustment)
        assertEquals(12, aiProfile.expressionSpeedLimitPct)
        assertEquals(8, aiProfile.expressionPitchLimitPct)
        assertEquals(15, aiProfile.expressionVolumeLimitPct)
    }

    private fun createLegacyDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE stories (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL DEFAULT '', story_url TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '', current_chapter TEXT DEFAULT '', current_url TEXT DEFAULT '', para INTEGER DEFAULT 1, total_para INTEGER DEFAULT 0, progress REAL DEFAULT 0, last_read_at INTEGER DEFAULT 0, bookmarked INTEGER DEFAULT 0, UNIQUE(source, story_url))")
            db.execSQL("CREATE TABLE chapters (url TEXT PRIMARY KEY, story_url TEXT DEFAULT '', source TEXT DEFAULT '', story_title TEXT DEFAULT '', title TEXT DEFAULT '', offline_path TEXT DEFAULT '', order_index INTEGER DEFAULT 0, is_read INTEGER DEFAULT 0, read_at INTEGER DEFAULT 0, content_size INTEGER DEFAULT 0, downloaded_at INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE reading_history (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT DEFAULT '', story_url TEXT DEFAULT '', chapter_url TEXT DEFAULT '', title TEXT DEFAULT '', para INTEGER DEFAULT 1, read_at INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT DEFAULT '', story_url TEXT DEFAULT '', chapter_url TEXT DEFAULT '', title TEXT DEFAULT '', para INTEGER DEFAULT 1, created_at INTEGER DEFAULT 0, UNIQUE(source, story_url, chapter_url, para))")
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")

            val storyUrl = "https://truyenfull.vision/deep-story/"
            val currentUrl = "${storyUrl}chuong-5/"
            db.execSQL(
                "INSERT INTO stories(source,story_url,title,current_chapter,current_url,para,total_para,progress,last_read_at,bookmarked) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("truyenfull", storyUrl, "Deep Story", "Chương 5", currentUrl, 3, 9, 0.5, 1_700_000_100_000L, 0),
            )
            for (number in 1..6) {
                val url = "${storyUrl}chuong-$number/"
                db.execSQL(
                    "INSERT INTO chapters(url,story_url,source,story_title,title,offline_path,order_index,is_read,read_at,content_size,downloaded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(url, storyUrl, "truyenfull", "Deep Story", "Chương $number", "", 0, 1, 1_700_000_100_000L + number, 0, 0),
                )
            }
            db.execSQL(
                "INSERT INTO reading_history(source,story_url,chapter_url,title,para,read_at) VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>("truyenfull", storyUrl, "${storyUrl}chuong-4/", "Chương 4", 2, 1_700_000_100_100L),
            )

            val privateProfiles = JSONArray()
                .put(voice("voice_narrator", "Người kể chuyện", "voice-narrator-private", "system", 0))
                .put(voice("character_a", "Nhân vật A", "voice-a", "sonic", 1))
            val storyVoice = JSONObject()
                .put("schema_version", 8)
                .put("mode", "story")
                .put("note", "Ghi chú riêng")
                .put("auto_cast_tts", true)
                .put("expressive_adjustment", true)
                .put("expression_speed_limit_pct", 12)
                .put("expression_pitch_limit_pct", 8)
                .put("expression_volume_limit_pct", 15)
                .put("expression_prompt", "Prompt diễn cảm")
                .put("profiles", privateProfiles)
            db.execSQL(
                "INSERT INTO settings(key,value) VALUES(?,?)",
                arrayOf<Any?>("story_ai_voice_cast|truyenfull|$storyUrl", storyVoice.toString()),
            )
        }
    }

    private fun createArchive(archive: File, legacyDb: File) {
        val globalProfiles = JSONArray()
            .put(voice("voice_narrator", "Người kể chuyện", "voice-narrator-global", "system", 0))
            .put(voice("voice_male_young", "Nam thiếu niên", "voice-male-global", "sonic", 1))
        val settingsJson = JSONObject()
            .put("reader_mode", "tts")
            .put("tts_speed", 1.1)
            .put("tts_pitch", 0.95)
            .put("tts_volume", 0.9)
            .put("tts_language", "vi-VN")
            .put("ai_voice_cast_enabled", true)
            .put("ai_voice_cast_profiles_json", globalProfiles.toString())
            .toString()
        val manifest = JSONObject()
            .put("format_version", 7)
            .put("database_schema", 11)
            .put("app", "NgheTruyen Modular")
            .put("scope", "all")
            .put("components", JSONArray(listOf("settings", "library")))
            .toString()

        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.writeText("manifest.json", manifest)
            zip.writeText("settings.json", settingsJson)
            zip.writeText("preferences.json", "{}")
            zip.writeFile("database/accessible_reader.db", legacyDb)
        }
    }

    private fun voice(id: String, name: String, voice: String, method: String, quality: Int): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("description", "Mô tả $name")
            .put("engine", "engine.pkg")
            .put("language", "vi-VN")
            .put("voice", voice)
            .put("processing_method", method)
            .put("sonic_quality", quality)
            .put("speed", 1.15)
            .put("pitch", 0.95)
            .put("volume", 0.8)
            .put("enabled", true)

    private fun ZipOutputStream.writeText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }
}
