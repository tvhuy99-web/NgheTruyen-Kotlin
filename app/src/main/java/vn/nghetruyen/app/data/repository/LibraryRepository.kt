package vn.nghetruyen.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.DownloadSelectionMode
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.core.model.ImportedBook
import vn.nghetruyen.app.core.model.DEFAULT_GLOBAL_VOICE_PROFILES
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.local.AiUsageDailyEntity
import vn.nghetruyen.app.data.local.AudioExportJobEntity
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.ChapterStorageSnapshot
import vn.nghetruyen.app.data.local.ChapterNoteEntity
import vn.nghetruyen.app.data.local.ChapterDownloadFailureEntity
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.ChapterVoiceAssignmentEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VietPhraseEntity
import vn.nghetruyen.app.data.local.VietPhraseSnapshotEntity
import vn.nghetruyen.app.data.local.VietPhraseDictionaryStateEntity
import vn.nghetruyen.app.data.local.VietPhraseSuggestionEntity
import vn.nghetruyen.app.ai.vietphrase.VietPhraseArchiveCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseAudit
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseImportPlanner
import vn.nghetruyen.app.ai.vietphrase.VietPhrasePersistenceArchiveCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseMatchMode
import vn.nghetruyen.app.ai.vietphrase.VietPhraseRule
import vn.nghetruyen.app.ai.vietphrase.VietPhraseScope
import vn.nghetruyen.app.ai.vietphrase.toEntity
import vn.nghetruyen.app.ai.vietphrase.toVietPhraseRule
import vn.nghetruyen.app.data.local.DownloadJobEntity
import vn.nghetruyen.app.data.local.FollowedStoryEntity
import vn.nghetruyen.app.data.local.OfflineStoryStorage
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.PlaybackCheckpointEntity
import vn.nghetruyen.app.data.local.PlaybackQueueChapterEntity
import vn.nghetruyen.app.data.local.StorageUsage
import vn.nghetruyen.app.data.local.StoryTtsProfileEntity
import vn.nghetruyen.app.data.local.StoryAiProfileEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.local.ReadingProgressEntity
import vn.nghetruyen.app.data.local.ReadingProgressWithChapterTitle
import vn.nghetruyen.app.data.local.ReadingHistoryEntity
import vn.nghetruyen.app.data.local.StoryEntity
import java.util.Locale
import java.util.UUID

data class CacheTrimResult(
    val removedChapters: Int,
    val freedBytes: Long,
    val remainingBytes: Long,
)

class LibraryRepository(private val db: AppDatabase) {
    fun observeReading(): Flow<List<StoryEntity>> = db.storyDao().observeReading()
    fun observeReadingProgressWithChapterTitle(): Flow<List<ReadingProgressWithChapterTitle>> =
        db.progressDao().observeAllWithChapterTitle()
    fun observeReadingHistory(): Flow<List<ReadingHistoryEntity>> = db.readingHistoryDao().observeRecent()
    fun observeOffline(): Flow<List<StoryEntity>> = db.storyDao().observeOffline()
    fun observeBookmarks(): Flow<List<BookmarkEntity>> = db.bookmarkDao().observeAll()
    fun observeNotes(): Flow<List<ChapterNoteEntity>> = db.chapterNoteDao().observeAll()
    fun observeFollowing(): Flow<List<FollowedStoryEntity>> = db.followingDao().observeAll()
    fun observeDownloads(): Flow<List<DownloadJobEntity>> = db.downloadJobDao().observeAll()
    fun observeDownloadFailures(): Flow<List<ChapterDownloadFailureEntity>> = db.chapterDownloadFailureDao().observeAll()
    fun observeChapterStorageSnapshot(): Flow<List<ChapterStorageSnapshot>> = db.chapterDao().observeStorageSnapshot()
    fun observePronunciations(): Flow<List<PronunciationEntity>> = db.pronunciationDao().observeAll()
    fun observeVietPhraseRules(): Flow<List<VietPhraseEntity>> = db.vietPhraseDao().observeAll()
    fun observeVietPhraseSnapshots(): Flow<List<VietPhraseSnapshotEntity>> = db.vietPhraseSnapshotDao().observeAll()
    fun observeVietPhraseDictionaryStates(): Flow<List<VietPhraseDictionaryStateEntity>> = db.vietPhraseDictionaryStateDao().observeAll()
    fun observeVietPhraseSuggestions(): Flow<List<VietPhraseSuggestionEntity>> = db.vietPhraseSuggestionDao().observeAll()
    fun observeStoryAiProfiles(): Flow<List<StoryAiProfileEntity>> = db.storyAiProfileDao().observeAll()
    fun observeSceneMusicTracks(): Flow<List<SceneMusicTrackEntity>> = db.sceneMusicTrackDao().observeAll()
    fun observeStoryTtsProfiles(): Flow<List<StoryTtsProfileEntity>> = db.storyTtsProfileDao().observeAll()
    fun observeVoiceRoles(): Flow<List<VoiceRoleEntity>> = db.voiceRoleDao().observeAll()
    suspend fun listAllVoiceRoles(): List<VoiceRoleEntity> = db.voiceRoleDao().listAll()
    fun observeAudioExports(): Flow<List<AudioExportJobEntity>> = db.audioExportJobDao().observeAll()

    suspend fun savePlaybackCheckpoint(item: PlaybackCheckpointEntity) = db.playbackCheckpointDao().upsert(item)
    suspend fun loadPlaybackCheckpoint(): PlaybackCheckpointEntity? = db.playbackCheckpointDao().get()

    suspend fun replacePlaybackQueue(items: List<PlaybackQueueChapterEntity>) = db.withTransaction {
        db.playbackQueueChapterDao().clear()
        if (items.isNotEmpty()) db.playbackQueueChapterDao().upsertAll(items.sortedBy(PlaybackQueueChapterEntity::position))
    }

    suspend fun loadPlaybackQueue(): List<PlaybackQueueChapterEntity> = db.playbackQueueChapterDao().listAll()

