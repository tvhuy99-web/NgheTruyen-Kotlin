package vn.nghetruyen.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databases = mutableListOf<String>()

    @After
    fun cleanUp() {
        databases.forEach(context::deleteDatabase)
    }

    @Test
    fun migration5To6CreatesEveryP4TableIncludingDownloadJobs() {
        withDatabase("migration-5-6.db", 5) { db ->
            AppDatabase.MIGRATION_5_6.migrate(db)

            listOf(
                "viet_phrase_rules",
                "chapter_transforms",
                "chapter_voice_assignments",
                "scene_music_tracks",
                "scene_music_cues",
                "download_jobs",
            ).forEach { table -> assertTrue("Missing table $table", db.hasTable(table)) }
        }
    }

    @Test
    fun migration6To7RepairsMissingTableAndNormalizesDefaultsWithoutDataLoss() {
        withDatabase("migration-6-7.db", 6) { db ->
            db.createVersion6TablesWithoutDefaults()
            db.execSQL(
                "INSERT INTO following VALUES " +
                    "('story-1','source-1','https://example.test/story','Truyện'," +
                    "'Chương 9',9,2,1234)",
            )
            db.execSQL(
                "INSERT INTO story_tts_profiles VALUES " +
                    "('story-1',1.1,0.9,0.8,'engine','voice','vi-VN',1234)",
            )
            db.execSQL(
                "INSERT INTO audio_export_jobs VALUES " +
                    "('job-1','story-1','Truyện',NULL,'content://export'," +
                    "'M4A','audio/mp4','RUNNING',3,8,NULL,1234)",
            )

            AppDatabase.MIGRATION_6_7.migrate(db)

            assertTrue(db.hasTable("download_jobs"))
            assertEquals(
                listOf(
                    "id",
                    "storyId",
                    "sourceId",
                    "state",
                    "completedChapters",
                    "totalChapters",
                    "errorMessage",
                    "updatedAt",
                ),
                db.columnNames("download_jobs"),
            )
            assertEquals("-1", db.columnDefault("following", "latestKnownChapterIndex"))
            assertEquals("0", db.columnDefault("following", "newChapterCount"))
            assertEquals("1.0", db.columnDefault("story_tts_profiles", "volume"))
            assertEquals("'WAV'", db.columnDefault("audio_export_jobs", "outputFormat"))
            assertEquals("'audio/wav'", db.columnDefault("audio_export_jobs", "mimeType"))

            assertEquals("Chương 9", db.scalarText("SELECT latestKnownChapter FROM following"))
            assertEquals("voice", db.scalarText("SELECT voiceName FROM story_tts_profiles"))
            assertEquals("M4A", db.scalarText("SELECT outputFormat FROM audio_export_jobs"))
            assertEquals("audio/mp4", db.scalarText("SELECT mimeType FROM audio_export_jobs"))
        }
    }

    @Test
    fun migration8To9AddsNotesAndPerChapterFailures() {
        withDatabase("migration-8-9.db", 8) { db ->
            AppDatabase.MIGRATION_8_9.migrate(db)
            assertTrue(db.hasTable("chapter_notes"))
            assertTrue(db.hasTable("chapter_download_failures"))
            assertEquals(
                listOf("id", "storyId", "chapterId", "paragraphIndex", "text", "createdAt", "updatedAt"),
                db.columnNames("chapter_notes"),
            )
            assertEquals(
                listOf("id", "jobId", "storyId", "sourceId", "chapterIndex", "chapterTitle", "errorMessage", "retryCount", "updatedAt"),
                db.columnNames("chapter_download_failures"),
            )
            db.execSQL("INSERT INTO chapter_notes VALUES ('note-1','story-1','chapter-1',4,'Ý chính',1,2)")
            db.execSQL("INSERT INTO chapter_download_failures VALUES ('job-1:7','job-1','story-1','source-1',7,'Chương 8','Mất mạng',1,2)")
            assertEquals("Ý chính", db.scalarText("SELECT text FROM chapter_notes"))
            assertEquals("7", db.scalarText("SELECT CAST(chapterIndex AS TEXT) FROM chapter_download_failures"))
        }
    }

    @Test
    fun migration9To10AddsPlaybackCheckpointWithoutTouchingExistingTables() {
        withDatabase("migration-9-10.db", 9) { db ->
            db.execSQL(
                """
                CREATE TABLE chapter_notes (
                    id TEXT NOT NULL PRIMARY KEY,
                    storyId TEXT NOT NULL,
                    chapterId TEXT NOT NULL,
                    paragraphIndex INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO chapter_notes VALUES ('note-1','story-1','chapter-1',2,'Giữ nguyên',1,2)")

            AppDatabase.MIGRATION_9_10.migrate(db)

            assertTrue(db.hasTable("playback_checkpoint"))
            assertEquals(
                listOf("id", "sourceId", "storyId", "chapterId", "chapterIndex", "paragraphIndex", "wasPlaying", "activeSceneTrackId", "updatedAt"),
                db.columnNames("playback_checkpoint"),
            )
            db.execSQL("INSERT INTO playback_checkpoint VALUES ('reader','source-1','story-1','chapter-1',7,3,1,'track-1',1234)")
            assertEquals("chapter-1", db.scalarText("SELECT chapterId FROM playback_checkpoint"))
            assertEquals("Giữ nguyên", db.scalarText("SELECT text FROM chapter_notes"))
        }
    }

    @Test
    fun migration10To11AddsResumableAudiobookMetadataWithoutLosingProgress() {
        withDatabase("migration-10-11.db", 10) { db ->
            db.execSQL(
                """
                CREATE TABLE audio_export_jobs (
                    id TEXT NOT NULL PRIMARY KEY,
                    storyId TEXT NOT NULL,
                    storyTitle TEXT NOT NULL,
                    chapterId TEXT,
                    destinationUri TEXT NOT NULL,
                    outputFormat TEXT NOT NULL DEFAULT 'WAV',
                    mimeType TEXT NOT NULL DEFAULT 'audio/wav',
                    state TEXT NOT NULL,
                    completedSegments INTEGER NOT NULL,
                    totalSegments INTEGER NOT NULL,
                    errorMessage TEXT,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO audio_export_jobs VALUES ('job-1','story-1','Truyện','chapter-1','content://out','M4A','audio/mp4','FAILED',3,10,'Mất điện',1234)")

            AppDatabase.MIGRATION_10_11.migrate(db)

            assertEquals("CURRENT_CHAPTER", db.scalarText("SELECT scope FROM audio_export_jobs"))
            assertEquals("3", db.scalarText("SELECT CAST(completedSegments AS TEXT) FROM audio_export_jobs"))
            assertEquals("1234", db.scalarText("SELECT CAST(createdAt AS TEXT) FROM audio_export_jobs"))
            assertEquals("'QUEUED'", db.columnDefault("audio_export_jobs", "stage"))
            assertEquals("0", db.columnDefault("audio_export_jobs", "includeSceneMusic"))
            assertEquals("''", db.columnDefault("audio_export_jobs", "sourceFingerprint"))
        }
    }

    @Test
    fun migration11To12AddsPackagingAndChapterMarkersWithoutLosingExportState() {
        withDatabase("migration-11-12.db", 11) { db ->
            db.execSQL(
                """
                CREATE TABLE audio_export_jobs (
                    id TEXT NOT NULL PRIMARY KEY,
                    storyId TEXT NOT NULL,
                    storyTitle TEXT NOT NULL,
                    chapterId TEXT,
                    destinationUri TEXT NOT NULL,
                    outputFormat TEXT NOT NULL DEFAULT 'WAV',
                    mimeType TEXT NOT NULL DEFAULT 'audio/wav',
                    scope TEXT NOT NULL DEFAULT 'CACHED_STORY',
                    startChapterIndex INTEGER NOT NULL DEFAULT -1,
                    endChapterIndex INTEGER NOT NULL DEFAULT 2147483647,
                    includeSceneMusic INTEGER NOT NULL DEFAULT 0,
                    author TEXT NOT NULL DEFAULT '',
                    sourceFingerprint TEXT NOT NULL DEFAULT '',
                    stage TEXT NOT NULL DEFAULT 'QUEUED',
                    state TEXT NOT NULL,
                    completedSegments INTEGER NOT NULL,
                    totalSegments INTEGER NOT NULL,
                    errorMessage TEXT,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO audio_export_jobs VALUES ('job-1','story-1','Truyện',NULL,'content://tree','MP3','audio/mpeg','CHAPTER_RANGE',2,8,1,'Tác giả','abc','MIXING','RUNNING',12,30,NULL,100,200)")

            AppDatabase.MIGRATION_11_12.migrate(db)

            assertEquals("SINGLE_FILE", db.scalarText("SELECT packaging FROM audio_export_jobs"))
            assertEquals("1", db.scalarText("SELECT CAST(chapterMarkers AS TEXT) FROM audio_export_jobs"))
            assertEquals("12", db.scalarText("SELECT CAST(completedSegments AS TEXT) FROM audio_export_jobs"))
            assertEquals("'SINGLE_FILE'", db.columnDefault("audio_export_jobs", "packaging"))
            assertEquals("1", db.columnDefault("audio_export_jobs", "chapterMarkers"))
        }
    }

    @Test
    fun migration12To13AddsRoleExpressionLoudnessAndAiQuotaWithoutLosingData() {
        withDatabase("migration-12-13.db", 12) { db ->
            db.execSQL(
                """
                CREATE TABLE voice_roles (
                    id TEXT NOT NULL PRIMARY KEY, storyId TEXT NOT NULL, roleName TEXT NOT NULL,
                    aliasesCsv TEXT NOT NULL, voiceName TEXT, languageTag TEXT NOT NULL,
                    rate REAL NOT NULL, pitch REAL NOT NULL, volume REAL NOT NULL,
                    isNarrator INTEGER NOT NULL, enabled INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE scene_music_tracks (
                    id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, uri TEXT NOT NULL,
                    tagsCsv TEXT NOT NULL, volume REAL NOT NULL, enabled INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO voice_roles VALUES ('r','s','Vai','alias','voice','vi-VN',1.1,0.9,0.8,0,1,123)")
            db.execSQL("INSERT INTO scene_music_tracks VALUES ('t','Track','content://track','calm',0.4,1,456)")

            AppDatabase.MIGRATION_12_13.migrate(db)

            assertTrue(db.hasTable("ai_usage_daily"))
            assertEquals("NEUTRAL", db.scalarText("SELECT expression FROM voice_roles"))
            assertEquals("1.0", db.scalarText("SELECT CAST(sonicSpeed AS TEXT) FROM voice_roles"))
            assertEquals("-18.0", db.scalarText("SELECT CAST(loudnessLufsEstimate AS TEXT) FROM scene_music_tracks"))
            assertEquals("Track", db.scalarText("SELECT title FROM scene_music_tracks"))
            assertEquals("voice", db.scalarText("SELECT voiceName FROM voice_roles"))
        }
    }

    @Test
    fun migration7To8AddsDurableDownloadRequestFieldsWithoutLosingProgress() {
        withDatabase("migration-7-8.db", 7) { db ->
            db.execSQL(
                """
                CREATE TABLE download_jobs (
                    id TEXT NOT NULL PRIMARY KEY,
                    storyId TEXT NOT NULL,
                    sourceId TEXT NOT NULL,
                    state TEXT NOT NULL,
                    completedChapters INTEGER NOT NULL,
                    totalChapters INTEGER NOT NULL,
                    errorMessage TEXT,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO download_jobs VALUES " +
                    "('job-1','story-1','source-1','RUNNING',7,20,NULL,1234)",
            )

            AppDatabase.MIGRATION_7_8.migrate(db)

            assertEquals("ALL", db.scalarText("SELECT selectionMode FROM download_jobs"))
            assertEquals("7", db.scalarText("SELECT CAST(completedChapters AS TEXT) FROM download_jobs"))
            assertEquals("1234", db.scalarText("SELECT CAST(requestedAt AS TEXT) FROM download_jobs"))
            assertEquals("0", db.columnDefault("download_jobs", "wifiOnly"))
            assertEquals("-1", db.columnDefault("download_jobs", "currentChapterIndex"))
            assertEquals("''", db.columnDefault("download_jobs", "currentChapterTitle"))
        }
    }


    @Test
    fun migration13To14PreservesLegacyVietPhraseAndCreatesAdvancedTables() {
        withDatabase("migration-13-14.db", 13) { db ->
            db.execSQL(
                """
                CREATE TABLE viet_phrase_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source TEXT NOT NULL,
                    target TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    enabled INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX index_viet_phrase_rules_source ON viet_phrase_rules(source)")
            db.execSQL("INSERT INTO viet_phrase_rules(source,target,priority,enabled,createdAt,updatedAt) VALUES('天道','Thiên Đạo',10,1,100,200)")

            AppDatabase.MIGRATION_13_14.migrate(db)

            listOf(
                "viet_phrase_rules",
                "viet_phrase_snapshots",
                "viet_phrase_dictionary_state",
                "viet_phrase_suggestions",
            ).forEach { table -> assertTrue("Missing table $table", db.hasTable(table)) }
            db.query("SELECT source,target,kind,scope,storyId,matchMode,ignoreCase FROM viet_phrase_rules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("天道", cursor.getString(0))
                assertEquals("Thiên Đạo", cursor.getString(1))
                assertEquals("VIET_PHRASE", cursor.getString(2))
                assertEquals("GLOBAL", cursor.getString(3))
                assertEquals("", cursor.getString(4))
                assertEquals("LITERAL", cursor.getString(5))
                assertEquals(0, cursor.getInt(6))
            }
        }
    }

    @Test
    fun migration16To17AddsPerStoryAiProfilesWithSafeDefaults() {
        withDatabase("migration-16-17.db", 16) { db ->
            AppDatabase.MIGRATION_16_17.migrate(db)

            assertTrue(db.hasTable("story_ai_profiles"))
            assertEquals(
                listOf(
                    "storyId", "mode", "overrideProvider", "provider", "endpoint", "model",
                    "temperature", "useCustomPrompts", "translationPrompt", "improvePrompt",
                    "autoRunOnOpen", "updatedAt",
                ),
                db.columnNames("story_ai_profiles"),
            )
            assertEquals("'INHERIT'", db.columnDefault("story_ai_profiles", "mode"))
            assertEquals("'OPENAI_COMPATIBLE'", db.columnDefault("story_ai_profiles", "provider"))
            assertEquals("-1.0", db.columnDefault("story_ai_profiles", "temperature"))
            db.execSQL(
                "INSERT INTO story_ai_profiles(storyId, updatedAt) VALUES('story-1', 1234)",
            )
            assertEquals("INHERIT", db.scalarText("SELECT mode FROM story_ai_profiles"))
            assertEquals("OPENAI_COMPATIBLE", db.scalarText("SELECT provider FROM story_ai_profiles"))
        }
    }


    @Test
    fun migration17To18AddsAdvancedVoiceCastAndExpressionDefaults() {
        withDatabase("migration-17-18.db", 17) { db ->
            db.execSQL(
                """
                CREATE TABLE story_ai_profiles (
                    storyId TEXT NOT NULL PRIMARY KEY,
                    mode TEXT NOT NULL DEFAULT 'INHERIT',
                    overrideProvider INTEGER NOT NULL DEFAULT 0,
                    provider TEXT NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
                    endpoint TEXT NOT NULL DEFAULT '',
                    model TEXT NOT NULL DEFAULT '',
                    temperature REAL NOT NULL DEFAULT -1.0,
                    useCustomPrompts INTEGER NOT NULL DEFAULT 0,
                    translationPrompt TEXT NOT NULL DEFAULT '',
                    improvePrompt TEXT NOT NULL DEFAULT '',
                    autoRunOnOpen INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE chapter_voice_assignments (
                    id TEXT NOT NULL PRIMARY KEY,
                    storyId TEXT NOT NULL,
                    chapterId TEXT NOT NULL,
                    paragraphIndex INTEGER NOT NULL,
                    roleName TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO story_ai_profiles(storyId, updatedAt) VALUES('story-1', 1234)")
            db.execSQL(
                "INSERT INTO chapter_voice_assignments(id, storyId, chapterId, paragraphIndex, roleName, confidence, updatedAt) " +
                    "VALUES('a-1', 'story-1', 'chapter-1', 0, 'Người kể chuyện', 1.0, 1234)",
            )

            AppDatabase.MIGRATION_17_18.migrate(db)

            listOf(
                "useCustomVoiceCastPrompt", "voiceCastPrompt", "voiceCastNote",
                "voiceCastDialogueOnly", "voiceCastStableNarrator", "expressiveAdjustment",
                "expressionPrompt", "expressionSpeedLimitPct", "expressionPitchLimitPct",
                "expressionVolumeLimitPct",
            ).forEach { column -> assertTrue("Missing story AI column $column", column in db.columnNames("story_ai_profiles")) }
            listOf("speedAdjustPct", "pitchAdjustPct", "volumeAdjustPct").forEach { column ->
                assertTrue("Missing voice assignment column $column", column in db.columnNames("chapter_voice_assignments"))
            }
            assertEquals("1", db.scalarText("SELECT CAST(voiceCastDialogueOnly AS TEXT) FROM story_ai_profiles"))
            assertEquals("1", db.scalarText("SELECT CAST(voiceCastStableNarrator AS TEXT) FROM story_ai_profiles"))
            assertEquals("1", db.scalarText("SELECT CAST(expressiveAdjustment AS TEXT) FROM story_ai_profiles"))
            assertEquals("10", db.scalarText("SELECT CAST(expressionSpeedLimitPct AS TEXT) FROM story_ai_profiles"))
            assertEquals("0", db.scalarText("SELECT CAST(speedAdjustPct AS INTEGER) FROM chapter_voice_assignments"))
        }
    }

    private fun withDatabase(
        name: String,
        version: Int,
        block: (SupportSQLiteDatabase) -> Unit,
    ) {
        databases += name
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }


    private fun SupportSQLiteDatabase.createVersion6TablesWithoutDefaults() {
        execSQL(
            """
            CREATE TABLE following (
                storyId TEXT NOT NULL PRIMARY KEY,
                sourceId TEXT NOT NULL,
                remoteUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                latestKnownChapter TEXT NOT NULL,
                latestKnownChapterIndex INTEGER NOT NULL,
                newChapterCount INTEGER NOT NULL,
                checkedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE TABLE story_tts_profiles (
                storyId TEXT NOT NULL PRIMARY KEY,
                rate REAL NOT NULL,
                pitch REAL NOT NULL,
                volume REAL NOT NULL,
                enginePackage TEXT,
                voiceName TEXT,
                languageTag TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE TABLE audio_export_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                storyId TEXT NOT NULL,
                storyTitle TEXT NOT NULL,
                chapterId TEXT,
                destinationUri TEXT NOT NULL,
                outputFormat TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                state TEXT NOT NULL,
                completedSegments INTEGER NOT NULL,
                totalSegments INTEGER NOT NULL,
                errorMessage TEXT,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.columnNames(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SupportSQLiteDatabase.columnDefault(table: String, column: String): String? =
        query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use cursor.getString(defaultIndex)
            }
            null
        }

    private fun SupportSQLiteDatabase.scalarText(sql: String): String? =
        query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
        query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name),
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }
}
