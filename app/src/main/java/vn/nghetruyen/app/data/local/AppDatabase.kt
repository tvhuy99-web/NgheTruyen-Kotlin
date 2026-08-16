package vn.nghetruyen.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "stories",
    indices = [Index(value = ["sourceId", "remoteUrl"], unique = true)],
)
data class StoryEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    val remoteUrl: String,
    val isOffline: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapters",
    indices = [Index(value = ["storyId", "chapterIndex"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val chapterIndex: Int,
    val title: String,
    val remoteUrl: String,
    val content: String?,
    val downloadedAt: Long?,
)

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val storyId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    @ColumnInfo(defaultValue = "0") val totalParagraphs: Int = 0,
    val updatedAt: Long,
)

data class ReadingProgressWithChapterTitle(
    val storyId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val totalParagraphs: Int,
    val updatedAt: Long,
    val chapterTitle: String,
)

data class ChapterStorageSnapshot(
    val chapterId: String,
    val storyId: String,
    val downloadedAt: Long?,
    val bytes: Long,
)

@Entity(
    tableName = "reading_history",
    indices = [
        Index(value = ["storyId", "chapterId"], unique = true),
        Index(value = ["visitedAt"]),
    ],
)
data class ReadingHistoryEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val sourceId: String,
    val storyTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val totalParagraphs: Int,
    val visitedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["storyId", "chapterId", "paragraphIndex"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val label: String,
    val createdAt: Long,
)


@Entity(
    tableName = "chapter_notes",
    indices = [Index(value = ["storyId", "chapterId", "paragraphIndex"], unique = true)],
)
data class ChapterNoteEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_download_failures",
    indices = [Index(value = ["jobId", "chapterIndex"], unique = true), Index(value = ["storyId"])],
)
data class ChapterDownloadFailureEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val storyId: String,
    val sourceId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val errorMessage: String,
    val retryCount: Int,
    val updatedAt: Long,
)

@Entity(tableName = "following")
data class FollowedStoryEntity(
    @PrimaryKey val storyId: String,
    val sourceId: String,
    val remoteUrl: String,
    val title: String,
    val latestKnownChapter: String,
    @ColumnInfo(defaultValue = "-1") val latestKnownChapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "0") val newChapterCount: Int = 0,
    val checkedAt: Long,
)


@Entity(
    tableName = "tts_pronunciations",
    indices = [Index(value = ["original"], unique = true)],
)
data class PronunciationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val original: String,
    val replacement: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CachedChapterStorage(
    val chapterId: String,
    val bytes: Long,
    val cachedAt: Long,
)




@Entity(
    tableName = "viet_phrase_rules",
    indices = [
        Index(value = ["kind", "scope", "storyId", "source", "matchMode"], unique = true),
        Index(value = ["kind"]),
        Index(value = ["storyId"]),
    ],
)
data class VietPhraseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val target: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "'VIET_PHRASE'") val kind: String = "VIET_PHRASE",
    @ColumnInfo(defaultValue = "'GLOBAL'") val scope: String = "GLOBAL",
    @ColumnInfo(defaultValue = "''") val storyId: String = "",
    @ColumnInfo(defaultValue = "'LITERAL'") val matchMode: String = "LITERAL",
    @ColumnInfo(defaultValue = "0") val ignoreCase: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "viet_phrase_snapshots")
data class VietPhraseSnapshotEntity(
    @PrimaryKey val id: String,
    val label: String,
    val checksum: String,
    val ruleCount: Int,
    val payload: ByteArray,
    val createdAt: Long,
)

@Entity(
    tableName = "viet_phrase_dictionary_state",
    indices = [Index(value = ["kind", "scope", "storyId"], unique = true)],
)
data class VietPhraseDictionaryStateEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val scope: String,
    @ColumnInfo(defaultValue = "''") val storyId: String = "",
    val enabled: Boolean,
    val sourceName: String,
    val sourceFormat: String,
    val checksum: String,
    val entryCount: Int,
    val revision: Long,
    val importedAt: Long,
)

@Entity(
    tableName = "viet_phrase_suggestions",
    indices = [Index(value = ["status", "createdAt"]), Index(value = ["storyId"])],
)
data class VietPhraseSuggestionEntity(
    @PrimaryKey val id: String,
    val source: String,
    val proposedTarget: String,
    val editedTarget: String,
    val reason: String,
    val contextText: String,
    val storyId: String?,
    val status: String,
    val createdAt: Long,
    val reviewedAt: Long?,
)

@Entity(tableName = "story_ai_profiles")
data class StoryAiProfileEntity(
    @PrimaryKey val storyId: String,
    @ColumnInfo(defaultValue = "'INHERIT'") val mode: String = "INHERIT",
    @ColumnInfo(defaultValue = "0") val overrideProvider: Boolean = false,
    @ColumnInfo(defaultValue = "'OPENAI_COMPATIBLE'") val provider: String = "OPENAI_COMPATIBLE",
    @ColumnInfo(defaultValue = "''") val endpoint: String = "",
    @ColumnInfo(defaultValue = "''") val model: String = "",
    @ColumnInfo(defaultValue = "-1.0") val temperature: Float = -1f,
    @ColumnInfo(defaultValue = "0") val useCustomPrompts: Boolean = false,
    @ColumnInfo(defaultValue = "''") val translationPrompt: String = "",
    @ColumnInfo(defaultValue = "''") val improvePrompt: String = "",
    @ColumnInfo(defaultValue = "0") val autoRunOnOpen: Boolean = false,
    @ColumnInfo(defaultValue = "0") val useCustomVoiceCastPrompt: Boolean = false,
    @ColumnInfo(defaultValue = "''") val voiceCastPrompt: String = "",
    @ColumnInfo(defaultValue = "''") val voiceCastNote: String = "",
    @ColumnInfo(defaultValue = "1") val voiceCastDialogueOnly: Boolean = true,
    @ColumnInfo(defaultValue = "1") val voiceCastStableNarrator: Boolean = true,
    @ColumnInfo(defaultValue = "1") val expressiveAdjustment: Boolean = true,
    @ColumnInfo(defaultValue = "''") val expressionPrompt: String = "",
    @ColumnInfo(defaultValue = "10") val expressionSpeedLimitPct: Int = 10,
    @ColumnInfo(defaultValue = "10") val expressionPitchLimitPct: Int = 10,
    @ColumnInfo(defaultValue = "10") val expressionVolumeLimitPct: Int = 10,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_transforms",
    indices = [Index(value = ["chapterId", "kind"], unique = true)],
)
data class ChapterTransformEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val chapterId: String,
    val kind: String,
    val provider: String,
    val model: String,
    val sourceSha256: String,
    val transformedText: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_voice_assignments",
    indices = [Index(value = ["chapterId", "paragraphIndex"], unique = true)],
)
data class ChapterVoiceAssignmentEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val roleName: String,
    val confidence: Float,
    @ColumnInfo(defaultValue = "0.0") val speedAdjustPct: Float = 0f,
    @ColumnInfo(defaultValue = "0.0") val pitchAdjustPct: Float = 0f,
    @ColumnInfo(defaultValue = "0.0") val volumeAdjustPct: Float = 0f,
    val updatedAt: Long,
)