    suspend fun addBookmark(
        storyId: String,
        chapterId: String,
        paragraphIndex: Int,
        label: String,
    ) {
        db.bookmarkDao().upsert(
            BookmarkEntity(
                id = UUID.randomUUID().toString(),
                storyId = storyId,
                chapterId = chapterId,
                paragraphIndex = paragraphIndex.coerceAtLeast(0),
                label = label,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteBookmark(bookmarkId: String) {
        db.bookmarkDao().delete(bookmarkId)
    }

    suspend fun recordReadingHistory(
        sourceId: String,
        storyTitle: String,
        chapter: ChapterSummary,
        paragraphIndex: Int,
        totalParagraphs: Int,
    ) {
        val storedStory = db.storyDao().get(chapter.storyId)
        val safeTotal = totalParagraphs.coerceAtLeast(0)
        val safeParagraph = if (safeTotal > 0) {
            paragraphIndex.coerceIn(0, safeTotal - 1)
        } else 0
        val id = UUID.nameUUIDFromBytes(
            "reading-history\u0000${chapter.storyId}\u0000${chapter.id}".toByteArray(),
        ).toString()
        db.readingHistoryDao().upsert(
            ReadingHistoryEntity(
                id = id,
                storyId = chapter.storyId,



                sourceId = storedStory?.sourceId?.takeIf(String::isNotBlank)
                    ?: sourceId,
                storyTitle = storyTitle.ifBlank { storedStory?.title.orEmpty() }.ifBlank { "Truyện" },
                chapterId = chapter.id,
                chapterTitle = chapter.title.ifBlank { "Chương ${chapter.index + 1}" },
                paragraphIndex = safeParagraph,
                totalParagraphs = safeTotal,
                visitedAt = System.currentTimeMillis(),
            ),
        )
        db.readingHistoryDao().prune(500)
    }

    suspend fun clearReadingHistory() = db.readingHistoryDao().clear()

    suspend fun removeFromReading(storyId: String) = db.progressDao().deleteForStory(storyId)


    suspend fun saveNote(
        storyId: String,
        chapterId: String,
        paragraphIndex: Int,
        text: String,
    ): ChapterNoteEntity {
        val clean = text.trim().take(4_000)
        require(clean.isNotBlank()) { "Ghi chú không được để trống." }
        val now = System.currentTimeMillis()
        val existing = db.chapterNoteDao().get(storyId, chapterId, paragraphIndex.coerceAtLeast(0))
        val note = ChapterNoteEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            storyId = storyId,
            chapterId = chapterId,
            paragraphIndex = paragraphIndex.coerceAtLeast(0),
            text = clean,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        db.chapterNoteDao().upsert(note)
        return note
    }

    suspend fun deleteNote(noteId: String) = db.chapterNoteDao().delete(noteId)

    suspend fun follow(story: StorySummary, latestKnownChapter: String, latestKnownChapterIndex: Int = -1) {
        db.followingDao().upsert(
            FollowedStoryEntity(
                storyId = story.id,
                sourceId = story.sourceId,
                remoteUrl = story.url.ifBlank { story.id },
                title = story.title,
                latestKnownChapter = latestKnownChapter,
                latestKnownChapterIndex = latestKnownChapterIndex,
                newChapterCount = 0,
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unfollow(storyId: String) {
        db.followingDao().delete(storyId)
    }

    suspend fun getFollowing(storyId: String): FollowedStoryEntity? = db.followingDao().get(storyId)

    suspend fun listFollowingForUpdate(limit: Int): List<FollowedStoryEntity> =
        db.followingDao().listForUpdate(limit.coerceAtLeast(1))

    suspend fun updateFollowCheck(
        item: FollowedStoryEntity,
        latestChapter: String,
        latestChapterIndex: Int = -1,
        additionalNewChapters: Int = 0,
    ) {
        db.followingDao().upsert(
            item.copy(
                latestKnownChapter = latestChapter.ifBlank { item.latestKnownChapter },
                latestKnownChapterIndex = if (latestChapterIndex >= 0) latestChapterIndex else item.latestKnownChapterIndex,
                newChapterCount = (item.newChapterCount + additionalNewChapters.coerceAtLeast(0)).coerceAtMost(99_999),
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markFollowingSeen(storyId: String) {
        db.followingDao().markSeen(storyId)
    }

    suspend fun rememberStory(story: StorySummary) {
        val existing = db.storyDao().get(story.id)
        db.storyDao().upsert(
            StoryEntity(
                id = story.id,
                sourceId = story.sourceId,
                title = story.title,
                author = story.author,
                description = story.description,
                coverUrl = story.coverUrl,
                remoteUrl = story.url.ifBlank { story.id },
                isOffline = existing?.isOffline == true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }







    suspend fun cacheChapter(content: ChapterContent) {
        val existingDownloadedAt = db.chapterDao().get(content.chapter.id)?.downloadedAt
        db.chapterDao().upsert(content.toEntity(downloadedAt = existingDownloadedAt))
    }

    suspend fun saveDownloadedChapter(content: ChapterContent) {
        db.chapterDao().upsert(content.toEntity(downloadedAt = System.currentTimeMillis()))
    }

    suspend fun markStoryDownloaded(story: StorySummary) {
        db.storyDao().upsert(
            StoryEntity(
                id = story.id,
                sourceId = story.sourceId,
                title = story.title,
                author = story.author,
                description = story.description,
                coverUrl = story.coverUrl,
                remoteUrl = story.url.ifBlank { story.id },
                isOffline = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateDownloadJob(
        id: String,
        storyId: String,
        sourceId: String,
        state: DownloadState,
        completedChapters: Int,
        totalChapters: Int,
        errorMessage: String? = null,
        selectionMode: DownloadSelectionMode? = null,
        startChapterIndex: Int? = null,
        endChapterIndex: Int? = null,
        wifiOnly: Boolean? = null,
        chargingOnly: Boolean? = null,
        currentChapterIndex: Int? = null,
        currentChapterTitle: String? = null,
        retryCount: Int? = null,
    ) {
        val now = System.currentTimeMillis()
        val existing = db.downloadJobDao().get(id)
        db.downloadJobDao().upsert(
            DownloadJobEntity(
                id = id,
                storyId = storyId,
                sourceId = sourceId,
                selectionMode = selectionMode?.name ?: existing?.selectionMode ?: DownloadSelectionMode.ALL.name,
                startChapterIndex = (startChapterIndex ?: existing?.startChapterIndex ?: 0).coerceAtLeast(0),
                endChapterIndex = (endChapterIndex ?: existing?.endChapterIndex ?: Int.MAX_VALUE)
                    .coerceAtLeast(startChapterIndex ?: existing?.startChapterIndex ?: 0),
                wifiOnly = wifiOnly ?: existing?.wifiOnly ?: false,
                chargingOnly = chargingOnly ?: existing?.chargingOnly ?: false,
                state = state.name,
                completedChapters = completedChapters.coerceAtLeast(0),
                totalChapters = totalChapters.coerceAtLeast(0),
                currentChapterIndex = currentChapterIndex ?: existing?.currentChapterIndex ?: -1,
                currentChapterTitle = currentChapterTitle ?: existing?.currentChapterTitle.orEmpty(),
                retryCount = (retryCount ?: existing?.retryCount ?: 0).coerceAtLeast(0),
                errorMessage = errorMessage,
                requestedAt = existing?.requestedAt?.takeIf { it > 0L } ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun recordDownloadFailure(
        jobId: String,
        storyId: String,
        sourceId: String,
        chapterIndex: Int,
        chapterTitle: String,
        errorMessage: String,
        retryCount: Int,
    ) {
        db.chapterDownloadFailureDao().upsert(
            ChapterDownloadFailureEntity(
                id = "$jobId:${chapterIndex.coerceAtLeast(0)}",
                jobId = jobId,
                storyId = storyId,
                sourceId = sourceId,
                chapterIndex = chapterIndex.coerceAtLeast(0),
                chapterTitle = chapterTitle.take(500),
                errorMessage = errorMessage.take(2_000),
                retryCount = retryCount.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearDownloadFailure(jobId: String, chapterIndex: Int) =
        db.chapterDownloadFailureDao().delete(jobId, chapterIndex)

    suspend fun clearDownloadFailures(jobId: String) = db.chapterDownloadFailureDao().deleteForJob(jobId)

    suspend fun getDownloadJob(jobId: String): DownloadJobEntity? = db.downloadJobDao().get(jobId)

    suspend fun getProgress(storyId: String): ReadingProgressEntity? = db.progressDao().get(storyId)

    suspend fun saveProgress(storyId: String, chapterId: String, paragraphIndex: Int, totalParagraphs: Int = 0) {
        val previous = db.progressDao().get(storyId)
        db.progressDao().save(
            ReadingProgressEntity(
                storyId = storyId,
                chapterId = chapterId,
                paragraphIndex = paragraphIndex.coerceAtLeast(0),
                totalParagraphs = totalParagraphs.takeIf { it > 0 } ?: previous?.totalParagraphs ?: 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }


    suspend fun saveReadingPosition(
        sourceId: String,
        storyTitle: String,
        chapter: ChapterSummary,
        paragraphIndex: Int,
        totalParagraphs: Int,
    ) = db.withTransaction {
        saveProgress(
            storyId = chapter.storyId,
            chapterId = chapter.id,
            paragraphIndex = paragraphIndex,
            totalParagraphs = totalParagraphs,
        )
        recordReadingHistory(
            sourceId = sourceId,
            storyTitle = storyTitle,
            chapter = chapter,
            paragraphIndex = paragraphIndex,
            totalParagraphs = totalParagraphs,
        )
    }





    suspend fun listReadableOfflineChapters(storyId: String, importedBook: Boolean): List<ChapterEntity> =
        db.chapterDao().listExportableForStory(storyId)
            .filter { importedBook || it.downloadedAt != null }

    suspend fun removeOfflineContent(storyId: String) = db.withTransaction {
        val story = db.storyDao().get(storyId) ?: return@withTransaction
        db.downloadJobDao().deleteForStory(storyId)
        db.chapterDownloadFailureDao().deleteForStory(storyId)
        if (story.sourceId == "offline") {
            db.bookmarkDao().deleteForStory(storyId)
            db.chapterNoteDao().deleteForStory(storyId)
            db.progressDao().deleteForStory(storyId)
            db.followingDao().delete(storyId)
            db.storyTtsProfileDao().delete(storyId)
            db.voiceRoleDao().deleteForStory(storyId)
            db.chapterTransformDao().deleteForStory(storyId)
            db.storyAiProfileDao().delete(storyId)
            db.chapterVoiceAssignmentDao().deleteForStory(storyId)
            db.sceneMusicCueDao().deleteForStory(storyId)
            db.chapterDao().deleteForStory(storyId)
            db.storyDao().delete(storyId)
        } else {
            db.chapterDao().clearContentForStory(storyId)
            db.storyDao().setOffline(storyId, false, System.currentTimeMillis())
        }
    }

    suspend fun clearTransientReaderCache() {
        db.chapterDao().clearTransientCache()
    }

    suspend fun trimTransientReaderCache(
        limitBytes: Long,
        protectedChapterIds: Set<String> = emptySet(),
    ): CacheTrimResult = db.withTransaction {
        val limit = limitBytes.coerceAtLeast(0L)
        val entries = db.chapterDao().listTransientCacheEntries()
        var remaining = entries.sumOf { it.bytes.coerceAtLeast(0L) }
        var removed = 0
        var freed = 0L
        if (remaining <= limit) return@withTransaction CacheTrimResult(0, 0, remaining)
        for (entry in entries) {
            if (entry.chapterId in protectedChapterIds) continue
            db.chapterDao().clearContent(entry.chapterId)
            removed += 1
            val bytes = entry.bytes.coerceAtLeast(0L)
            freed += bytes
            remaining = (remaining - bytes).coerceAtLeast(0L)
            if (remaining <= limit) break
        }
        CacheTrimResult(removed, freed, remaining)
    }

    suspend fun savePronunciation(original: String, replacement: String): Result<Long> = runCatching {
        val cleanOriginal = original.trim()
        val cleanReplacement = replacement.trim()
        require(cleanOriginal.isNotEmpty()) { "Từ gốc không được để trống." }
        require(cleanReplacement.isNotEmpty()) { "Cách đọc không được để trống." }
        require(cleanOriginal.length <= 120) { "Từ gốc quá dài." }
        require(cleanReplacement.length <= 240) { "Cách đọc quá dài." }
        val now = System.currentTimeMillis()
        db.pronunciationDao().upsert(
            PronunciationEntity(
                original = cleanOriginal,
                replacement = cleanReplacement,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updatePronunciation(id: Long, original: String, replacement: String): Result<Unit> = runCatching {
        val current = requireNotNull(db.pronunciationDao().get(id)) { "Không tìm thấy cách đọc." }
        val cleanOriginal = original.trim()
        val cleanReplacement = replacement.trim()
        require(cleanOriginal.isNotEmpty() && cleanReplacement.isNotEmpty()) { "Hãy nhập đầy đủ từ gốc và cách đọc." }
        require(cleanOriginal.length <= 120) { "Từ gốc quá dài." }
        require(cleanReplacement.length <= 240) { "Cách đọc quá dài." }
        db.pronunciationDao().upsert(
            current.copy(
                original = cleanOriginal,
                replacement = cleanReplacement,
                enabled = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Unit
    }

    suspend fun setPronunciationEnabled(id: Long, enabled: Boolean) {
        db.pronunciationDao().setEnabled(id, enabled, System.currentTimeMillis())
    }

    suspend fun deletePronunciation(id: Long) {
        db.pronunciationDao().delete(id)
    }

    suspend fun listEnabledPronunciations(): List<PronunciationEntity> =
        db.pronunciationDao().listEnabled()

    suspend fun listDownloadedChapterIds(storyId: String): Set<String> =
        db.chapterDao().listDownloadedIds(storyId).toHashSet()

    suspend fun saveStoryTtsProfile(
        storyId: String,
        rate: Float,
        pitch: Float,
        volume: Float,
        enginePackage: String?,
        voiceName: String?,
        languageTag: String,
    ) {
        require(storyId.isNotBlank()) { "Thiếu mã truyện." }
        db.storyTtsProfileDao().upsert(
            StoryTtsProfileEntity(
                storyId = storyId,
                rate = rate.coerceIn(0.25f, 3.0f),
                pitch = pitch.coerceIn(0.5f, 2.0f),
                volume = volume.coerceIn(0f, 2.0f),
                enginePackage = enginePackage?.takeIf(String::isNotBlank),
                voiceName = voiceName?.takeIf(String::isNotBlank),
                languageTag = languageTag.ifBlank { "vi-VN" },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun getStoryTtsProfile(storyId: String): StoryTtsProfileEntity? =
        db.storyTtsProfileDao().get(storyId)

    suspend fun deleteStoryTtsProfile(storyId: String) {
        db.storyTtsProfileDao().delete(storyId)
    }


    suspend fun listVoiceRoles(storyId: String): List<VoiceRoleEntity> =
        db.voiceRoleDao().listForStory(storyId)

    suspend fun listEffectiveVoiceRoles(storyId: String, includeGlobal: Boolean = true): List<VoiceRoleEntity> {
        val local = db.voiceRoleDao().listForStory(storyId).filter(VoiceRoleEntity::enabled)
        if (!includeGlobal || storyId == GLOBAL_VOICE_PROFILE_STORY_ID) return local
        val global = db.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)
        if (local.isEmpty()) return global
        val overridden = local.map { it.roleName.trim().lowercase(Locale.ROOT) }.toSet()
        return local + global.filter { it.roleName.trim().lowercase(Locale.ROOT) !in overridden }
    }

    suspend fun ensureGlobalVoiceProfiles() {
        if (db.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID).isEmpty()) restoreGlobalVoiceProfiles()
    }

    suspend fun restoreGlobalVoiceProfiles(): List<VoiceRoleEntity> = db.withTransaction {
        val existing = db.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID)
        val byName = existing.associateBy { it.roleName.trim().lowercase(Locale.ROOT) }
        val defaultNames = DEFAULT_GLOBAL_VOICE_PROFILES.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val now = System.currentTimeMillis()
        val defaults = DEFAULT_GLOBAL_VOICE_PROFILES.map { seed ->
            val old = byName[seed.name.lowercase(Locale.ROOT)]
            VoiceRoleEntity(
                id = old?.id ?: UUID.nameUUIDFromBytes("$GLOBAL_VOICE_PROFILE_STORY_ID\u0000${seed.name.lowercase(Locale.ROOT)}".toByteArray()).toString(),
                storyId = GLOBAL_VOICE_PROFILE_STORY_ID,
                roleName = seed.name,
                aliasesCsv = old?.aliasesCsv.orEmpty(),
                description = seed.description,
                enginePackage = old?.enginePackage,
                voiceName = old?.voiceName,
                languageTag = old?.languageTag?.ifBlank { "vi-VN" } ?: "vi-VN",
                rate = old?.rate ?: 1f,
                pitch = old?.pitch ?: 1f,
                volume = old?.volume ?: 1f,
                expression = old?.expression ?: "NEUTRAL",
                expressionStrength = old?.expressionStrength ?: 0.5f,
                sonicSpeed = old?.sonicSpeed ?: 1f,
                sonicPitch = old?.sonicPitch ?: 1f,
                isNarrator = seed.narrator,
                enabled = true,
                updatedAt = now,
            )
        }
        val custom = existing.filter { it.roleName.trim().lowercase(Locale.ROOT) !in defaultNames }.take((10 - defaults.size).coerceAtLeast(0))
        db.voiceRoleDao().deleteForStory(GLOBAL_VOICE_PROFILE_STORY_ID)
        db.voiceRoleDao().upsertAll(defaults + custom)
        defaults + custom
    }

    suspend fun saveVoiceRole(
        storyId: String,
        roleName: String,
        aliasesCsv: String,
        voiceName: String?,
        languageTag: String,
        rate: Float,
        pitch: Float,
        volume: Float,
        isNarrator: Boolean,
        enginePackage: String? = null,
        expression: String = "NEUTRAL",
        expressionStrength: Float = 0.5f,
        sonicSpeed: Float = 1.0f,
        sonicPitch: Float = 1.0f,
        enabled: Boolean = true,
        description: String = "",
    ): Result<String> = runCatching {
        require(storyId.isNotBlank()) { "Thiếu mã truyện." }
        val cleanName = roleName.trim().take(80)
        require(cleanName.isNotBlank()) { "Tên vai không được để trống." }
        if (isNarrator) db.voiceRoleDao().deleteNarrator(storyId)
        val id = UUID.nameUUIDFromBytes("$storyId\u0000${cleanName.lowercase()}".toByteArray()).toString()
        db.voiceRoleDao().upsert(
            VoiceRoleEntity(
                id = id,
                storyId = storyId,
                roleName = cleanName,
                aliasesCsv = aliasesCsv.trim().take(500),
                description = description.trim().take(1000),
                enginePackage = enginePackage?.takeIf(String::isNotBlank),
                voiceName = voiceName?.takeIf(String::isNotBlank),
                languageTag = languageTag.ifBlank { "vi-VN" },
                rate = rate.coerceIn(0.25f, 3.0f),
                pitch = pitch.coerceIn(0.5f, 2.0f),
                volume = volume.coerceIn(0f, 2.0f),
                expression = expression.trim().uppercase(Locale.ROOT).takeIf { it in setOf("NEUTRAL", "CALM", "WARM", "SAD", "TENSE", "ANGRY", "EXCITED", "WHISPER") } ?: "NEUTRAL",
                expressionStrength = expressionStrength.coerceIn(0f, 1f),
                sonicSpeed = sonicSpeed.coerceIn(0.25f, 3.0f),
                sonicPitch = sonicPitch.coerceIn(0.5f, 2.0f),
                isNarrator = isNarrator,
                enabled = enabled,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        id
    }

    suspend fun setVoiceRoleEnabled(id: String, enabled: Boolean) {
        db.voiceRoleDao().setEnabled(id, enabled, System.currentTimeMillis())
    }

    suspend fun deleteVoiceRole(id: String) {
        db.voiceRoleDao().delete(id)
    }

    suspend fun createAudioExportJob(job: AudioExportJobEntity) {
        db.audioExportJobDao().upsert(job)
        db.audioExportJobDao().pruneFinished(keep = 30)
    }

    suspend fun getAudioExportJob(jobId: String): AudioExportJobEntity? =
        db.audioExportJobDao().get(jobId)

    suspend fun updateAudioExportJob(job: AudioExportJobEntity) {
        db.audioExportJobDao().upsert(job)
    }

    suspend fun updateAudioExportProgress(
        jobId: String,
        completed: Int,
        total: Int,
        state: DownloadState,
        errorMessage: String?,
    ) {
        val current = db.audioExportJobDao().get(jobId) ?: return
        db.audioExportJobDao().upsert(
            current.copy(
                state = state.name,
                completedSegments = completed.coerceAtLeast(0),
                totalSegments = total.coerceAtLeast(0),
                errorMessage = errorMessage?.take(500),
                stage = when (state) {
                    DownloadState.QUEUED -> "QUEUED"
                    DownloadState.RUNNING -> current.stage
                    DownloadState.COMPLETED -> "COMPLETED"
                    DownloadState.FAILED -> "FAILED"
                    DownloadState.CANCELLED -> "CANCELLED"
                    DownloadState.PAUSED -> "PAUSED"
                },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun listExportableChapters(storyId: String): List<ChapterEntity> =
        db.chapterDao().listExportableForStory(storyId)

    suspend fun listExportableChapters(storyId: String, startIndex: Int, endIndex: Int): List<ChapterEntity> =
        db.chapterDao().listExportableForStory(storyId)
            .filter { it.chapterIndex in startIndex..endIndex }

    suspend fun getStory(storyId: String): StoryEntity? = db.storyDao().get(storyId)

    suspend fun getChapter(chapterId: String): ChapterEntity? = db.chapterDao().get(chapterId)

    suspend fun loadCachedChapter(chapterId: String): ChapterContent? =
        db.chapterDao().get(chapterId)?.toContentWithNeighbors()

    suspend fun loadCachedChapterByUrl(storyId: String, remoteUrl: String): ChapterContent? =
        db.chapterDao().getByRemoteUrl(storyId, remoteUrl)?.toContentWithNeighbors()

    suspend fun loadNextCachedChapter(storyId: String, chapterIndex: Int): ChapterContent? =
        db.chapterDao().getNextAfter(storyId, chapterIndex)?.toContentWithNeighbors()

    suspend fun loadPreviousCachedChapter(storyId: String, chapterIndex: Int): ChapterContent? =
        db.chapterDao().getPreviousBefore(storyId, chapterIndex)?.toContentWithNeighbors()

    suspend fun importBook(book: ImportedBook): StorySummary = db.withTransaction {
        val storyId = "offline:${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val story = StoryEntity(
            id = storyId,
            sourceId = "offline",
            title = book.title,
            author = book.author,
            description = "Truyện nhập từ tệp trên thiết bị",
            coverUrl = null,
            remoteUrl = storyId,
            isOffline = true,
            updatedAt = now,
        )
        db.storyDao().upsert(story)
        db.chapterDao().upsertAll(
            book.chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    id = "$storyId:$index",
                    storyId = storyId,
                    chapterIndex = index,
                    title = chapter.title,
                    remoteUrl = "$storyId:$index",
                    content = chapter.paragraphs.joinToString(PARAGRAPH_SEPARATOR),
                    downloadedAt = now,
                )
            },
        )
        StorySummary(
            id = story.id,
            sourceId = story.sourceId,
            title = story.title,
            author = story.author,
            description = story.description,
            url = story.remoteUrl,
        )
    }

    private fun ChapterContent.toEntity(downloadedAt: Long? = null) = ChapterEntity(
        id = chapter.id,
        storyId = chapter.storyId,
        chapterIndex = chapter.index,
        title = chapter.title,
        remoteUrl = chapter.url.ifBlank { chapter.id },
        content = paragraphs.joinToString(PARAGRAPH_SEPARATOR),
        downloadedAt = downloadedAt,
    )

    private suspend fun ChapterEntity.toContentWithNeighbors(): ChapterContent? {
        val content = toContent() ?: return null
        val previous = db.chapterDao().getAt(storyId, chapterIndex - 1)
        val next = db.chapterDao().getAt(storyId, chapterIndex + 1)
        return content.copy(
            previousChapterUrl = previous?.remoteUrl?.takeIf(String::isNotBlank),
            nextChapterUrl = next?.remoteUrl?.takeIf(String::isNotBlank),
        )
    }

    private fun ChapterEntity.toContent(): ChapterContent? {
        val paragraphs = content.orEmpty()
            .split(PARAGRAPH_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotBlank)
        if (paragraphs.isEmpty()) return null
        return ChapterContent(
            chapter = ChapterSummary(
                id = id,
                storyId = storyId,
                index = chapterIndex,
                title = title,
                url = remoteUrl,
            ),
            paragraphs = paragraphs,
        )
    }

    companion object {
        private const val PARAGRAPH_SEPARATOR = "\n\u0000\n"
    }

    suspend fun saveVietPhrase(
        source: String,
        target: String,
        priority: Int = 0,
        kind: VietPhraseDictionaryKind = VietPhraseDictionaryKind.VIET_PHRASE,
        scope: VietPhraseScope = VietPhraseScope.GLOBAL,
        storyId: String? = null,
        matchMode: VietPhraseMatchMode = VietPhraseMatchMode.LITERAL,
        ignoreCase: Boolean = false,
    ): Result<Long> = runCatching {
        val cleanSource = source.trim()
        val cleanTarget = target.trim()
        require(cleanSource.isNotBlank()) { "Cụm nguồn không được để trống." }
        require(cleanTarget.isNotBlank()) { "Cụm thay thế không được để trống." }
        require(cleanSource.length <= 2_000 && cleanTarget.length <= 4_000) { "Quy tắc VietPhrase quá dài." }
        require(scope != VietPhraseScope.STORY || !storyId.isNullOrBlank()) { "Quy tắc theo truyện phải có storyId." }
        val effectiveMatchMode = if (matchMode == VietPhraseMatchMode.LITERAL && Regex("\\{\\d+}").containsMatchIn(cleanSource)) {
            VietPhraseMatchMode.TEMPLATE
        } else matchMode
        val now = System.currentTimeMillis()
        db.vietPhraseDao().upsert(
            VietPhraseEntity(
                source = cleanSource,
                target = cleanTarget,
                priority = priority.coerceIn(-999, 999),
                kind = kind.name,
                scope = scope.name,
                storyId = storyId.orEmpty(),
                matchMode = effectiveMatchMode.name,
                ignoreCase = ignoreCase,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateVietPhrase(
        id: Long,
        source: String,
        target: String,
        priority: Int,
        kind: VietPhraseDictionaryKind,
        scope: VietPhraseScope,
        storyId: String?,
        ignoreCase: Boolean,
    ): Result<Unit> = runCatching {
        val cleanSource = source.trim()
        val cleanTarget = target.trim()
        require(cleanSource.isNotBlank()) { "Cụm nguồn không được để trống." }
        require(cleanTarget.isNotBlank()) { "Cụm thay thế không được để trống." }
        require(cleanSource.length <= 2_000 && cleanTarget.length <= 4_000) { "Quy tắc VietPhrase quá dài." }
        require(scope != VietPhraseScope.STORY || !storyId.isNullOrBlank()) { "Quy tắc theo truyện phải có storyId." }
        val dao = db.vietPhraseDao()
        val existing = dao.get(id) ?: error("Quy tắc VietPhrase không còn tồn tại.")
        val matchMode = when {
            existing.matchMode == "REGEX" -> "REGEX"
            Regex("\\{\\d+}").containsMatchIn(cleanSource) -> VietPhraseMatchMode.TEMPLATE.name
            else -> VietPhraseMatchMode.LITERAL.name
        }
        check(
            dao.update(
                existing.copy(
                    source = cleanSource,
                    target = cleanTarget,
                    priority = priority.coerceIn(-999, 999),
                    kind = kind.name,
                    scope = scope.name,
                    storyId = storyId.orEmpty(),
                    matchMode = matchMode,
                    ignoreCase = ignoreCase,
                    updatedAt = System.currentTimeMillis(),
                ),
            ) == 1,
        ) { "Không cập nhật được quy tắc VietPhrase." }
    }

    suspend fun importVietPhrase(items: List<VietPhraseEntity>) = db.withTransaction {
        items.asSequence()
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .take(1_000_000)
            .chunked(1_000)
            .forEach { db.vietPhraseDao().upsertAll(it) }
    }

    suspend fun listAllVietPhrase(): List<VietPhraseEntity> = db.vietPhraseDao().listAll()
    suspend fun listAllVietPhraseRules(): List<VietPhraseRule> = listAllVietPhrase().map(VietPhraseEntity::toVietPhraseRule)
    suspend fun listVietPhraseDictionaryStates(): List<VietPhrasePersistenceArchiveCodec.DictionaryState> =
        db.vietPhraseDictionaryStateDao().listAll().map { it.toSnapshotState() }

    suspend fun setVietPhraseEnabled(id: Long, enabled: Boolean) = db.vietPhraseDao().setEnabled(id, enabled, System.currentTimeMillis())
    suspend fun deleteVietPhrase(id: Long) = db.vietPhraseDao().delete(id)
    suspend fun listEnabledVietPhrase(): List<VietPhraseEntity> = db.vietPhraseDao().listEnabled()
    suspend fun listEnabledVietPhrase(storyId: String?): List<VietPhraseRule> {
        val states = db.vietPhraseDictionaryStateDao().listAll()
        val disabled = states.asSequence().filterNot { it.enabled }
            .map { dictionaryKey(it.kind, it.scope, it.storyId) }.toHashSet()
        return db.vietPhraseDao().listEnabledForStory(storyId)
            .asSequence()
            .filterNot { dictionaryKey(it.kind, it.scope, it.storyId) in disabled }
            .map(VietPhraseEntity::toVietPhraseRule)
            .toList()
    }

    suspend fun setVietPhraseDictionaryEnabled(id: String, enabled: Boolean) =
        db.vietPhraseDictionaryStateDao().setEnabled(id, enabled)

    suspend fun deleteVietPhraseDictionary(kind: VietPhraseDictionaryKind) = db.withTransaction {
        db.vietPhraseDao().deleteDictionary(kind.name, VietPhraseScope.GLOBAL.name, null)
        db.vietPhraseDictionaryStateDao().deleteKinds(listOf(kind.name))
    }

    suspend fun clearAllVietPhraseDictionaries() = db.withTransaction {
        db.vietPhraseDao().deleteAll()
        db.vietPhraseDictionaryStateDao().deleteAll()
    }

    suspend fun previewVietPhraseImport(
        incoming: List<VietPhraseRule>,
        replaceKinds: Set<VietPhraseDictionaryKind>,
    ): VietPhraseImportPlanner.Plan = VietPhraseImportPlanner.plan(listAllVietPhraseRules(), incoming, replaceKinds)

    suspend fun commitVietPhraseImport(
        plan: VietPhraseImportPlanner.Plan,
        sourceName: String,
        sourceFormat: String,
        importedStates: List<VietPhrasePersistenceArchiveCodec.DictionaryState> = emptyList(),
        label: String = "Trước khi nhập $sourceName",
    ): VietPhraseSnapshotEntity = db.withTransaction {
        val committed = VietPhraseImportPlanner.commit(plan)
        val now = System.currentTimeMillis()
        val beforeStates = db.vietPhraseDictionaryStateDao().listAll().map { it.toSnapshotState() }
        val payload = VietPhrasePersistenceArchiveCodec.encode(plan.beforeSnapshot.rules, beforeStates)
        val snapshot = VietPhraseSnapshotEntity(
            id = plan.beforeSnapshot.id,
            label = label.take(200),
            checksum = VietPhrasePersistenceArchiveCodec.checksumBytes(payload),
            ruleCount = plan.beforeSnapshot.rules.size,
            payload = payload,
            createdAt = now,
        )
        db.vietPhraseSnapshotDao().upsert(snapshot)
        db.vietPhraseDao().deleteAll()
        committed.chunked(1_000).forEach { chunk -> db.vietPhraseDao().upsertAll(chunk.map { it.toEntity(now) }) }
        val replacedKindNames = plan.replacedKinds.map { it.name }
        if (replacedKindNames.isNotEmpty()) db.vietPhraseDictionaryStateDao().deleteKinds(replacedKindNames)
        plan.replacedKinds.forEach { kind ->
            val archivedStates = importedStates.filter { it.kind == kind }
            if (archivedStates.isNotEmpty()) {
                db.vietPhraseDictionaryStateDao().upsertAll(archivedStates.map { it.toEntity() })
            } else {
                val scoped = committed.filter { it.kind == kind }
                scoped.groupBy { it.scope to it.storyId }.forEach { (scopeKey, rules) ->
                    val (scope, storyId) = scopeKey
                    val stateId = listOf(kind.name, scope.name, storyId.orEmpty()).joinToString(":")
                    val archive = VietPhraseArchiveCodec.encode(rules)
                    db.vietPhraseDictionaryStateDao().upsert(
                        VietPhraseDictionaryStateEntity(
                            id = stateId,
                            kind = kind.name,
                            scope = scope.name,
                            storyId = storyId.orEmpty(),
                            enabled = true,
                            sourceName = sourceName.take(500),
                            sourceFormat = sourceFormat.take(100),
                            checksum = VietPhraseArchiveCodec.checksumBytes(archive),
                            entryCount = rules.size,
                            revision = now,
                            importedAt = now,
                        ),
                    )
                }
            }
        }
        db.vietPhraseSnapshotDao().prune(20)
        snapshot
    }

    suspend fun rollbackVietPhraseSnapshot(snapshotId: String): Result<Int> = runCatching {
        db.withTransaction {
            val snapshot = requireNotNull(db.vietPhraseSnapshotDao().get(snapshotId)) { "Không tìm thấy snapshot VietPhrase." }
            require(VietPhrasePersistenceArchiveCodec.checksumBytes(snapshot.payload) == snapshot.checksum) { "Snapshot VietPhrase không còn toàn vẹn." }
            val archive = VietPhrasePersistenceArchiveCodec.decodeCompatible(snapshot.payload)
            require(archive.rules.size == snapshot.ruleCount) { "Số quy tắc snapshot không khớp." }
            db.vietPhraseDao().deleteAll()
            val now = System.currentTimeMillis()
            archive.rules.chunked(1_000).forEach { chunk -> db.vietPhraseDao().upsertAll(chunk.map { it.toEntity(now) }) }
            if (!archive.legacyRuleOnly) {
                db.vietPhraseDictionaryStateDao().deleteAll()
                if (archive.dictionaryStates.isNotEmpty()) {
                    db.vietPhraseDictionaryStateDao().upsertAll(archive.dictionaryStates.map { it.toEntity() })
                }
            }
            archive.rules.size
        }
    }

    suspend fun getStoryAiProfile(storyId: String): StoryAiProfileEntity? =
        db.storyAiProfileDao().get(storyId)

    suspend fun saveStoryAiProfile(profile: StoryAiProfileEntity): Result<Unit> = runCatching {
        require(profile.storyId.isNotBlank()) { "Thiếu mã truyện." }
        require(profile.mode in setOf("INHERIT", "TRANSLATE", "IMPROVE")) { "Chế độ AI theo truyện không hợp lệ." }
        require(profile.provider in setOf("OPENAI_COMPATIBLE", "GEMINI")) { "Nhà cung cấp AI không hợp lệ." }
        require(profile.temperature == -1f || profile.temperature in 0f..1f) { "Temperature không hợp lệ." }
        val translationPrompt = profile.translationPrompt.trim().take(8_000)
        val improvePrompt = profile.improvePrompt.trim().take(12_000)
        if (profile.useCustomPrompts && translationPrompt.isNotBlank()) {
            require("{{CHAPTER_TEXT}}" in translationPrompt) { "Prompt dịch riêng phải có {{CHAPTER_TEXT}}." }
        }
        if (profile.useCustomPrompts && improvePrompt.isNotBlank()) {
            require("{{SOURCE_TEXT}}" in improvePrompt && "{{VIETPHRASE_TEXT}}" in improvePrompt) {
                "Prompt cải thiện phải có {{SOURCE_TEXT}} và {{VIETPHRASE_TEXT}}."
            }
        }
        val voiceCastPrompt = profile.voiceCastPrompt.trim().take(16_000)
        val voiceCastNote = profile.voiceCastNote.trim().take(4_000)
        val expressionPrompt = profile.expressionPrompt.trim().take(8_000)
        if (profile.useCustomVoiceCastPrompt && voiceCastPrompt.isNotBlank()) {
            require("{{CHAPTER_TEXT}}" in voiceCastPrompt) { "Prompt phân vai riêng phải có {{CHAPTER_TEXT}}." }
        }
        val normalizedModel = profile.model.trim().take(200).ifBlank {
            if (profile.overrideProvider && profile.provider == "GEMINI") "gemini-3.6-flash" else ""
        }
        db.storyAiProfileDao().upsert(
            profile.copy(
                endpoint = profile.endpoint.trim().take(500),
                model = normalizedModel,
                translationPrompt = translationPrompt,
                improvePrompt = improvePrompt,
                voiceCastPrompt = voiceCastPrompt,
                voiceCastNote = voiceCastNote,
                expressionPrompt = expressionPrompt,
                expressionSpeedLimitPct = profile.expressionSpeedLimitPct.coerceIn(0, 100),
                expressionPitchLimitPct = profile.expressionPitchLimitPct.coerceIn(0, 100),
                expressionVolumeLimitPct = profile.expressionVolumeLimitPct.coerceIn(0, 100),
                autoRunOnOpen = profile.autoRunOnOpen && profile.mode != "INHERIT",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteStoryAiProfile(storyId: String) = db.storyAiProfileDao().delete(storyId)

    suspend fun saveVietPhraseSuggestion(
        source: String,
        target: String,
        reason: String,
        contextText: String,
        storyId: String?,
    ): Result<String> = runCatching {
        val cleanSource = source.trim().take(2_000)
        val cleanTarget = target.trim().take(4_000)
        require(cleanSource.isNotBlank() && cleanTarget.isNotBlank()) { "Suggestion VietPhrase không hợp lệ." }
        val id = UUID.nameUUIDFromBytes("$storyId\u0000$cleanSource\u0000$cleanTarget".toByteArray()).toString()
        db.vietPhraseSuggestionDao().upsert(
            VietPhraseSuggestionEntity(
                id = id,
                source = cleanSource,
                proposedTarget = cleanTarget,
                editedTarget = cleanTarget,
                reason = reason.trim().take(2_000),
                contextText = contextText.trim().take(8_000),
                storyId = storyId,
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
                reviewedAt = null,
            ),
        )
        id
    }

    suspend fun acceptVietPhraseSuggestion(id: String, editedTarget: String): Result<Long> = runCatching {
        val suggestion = db.vietPhraseSuggestionDao().listPending().firstOrNull { it.id == id }
            ?: error("Suggestion không còn ở trạng thái chờ duyệt.")
        val target = editedTarget.trim().ifBlank { suggestion.proposedTarget }
        val result = saveVietPhrase(
            source = suggestion.source,
            target = target,
            priority = 0,
            kind = VietPhraseDictionaryKind.AI_REPLACE,
            scope = if (suggestion.storyId.isNullOrBlank()) VietPhraseScope.GLOBAL else VietPhraseScope.STORY,
            storyId = suggestion.storyId,
        ).getOrThrow()
        db.vietPhraseSuggestionDao().review(id, target, "ACCEPTED", System.currentTimeMillis())
        result
    }

    suspend fun rejectVietPhraseSuggestion(id: String) {
        val suggestion = db.vietPhraseSuggestionDao().listPending().firstOrNull { it.id == id } ?: return
        db.vietPhraseSuggestionDao().review(id, suggestion.editedTarget, "REJECTED", System.currentTimeMillis())
    }

    private fun dictionaryKey(kind: String, scope: String, storyId: String?): String =
        listOf(kind, scope, storyId.orEmpty()).joinToString("\u0000")

    private fun VietPhraseDictionaryStateEntity.toSnapshotState() = VietPhrasePersistenceArchiveCodec.DictionaryState(
        id = id,
        kind = runCatching { VietPhraseDictionaryKind.valueOf(kind) }.getOrDefault(VietPhraseDictionaryKind.VIET_PHRASE),
        scope = runCatching { VietPhraseScope.valueOf(scope) }.getOrDefault(VietPhraseScope.GLOBAL),
        storyId = storyId.ifBlank { null },
        enabled = enabled,
        sourceName = sourceName,
        sourceFormat = sourceFormat,
        checksum = checksum,
        entryCount = entryCount,
        revision = revision,
        importedAt = importedAt,
    )

    private fun VietPhrasePersistenceArchiveCodec.DictionaryState.toEntity() = VietPhraseDictionaryStateEntity(
        id = id,
        kind = kind.name,
        scope = scope.name,
        storyId = storyId.orEmpty(),
        enabled = enabled,
        sourceName = sourceName,
        sourceFormat = sourceFormat,
        checksum = checksum,
        entryCount = entryCount,
        revision = revision,
        importedAt = importedAt,
    )

    suspend fun saveChapterTransform(item: ChapterTransformEntity) = db.chapterTransformDao().upsert(item)
    suspend fun getChapterTransform(chapterId: String, kind: String): ChapterTransformEntity? = db.chapterTransformDao().get(chapterId, kind)
    suspend fun deleteChapterTransform(chapterId: String, kind: String) = db.chapterTransformDao().delete(chapterId, kind)

    suspend fun replaceVoiceAssignments(storyId: String, chapterId: String, assignments: List<ChapterVoiceAssignmentEntity>) = db.withTransaction {
        db.chapterVoiceAssignmentDao().deleteForChapter(chapterId)
        if (assignments.isNotEmpty()) db.chapterVoiceAssignmentDao().upsertAll(assignments.filter { it.storyId == storyId && it.chapterId == chapterId })
    }

    suspend fun listVoiceAssignments(chapterId: String): List<ChapterVoiceAssignmentEntity> =
        db.chapterVoiceAssignmentDao().listForChapter(chapterId)

    suspend fun saveSceneMusicTrack(title: String, uri: String, tagsCsv: String): Result<String> = runCatching {
        require(uri.isNotBlank()) { "Thiếu URI tệp nhạc." }
        val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        db.sceneMusicTrackDao().upsert(
            SceneMusicTrackEntity(
                id = id,
                title = title.trim().ifBlank { "Nhạc cảnh" }.take(120),
                uri = uri.trim().take(2000),
                tagsCsv = tagsCsv.trim().take(500),
                volume = 1.0f,
                enabled = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        id
    }

    suspend fun updateSceneMusicTrackMetadata(id: String, title: String, tagsCsv: String): Result<Unit> = runCatching {
        val current = db.sceneMusicTrackDao().get(id) ?: error("Không tìm thấy tệp nhạc cảnh.")
        db.sceneMusicTrackDao().upsert(
            current.copy(
                title = title.trim().ifBlank { current.title }.take(120),

                tagsCsv = tagsCsv.trim().take(300),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun setSceneMusicTrackEnabled(id: String, enabled: Boolean) =
        db.sceneMusicTrackDao().setEnabled(id, enabled, System.currentTimeMillis())
    suspend fun deleteSceneMusicTrack(id: String) = db.sceneMusicTrackDao().delete(id)
    suspend fun listEnabledSceneMusicTracks(): List<SceneMusicTrackEntity> = db.sceneMusicTrackDao().listEnabled()
    suspend fun getSceneMusicTrack(id: String): SceneMusicTrackEntity? = db.sceneMusicTrackDao().get(id)

    suspend fun updateSceneMusicNormalization(
        id: String,
        loudnessLufs: Float,
        peakDbfs: Float,
        targetLufs: Float,
        gainDb: Float,
        peakLimited: Boolean,
        version: Int,
    ) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(
                loudnessLufsEstimate = loudnessLufs.coerceIn(-120f, 12f),
                peakDbfs = peakDbfs.coerceIn(-120f, 12f),
                normalizationTargetLufs = targetLufs.coerceIn(-36f, -18f),
                normalizationGainDb = gainDb.coerceIn(-36f, 12f),
                normalizationPeakLimited = peakLimited,
                normalizationVersion = version.coerceAtLeast(0),
                normalizationError = "",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markSceneMusicNormalizationError(id: String, error: String) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(
                loudnessLufsEstimate = -70f,
                peakDbfs = 0f,
                normalizationTargetLufs = -24f,
                normalizationGainDb = 0f,
                normalizationPeakLimited = false,
                normalizationVersion = 0,
                normalizationError = error.trim().take(300),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun setSceneMusicOrder(id: String, orderIndex: Int) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(orderIndex = orderIndex.coerceIn(0, 10_000), updatedAt = System.currentTimeMillis()),
        )
    }

    suspend fun markSceneMusicPlayed(id: String, playedAt: Long = System.currentTimeMillis()) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(
                playCount = (current.playCount + 1).coerceAtMost(Int.MAX_VALUE),
                lastPlayedAt = playedAt,
                updatedAt = playedAt,
            ),
        )
    }

    suspend fun replaceSceneMusicCues(storyId: String, chapterId: String, cues: List<SceneMusicCueEntity>) = db.withTransaction {
        db.sceneMusicCueDao().deleteForChapter(chapterId)
        if (cues.isNotEmpty()) db.sceneMusicCueDao().upsertAll(cues.filter { it.storyId == storyId && it.chapterId == chapterId })
    }

    suspend fun listSceneMusicCues(chapterId: String): List<SceneMusicCueEntity> = db.sceneMusicCueDao().listForChapter(chapterId)

}