@Entity(tableName = "scene_music_tracks")
data class SceneMusicTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val uri: String,
    val tagsCsv: String,
    val volume: Float,
    val enabled: Boolean,
    @ColumnInfo(defaultValue = "-18.0") val loudnessLufsEstimate: Float = -18.0f,
    @ColumnInfo(defaultValue = "0.0") val peakDbfs: Float = 0f,
    @ColumnInfo(defaultValue = "-24.0") val normalizationTargetLufs: Float = -24f,
    @ColumnInfo(defaultValue = "0.0") val normalizationGainDb: Float = 0f,
    @ColumnInfo(defaultValue = "0") val normalizationPeakLimited: Boolean = false,
    @ColumnInfo(defaultValue = "0") val normalizationVersion: Int = 0,
    @ColumnInfo(defaultValue = "''") val normalizationError: String = "",
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastPlayedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val orderIndex: Int = 0,
    val updatedAt: Long,
)

@Entity(
    tableName = "scene_music_cues",
    indices = [Index(value = ["chapterId", "startParagraph"], unique = true)],
)
data class SceneMusicCueEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val chapterId: String,
    val startParagraph: Int,
    val trackId: String,
    val volume: Float,
    val mood: String,
    val updatedAt: Long,
)

@Entity(tableName = "story_tts_profiles")
data class StoryTtsProfileEntity(
    @PrimaryKey val storyId: String,
    val rate: Float,
    val pitch: Float,
    @ColumnInfo(defaultValue = "1.0") val volume: Float = 1.0f,
    val enginePackage: String? = null,
    val voiceName: String?,
    val languageTag: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "voice_roles",
    indices = [
        Index(value = ["storyId"]),
        Index(value = ["storyId", "roleName"], unique = true),
    ],
)
data class VoiceRoleEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val roleName: String,
    val aliasesCsv: String,
    @ColumnInfo(defaultValue = "''") val description: String = "",
    val enginePackage: String? = null,
    val voiceName: String?,
    val languageTag: String,
    val rate: Float,
    val pitch: Float,
    val volume: Float,
    @ColumnInfo(defaultValue = "'NEUTRAL'") val expression: String = "NEUTRAL",
    @ColumnInfo(defaultValue = "0.5") val expressionStrength: Float = 0.5f,
    @ColumnInfo(defaultValue = "1.0") val sonicSpeed: Float = 1.0f,
    @ColumnInfo(defaultValue = "1.0") val sonicPitch: Float = 1.0f,
    val isNarrator: Boolean,
    val enabled: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "playback_checkpoint")
data class PlaybackCheckpointEntity(
    @PrimaryKey val id: String = "reader",
    val sourceId: String,
    val storyId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    @ColumnInfo(defaultValue = "0") val speechChunkIndex: Int = 0,
    val wasPlaying: Boolean,
    val activeSceneTrackId: String? = null,
    val nextChapterUrl: String? = null,
    val previousChapterUrl: String? = null,
    val sleepTimerEndsAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "''") val sessionId: String = "",
    val updatedAt: Long,
)



@Entity(
    tableName = "playback_queue_chapters",
    indices = [Index(value = ["storyId", "position"], unique = true)],
)
data class PlaybackQueueChapterEntity(
    @PrimaryKey val position: Int,
    val sourceId: String,
    val storyId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterUrl: String,
    val nextChapterUrl: String? = null,
    val previousChapterUrl: String? = null,
    val updatedAt: Long,
)

@Entity(tableName = "ai_usage_daily")
data class AiUsageDailyEntity(
    @PrimaryKey val dayEpoch: Int,
    val requestCount: Int,
    val inputChars: Long,
    val outputChars: Long,
    val retryCount: Int,
    val lastErrorCode: String?,
    val updatedAt: Long,
)

@Entity(tableName = "audio_export_jobs")
data class AudioExportJobEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val storyTitle: String,
    val chapterId: String?,
    val destinationUri: String,
    @ColumnInfo(defaultValue = "'WAV'") val outputFormat: String = "WAV",
    @ColumnInfo(defaultValue = "'audio/wav'") val mimeType: String = "audio/wav",
    @ColumnInfo(defaultValue = "'CACHED_STORY'") val scope: String = "CACHED_STORY",
    @ColumnInfo(defaultValue = "-1") val startChapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "2147483647") val endChapterIndex: Int = Int.MAX_VALUE,
    @ColumnInfo(defaultValue = "0") val includeSceneMusic: Boolean = false,
    @ColumnInfo(defaultValue = "'SINGLE_FILE'") val packaging: String = "SINGLE_FILE",
    @ColumnInfo(defaultValue = "1") val chapterMarkers: Boolean = true,
    @ColumnInfo(defaultValue = "''") val author: String = "",
    @ColumnInfo(defaultValue = "''") val sourceFingerprint: String = "",
    @ColumnInfo(defaultValue = "'QUEUED'") val stage: String = "QUEUED",
    val state: String,
    val completedSegments: Int,
    val totalSegments: Int,
    val errorMessage: String?,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    val updatedAt: Long,
)

@Entity(tableName = "download_jobs")
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val sourceId: String,
    @ColumnInfo(defaultValue = "'ALL'") val selectionMode: String = "ALL",
    @ColumnInfo(defaultValue = "0") val startChapterIndex: Int = 0,
    @ColumnInfo(defaultValue = "2147483647") val endChapterIndex: Int = Int.MAX_VALUE,
    @ColumnInfo(defaultValue = "0") val wifiOnly: Boolean = false,
    @ColumnInfo(defaultValue = "0") val chargingOnly: Boolean = false,
    val state: String,
    val completedChapters: Int,
    val totalChapters: Int,
    @ColumnInfo(defaultValue = "-1") val currentChapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "''") val currentChapterTitle: String = "",
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    val errorMessage: String?,
    @ColumnInfo(defaultValue = "0") val requestedAt: Long = 0L,
    val updatedAt: Long,
)

data class OfflineStoryStorage(
    val storyId: String,
    val chapterCount: Int,
    val bytes: Long,
)

data class StorageUsage(
    val downloadedChapters: Int,
    val downloadedBytes: Long,
    val cachedChapters: Int,
    val cachedBytes: Long,
)

@Dao
interface StoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(story: StoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stories: List<StoryEntity>)

    @Query("SELECT * FROM stories WHERE id = :storyId LIMIT 1")
    suspend fun get(storyId: String): StoryEntity?

    @Query("SELECT * FROM stories ORDER BY updatedAt DESC")
    suspend fun listAll(): List<StoryEntity>

    @Query("SELECT * FROM stories WHERE isOffline = 1 ORDER BY updatedAt DESC")
    fun observeOffline(): Flow<List<StoryEntity>>

    @Query("SELECT s.* FROM stories s INNER JOIN reading_progress p ON p.storyId = s.id ORDER BY p.updatedAt DESC")
    fun observeReading(): Flow<List<StoryEntity>>

    @Query("UPDATE stories SET isOffline = :offline, updatedAt = :updatedAt WHERE id = :storyId")
    suspend fun setOffline(storyId: String, offline: Boolean, updatedAt: Long)

    @Query("DELETE FROM stories WHERE id = :storyId")
    suspend fun delete(storyId: String)
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun get(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE storyId = :storyId AND chapterIndex = :chapterIndex LIMIT 1")
    suspend fun getAt(storyId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE storyId = :storyId AND chapterIndex > :chapterIndex AND content IS NOT NULL AND TRIM(content) != '' ORDER BY chapterIndex LIMIT 1")
    suspend fun getNextAfter(storyId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE storyId = :storyId AND chapterIndex < :chapterIndex AND content IS NOT NULL AND TRIM(content) != '' ORDER BY chapterIndex DESC LIMIT 1")
    suspend fun getPreviousBefore(storyId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE storyId = :storyId AND remoteUrl = :remoteUrl LIMIT 1")
    suspend fun getByRemoteUrl(storyId: String, remoteUrl: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE storyId = :storyId ORDER BY chapterIndex")
    suspend fun listForStory(storyId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE storyId = :storyId AND content IS NOT NULL AND TRIM(content) != '' ORDER BY chapterIndex")
    suspend fun listExportableForStory(storyId: String): List<ChapterEntity>

    @Query("SELECT id FROM chapters WHERE storyId = :storyId AND downloadedAt IS NOT NULL AND content IS NOT NULL AND TRIM(content) != ''")
    suspend fun listDownloadedIds(storyId: String): List<String>

    @Query("SELECT id FROM chapters WHERE downloadedAt IS NOT NULL AND content IS NOT NULL AND TRIM(content) != ''")
    fun observeDownloadedIds(): Flow<List<String>>

    @Query("SELECT * FROM chapters ORDER BY storyId, chapterIndex")
    suspend fun listAll(): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)

    @Query("UPDATE chapters SET content = NULL, downloadedAt = NULL WHERE storyId = :storyId")
    suspend fun clearContentForStory(storyId: String)

    @Query("UPDATE chapters SET content = NULL, downloadedAt = NULL WHERE downloadedAt IS NULL")
    suspend fun clearTransientCache()

    @Query("""
        SELECT c.storyId AS storyId, COUNT(*) AS chapterCount,
               COALESCE(SUM(LENGTH(CAST(c.content AS BLOB))), 0) AS bytes
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
        WHERE c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != ''
        GROUP BY c.storyId
    """)
    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>>

    @Query("""
        SELECT
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS downloadedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS downloadedBytes,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS cachedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS cachedBytes
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
    """)
    fun observeStorageUsage(): Flow<StorageUsage>

    @Query("""
        SELECT c.id AS chapterId, c.storyId AS storyId, c.downloadedAt AS downloadedAt,
               COALESCE(LENGTH(CAST(c.content AS BLOB)), 0) AS bytes
        FROM chapters c
        WHERE c.content IS NOT NULL AND TRIM(c.content) != ''
    """)
    fun observeStorageSnapshot(): Flow<List<ChapterStorageSnapshot>>

    @Query("""
        SELECT c.id AS chapterId,
               COALESCE(LENGTH(CAST(c.content AS BLOB)), 0) AS bytes,
               COALESCE(c.downloadedAt, 0) AS cachedAt
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
        WHERE c.downloadedAt IS NULL AND c.content IS NOT NULL AND TRIM(c.content) != ''
        ORDER BY c.chapterIndex ASC
    """)
    suspend fun listTransientCacheEntries(): List<CachedChapterStorage>

    @Query("UPDATE chapters SET content = NULL, downloadedAt = NULL WHERE id = :chapterId")
    suspend fun clearContent(chapterId: String)
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ReadingProgressEntity>)

    @Query("SELECT * FROM reading_progress WHERE storyId = :storyId LIMIT 1")
    suspend fun get(storyId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("""
        SELECT p.storyId AS storyId, p.chapterId AS chapterId, p.paragraphIndex AS paragraphIndex,
               p.totalParagraphs AS totalParagraphs, p.updatedAt AS updatedAt, COALESCE(c.title, '') AS chapterTitle
        FROM reading_progress p
        LEFT JOIN chapters c ON c.id = p.chapterId
        ORDER BY p.updatedAt DESC
    """)
    fun observeAllWithChapterTitle(): Flow<List<ReadingProgressWithChapterTitle>>

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    suspend fun listAll(): List<ReadingProgressEntity>

    @Query("DELETE FROM reading_progress WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface ReadingHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReadingHistoryEntity)

    @Query("SELECT * FROM reading_history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<ReadingHistoryEntity>>

    @Query("SELECT * FROM reading_history ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 500): List<ReadingHistoryEntity>

    @Query("DELETE FROM reading_history")
    suspend fun clear()

    @Query("DELETE FROM reading_history WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)

    @Query("DELETE FROM reading_history WHERE id IN (SELECT id FROM reading_history ORDER BY visitedAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun prune(keep: Int = 500)
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun delete(bookmarkId: String)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun listAll(): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface ChapterNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: ChapterNoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ChapterNoteEntity>)

    @Query("SELECT * FROM chapter_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChapterNoteEntity>>

    @Query("SELECT * FROM chapter_notes ORDER BY updatedAt DESC")
    suspend fun listAll(): List<ChapterNoteEntity>

    @Query("SELECT * FROM chapter_notes WHERE storyId = :storyId AND chapterId = :chapterId AND paragraphIndex = :paragraphIndex LIMIT 1")
    suspend fun get(storyId: String, chapterId: String, paragraphIndex: Int): ChapterNoteEntity?

    @Query("DELETE FROM chapter_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chapter_notes WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface FollowingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FollowedStoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FollowedStoryEntity>)

    @Query("DELETE FROM following WHERE storyId = :storyId")
    suspend fun delete(storyId: String)

    @Query("SELECT * FROM following ORDER BY newChapterCount DESC, checkedAt DESC")
    fun observeAll(): Flow<List<FollowedStoryEntity>>

    @Query("SELECT * FROM following WHERE storyId = :storyId LIMIT 1")
    suspend fun get(storyId: String): FollowedStoryEntity?

    @Query("SELECT * FROM following ORDER BY newChapterCount DESC, checkedAt DESC")
    suspend fun listAll(): List<FollowedStoryEntity>

    @Query("UPDATE following SET newChapterCount = 0 WHERE storyId = :storyId")
    suspend fun markSeen(storyId: String)

    @Query("SELECT * FROM following ORDER BY checkedAt ASC LIMIT :limit")
    suspend fun listForUpdate(limit: Int): List<FollowedStoryEntity>
}

@Dao
interface PronunciationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PronunciationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PronunciationEntity>)

    @Query("SELECT * FROM tts_pronunciations ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PronunciationEntity>>

    @Query("SELECT * FROM tts_pronunciations ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")
    suspend fun listAll(): List<PronunciationEntity>

    @Query("SELECT * FROM tts_pronunciations WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): PronunciationEntity?

    @Query("SELECT * FROM tts_pronunciations WHERE enabled = 1 ORDER BY LENGTH(original) DESC, original COLLATE NOCASE ASC")
    suspend fun listEnabled(): List<PronunciationEntity>

    @Query("UPDATE tts_pronunciations SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM tts_pronunciations WHERE id = :id")
    suspend fun delete(id: Long)
}



@Dao
interface VietPhraseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VietPhraseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VietPhraseEntity>)

    @Update
    suspend fun update(item: VietPhraseEntity): Int

    @Query("SELECT * FROM viet_phrase_rules WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): VietPhraseEntity?

    @Query("SELECT * FROM viet_phrase_rules ORDER BY kind, scope, storyId, priority DESC, LENGTH(source) DESC, source COLLATE NOCASE")
    fun observeAll(): Flow<List<VietPhraseEntity>>

    @Query("SELECT * FROM viet_phrase_rules WHERE enabled = 1 AND (scope = 'GLOBAL' OR (scope = 'STORY' AND storyId = :storyId)) ORDER BY priority DESC, LENGTH(source) DESC, source COLLATE NOCASE")
    suspend fun listEnabledForStory(storyId: String?): List<VietPhraseEntity>

    @Query("SELECT * FROM viet_phrase_rules WHERE enabled = 1 ORDER BY priority DESC, LENGTH(source) DESC, source COLLATE NOCASE")
    suspend fun listEnabled(): List<VietPhraseEntity>

    @Query("SELECT * FROM viet_phrase_rules ORDER BY kind, scope, storyId, priority DESC, LENGTH(source) DESC, source COLLATE NOCASE")
    suspend fun listAll(): List<VietPhraseEntity>

    @Query("SELECT * FROM viet_phrase_rules WHERE kind = :kind AND scope = :scope AND storyId = COALESCE(:storyId, '') ORDER BY priority DESC, LENGTH(source) DESC")
    suspend fun listForDictionary(kind: String, scope: String, storyId: String?): List<VietPhraseEntity>

    @Query("UPDATE viet_phrase_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM viet_phrase_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM viet_phrase_rules")
    suspend fun deleteAll()

    @Query("DELETE FROM viet_phrase_rules WHERE kind = :kind AND scope = :scope AND storyId = COALESCE(:storyId, '')")
    suspend fun deleteDictionary(kind: String, scope: String, storyId: String?)
}

@Dao
interface VietPhraseSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VietPhraseSnapshotEntity)

    @Query("SELECT * FROM viet_phrase_snapshots ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VietPhraseSnapshotEntity>>

    @Query("SELECT * FROM viet_phrase_snapshots ORDER BY createdAt DESC")
    suspend fun listAll(): List<VietPhraseSnapshotEntity>

    @Query("SELECT * FROM viet_phrase_snapshots WHERE id = :id LIMIT 1")
    suspend fun get(id: String): VietPhraseSnapshotEntity?

    @Query("DELETE FROM viet_phrase_snapshots WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM viet_phrase_snapshots WHERE id IN (SELECT id FROM viet_phrase_snapshots ORDER BY createdAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun prune(keep: Int)
}

@Dao
interface VietPhraseDictionaryStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VietPhraseDictionaryStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VietPhraseDictionaryStateEntity>)

    @Query("SELECT * FROM viet_phrase_dictionary_state ORDER BY kind, scope, storyId")
    fun observeAll(): Flow<List<VietPhraseDictionaryStateEntity>>

    @Query("SELECT * FROM viet_phrase_dictionary_state ORDER BY kind, scope, storyId")
    suspend fun listAll(): List<VietPhraseDictionaryStateEntity>

    @Query("UPDATE viet_phrase_dictionary_state SET enabled = :enabled, revision = revision + 1 WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM viet_phrase_dictionary_state WHERE kind IN (:kinds)")
    suspend fun deleteKinds(kinds: List<String>)

    @Query("DELETE FROM viet_phrase_dictionary_state")
    suspend fun deleteAll()
}

@Dao
interface VietPhraseSuggestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VietPhraseSuggestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VietPhraseSuggestionEntity>)

    @Query("SELECT * FROM viet_phrase_suggestions ORDER BY CASE status WHEN 'PENDING' THEN 0 ELSE 1 END, createdAt DESC")
    fun observeAll(): Flow<List<VietPhraseSuggestionEntity>>

    @Query("SELECT * FROM viet_phrase_suggestions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun listPending(): List<VietPhraseSuggestionEntity>

    @Query("UPDATE viet_phrase_suggestions SET editedTarget = :editedTarget, status = :status, reviewedAt = :reviewedAt WHERE id = :id")
    suspend fun review(id: String, editedTarget: String, status: String, reviewedAt: Long)

    @Query("DELETE FROM viet_phrase_suggestions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface StoryAiProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StoryAiProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<StoryAiProfileEntity>)

    @Query("SELECT * FROM story_ai_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StoryAiProfileEntity>>

    @Query("SELECT * FROM story_ai_profiles ORDER BY updatedAt DESC")
    suspend fun listAll(): List<StoryAiProfileEntity>

    @Query("SELECT * FROM story_ai_profiles WHERE storyId = :storyId LIMIT 1")
    suspend fun get(storyId: String): StoryAiProfileEntity?

    @Query("DELETE FROM story_ai_profiles WHERE storyId = :storyId")
    suspend fun delete(storyId: String)
}

@Dao
interface ChapterTransformDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChapterTransformEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ChapterTransformEntity>)

    @Query("SELECT * FROM chapter_transforms ORDER BY storyId, chapterId, kind")
    suspend fun listAll(): List<ChapterTransformEntity>

    @Query("SELECT * FROM chapter_transforms WHERE chapterId = :chapterId AND kind = :kind LIMIT 1")
    suspend fun get(chapterId: String, kind: String): ChapterTransformEntity?

    @Query("DELETE FROM chapter_transforms WHERE chapterId = :chapterId AND kind = :kind")
    suspend fun delete(chapterId: String, kind: String)

    @Query("DELETE FROM chapter_transforms WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface ChapterVoiceAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ChapterVoiceAssignmentEntity>)

    @Query("SELECT * FROM chapter_voice_assignments ORDER BY storyId, chapterId, paragraphIndex")
    suspend fun listAll(): List<ChapterVoiceAssignmentEntity>

    @Query("SELECT * FROM chapter_voice_assignments WHERE chapterId = :chapterId ORDER BY paragraphIndex")
    suspend fun listForChapter(chapterId: String): List<ChapterVoiceAssignmentEntity>

    @Query("DELETE FROM chapter_voice_assignments WHERE chapterId = :chapterId")
    suspend fun deleteForChapter(chapterId: String)

    @Query("DELETE FROM chapter_voice_assignments WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface SceneMusicTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SceneMusicTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SceneMusicTrackEntity>)

    @Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, title COLLATE NOCASE")
    suspend fun listAll(): List<SceneMusicTrackEntity>

    @Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<SceneMusicTrackEntity>>

    @Query("SELECT * FROM scene_music_tracks WHERE enabled = 1 ORDER BY title COLLATE NOCASE")
    suspend fun listEnabled(): List<SceneMusicTrackEntity>

    @Query("SELECT * FROM scene_music_tracks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SceneMusicTrackEntity?

    @Query("UPDATE scene_music_tracks SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM scene_music_tracks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scene_music_tracks")
    suspend fun deleteAll()
}

@Dao
interface SceneMusicCueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SceneMusicCueEntity>)

    @Query("SELECT * FROM scene_music_cues ORDER BY storyId, chapterId, startParagraph")
    suspend fun listAll(): List<SceneMusicCueEntity>

    @Query("SELECT * FROM scene_music_cues WHERE chapterId = :chapterId ORDER BY startParagraph")
    suspend fun listForChapter(chapterId: String): List<SceneMusicCueEntity>

    @Query("DELETE FROM scene_music_cues WHERE chapterId = :chapterId")
    suspend fun deleteForChapter(chapterId: String)

    @Query("DELETE FROM scene_music_cues WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface StoryTtsProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StoryTtsProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<StoryTtsProfileEntity>)

    @Query("SELECT * FROM story_tts_profiles WHERE storyId = :storyId LIMIT 1")
    suspend fun get(storyId: String): StoryTtsProfileEntity?

    @Query("SELECT * FROM story_tts_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StoryTtsProfileEntity>>

    @Query("SELECT * FROM story_tts_profiles ORDER BY updatedAt DESC")
    suspend fun listAll(): List<StoryTtsProfileEntity>

    @Query("DELETE FROM story_tts_profiles WHERE storyId = :storyId")
    suspend fun delete(storyId: String)
}

@Dao
interface VoiceRoleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VoiceRoleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VoiceRoleEntity>)

    @Query("SELECT * FROM voice_roles WHERE storyId = :storyId ORDER BY isNarrator DESC, roleName COLLATE NOCASE")
    suspend fun listForStory(storyId: String): List<VoiceRoleEntity>

    @Query("SELECT * FROM voice_roles ORDER BY storyId, isNarrator DESC, roleName COLLATE NOCASE")
    fun observeAll(): Flow<List<VoiceRoleEntity>>

    @Query("SELECT * FROM voice_roles ORDER BY storyId, isNarrator DESC, roleName COLLATE NOCASE")
    suspend fun listAll(): List<VoiceRoleEntity>

    @Query("UPDATE voice_roles SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM voice_roles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM voice_roles WHERE storyId = :storyId AND isNarrator = 1")
    suspend fun deleteNarrator(storyId: String)

    @Query("DELETE FROM voice_roles WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface PlaybackCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlaybackCheckpointEntity)

    @Query("SELECT * FROM playback_checkpoint WHERE id = 'reader' LIMIT 1")
    suspend fun get(): PlaybackCheckpointEntity?

    @Query("DELETE FROM playback_checkpoint WHERE id = 'reader'")
    suspend fun clear()
}



@Dao
interface PlaybackQueueChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PlaybackQueueChapterEntity>)

    @Query("SELECT * FROM playback_queue_chapters ORDER BY position")
    suspend fun listAll(): List<PlaybackQueueChapterEntity>

    @Query("DELETE FROM playback_queue_chapters")
    suspend fun clear()
}

@Dao
interface AiUsageDailyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AiUsageDailyEntity)

    @Query("SELECT * FROM ai_usage_daily WHERE dayEpoch = :dayEpoch LIMIT 1")
    suspend fun get(dayEpoch: Int): AiUsageDailyEntity?

    @Query("SELECT * FROM ai_usage_daily ORDER BY dayEpoch DESC LIMIT :limit")
    fun observeRecent(limit: Int = 14): Flow<List<AiUsageDailyEntity>>

    @Query("DELETE FROM ai_usage_daily WHERE dayEpoch < :minimumDayEpoch")
    suspend fun pruneBefore(minimumDayEpoch: Int)
}

@Dao
interface AudioExportJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AudioExportJobEntity)

    @Query("SELECT * FROM audio_export_jobs WHERE id = :jobId LIMIT 1")
    suspend fun get(jobId: String): AudioExportJobEntity?

    @Query("SELECT * FROM audio_export_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AudioExportJobEntity>>

    @Query("DELETE FROM audio_export_jobs WHERE id = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM audio_export_jobs WHERE id IN (SELECT id FROM audio_export_jobs WHERE state NOT IN ('QUEUED', 'RUNNING') ORDER BY updatedAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun pruneFinished(keep: Int)
}

@Dao
interface DownloadJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: DownloadJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DownloadJobEntity>)

    @Query("SELECT * FROM download_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs ORDER BY updatedAt DESC")
    suspend fun listAll(): List<DownloadJobEntity>

    @Query("SELECT * FROM download_jobs WHERE id = :jobId LIMIT 1")
    suspend fun get(jobId: String): DownloadJobEntity?

    @Query("SELECT * FROM download_jobs WHERE storyId = :storyId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForStory(storyId: String): DownloadJobEntity?

    @Query("DELETE FROM download_jobs WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Dao
interface ChapterDownloadFailureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChapterDownloadFailureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ChapterDownloadFailureEntity>)

    @Query("SELECT * FROM chapter_download_failures ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChapterDownloadFailureEntity>>

    @Query("SELECT * FROM chapter_download_failures ORDER BY updatedAt DESC")
    suspend fun listAll(): List<ChapterDownloadFailureEntity>

    @Query("DELETE FROM chapter_download_failures WHERE jobId = :jobId AND chapterIndex = :chapterIndex")
    suspend fun delete(jobId: String, chapterIndex: Int)

    @Query("DELETE FROM chapter_download_failures WHERE jobId = :jobId")
    suspend fun deleteForJob(jobId: String)

    @Query("DELETE FROM chapter_download_failures WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)
}

@Database(
    entities = [
        StoryEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ReadingHistoryEntity::class,
        BookmarkEntity::class,
        ChapterNoteEntity::class,
        FollowedStoryEntity::class,
        PronunciationEntity::class,
        VietPhraseEntity::class,
        VietPhraseSnapshotEntity::class,
        VietPhraseDictionaryStateEntity::class,
        VietPhraseSuggestionEntity::class,
        StoryAiProfileEntity::class,
        ChapterTransformEntity::class,
        ChapterVoiceAssignmentEntity::class,
        SceneMusicTrackEntity::class,
        SceneMusicCueEntity::class,
        StoryTtsProfileEntity::class,
        VoiceRoleEntity::class,
        PlaybackCheckpointEntity::class,
        PlaybackQueueChapterEntity::class,
        AiUsageDailyEntity::class,
        AudioExportJobEntity::class,
        DownloadJobEntity::class,
        ChapterDownloadFailureEntity::class,
    ],
    version = 23,
    // Legacy wiring validator token: version = 18
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storyDao(): StoryDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun readingHistoryDao(): ReadingHistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun chapterNoteDao(): ChapterNoteDao
    abstract fun followingDao(): FollowingDao
    abstract fun pronunciationDao(): PronunciationDao
    abstract fun vietPhraseDao(): VietPhraseDao
    abstract fun vietPhraseSnapshotDao(): VietPhraseSnapshotDao
    abstract fun vietPhraseDictionaryStateDao(): VietPhraseDictionaryStateDao
    abstract fun vietPhraseSuggestionDao(): VietPhraseSuggestionDao
    abstract fun storyAiProfileDao(): StoryAiProfileDao
    abstract fun chapterTransformDao(): ChapterTransformDao
    abstract fun chapterVoiceAssignmentDao(): ChapterVoiceAssignmentDao
    abstract fun sceneMusicTrackDao(): SceneMusicTrackDao
    abstract fun sceneMusicCueDao(): SceneMusicCueDao
    abstract fun storyTtsProfileDao(): StoryTtsProfileDao
    abstract fun voiceRoleDao(): VoiceRoleDao
    abstract fun playbackCheckpointDao(): PlaybackCheckpointDao
    abstract fun playbackQueueChapterDao(): PlaybackQueueChapterDao
    abstract fun aiUsageDailyDao(): AiUsageDailyDao
    abstract fun audioExportJobDao(): AudioExportJobDao
    abstract fun downloadJobDao(): DownloadJobDao
    abstract fun chapterDownloadFailureDao(): ChapterDownloadFailureDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tts_pronunciations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        original TEXT NOT NULL,
                        replacement TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tts_pronunciations_original ON tts_pronunciations(original)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS story_tts_profiles (
                        storyId TEXT NOT NULL PRIMARY KEY,
                        rate REAL NOT NULL,
                        pitch REAL NOT NULL,
                        voiceName TEXT,
                        languageTag TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audio_export_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        storyId TEXT NOT NULL,
                        storyTitle TEXT NOT NULL,
                        chapterId TEXT,
                        destinationUri TEXT NOT NULL,
                        state TEXT NOT NULL,
                        completedSegments INTEGER NOT NULL,
                        totalSegments INTEGER NOT NULL,
                        errorMessage TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }



        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE following ADD COLUMN latestKnownChapterIndex INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE following ADD COLUMN newChapterCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE story_tts_profiles ADD COLUMN volume REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE story_tts_profiles ADD COLUMN enginePackage TEXT")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN outputFormat TEXT NOT NULL DEFAULT 'WAV'")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'audio/wav'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voice_roles (
                        id TEXT NOT NULL PRIMARY KEY,
                        storyId TEXT NOT NULL,
                        roleName TEXT NOT NULL,
                        aliasesCsv TEXT NOT NULL,
                        voiceName TEXT,
                        languageTag TEXT NOT NULL,
                        rate REAL NOT NULL,
                        pitch REAL NOT NULL,
                        volume REAL NOT NULL,
                        isNarrator INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voice_roles_storyId ON voice_roles(storyId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_voice_roles_storyId_roleName ON voice_roles(storyId, roleName)")
            }
        }


        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS viet_phrase_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        source TEXT NOT NULL,
                        target TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_viet_phrase_rules_source ON viet_phrase_rules(source)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_transforms (
                        id TEXT NOT NULL PRIMARY KEY,
                        storyId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        sourceSha256 TEXT NOT NULL,
                        transformedText TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_transforms_chapterId_kind ON chapter_transforms(chapterId, kind)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_voice_assignments (
                        id TEXT NOT NULL PRIMARY KEY,
                        storyId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        paragraphIndex INTEGER NOT NULL,
                        roleName TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_voice_assignments_chapterId_paragraphIndex ON chapter_voice_assignments(chapterId, paragraphIndex)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scene_music_tracks (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        tagsCsv TEXT NOT NULL,
                        volume REAL NOT NULL,
                        enabled INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scene_music_cues (
                        id TEXT NOT NULL PRIMARY KEY,
                        storyId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        startParagraph INTEGER NOT NULL,
                        trackId TEXT NOT NULL,
                        volume REAL NOT NULL,
                        mood TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_scene_music_cues_chapterId_startParagraph ON scene_music_cues(chapterId, startParagraph)")
                createDownloadJobsTable(db)
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 6 source builds declared DownloadJobEntity but the 5 -> 6
                // migration did not create its table. They also had SQL defaults
                // introduced by migrations without matching @ColumnInfo metadata.
                // Rebuilding the affected tables normalizes both fresh version-6
                // databases and databases upgraded through older versions.
                createDownloadJobsTable(db)
                normalizeFollowingDefaults(db)
                normalizeStoryTtsProfileDefaults(db)
                normalizeAudioExportDefaults(db)
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN selectionMode TEXT NOT NULL DEFAULT 'ALL'")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN startChapterIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN endChapterIndex INTEGER NOT NULL DEFAULT 2147483647")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN wifiOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN chargingOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN currentChapterIndex INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN currentChapterTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN requestedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE download_jobs SET requestedAt = updatedAt WHERE requestedAt = 0")
            }
        }


        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_notes (
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
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_notes_storyId_chapterId_paragraphIndex ON chapter_notes(storyId, chapterId, paragraphIndex)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_download_failures (
                        id TEXT NOT NULL PRIMARY KEY,
                        jobId TEXT NOT NULL,
                        storyId TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        errorMessage TEXT NOT NULL,
                        retryCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_download_failures_jobId_chapterIndex ON chapter_download_failures(jobId, chapterIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_download_failures_storyId ON chapter_download_failures(storyId)")
            }
        }


        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_checkpoint (
                        id TEXT NOT NULL PRIMARY KEY,
                        sourceId TEXT NOT NULL,
                        storyId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        paragraphIndex INTEGER NOT NULL,
                        wasPlaying INTEGER NOT NULL,
                        activeSceneTrackId TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN scope TEXT NOT NULL DEFAULT 'CACHED_STORY'")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN startChapterIndex INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN endChapterIndex INTEGER NOT NULL DEFAULT 2147483647")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN includeSceneMusic INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN author TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN sourceFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN stage TEXT NOT NULL DEFAULT 'QUEUED'")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE audio_export_jobs SET createdAt = updatedAt WHERE createdAt = 0")
                db.execSQL("UPDATE audio_export_jobs SET scope = CASE WHEN chapterId IS NULL THEN 'CACHED_STORY' ELSE 'CURRENT_CHAPTER' END")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN packaging TEXT NOT NULL DEFAULT 'SINGLE_FILE'")
                db.execSQL("ALTER TABLE audio_export_jobs ADD COLUMN chapterMarkers INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN enginePackage TEXT")
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN expression TEXT NOT NULL DEFAULT 'NEUTRAL'")
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN expressionStrength REAL NOT NULL DEFAULT 0.5")
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN sonicSpeed REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN sonicPitch REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN loudnessLufsEstimate REAL NOT NULL DEFAULT -18.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_usage_daily (
                        dayEpoch INTEGER NOT NULL PRIMARY KEY,
                        requestCount INTEGER NOT NULL,
                        inputChars INTEGER NOT NULL,
                        outputChars INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastErrorCode TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }


        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE viet_phrase_rules_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        source TEXT NOT NULL,
                        target TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        kind TEXT NOT NULL DEFAULT 'VIET_PHRASE',
                        scope TEXT NOT NULL DEFAULT 'GLOBAL',
                        storyId TEXT NOT NULL DEFAULT '',
                        matchMode TEXT NOT NULL DEFAULT 'LITERAL',
                        ignoreCase INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO viet_phrase_rules_new (
                        id, source, target, priority, enabled, kind, scope, storyId,
                        matchMode, ignoreCase, createdAt, updatedAt
                    )
                    SELECT id, source, target, priority, enabled, 'VIET_PHRASE', 'GLOBAL', '',
                           'LITERAL', 0, createdAt, updatedAt
                    FROM viet_phrase_rules
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE viet_phrase_rules")
                db.execSQL("ALTER TABLE viet_phrase_rules_new RENAME TO viet_phrase_rules")
                db.execSQL("CREATE UNIQUE INDEX index_viet_phrase_rules_kind_scope_storyId_source_matchMode ON viet_phrase_rules(kind, scope, storyId, source, matchMode)")
                db.execSQL("CREATE INDEX index_viet_phrase_rules_kind ON viet_phrase_rules(kind)")
                db.execSQL("CREATE INDEX index_viet_phrase_rules_storyId ON viet_phrase_rules(storyId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS viet_phrase_snapshots (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        ruleCount INTEGER NOT NULL,
                        payload BLOB NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS viet_phrase_dictionary_state (
                        id TEXT NOT NULL PRIMARY KEY,
                        kind TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        storyId TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL,
                        sourceName TEXT NOT NULL,
                        sourceFormat TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        entryCount INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        importedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX index_viet_phrase_dictionary_state_kind_scope_storyId ON viet_phrase_dictionary_state(kind, scope, storyId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS viet_phrase_suggestions (
                        id TEXT NOT NULL PRIMARY KEY,
                        source TEXT NOT NULL,
                        proposedTarget TEXT NOT NULL,
                        editedTarget TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        contextText TEXT NOT NULL,
                        storyId TEXT,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        reviewedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_viet_phrase_suggestions_status_createdAt ON viet_phrase_suggestions(status, createdAt)")
                db.execSQL("CREATE INDEX index_viet_phrase_suggestions_storyId ON viet_phrase_suggestions(storyId)")
            }
        }

        private fun normalizeFollowingDefaults(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE following_new (
                    storyId TEXT NOT NULL PRIMARY KEY,
                    sourceId TEXT NOT NULL,
                    remoteUrl TEXT NOT NULL,
                    title TEXT NOT NULL,
                    latestKnownChapter TEXT NOT NULL,
                    latestKnownChapterIndex INTEGER NOT NULL DEFAULT -1,
                    newChapterCount INTEGER NOT NULL DEFAULT 0,
                    checkedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO following_new (
                    storyId, sourceId, remoteUrl, title, latestKnownChapter,
                    latestKnownChapterIndex, newChapterCount, checkedAt
                )
                SELECT
                    storyId, sourceId, remoteUrl, title, latestKnownChapter,
                    latestKnownChapterIndex, newChapterCount, checkedAt
                FROM following
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE following")
            db.execSQL("ALTER TABLE following_new RENAME TO following")
        }

        private fun normalizeStoryTtsProfileDefaults(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE story_tts_profiles_new (
                    storyId TEXT NOT NULL PRIMARY KEY,
                    rate REAL NOT NULL,
                    pitch REAL NOT NULL,
                    volume REAL NOT NULL DEFAULT 1.0,
                    enginePackage TEXT,
                    voiceName TEXT,
                    languageTag TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO story_tts_profiles_new (
                    storyId, rate, pitch, volume, enginePackage, voiceName,
                    languageTag, updatedAt
                )
                SELECT
                    storyId, rate, pitch, volume, enginePackage, voiceName,
                    languageTag, updatedAt
                FROM story_tts_profiles
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE story_tts_profiles")
            db.execSQL("ALTER TABLE story_tts_profiles_new RENAME TO story_tts_profiles")
        }

        private fun normalizeAudioExportDefaults(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE audio_export_jobs_new (
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
            db.execSQL(
                """
                INSERT INTO audio_export_jobs_new (
                    id, storyId, storyTitle, chapterId, destinationUri,
                    outputFormat, mimeType, state, completedSegments,
                    totalSegments, errorMessage, updatedAt
                )
                SELECT
                    id, storyId, storyTitle, chapterId, destinationUri,
                    outputFormat, mimeType, state, completedSegments,
                    totalSegments, errorMessage, updatedAt
                FROM audio_export_jobs
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE audio_export_jobs")
            db.execSQL("ALTER TABLE audio_export_jobs_new RENAME TO audio_export_jobs")
        }

        private fun createDownloadJobsTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS download_jobs (
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
        }


        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_checkpoint ADD COLUMN speechChunkIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playback_checkpoint ADD COLUMN nextChapterUrl TEXT")
                db.execSQL("ALTER TABLE playback_checkpoint ADD COLUMN previousChapterUrl TEXT")
                db.execSQL("ALTER TABLE playback_checkpoint ADD COLUMN sleepTimerEndsAtMillis INTEGER")
                db.execSQL("ALTER TABLE playback_checkpoint ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
            }
        }



        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_queue_chapters (
                        position INTEGER NOT NULL PRIMARY KEY,
                        sourceId TEXT NOT NULL,
                        storyId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        chapterUrl TEXT NOT NULL,
                        nextChapterUrl TEXT,
                        previousChapterUrl TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_playback_queue_chapters_storyId_position " +
                        "ON playback_queue_chapters(storyId, position)",
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS story_ai_profiles (
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
            }
        }



        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN useCustomVoiceCastPrompt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN voiceCastPrompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN voiceCastNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN voiceCastDialogueOnly INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN voiceCastStableNarrator INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN expressiveAdjustment INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN expressionPrompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN expressionSpeedLimitPct INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN expressionPitchLimitPct INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE story_ai_profiles ADD COLUMN expressionVolumeLimitPct INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE chapter_voice_assignments ADD COLUMN speedAdjustPct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE chapter_voice_assignments ADD COLUMN pitchAdjustPct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE chapter_voice_assignments ADD COLUMN volumeAdjustPct REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
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

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        storyId TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        storyTitle TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        paragraphIndex INTEGER NOT NULL,
                        totalParagraphs INTEGER NOT NULL,
                        visitedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reading_history_storyId_chapterId ON reading_history(storyId, chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_visitedAt ON reading_history(visitedAt)")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN peakDbfs REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationTargetLufs REAL NOT NULL DEFAULT -24.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationGainDb REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationPeakLimited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationError TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "nghe-truyen.db",
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
        ).build()
    }
}
