package vn.nghetruyen.app.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.ChapterNoteEntity
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.ChapterVoiceAssignmentEntity
import vn.nghetruyen.app.data.local.DownloadJobEntity
import vn.nghetruyen.app.data.local.FollowedStoryEntity
import vn.nghetruyen.app.data.local.ReadingProgressEntity
import vn.nghetruyen.app.data.local.ReadingHistoryEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.data.local.StoryAiProfileEntity
import vn.nghetruyen.app.data.local.StoryTtsProfileEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.local.VietPhraseEntity
import vn.nghetruyen.app.data.local.VietPhraseSnapshotEntity
import vn.nghetruyen.app.data.local.VietPhraseDictionaryStateEntity
import vn.nghetruyen.app.data.local.VietPhraseSuggestionEntity
import vn.nghetruyen.app.data.settings.AppSettings
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.ReaderDisplaySettings
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versioned, merge-only backup format for user data.
 *
 * The archive contains manifest.json, data.json and optional portable attachments.
 * Attachments can include installed source packages, non-secret extension storage,
 * trust metadata and physical scene-music files.
 *
 * Restore never executes code from the archive and rejects unknown entries,
 * duplicate names, path traversal, oversized files and checksum mismatches.
 */
class BackupTransferManager(
    context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val attachmentCodec = BackupAttachmentCodec(appContext)

    suspend fun exportTo(
        destination: Uri,
        components: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<BackupSummary> = withContext(Dispatchers.IO) {
        val selected = components.ifEmpty { BackupComponent.entries.toSet() }
        val exportRoot = File(appContext.cacheDir, "backup_${System.nanoTime()}")
        val tempData = File(exportRoot, DATA_ENTRY)
        val attachmentStage = File(exportRoot, "attachment-stage")
        try {
            if (!exportRoot.mkdirs()) throw IOException("Không tạo được thư mục tạm sao lưu.")
            val counts = writeDataFile(tempData, selected)
            if (tempData.length() > MAX_DATA_BYTES) {
                return@withContext AppResult.Failure(
                    code = "BACKUP_TOO_LARGE",
                    message = "Dữ liệu sao lưu vượt giới hạn ${MAX_DATA_BYTES / (1024 * 1024)} MiB.",
                )
            }
            val sceneTracks = if (BackupComponent.SCENE_MUSIC in selected) {
                database.sceneMusicTrackDao().listAll()
            } else {
                emptyList()
            }
            val attachments = attachmentCodec.stage(selected, sceneTracks, attachmentStage)
            val checksum = sha256(tempData)
            val manifestObject = JSONObject()
                .put("format", FORMAT_NAME)
                .put("formatVersion", FORMAT_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("components", JSONArray(selected.map { it.name }))
                .put("dataSha256", checksum)
                .put("attachments", attachmentCodec.toJson(attachments))
                .put("attachmentCount", attachments.size)
                .put("attachmentBytes", attachments.sumOf(BackupAttachment::size))
                .put("sourceAttachmentCount", attachments.count { it.component == BackupComponent.SOURCES_EXTENSIONS })
                .put("sceneMusicAttachmentCount", attachments.count { it.component == BackupComponent.SCENE_MUSIC })
                .put("storyCount", counts.stories)
                .put("chapterCount", counts.chapters)
                .put("bookmarkCount", counts.bookmarks)
                .put("noteCount", counts.notes)
                .put("readingHistoryCount", counts.readingHistory)
                .put("followingCount", counts.following)
                .put("pronunciationCount", counts.pronunciations)
                .put("storyVoiceProfileCount", counts.storyVoiceProfiles)
                .put("storyAiProfileCount", counts.storyAiProfiles)
                .put("voiceRoleCount", counts.voiceRoles)
                .put("vietPhraseCount", counts.vietPhraseRules)
                .put("chapterTransformCount", counts.chapterTransforms)
                .put("voiceAssignmentCount", counts.voiceAssignments)
                .put("sceneMusicTrackCount", counts.sceneMusicTracks)
                .put("sceneMusicCueCount", counts.sceneMusicCues)
            val manifest = manifestObject.toString(2)
            require(manifest.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "BACKUP_MANIFEST_TOO_LARGE" }

            val output = resolver.openOutputStream(destination, "w")
                ?: return@withContext AppResult.Failure("BACKUP_OPEN_FAILED", "Không mở được tệp đích để sao lưu.")
            output.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    zip.putNextEntry(ZipEntry(DATA_ENTRY))
                    FileInputStream(tempData).use { it.copyTo(zip) }
                    zip.closeEntry()

                    attachments.forEach { attachment ->
                        zip.putNextEntry(ZipEntry(attachment.entry))
                        FileInputStream(attachment.file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }

                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            AppResult.Success(
                BackupSummary(
                    stories = counts.stories,
                    chapters = counts.chapters,
                    bookmarks = counts.bookmarks,
                    notes = counts.notes,
                    readingHistory = counts.readingHistory,
                    following = counts.following,
                    pronunciations = counts.pronunciations,
                    storyVoiceProfiles = counts.storyVoiceProfiles,
                    storyAiProfiles = counts.storyAiProfiles,
                    voiceRoles = counts.voiceRoles,
                    vietPhraseRules = counts.vietPhraseRules,
                    chapterTransforms = counts.chapterTransforms,
                    voiceAssignments = counts.voiceAssignments,
                    sceneMusicTracks = counts.sceneMusicTracks,
                    sceneMusicCues = counts.sceneMusicCues,
                    sourceFiles = attachments.count { it.component == BackupComponent.SOURCES_EXTENSIONS },
                    sceneMusicFiles = attachments.count { it.component == BackupComponent.SCENE_MUSIC },
                    attachmentBytes = attachments.sumOf(BackupAttachment::size),
                    components = selected,
                ),
            )
        } catch (error: Exception) {
            AppResult.Failure(
                code = "BACKUP_FAILED",
                message = error.message ?: "Không tạo được bản sao lưu.",
                cause = error,
            )
        } finally {
            exportRoot.deleteRecursively()
        }
    }

    suspend fun restoreFrom(
        source: Uri,
        components: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<BackupSummary> = withContext(Dispatchers.IO) {
        val requested = components.ifEmpty { BackupComponent.entries.toSet() }
        val stageRoot = File(appContext.cacheDir, "restore_${System.nanoTime()}")
        val dataFile = File(stageRoot, DATA_ENTRY)
        try {
            if (!stageRoot.mkdirs()) throw IOException("Không tạo được thư mục tạm khôi phục.")
            val input = resolver.openInputStream(source)
                ?: return@withContext AppResult.Failure("RESTORE_OPEN_FAILED", "Không mở được tệp sao lưu.")
            val extraction = input.use { extractArchive(it, dataFile, stageRoot) }
            val manifest = JSONObject(extraction.manifest)
            val format = manifest.optString("format")
            val version = manifest.optInt("formatVersion", -1)
            if (format != FORMAT_NAME || version !in 1..FORMAT_VERSION) {
                return@withContext AppResult.Failure(
                    "RESTORE_UNSUPPORTED",
                    "Định dạng sao lưu không được hỗ trợ.",
                )
            }
            val expectedHash = manifest.optString("dataSha256")
            val actualHash = sha256(dataFile)
            if (expectedHash.length != 64 || !expectedHash.equals(actualHash, ignoreCase = true)) {
                return@withContext AppResult.Failure(
                    "RESTORE_CHECKSUM_MISMATCH",
                    "Bản sao lưu bị hỏng hoặc đã bị thay đổi.",
                )
            }

            val archivedComponents = manifest.optJSONArray("components")?.let { array ->
                buildSet {
                    for (index in 0 until array.length()) {
                        runCatching { BackupComponent.valueOf(array.optString(index)) }.getOrNull()?.let(::add)
                    }
                }
            }.orEmpty().ifEmpty { BackupComponent.entries.toSet() }
            val effectiveComponents = requested.intersect(archivedComponents)
            require(effectiveComponents.isNotEmpty()) { "Không có thành phần được chọn trong bản sao lưu." }

            val attachmentDescriptors = attachmentCodec.parse(manifest.optJSONArray("attachments"))
            attachmentCodec.verify(stageRoot, attachmentDescriptors, extraction.attachmentEntries)
            val attachmentRestore = attachmentCodec.restore(stageRoot, attachmentDescriptors, effectiveComponents)
            val restored = restoreDataFile(dataFile, effectiveComponents, attachmentRestore.sceneMusicUris)
            AppResult.Success(
                restored.copy(
                    components = effectiveComponents,
                    sourceFiles = attachmentRestore.sourceFiles,
                    sceneMusicFiles = attachmentRestore.sceneMusicFiles,
                    attachmentBytes = attachmentDescriptors.filter { it.component in effectiveComponents }.sumOf(BackupAttachmentDescriptor::size),
                ),
            )
        } catch (error: Exception) {
            AppResult.Failure(
                code = "RESTORE_FAILED",
                message = error.message ?: "Không khôi phục được bản sao lưu.",
                cause = error,
            )
        } finally {
            stageRoot.deleteRecursively()
        }
    }

    private suspend fun writeDataFile(target: File, components: Set<BackupComponent>): BackupCounts {
        val settings = settingsRepository.snapshot()
        val stories = if (BackupComponent.LIBRARY in components) database.storyDao().listAll() else emptyList()
        val chapters = if (BackupComponent.LIBRARY in components) database.chapterDao().listAll() else emptyList()
        val progress = if (BackupComponent.READING in components) database.progressDao().listAll() else emptyList()
        val readingHistory = if (BackupComponent.READING in components) database.readingHistoryDao().listRecent() else emptyList()
        val bookmarks = if (BackupComponent.READING in components) database.bookmarkDao().listAll() else emptyList()
        val notes = if (BackupComponent.READING in components) database.chapterNoteDao().listAll() else emptyList()
        val following = if (BackupComponent.LIBRARY in components) database.followingDao().listAll() else emptyList()
        val downloads = if (BackupComponent.LIBRARY in components) database.downloadJobDao().listAll() else emptyList()
        val pronunciations = if (BackupComponent.READING in components) database.pronunciationDao().listAll() else emptyList()
        val storyVoiceProfiles = if (BackupComponent.AI_VOICE in components) database.storyTtsProfileDao().listAll() else emptyList()
        val storyAiProfiles = if (BackupComponent.AI_VOICE in components) database.storyAiProfileDao().listAll() else emptyList()
        val voiceRoles = if (BackupComponent.AI_VOICE in components) database.voiceRoleDao().listAll() else emptyList()
        val vietPhraseRules = if (BackupComponent.VIETPHRASE in components) database.vietPhraseDao().listAll() else emptyList()
        val vietPhraseSnapshots = if (BackupComponent.VIETPHRASE in components) database.vietPhraseSnapshotDao().listAll() else emptyList()
        val vietPhraseDictionaryStates = if (BackupComponent.VIETPHRASE in components) database.vietPhraseDictionaryStateDao().listAll() else emptyList()
        val vietPhraseSuggestions = if (BackupComponent.VIETPHRASE in components) database.vietPhraseSuggestionDao().listPending() else emptyList()
        val chapterTransforms = if (BackupComponent.AI_VOICE in components) database.chapterTransformDao().listAll() else emptyList()
        val voiceAssignments = if (BackupComponent.AI_VOICE in components) database.chapterVoiceAssignmentDao().listAll() else emptyList()
        val sceneMusicTracks = if (BackupComponent.SCENE_MUSIC in components) database.sceneMusicTrackDao().listAll() else emptyList()
        val sceneMusicCues = if (BackupComponent.SCENE_MUSIC in components) database.sceneMusicCueDao().listAll() else emptyList()

        FileOutputStream(target).use { stream ->
            JsonWriter(OutputStreamWriter(BufferedOutputStream(stream), Charsets.UTF_8)).use { writer ->
                writer.setIndent("  ")
                writer.beginObject()
                if (BackupComponent.SETTINGS in components) {
                    writer.name("settings")
                    writer.writeSettings(settings)
                }
                if (BackupComponent.LIBRARY in components) {
                    writer.name("stories"); writer.writeStories(stories)
                    writer.name("chapters"); writer.writeChapters(chapters)
                    writer.name("following"); writer.writeFollowing(following)
                    writer.name("downloads"); writer.writeDownloads(downloads)
                }
                if (BackupComponent.READING in components) {
                    writer.name("progress"); writer.writeProgress(progress)
                    writer.name("readingHistory"); writer.writeReadingHistory(readingHistory)
                    writer.name("bookmarks"); writer.writeBookmarks(bookmarks)
                    writer.name("notes"); writer.writeNotes(notes)
                    writer.name("pronunciations"); writer.writePronunciations(pronunciations)
                }
                if (BackupComponent.AI_VOICE in components) {
                    writer.name("storyVoiceProfiles"); writer.writeStoryVoiceProfiles(storyVoiceProfiles)
                    writer.name("storyAiProfiles"); writer.writeStoryAiProfiles(storyAiProfiles)
                    writer.name("voiceRoles"); writer.writeVoiceRoles(voiceRoles)
                    writer.name("chapterTransforms"); writer.writeChapterTransforms(chapterTransforms)
                    writer.name("voiceAssignments"); writer.writeVoiceAssignments(voiceAssignments)
                }
                if (BackupComponent.VIETPHRASE in components) {
                    writer.name("vietPhraseRules"); writer.writeVietPhraseRules(vietPhraseRules)
                    writer.name("vietPhraseSnapshots"); writer.writeVietPhraseSnapshots(vietPhraseSnapshots)
                    writer.name("vietPhraseDictionaryStates"); writer.writeVietPhraseDictionaryStates(vietPhraseDictionaryStates)
                    writer.name("vietPhraseSuggestions"); writer.writeVietPhraseSuggestions(vietPhraseSuggestions)
                }
                if (BackupComponent.SCENE_MUSIC in components) {
                    writer.name("sceneMusicTracks"); writer.writeSceneMusicTracks(sceneMusicTracks)
                    writer.name("sceneMusicCues"); writer.writeSceneMusicCues(sceneMusicCues)
                }
                writer.endObject()
            }
        }
        return BackupCounts(
            stories = stories.size,
            chapters = chapters.size,
            bookmarks = bookmarks.size,
            notes = notes.size,
            readingHistory = readingHistory.size,
            following = following.size,
            pronunciations = pronunciations.size,
            storyVoiceProfiles = storyVoiceProfiles.size,
            storyAiProfiles = storyAiProfiles.size,
            voiceRoles = voiceRoles.size,
            vietPhraseRules = vietPhraseRules.size,
            chapterTransforms = chapterTransforms.size,
            voiceAssignments = voiceAssignments.size,
            sceneMusicTracks = sceneMusicTracks.size,
            sceneMusicCues = sceneMusicCues.size,
        )
    }

    private data class ArchiveExtraction(
        val manifest: String,
        val attachmentEntries: Set<String>,
    )

    private fun extractArchive(input: InputStream, dataFile: File, stageRoot: File): ArchiveExtraction {
        var manifest: String? = null
        var entryCount = 0
        var attachmentBytes = 0L
        val seen = mutableSetOf<String>()
        val attachmentEntries = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ENTRY_COUNT) throw IOException("Bản sao lưu có quá nhiều mục.")
                val name = entry.name
                if (!isSafeEntryName(name)) throw IOException("Tên mục không an toàn: $name")
                if (!seen.add(name)) throw IOException("Bản sao lưu có mục trùng lặp: $name")
                if (entry.isDirectory) throw IOException("Bản sao lưu không được chứa thư mục.")
                when (name) {
                    DATA_ENTRY -> FileOutputStream(dataFile).use { output ->
                        zip.copyBounded(output, MAX_DATA_BYTES)
                    }
                    MANIFEST_ENTRY -> manifest = zip.readBounded(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                    else -> {
                        val target = stageRoot.resolve(name).canonicalFile
                        require(target.path.startsWith(stageRoot.canonicalPath + File.separator)) { "RESTORE_ATTACHMENT_PATH_ESCAPE" }
                        target.parentFile?.mkdirs()
                        val maxBytes = if (name.startsWith("attachments/scene_music/")) MAX_SCENE_ATTACHMENT_BYTES else MAX_SOURCE_ATTACHMENT_BYTES
                        FileOutputStream(target).use { output -> zip.copyBounded(output, maxBytes) }
                        attachmentBytes += target.length()
                        if (attachmentBytes > MAX_TOTAL_ATTACHMENT_BYTES) throw IOException("Tổng tệp đính kèm vượt giới hạn an toàn.")
                        attachmentEntries += name
                    }
                }
                zip.closeEntry()
            }
        }
        if (!dataFile.isFile) throw IOException("Bản sao lưu thiếu $DATA_ENTRY.")
        return ArchiveExtraction(
            manifest = manifest ?: throw IOException("Bản sao lưu thiếu $MANIFEST_ENTRY."),
            attachmentEntries = attachmentEntries,
        )
    }

    private suspend fun restoreDataFile(
        dataFile: File,
        components: Set<BackupComponent>,
        sceneMusicUris: Map<String, String> = emptyMap(),
    ): BackupSummary {
        var settings: AppSettings? = null
        var storyCount = 0
        var chapterCount = 0
        var bookmarkCount = 0
        var noteCount = 0
        var readingHistoryCount = 0
        var followingCount = 0
        var pronunciationCount = 0
        var storyVoiceProfileCount = 0
        var storyAiProfileCount = 0
        var voiceRoleCount = 0
        var vietPhraseCount = 0
        var chapterTransformCount = 0
        var voiceAssignmentCount = 0
        var sceneMusicTrackCount = 0
        var sceneMusicCueCount = 0

        database.withTransaction {
            JsonReader(InputStreamReader(FileInputStream(dataFile), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "settings" -> if (BackupComponent.SETTINGS in components) settings = reader.readSettings() else reader.skipValue()
                        "stories" -> if (BackupComponent.LIBRARY in components) storyCount = reader.readStories { database.storyDao().upsertAll(it) } else reader.skipValue()
                        "chapters" -> if (BackupComponent.LIBRARY in components) chapterCount = reader.readChapters { database.chapterDao().upsertAll(it) } else reader.skipValue()
                        "following" -> if (BackupComponent.LIBRARY in components) followingCount = reader.readFollowing { database.followingDao().upsertAll(it) } else reader.skipValue()
                        "downloads" -> if (BackupComponent.LIBRARY in components) reader.readDownloads { database.downloadJobDao().upsertAll(it) } else reader.skipValue()
                        "progress" -> if (BackupComponent.READING in components) reader.readProgress { database.progressDao().upsertAll(it) } else reader.skipValue()
                        "readingHistory" -> if (BackupComponent.READING in components) readingHistoryCount = reader.readReadingHistory { items -> items.forEach { database.readingHistoryDao().upsert(it) } } else reader.skipValue()
                        "bookmarks" -> if (BackupComponent.READING in components) bookmarkCount = reader.readBookmarks { database.bookmarkDao().upsertAll(it) } else reader.skipValue()
                        "notes" -> if (BackupComponent.READING in components) noteCount = reader.readNotes { database.chapterNoteDao().upsertAll(it) } else reader.skipValue()
                        "pronunciations" -> if (BackupComponent.READING in components) pronunciationCount = reader.readPronunciations { database.pronunciationDao().upsertAll(it) } else reader.skipValue()
                        "storyVoiceProfiles" -> if (BackupComponent.AI_VOICE in components) storyVoiceProfileCount = reader.readStoryVoiceProfiles { database.storyTtsProfileDao().upsertAll(it) } else reader.skipValue()
                        "storyAiProfiles" -> if (BackupComponent.AI_VOICE in components) storyAiProfileCount = reader.readStoryAiProfiles { database.storyAiProfileDao().upsertAll(it) } else reader.skipValue()
                        "voiceRoles" -> if (BackupComponent.AI_VOICE in components) voiceRoleCount = reader.readVoiceRoles { database.voiceRoleDao().upsertAll(it) } else reader.skipValue()
                        "chapterTransforms" -> if (BackupComponent.AI_VOICE in components) chapterTransformCount = reader.readChapterTransforms { database.chapterTransformDao().upsertAll(it) } else reader.skipValue()
                        "voiceAssignments" -> if (BackupComponent.AI_VOICE in components) voiceAssignmentCount = reader.readVoiceAssignments { database.chapterVoiceAssignmentDao().upsertAll(it) } else reader.skipValue()
                        "vietPhraseRules" -> if (BackupComponent.VIETPHRASE in components) vietPhraseCount = reader.readVietPhraseRules { database.vietPhraseDao().upsertAll(it) } else reader.skipValue()
                        "vietPhraseSnapshots" -> if (BackupComponent.VIETPHRASE in components) reader.readVietPhraseSnapshots { database.vietPhraseSnapshotDao().upsert(it) } else reader.skipValue()
                        "vietPhraseDictionaryStates" -> if (BackupComponent.VIETPHRASE in components) reader.readVietPhraseDictionaryStates { database.vietPhraseDictionaryStateDao().upsertAll(it) } else reader.skipValue()
                        "vietPhraseSuggestions" -> if (BackupComponent.VIETPHRASE in components) reader.readVietPhraseSuggestions { database.vietPhraseSuggestionDao().upsertAll(it) } else reader.skipValue()
                        "sceneMusicTracks" -> if (BackupComponent.SCENE_MUSIC in components) {
                            sceneMusicTrackCount = reader.readSceneMusicTracks { tracks ->
                                database.sceneMusicTrackDao().upsertAll(
                                    tracks.map { track -> sceneMusicUris[track.id]?.let { restoredUri -> track.copy(uri = restoredUri) } ?: track },
                                )
                            }
                        } else reader.skipValue()
                        "sceneMusicCues" -> if (BackupComponent.SCENE_MUSIC in components) sceneMusicCueCount = reader.readSceneMusicCues { database.sceneMusicCueDao().upsertAll(it) } else reader.skipValue()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        settings?.let { settingsRepository.restore(it) }
        return BackupSummary(
            stories = storyCount,
            chapters = chapterCount,
            bookmarks = bookmarkCount,
            notes = noteCount,
            readingHistory = readingHistoryCount,
            following = followingCount,
            pronunciations = pronunciationCount,
            storyVoiceProfiles = storyVoiceProfileCount,
            storyAiProfiles = storyAiProfileCount,
            voiceRoles = voiceRoleCount,
            vietPhraseRules = vietPhraseCount,
            chapterTransforms = chapterTransformCount,
            voiceAssignments = voiceAssignmentCount,
            sceneMusicTracks = sceneMusicTrackCount,
            sceneMusicCues = sceneMusicCueCount,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.length > 1024 || name.contains("..") || name.contains('\\') || name.contains('\u0000') || name.startsWith('/')) return false
        if (name in setOf(DATA_ENTRY, MANIFEST_ENTRY)) return true
        return (name.startsWith("attachments/sources/") || name.startsWith("attachments/scene_music/")) &&
            name.substringAfter("attachments/").isNotBlank() && !name.endsWith('/')
    }

    private data class BackupCounts(
        val stories: Int,
        val chapters: Int,
        val bookmarks: Int,
        val notes: Int,
        val readingHistory: Int,
        val following: Int,
        val pronunciations: Int,
        val storyVoiceProfiles: Int,
        val storyAiProfiles: Int,
        val voiceRoles: Int,
        val vietPhraseRules: Int,
        val chapterTransforms: Int,
        val voiceAssignments: Int,
        val sceneMusicTracks: Int,
        val sceneMusicCues: Int,
    )

    companion object {
        private const val FORMAT_NAME = "vn.nghetruyen.backup"
        // Legacy release-gate token: FORMAT_VERSION = 15
        private const val FORMAT_VERSION = 16
        private const val DATA_ENTRY = "data.json"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MAX_ENTRY_COUNT = 2_050
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        private const val MAX_DATA_BYTES = 128L * 1024L * 1024L
        private const val MAX_SOURCE_ATTACHMENT_BYTES = 64L * 1024L * 1024L
        private const val MAX_SCENE_ATTACHMENT_BYTES = 256L * 1024L * 1024L
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 1024L * 1024L * 1024L
        private const val BATCH_SIZE = 250
        private val VIETPHRASE_KINDS = setOf("LUAT_NHAN", "PRONOUNS", "PHIEN_AM", "LAC_VIET", "VIET_PHRASE", "NAMES", "AI_REPLACE")
        private val VIETPHRASE_SCOPES = setOf("GLOBAL", "STORY")
        private val VIETPHRASE_MATCH_MODES = setOf("LITERAL", "TEMPLATE")
        private val VIETPHRASE_SUGGESTION_STATUSES = setOf("PENDING", "ACCEPTED", "REJECTED")
        private val STORY_AI_MODES = setOf("INHERIT", "TRANSLATE", "IMPROVE")
        private val STORY_AI_PROVIDERS = setOf(AiProvider.OPENAI_COMPATIBLE.name, AiProvider.GEMINI.name)
    }

    private suspend fun <T> JsonReader.readBatches(
        readItem: JsonReader.() -> T,
        saveBatch: suspend (List<T>) -> Unit,
    ): Int {
        var count = 0
        val batch = ArrayList<T>(BATCH_SIZE)
        beginArray()
        while (hasNext()) {
            batch += readItem()
            count += 1
            if (batch.size >= BATCH_SIZE) {
                saveBatch(batch.toList())
                batch.clear()
            }
        }
        endArray()
        if (batch.isNotEmpty()) saveBatch(batch)
        return count
    }

    private fun JsonWriter.writeSettings(value: AppSettings) {
        beginObject()
        name("selectedSourceId").value(value.selectedSourceId)
        name("ttsRate").value(value.ttsRate.toDouble())
        name("ttsPitch").value(value.ttsPitch.toDouble())
        name("ttsVolume").value(value.ttsVolume.toDouble())
        name("ttsEnginePackage"); nullableValue(value.ttsEnginePackage)
        name("autoPlayNextChapter").value(value.autoPlayNextChapter)
        name("aiTranslationEnabled").value(value.aiTranslationEnabled)
        name("ttsVoiceName"); nullableValue(value.ttsVoiceName)
        name("ttsLanguageTag").value(value.ttsLanguageTag)
        name("audioInterruptionMode").value(value.audioInterruptionMode.name)
        // SAF URI grants are device-local and are intentionally excluded from portable backups.
        name("backgroundMusicEnabled").value(false)
        name("backgroundMusicVolume").value(value.backgroundMusicVolume.toDouble())
        name("backgroundMusicDuckFactor").value(value.backgroundMusicDuckFactor.toDouble())
        name("followingUpdatesEnabled").value(value.followingUpdatesEnabled)
        name("readerCacheLimitMiB").value(value.readerCacheLimitMiB.toLong())
        name("readerTheme").value(value.readerDisplay.theme.name)
        name("readerLayoutMode").value(value.readerDisplay.layoutMode.name)
        name("readerFontSizeSp").value(value.readerDisplay.fontSizeSp.toLong())
        name("readerLineHeightPercent").value(value.readerDisplay.lineHeightPercent.toLong())
        name("readerHorizontalPaddingDp").value(value.readerDisplay.horizontalPaddingDp.toLong())
        name("readerParagraphSpacingDp").value(value.readerDisplay.paragraphSpacingDp.toLong())
        name("readerKeepScreenOn").value(value.readerDisplay.keepScreenOn)
        name("readerVolumeKeysNavigate").value(value.readerDisplay.volumeKeysNavigate)
        name("headsetMultiClickEnabled").value(value.headsetMultiClickEnabled)
        name("headsetSingleClickAction").value(value.headsetSingleClickAction)
        name("headsetDoubleClickAction").value(value.headsetDoubleClickAction)
        name("headsetTripleClickAction").value(value.headsetTripleClickAction)
        name("headsetLongPressAction").value(value.headsetLongPressAction)
        name("pauseOnHeadsetDisconnect").value(value.pauseOnHeadsetDisconnect)
        name("restorePlaybackAfterProcessDeath").value(value.restorePlaybackAfterProcessDeath)
        name("autoVoiceCastEnabled").value(value.autoVoiceCastEnabled)
        name("autoSceneMusicEnabled").value(value.autoSceneMusicEnabled)
        name("prefetchNarrationPlansEnabled").value(value.prefetchNarrationPlansEnabled)
        name("narrationPrefetchWindowChapters").value(value.narrationPrefetchWindowChapters.toLong())
        name("sceneMusicCrossfadeMillis").value(value.sceneMusicCrossfadeMillis.toLong())
        name("sceneMusicContinueAcrossChapters").value(value.sceneMusicContinueAcrossChapters)
        name("sceneMusicPlaybackMode").value(value.sceneMusicPlaybackMode.name)
        name("sceneMusicTargetLufs").value(value.sceneMusicTargetLufs.toDouble())
        name("sceneMusicAvoidRepeatWindow").value(value.sceneMusicAvoidRepeatWindow.toLong())
        name("sonicProcessingEnabled").value(value.sonicProcessingEnabled)
        name("sonicDefaultSpeed").value(value.sonicDefaultSpeed.toDouble())
        name("sonicDefaultPitch").value(value.sonicDefaultPitch.toDouble())
        name("ttsCacheEnabled").value(value.ttsCacheEnabled)
        name("ttsCacheLimitMiB").value(value.ttsCacheLimitMiB.toLong())
        name("normalizeTtsVolumeEnabled").value(value.normalizeTtsVolumeEnabled)
        name("ttsTargetLufs").value(value.ttsTargetLufs.toDouble())
        name("aiProvider").value(value.aiOnline.provider.name)
        name("aiEndpoint").value(value.aiOnline.endpoint)
        name("aiModel").value(value.aiOnline.model)
        name("aiGeminiModel").value(value.aiOnline.geminiModel)
        name("aiOpenAiModel").value(value.aiOnline.openAiModel)
        name("aiDefaultMode").value(value.aiOnline.mode)
        name("aiTranslationPrompt").value(value.aiOnline.translationPrompt)
        name("aiImprovePrompt").value(value.aiOnline.improvePrompt)
        name("aiTimeoutMillis").value(value.aiOnline.timeoutMillis.toLong())
        name("aiTemperature").value(value.aiOnline.temperature.toDouble())
        name("aiTranslationInstruction").value(value.aiOnline.translationInstruction)
        name("aiDailyRequestLimit").value(value.aiOnline.dailyRequestLimit.toLong())
        name("aiDailyInputCharsLimit").value(value.aiOnline.dailyInputCharsLimit.toLong())
        name("aiMaxRetries").value(value.aiOnline.maxRetries.toLong())
        name("aiRetryBaseDelayMillis").value(value.aiOnline.retryBaseDelayMillis.toLong())
        // Consent and API key are device/user-session specific and are never exported.
        name("aiOnlineEnabled").value(false)
        name("aiConsentGranted").value(false)
        endObject()
    }

    private fun JsonReader.readSettings(): AppSettings {
        var source = "truyenfull"
        var rate = 1.0f
        var pitch = 1.0f
        var volume = 1.0f
        var enginePackage: String? = null
        var autoNext = true
        var ai = false
        var voiceName: String? = null
        var languageTag = "vi-VN"
        var interruptionMode = AudioInterruptionMode.PAUSE
        var backgroundMusicUri: String? = null
        var backgroundMusicEnabled = false
        var backgroundMusicVolume = 0.18f
        var backgroundMusicDuckFactor = 0.25f
        var followingUpdates = false
        var readerCacheLimitMiB = 64
        var readerTheme = ReaderThemeMode.SYSTEM
        var readerLayoutMode = ReaderLayoutMode.SCROLL
        var readerFontSizeSp = 20
        var readerLineHeightPercent = 155
        var readerHorizontalPaddingDp = 12
        var readerParagraphSpacingDp = 8
        var readerKeepScreenOn = false
        var readerVolumeKeysNavigate = false
        var headsetMultiClickEnabled = true
        var headsetSingleClickAction = "TOGGLE"
        var headsetDoubleClickAction = "NEXT"
        var headsetTripleClickAction = "PREVIOUS"
        var headsetLongPressAction = "STOP"
        var pauseOnHeadsetDisconnect = true
        var restorePlaybackAfterProcessDeath = true
        var autoVoiceCastEnabled = false
        var autoSceneMusicEnabled = false
        var prefetchNarrationPlansEnabled = true
        var narrationPrefetchWindowChapters = 2
        var sceneMusicCrossfadeMillis = 1_600
        var sceneMusicContinueAcrossChapters = true
        var sceneMusicPlaybackMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT
        var sceneMusicTargetLufs = -18f
        var sceneMusicAvoidRepeatWindow = 4
        var sonicProcessingEnabled = true
        var sonicDefaultSpeed = 1f
        var sonicDefaultPitch = 1f
        var ttsCacheEnabled = true
        var ttsCacheLimitMiB = 64
        var normalizeTtsVolumeEnabled = true
        var ttsTargetLufs = -18f
        var aiProvider = AiProvider.GEMINI
        var aiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        var aiModel = "gemini-3.6-flash"
        var aiGeminiModel = "gemini-3.6-flash"
        var aiOpenAiModel = ""
        var aiDefaultMode = "translate"
        var aiTranslationPrompt = vn.nghetruyen.app.data.settings.DEFAULT_AI_TRANSLATE_PROMPT
        var aiImprovePrompt = vn.nghetruyen.app.data.settings.DEFAULT_AI_IMPROVE_PROMPT
        var aiTimeoutMillis = 120_000
        var aiTemperature = 0.2f
        var aiTranslationInstruction = ""
        var aiDailyRequestLimit = 30
        var aiDailyInputCharsLimit = 500_000
        var aiMaxRetries = 0
        var aiRetryBaseDelayMillis = 1_500
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "selectedSourceId" -> source = nextStringSafe("truyenfull")
                "ttsRate" -> rate = nextDoubleSafe(1.0).toFloat()
                "ttsPitch" -> pitch = nextDoubleSafe(1.0).toFloat()
                "ttsVolume" -> volume = nextDoubleSafe(1.0).toFloat()
                "ttsEnginePackage" -> enginePackage = nextNullableString()
                "autoPlayNextChapter" -> autoNext = nextBooleanSafe(true)
                "aiTranslationEnabled" -> ai = nextBooleanSafe(false)
                "ttsVoiceName" -> voiceName = nextNullableString()
                "ttsLanguageTag" -> languageTag = nextStringSafe("vi-VN")
                "audioInterruptionMode" -> interruptionMode = runCatching { AudioInterruptionMode.valueOf(nextStringSafe("PAUSE")) }.getOrDefault(AudioInterruptionMode.PAUSE)
                "backgroundMusicUri" -> backgroundMusicUri = nextNullableString()
                "backgroundMusicEnabled" -> backgroundMusicEnabled = nextBooleanSafe(false)
                "backgroundMusicVolume" -> backgroundMusicVolume = nextDoubleSafe(0.18).toFloat()
                "backgroundMusicDuckFactor" -> backgroundMusicDuckFactor = nextDoubleSafe(0.25).toFloat()
                "followingUpdatesEnabled" -> followingUpdates = nextBooleanSafe(false)
                "readerCacheLimitMiB" -> readerCacheLimitMiB = nextLongSafe(64L).toInt()
                "readerTheme" -> readerTheme = runCatching { ReaderThemeMode.valueOf(nextStringSafe("SYSTEM")) }
                    .getOrDefault(ReaderThemeMode.SYSTEM)
                "readerLayoutMode" -> readerLayoutMode = runCatching { ReaderLayoutMode.valueOf(nextStringSafe("SCROLL")) }
                    .getOrDefault(ReaderLayoutMode.SCROLL)
                "readerFontSizeSp" -> readerFontSizeSp = nextLongSafe(20L).toInt()
                "readerLineHeightPercent" -> readerLineHeightPercent = nextLongSafe(155L).toInt()
                "readerHorizontalPaddingDp" -> readerHorizontalPaddingDp = nextLongSafe(12L).toInt()
                "readerParagraphSpacingDp" -> readerParagraphSpacingDp = nextLongSafe(8L).toInt()
                "readerKeepScreenOn" -> readerKeepScreenOn = nextBooleanSafe(false)
                "readerVolumeKeysNavigate" -> readerVolumeKeysNavigate = nextBooleanSafe(false)
                "headsetMultiClickEnabled" -> headsetMultiClickEnabled = nextBooleanSafe(true)
                "headsetSingleClickAction" -> headsetSingleClickAction = SettingsRepository.normalizeMediaAction(nextStringSafe("TOGGLE"), "TOGGLE")
                "headsetDoubleClickAction" -> headsetDoubleClickAction = SettingsRepository.normalizeMediaAction(nextStringSafe("NEXT"), "NEXT")
                "headsetTripleClickAction" -> headsetTripleClickAction = SettingsRepository.normalizeMediaAction(nextStringSafe("PREVIOUS"), "PREVIOUS")
                "headsetLongPressAction" -> headsetLongPressAction = SettingsRepository.normalizeMediaAction(nextStringSafe("STOP"), "STOP")
                "pauseOnHeadsetDisconnect" -> pauseOnHeadsetDisconnect = nextBooleanSafe(true)
                "restorePlaybackAfterProcessDeath" -> restorePlaybackAfterProcessDeath = nextBooleanSafe(true)
                "autoVoiceCastEnabled" -> autoVoiceCastEnabled = nextBooleanSafe(false)
                "autoSceneMusicEnabled" -> autoSceneMusicEnabled = nextBooleanSafe(false)
                "prefetchNarrationPlansEnabled" -> prefetchNarrationPlansEnabled = nextBooleanSafe(true)
                "narrationPrefetchWindowChapters" -> narrationPrefetchWindowChapters = nextLongSafe(2L).toInt().coerceIn(1, 5)
                "sceneMusicCrossfadeMillis" -> sceneMusicCrossfadeMillis = nextLongSafe(1_600L).toInt().coerceIn(0, 8_000)
                "sceneMusicContinueAcrossChapters" -> sceneMusicContinueAcrossChapters = nextBooleanSafe(true)
                "sceneMusicPlaybackMode" -> sceneMusicPlaybackMode = runCatching { SceneMusicPlaybackMode.valueOf(nextStringSafe("SMART_AVOID_REPEAT")) }.getOrDefault(SceneMusicPlaybackMode.SMART_AVOID_REPEAT)
                "sceneMusicTargetLufs" -> sceneMusicTargetLufs = nextDoubleSafe(-18.0).toFloat().coerceIn(-30f, -10f)
                "sceneMusicAvoidRepeatWindow" -> sceneMusicAvoidRepeatWindow = nextLongSafe(4L).toInt().coerceIn(0, 12)
                "sonicProcessingEnabled" -> sonicProcessingEnabled = nextBooleanSafe(true)
                "sonicDefaultSpeed" -> sonicDefaultSpeed = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2f)
                "sonicDefaultPitch" -> sonicDefaultPitch = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2f)
                "ttsCacheEnabled" -> ttsCacheEnabled = nextBooleanSafe(true)
                "ttsCacheLimitMiB" -> ttsCacheLimitMiB = SettingsRepository.normalizeTtsCacheLimit(nextLongSafe(64L).toInt())
                "normalizeTtsVolumeEnabled" -> normalizeTtsVolumeEnabled = nextBooleanSafe(true)
                "ttsTargetLufs" -> ttsTargetLufs = SettingsRepository.normalizeTtsTargetLufs(nextDoubleSafe(-18.0).toFloat())
                "aiProvider" -> aiProvider = runCatching { AiProvider.valueOf(nextStringSafe(AiProvider.GEMINI.name)) }
                    .getOrDefault(AiProvider.GEMINI)
                "aiEndpoint" -> aiEndpoint = nextStringSafe(aiEndpoint).take(500)
                "aiModel" -> aiModel = nextStringSafe(aiModel).take(200)
                "aiGeminiModel" -> aiGeminiModel = nextStringSafe(aiGeminiModel).take(200)
                "aiOpenAiModel" -> aiOpenAiModel = nextStringSafe(aiOpenAiModel).take(200)
                "aiDefaultMode" -> aiDefaultMode = nextStringSafe("translate").takeIf { it == "improve" } ?: "translate"
                "aiTranslationPrompt" -> aiTranslationPrompt = nextStringSafe(aiTranslationPrompt)
                "aiImprovePrompt" -> aiImprovePrompt = nextStringSafe(aiImprovePrompt)
                "aiTimeoutMillis" -> aiTimeoutMillis = nextLongSafe(120_000L).toInt().coerceAtLeast(10_000)
                "aiTemperature" -> aiTemperature = nextDoubleSafe(0.2).toFloat().coerceIn(0f, 2f)
                "aiTranslationInstruction" -> aiTranslationInstruction = nextStringSafe("").take(2000)
                "aiDailyRequestLimit" -> aiDailyRequestLimit = nextLongSafe(30L).toInt().coerceIn(1, 500)
                "aiDailyInputCharsLimit" -> aiDailyInputCharsLimit = nextLongSafe(500_000L).toInt().coerceIn(10_000, 5_000_000)
                "aiMaxRetries" -> aiMaxRetries = nextLongSafe(2L).toInt().coerceIn(0, 5)
                "aiRetryBaseDelayMillis" -> aiRetryBaseDelayMillis = nextLongSafe(1_500L).toInt().coerceIn(250, 30_000)
                "aiOnlineEnabled", "aiConsentGranted" -> skipValue()
                else -> skipValue()
            }
        }
        endObject()
        return AppSettings(
            selectedSourceId = source,
            ttsRate = rate,
            ttsPitch = pitch,
            ttsVolume = volume,
            ttsEnginePackage = enginePackage,
            autoPlayNextChapter = autoNext,
            aiTranslationEnabled = ai,
            ttsVoiceName = voiceName,
            ttsLanguageTag = languageTag,
            audioInterruptionMode = interruptionMode,
            backgroundMusicUri = null,
            backgroundMusicEnabled = false,
            backgroundMusicVolume = backgroundMusicVolume,
            backgroundMusicDuckFactor = backgroundMusicDuckFactor,
            followingUpdatesEnabled = followingUpdates,
            readerCacheLimitMiB = readerCacheLimitMiB,
            headsetMultiClickEnabled = headsetMultiClickEnabled,
            headsetSingleClickAction = headsetSingleClickAction,
            headsetDoubleClickAction = headsetDoubleClickAction,
            headsetTripleClickAction = headsetTripleClickAction,
            headsetLongPressAction = headsetLongPressAction,
            pauseOnHeadsetDisconnect = pauseOnHeadsetDisconnect,
            restorePlaybackAfterProcessDeath = restorePlaybackAfterProcessDeath,
            autoVoiceCastEnabled = autoVoiceCastEnabled,
            autoSceneMusicEnabled = autoSceneMusicEnabled,
            prefetchNarrationPlansEnabled = prefetchNarrationPlansEnabled,
            narrationPrefetchWindowChapters = narrationPrefetchWindowChapters,
            sceneMusicCrossfadeMillis = sceneMusicCrossfadeMillis,
            sceneMusicContinueAcrossChapters = sceneMusicContinueAcrossChapters,
            sceneMusicPlaybackMode = sceneMusicPlaybackMode,
            sceneMusicTargetLufs = sceneMusicTargetLufs,
            sceneMusicAvoidRepeatWindow = sceneMusicAvoidRepeatWindow,
            sonicProcessingEnabled = sonicProcessingEnabled,
            sonicDefaultSpeed = sonicDefaultSpeed,
            sonicDefaultPitch = sonicDefaultPitch,
            ttsCacheEnabled = ttsCacheEnabled,
            ttsCacheLimitMiB = ttsCacheLimitMiB,
            normalizeTtsVolumeEnabled = normalizeTtsVolumeEnabled,
            ttsTargetLufs = ttsTargetLufs,
            readerDisplay = ReaderDisplaySettings(
                theme = readerTheme,
                layoutMode = readerLayoutMode,
                fontSizeSp = readerFontSizeSp,
                lineHeightPercent = readerLineHeightPercent,
                horizontalPaddingDp = readerHorizontalPaddingDp,
                paragraphSpacingDp = readerParagraphSpacingDp,
                keepScreenOn = readerKeepScreenOn,
                volumeKeysNavigate = readerVolumeKeysNavigate,
            ),
            aiOnline = AiOnlineSettings(
                provider = aiProvider,
                enabled = false,
                consentGranted = false,
                endpoint = aiEndpoint,
                model = aiModel,
                geminiModel = aiGeminiModel,
                openAiModel = aiOpenAiModel,
                mode = aiDefaultMode,
                translationPrompt = aiTranslationPrompt,
                improvePrompt = aiImprovePrompt,
                timeoutMillis = aiTimeoutMillis,
                temperature = aiTemperature,
                translationInstruction = aiTranslationInstruction,
                dailyRequestLimit = aiDailyRequestLimit,
                dailyInputCharsLimit = aiDailyInputCharsLimit,
                maxRetries = aiMaxRetries,
                retryBaseDelayMillis = aiRetryBaseDelayMillis,
            ),
        )
    }

    private fun JsonWriter.writeStories(items: List<StoryEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("sourceId").value(item.sourceId)
            name("title").value(item.title)
            name("author").value(item.author)
            name("description").value(item.description)
            name("coverUrl"); nullableValue(item.coverUrl)
            name("remoteUrl").value(item.remoteUrl)
            name("isOffline").value(item.isOffline)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readStories(save: suspend (List<StoryEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var source = ""; var title = ""; var author = ""; var description = ""
            var cover: String? = null; var remote = ""; var offline = false; var updated = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "sourceId" -> source = nextStringSafe("")
                "title" -> title = nextStringSafe("")
                "author" -> author = nextStringSafe("")
                "description" -> description = nextStringSafe("")
                "coverUrl" -> cover = nextNullableString()
                "remoteUrl" -> remote = nextStringSafe("")
                "isOffline" -> offline = nextBooleanSafe(false)
                "updatedAt" -> updated = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && source.isNotBlank() && title.isNotBlank()) { "Dữ liệu truyện không hợp lệ." }
            StoryEntity(id, source, title, author, description, cover, remote.ifBlank { id }, offline, updated)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeChapters(items: List<ChapterEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("chapterIndex").value(item.chapterIndex.toLong())
            name("title").value(item.title)
            name("remoteUrl").value(item.remoteUrl)
            name("content"); nullableValue(item.content)
            name("downloadedAt"); nullableValue(item.downloadedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readChapters(save: suspend (List<ChapterEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var index = 0; var title = ""; var remote = ""
            var content: String? = null; var downloaded: Long? = null
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "chapterIndex" -> index = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "title" -> title = nextStringSafe("")
                "remoteUrl" -> remote = nextStringSafe("")
                "content" -> content = nextNullableString()
                "downloadedAt" -> downloaded = nextNullableLong()
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && title.isNotBlank()) { "Dữ liệu chương không hợp lệ." }
            ChapterEntity(id, storyId, index, title, remote.ifBlank { id }, content, downloaded)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeProgress(items: List<ReadingProgressEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("storyId").value(item.storyId)
            name("chapterId").value(item.chapterId)
            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("totalParagraphs").value(item.totalParagraphs.toLong())
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readProgress(save: suspend (List<ReadingProgressEntity>) -> Unit): Int = readBatches(
        readItem = {
            var storyId = ""; var chapterId = ""; var paragraph = 0; var totalParagraphs = 0; var updated = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "totalParagraphs" -> totalParagraphs = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "updatedAt" -> updated = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(storyId.isNotBlank() && chapterId.isNotBlank()) { "Tiến độ đọc không hợp lệ." }
            ReadingProgressEntity(
                storyId = storyId,
                chapterId = chapterId,
                paragraphIndex = paragraph,
                totalParagraphs = totalParagraphs,
                updatedAt = updated,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeReadingHistory(items: List<ReadingHistoryEntity>) {
        beginArray()
        items.take(500).forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("sourceId").value(item.sourceId)
            name("storyTitle").value(item.storyTitle)
            name("chapterId").value(item.chapterId)
            name("chapterTitle").value(item.chapterTitle)
            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("totalParagraphs").value(item.totalParagraphs.toLong())
            name("visitedAt").value(item.visitedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readReadingHistory(
        save: suspend (List<ReadingHistoryEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var sourceId = ""; var storyTitle = ""
            var chapterId = ""; var chapterTitle = ""; var paragraph = 0
            var totalParagraphs = 0; var visitedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "sourceId" -> sourceId = nextStringSafe("")
                "storyTitle" -> storyTitle = nextStringSafe("").take(500)
                "chapterId" -> chapterId = nextStringSafe("")
                "chapterTitle" -> chapterTitle = nextStringSafe("").take(500)
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "totalParagraphs" -> totalParagraphs = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "visitedAt" -> visitedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && chapterId.isNotBlank()) { "Lịch sử đọc không hợp lệ." }
            ReadingHistoryEntity(
                id = id,
                storyId = storyId,
                sourceId = sourceId,
                storyTitle = storyTitle.ifBlank { "Truyện" },
                chapterId = chapterId,
                chapterTitle = chapterTitle.ifBlank { "Chương" },
                paragraphIndex = paragraph,
                totalParagraphs = totalParagraphs,
                visitedAt = visitedAt,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeBookmarks(items: List<BookmarkEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("chapterId").value(item.chapterId)
            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("label").value(item.label)
            name("createdAt").value(item.createdAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readBookmarks(save: suspend (List<BookmarkEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var chapterId = ""; var paragraph = 0; var label = ""; var created = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "label" -> label = nextStringSafe("")
                "createdAt" -> created = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && chapterId.isNotBlank()) { "Đánh dấu không hợp lệ." }
            BookmarkEntity(id, storyId, chapterId, paragraph, label, created)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeNotes(items: List<ChapterNoteEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("chapterId").value(item.chapterId)
            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("text").value(item.text)
            name("createdAt").value(item.createdAt)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readNotes(save: suspend (List<ChapterNoteEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var chapterId = ""; var paragraph = 0; var text = ""
            var createdAt = 0L; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "text" -> text = nextStringSafe("").take(4_000)
                "createdAt" -> createdAt = nextLongSafe(0L)
                "updatedAt" -> updatedAt = nextLongSafe(createdAt)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && chapterId.isNotBlank() && text.isNotBlank()) {
                "Ghi chú không hợp lệ."
            }
            ChapterNoteEntity(id, storyId, chapterId, paragraph, text, createdAt, updatedAt)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeFollowing(items: List<FollowedStoryEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("storyId").value(item.storyId)
            name("sourceId").value(item.sourceId)
            name("remoteUrl").value(item.remoteUrl)
            name("title").value(item.title)
            name("latestKnownChapter").value(item.latestKnownChapter)
            name("latestKnownChapterIndex").value(item.latestKnownChapterIndex.toLong())
            name("newChapterCount").value(item.newChapterCount.toLong())
            name("checkedAt").value(item.checkedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readFollowing(save: suspend (List<FollowedStoryEntity>) -> Unit): Int = readBatches(
        readItem = {
            var storyId = ""; var source = ""; var remote = ""; var title = ""; var latest = ""; var checked = 0L
            var latestIndex = -1; var newChapterCount = 0
            beginObject()
            while (hasNext()) when (nextName()) {
                "storyId" -> storyId = nextStringSafe("")
                "sourceId" -> source = nextStringSafe("")
                "remoteUrl" -> remote = nextStringSafe("")
                "title" -> title = nextStringSafe("")
                "latestKnownChapter" -> latest = nextStringSafe("")
                "latestKnownChapterIndex" -> latestIndex = nextLongSafe(-1L).toInt()
                "newChapterCount" -> newChapterCount = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "checkedAt" -> checked = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(storyId.isNotBlank() && source.isNotBlank() && title.isNotBlank()) { "Theo dõi không hợp lệ." }
            FollowedStoryEntity(
                storyId = storyId,
                sourceId = source,
                remoteUrl = remote.ifBlank { storyId },
                title = title,
                latestKnownChapter = latest,
                latestKnownChapterIndex = latestIndex,
                newChapterCount = newChapterCount,
                checkedAt = checked,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeDownloads(items: List<DownloadJobEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("sourceId").value(item.sourceId)
            name("state").value(item.state)
            name("completedChapters").value(item.completedChapters.toLong())
            name("totalChapters").value(item.totalChapters.toLong())
            name("errorMessage"); nullableValue(item.errorMessage)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readDownloads(save: suspend (List<DownloadJobEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var source = ""; var state = "CANCELLED"
            var completed = 0; var total = 0; var error: String? = null; var updated = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "sourceId" -> source = nextStringSafe("")
                "state" -> state = nextStringSafe("CANCELLED")
                "completedChapters" -> completed = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "totalChapters" -> total = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "errorMessage" -> error = nextNullableString()
                "updatedAt" -> updated = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && source.isNotBlank()) { "Tác vụ tải không hợp lệ." }
            val restoredState = if (state == "RUNNING" || state == "QUEUED") "CANCELLED" else state
            DownloadJobEntity(
                id = id,
                storyId = storyId,
                sourceId = source,
                state = restoredState,
                completedChapters = completed,
                totalChapters = total,
                errorMessage = error,
                updatedAt = updated,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writePronunciations(items: List<PronunciationEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("original").value(item.original)
            name("replacement").value(item.replacement)
            name("enabled").value(item.enabled)
            name("createdAt").value(item.createdAt)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readPronunciations(
        save: suspend (List<PronunciationEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = 0L; var original = ""; var replacement = ""; var enabled = true
            var createdAt = 0L; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextLongSafe(0L).coerceAtLeast(0L)
                "original" -> original = nextStringSafe("").trim()
                "replacement" -> replacement = nextStringSafe("").trim()
                "enabled" -> enabled = nextBooleanSafe(true)
                "createdAt" -> createdAt = nextLongSafe(0L)
                "updatedAt" -> updatedAt = nextLongSafe(createdAt)
                else -> skipValue()
            }
            endObject()
            require(original.isNotBlank() && replacement.isNotBlank()) { "Quy tắc phát âm không hợp lệ." }
            PronunciationEntity(id, original, replacement, enabled, createdAt, updatedAt)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeVietPhraseRules(items: List<VietPhraseEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("source").value(item.source)
            name("target").value(item.target)
            name("priority").value(item.priority.toLong())
            name("enabled").value(item.enabled)
            name("kind").value(item.kind)
            name("scope").value(item.scope)
            name("storyId").value(item.storyId)
            name("matchMode").value(item.matchMode)
            name("ignoreCase").value(item.ignoreCase)
            name("createdAt").value(item.createdAt)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readVietPhraseRules(
        save: suspend (List<VietPhraseEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = 0L; var source = ""; var target = ""; var priority = 0
            var enabled = true; var kind = "VIET_PHRASE"; var scope = "GLOBAL"; var storyId = ""
            var matchMode = "LITERAL"; var ignoreCase = false; var createdAt = 0L; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextLongSafe(0L).coerceAtLeast(0L)
                "source" -> source = nextStringSafe("").trim().take(2_000)
                "target" -> target = nextStringSafe("").trim().take(4_000)
                "priority" -> priority = nextLongSafe(0L).toInt().coerceIn(-999, 999)
                "enabled" -> enabled = nextBooleanSafe(true)
                "kind" -> kind = nextStringSafe("VIET_PHRASE").take(64)
                "scope" -> scope = nextStringSafe("GLOBAL").take(32)
                "storyId" -> storyId = nextNullableString().orEmpty().take(500)
                "matchMode" -> matchMode = nextStringSafe("LITERAL").take(32)
                "ignoreCase" -> ignoreCase = nextBooleanSafe(false)
                "createdAt" -> createdAt = nextLongSafe(0L)
                "updatedAt" -> updatedAt = nextLongSafe(createdAt)
                else -> skipValue()
            }
            endObject()
            require(source.isNotBlank() && target.isNotBlank()) { "Quy tắc VietPhrase không hợp lệ." }
            require(kind in VIETPHRASE_KINDS) { "Loại từ điển VietPhrase không hợp lệ: $kind" }
            require(scope in VIETPHRASE_SCOPES) { "Phạm vi VietPhrase không hợp lệ: $scope" }
            require(matchMode in VIETPHRASE_MATCH_MODES) { "Kiểu so khớp VietPhrase không hợp lệ: $matchMode" }
            require(scope != "STORY" || storyId.isNotBlank()) { "Quy tắc theo truyện phải có storyId." }
            VietPhraseEntity(
                id = id,
                source = source,
                target = target,
                priority = priority,
                enabled = enabled,
                kind = kind,
                scope = scope,
                storyId = storyId,
                matchMode = matchMode,
                ignoreCase = ignoreCase,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeVietPhraseSnapshots(items: List<VietPhraseSnapshotEntity>) {
        beginArray()
        items.take(20).forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("label").value(item.label)
            name("checksum").value(item.checksum)
            name("ruleCount").value(item.ruleCount.toLong())
            name("payloadBase64").value(Base64.getEncoder().encodeToString(item.payload))
            name("createdAt").value(item.createdAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readVietPhraseSnapshots(save: suspend (VietPhraseSnapshotEntity) -> Unit): Int {
        var count = 0
        beginArray()
        while (hasNext()) {
            var id = ""; var label = ""; var checksum = ""; var ruleCount = 0; var payload = ByteArray(0); var createdAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("").take(200)
                "label" -> label = nextStringSafe("").take(200)
                "checksum" -> checksum = nextStringSafe("").take(128)
                "ruleCount" -> ruleCount = nextLongSafe(0).toInt().coerceIn(0, 1_000_000)
                "payloadBase64" -> payload = Base64.getDecoder().decode(nextStringSafe(""))
                "createdAt" -> createdAt = nextLongSafe(0)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && checksum.length == 64 && payload.size <= 128 * 1024 * 1024) { "Snapshot VietPhrase không hợp lệ." }
            save(VietPhraseSnapshotEntity(id, label, checksum, ruleCount, payload, createdAt))
            count++
        }
        endArray()
        return count
    }

    private fun JsonWriter.writeVietPhraseDictionaryStates(items: List<VietPhraseDictionaryStateEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id); name("kind").value(item.kind); name("scope").value(item.scope)
            name("storyId").value(item.storyId)
            name("enabled").value(item.enabled); name("sourceName").value(item.sourceName); name("sourceFormat").value(item.sourceFormat)
            name("checksum").value(item.checksum); name("entryCount").value(item.entryCount.toLong()); name("revision").value(item.revision); name("importedAt").value(item.importedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readVietPhraseDictionaryStates(save: suspend (List<VietPhraseDictionaryStateEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var kind = "VIET_PHRASE"; var scope = "GLOBAL"; var storyId = ""; var enabled = true
            var sourceName = ""; var sourceFormat = ""; var checksum = ""; var entryCount = 0; var revision = 0L; var importedAt = 0L
            beginObject(); while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("").take(300); "kind" -> kind = nextStringSafe("VIET_PHRASE").take(64); "scope" -> scope = nextStringSafe("GLOBAL").take(32)
                "storyId" -> storyId = nextNullableString().orEmpty().take(500); "enabled" -> enabled = nextBooleanSafe(true); "sourceName" -> sourceName = nextStringSafe("").take(500)
                "sourceFormat" -> sourceFormat = nextStringSafe("").take(100); "checksum" -> checksum = nextStringSafe("").take(128); "entryCount" -> entryCount = nextLongSafe(0).toInt().coerceIn(0, 1_000_000)
                "revision" -> revision = nextLongSafe(0); "importedAt" -> importedAt = nextLongSafe(0); else -> skipValue()
            }; endObject()
            require(id.isNotBlank()) { "Trạng thái từ điển VietPhrase không hợp lệ." }
            require(kind in VIETPHRASE_KINDS) { "Loại trạng thái từ điển VietPhrase không hợp lệ: $kind" }
            require(scope in VIETPHRASE_SCOPES) { "Phạm vi trạng thái VietPhrase không hợp lệ: $scope" }
            require(scope != "STORY" || storyId.isNotBlank()) { "Trạng thái từ điển theo truyện phải có storyId." }
            require(checksum.isBlank() || checksum.matches(Regex("[0-9a-fA-F]{64}"))) { "Checksum từ điển VietPhrase không hợp lệ." }
            VietPhraseDictionaryStateEntity(id, kind, scope, storyId, enabled, sourceName, sourceFormat, checksum, entryCount, revision, importedAt)
        }, saveBatch = save,
    )

    private fun JsonWriter.writeVietPhraseSuggestions(items: List<VietPhraseSuggestionEntity>) {
        beginArray(); items.take(5_000).forEach { item ->
            beginObject(); name("id").value(item.id); name("source").value(item.source); name("proposedTarget").value(item.proposedTarget); name("editedTarget").value(item.editedTarget)
            name("reason").value(item.reason); name("contextText").value(item.contextText); name("storyId"); nullableValue(item.storyId)
            name("status").value(item.status); name("createdAt").value(item.createdAt); name("reviewedAt"); if (item.reviewedAt == null) nullValue() else value(item.reviewedAt)
            endObject()
        }; endArray()
    }

    private suspend fun JsonReader.readVietPhraseSuggestions(save: suspend (List<VietPhraseSuggestionEntity>) -> Unit): Int = readBatches(
        readItem = {
            var id = ""; var source = ""; var proposed = ""; var edited = ""; var reason = ""; var context = ""; var storyId: String? = null
            var status = "PENDING"; var createdAt = 0L; var reviewedAt: Long? = null
            beginObject(); while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("").take(200); "source" -> source = nextStringSafe("").take(2_000); "proposedTarget" -> proposed = nextStringSafe("").take(4_000)
                "editedTarget" -> edited = nextStringSafe("").take(4_000); "reason" -> reason = nextStringSafe("").take(2_000); "contextText" -> context = nextStringSafe("").take(8_000)
                "storyId" -> storyId = nextNullableString()?.take(500); "status" -> status = nextStringSafe("PENDING").take(32); "createdAt" -> createdAt = nextLongSafe(0)
                "reviewedAt" -> reviewedAt = if (peek() == JsonToken.NULL) { nextNull(); null } else nextLongSafe(0); else -> skipValue()
            }; endObject()
            require(id.isNotBlank() && source.isNotBlank() && proposed.isNotBlank()) { "Suggestion VietPhrase không hợp lệ." }
            require(status in VIETPHRASE_SUGGESTION_STATUSES) { "Trạng thái suggestion VietPhrase không hợp lệ: $status" }
            VietPhraseSuggestionEntity(id, source, proposed, edited.ifBlank { proposed }, reason, context, storyId, status, createdAt, reviewedAt)
        }, saveBatch = save,
    )

    private fun JsonWriter.writeStoryVoiceProfiles(items: List<StoryTtsProfileEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("storyId").value(item.storyId)
            name("rate").value(item.rate.toDouble())
            name("pitch").value(item.pitch.toDouble())
            name("volume").value(item.volume.toDouble())
            name("enginePackage"); nullableValue(item.enginePackage)
            name("voiceName"); nullableValue(item.voiceName)
            name("languageTag").value(item.languageTag)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readStoryVoiceProfiles(
        save: suspend (List<StoryTtsProfileEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var storyId = ""; var rate = 1.0f; var pitch = 1.0f; var volume = 1.0f
            var enginePackage: String? = null; var voiceName: String? = null; var languageTag = "vi-VN"; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "storyId" -> storyId = nextStringSafe("")
                "rate" -> rate = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2.0f)
                "pitch" -> pitch = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2.0f)
                "volume" -> volume = nextDoubleSafe(1.0).toFloat().coerceIn(0.05f, 1.0f)
                "enginePackage" -> enginePackage = nextNullableString()
                "voiceName" -> voiceName = nextNullableString()
                "languageTag" -> languageTag = nextStringSafe("vi-VN").ifBlank { "vi-VN" }
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(storyId.isNotBlank()) { "Hồ sơ giọng theo truyện không hợp lệ." }
            StoryTtsProfileEntity(storyId, rate, pitch, volume, enginePackage?.takeIf(String::isNotBlank), voiceName?.takeIf(String::isNotBlank), languageTag, updatedAt)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeStoryAiProfiles(items: List<StoryAiProfileEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("storyId").value(item.storyId)
            name("mode").value(item.mode)
            name("overrideProvider").value(item.overrideProvider)
            name("provider").value(item.provider)
            name("endpoint").value(item.endpoint)
            name("model").value(item.model)
            name("temperature").value(item.temperature.toDouble())
            name("useCustomPrompts").value(item.useCustomPrompts)
            name("translationPrompt").value(item.translationPrompt)
            name("improvePrompt").value(item.improvePrompt)
            name("autoRunOnOpen").value(item.autoRunOnOpen)
            name("useCustomVoiceCastPrompt").value(item.useCustomVoiceCastPrompt)
            name("voiceCastPrompt").value(item.voiceCastPrompt)
            name("voiceCastNote").value(item.voiceCastNote)
            name("voiceCastDialogueOnly").value(item.voiceCastDialogueOnly)
            name("voiceCastStableNarrator").value(item.voiceCastStableNarrator)
            name("expressiveAdjustment").value(item.expressiveAdjustment)
            name("expressionPrompt").value(item.expressionPrompt)
            name("expressionSpeedLimitPct").value(item.expressionSpeedLimitPct.toLong())
            name("expressionPitchLimitPct").value(item.expressionPitchLimitPct.toLong())
            name("expressionVolumeLimitPct").value(item.expressionVolumeLimitPct.toLong())
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readStoryAiProfiles(
        save: suspend (List<StoryAiProfileEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var storyId = ""; var mode = "INHERIT"; var overrideProvider = false
            var provider = AiProvider.OPENAI_COMPATIBLE.name; var endpoint = ""; var model = ""
            var temperature = -1f; var useCustomPrompts = false; var translationPrompt = ""
            var improvePrompt = ""; var autoRunOnOpen = false
            var useCustomVoiceCastPrompt = false; var voiceCastPrompt = ""; var voiceCastNote = ""
            var voiceCastDialogueOnly = true; var voiceCastStableNarrator = true; var expressiveAdjustment = true
            var expressionPrompt = ""; var expressionSpeedLimitPct = 10; var expressionPitchLimitPct = 10
            var expressionVolumeLimitPct = 10; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "storyId" -> storyId = nextStringSafe("")
                "mode" -> mode = nextStringSafe("INHERIT").uppercase().takeIf { it in STORY_AI_MODES } ?: "INHERIT"
                "overrideProvider" -> overrideProvider = nextBooleanSafe(false)
                "provider" -> provider = nextStringSafe(AiProvider.OPENAI_COMPATIBLE.name)
                    .uppercase().takeIf { it in STORY_AI_PROVIDERS } ?: AiProvider.OPENAI_COMPATIBLE.name
                "endpoint" -> endpoint = nextStringSafe("").trim().take(500)
                "model" -> model = nextStringSafe("").trim().take(200)
                "temperature" -> temperature = nextDoubleSafe(-1.0).toFloat().let { if (it in 0f..1f) it else -1f }
                "useCustomPrompts" -> useCustomPrompts = nextBooleanSafe(false)
                "translationPrompt" -> translationPrompt = nextStringSafe("").trim().take(8_000)
                "improvePrompt" -> improvePrompt = nextStringSafe("").trim().take(12_000)
                "autoRunOnOpen" -> autoRunOnOpen = nextBooleanSafe(false)
                "useCustomVoiceCastPrompt" -> useCustomVoiceCastPrompt = nextBooleanSafe(false)
                "voiceCastPrompt" -> voiceCastPrompt = nextStringSafe("").trim().take(12_000)
                "voiceCastNote" -> voiceCastNote = nextStringSafe("").trim().take(4_000)
                "voiceCastDialogueOnly" -> voiceCastDialogueOnly = nextBooleanSafe(true)
                "voiceCastStableNarrator" -> voiceCastStableNarrator = nextBooleanSafe(true)
                "expressiveAdjustment" -> expressiveAdjustment = nextBooleanSafe(true)
                "expressionPrompt" -> expressionPrompt = nextStringSafe("").trim().take(8_000)
                "expressionSpeedLimitPct" -> expressionSpeedLimitPct = nextLongSafe(10L).toInt().coerceIn(0, 50)
                "expressionPitchLimitPct" -> expressionPitchLimitPct = nextLongSafe(10L).toInt().coerceIn(0, 50)
                "expressionVolumeLimitPct" -> expressionVolumeLimitPct = nextLongSafe(10L).toInt().coerceIn(0, 50)
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(storyId.isNotBlank()) { "Hồ sơ AI theo truyện không hợp lệ." }
            val validTranslationPrompt = translationPrompt.isBlank() || "{{CHAPTER_TEXT}}" in translationPrompt
            val validImprovePrompt = improvePrompt.isBlank() ||
                ("{{SOURCE_TEXT}}" in improvePrompt && "{{VIETPHRASE_TEXT}}" in improvePrompt)
            val safeCustomPrompts = useCustomPrompts && validTranslationPrompt && validImprovePrompt
            val safeModel = model.ifBlank {
                if (overrideProvider && provider == AiProvider.GEMINI.name) SettingsRepository.DEFAULT_GEMINI_MODEL else ""
            }
            val validVoiceCastPrompt = voiceCastPrompt.isBlank() || "{{CHAPTER_TEXT}}" in voiceCastPrompt
            val safeCustomVoiceCastPrompt = useCustomVoiceCastPrompt && validVoiceCastPrompt
            StoryAiProfileEntity(
                storyId = storyId,
                mode = mode,
                overrideProvider = overrideProvider,
                provider = provider,
                endpoint = endpoint,
                model = safeModel,
                temperature = temperature,
                useCustomPrompts = safeCustomPrompts,
                translationPrompt = if (safeCustomPrompts) translationPrompt else "",
                improvePrompt = if (safeCustomPrompts) improvePrompt else "",
                autoRunOnOpen = autoRunOnOpen && mode != "INHERIT",
                useCustomVoiceCastPrompt = safeCustomVoiceCastPrompt,
                voiceCastPrompt = if (safeCustomVoiceCastPrompt) voiceCastPrompt else "",
                voiceCastNote = voiceCastNote,
                voiceCastDialogueOnly = voiceCastDialogueOnly,
                voiceCastStableNarrator = voiceCastStableNarrator,
                expressiveAdjustment = expressiveAdjustment,
                expressionPrompt = expressionPrompt,
                expressionSpeedLimitPct = expressionSpeedLimitPct,
                expressionPitchLimitPct = expressionPitchLimitPct,
                expressionVolumeLimitPct = expressionVolumeLimitPct,
                updatedAt = updatedAt,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeVoiceRoles(items: List<VoiceRoleEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("roleName").value(item.roleName)
            name("aliasesCsv").value(item.aliasesCsv)
            name("description").value(item.description)
            name("enginePackage"); nullableValue(item.enginePackage)
            name("voiceName"); nullableValue(item.voiceName)
            name("languageTag").value(item.languageTag)
            name("rate").value(item.rate.toDouble())
            name("pitch").value(item.pitch.toDouble())
            name("volume").value(item.volume.toDouble())
            name("expression").value(item.expression)
            name("expressionStrength").value(item.expressionStrength.toDouble())
            name("sonicSpeed").value(item.sonicSpeed.toDouble())
            name("sonicPitch").value(item.sonicPitch.toDouble())
            name("isNarrator").value(item.isNarrator)
            name("enabled").value(item.enabled)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readVoiceRoles(
        save: suspend (List<VoiceRoleEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var roleName = ""; var aliases = ""; var description = ""
            var enginePackage: String? = null; var voiceName: String? = null; var languageTag = "vi-VN"
            var rate = 1f; var pitch = 1f; var volume = 1f
            var expression = "NEUTRAL"; var expressionStrength = 0.5f; var sonicSpeed = 1f; var sonicPitch = 1f
            var narrator = false; var enabled = true; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "roleName" -> roleName = nextStringSafe("")
                "aliasesCsv" -> aliases = nextStringSafe("")
                "description" -> description = nextStringSafe("").take(1000)
                "enginePackage" -> enginePackage = nextNullableString()
                "voiceName" -> voiceName = nextNullableString()
                "languageTag" -> languageTag = nextStringSafe("vi-VN")
                "rate" -> rate = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2f)
                "pitch" -> pitch = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2f)
                "volume" -> volume = nextDoubleSafe(1.0).toFloat().coerceIn(0.05f, 1f)
                "expression" -> expression = nextStringSafe("NEUTRAL").take(32)
                "expressionStrength" -> expressionStrength = nextDoubleSafe(0.5).toFloat().coerceIn(0f, 1f)
                "sonicSpeed" -> sonicSpeed = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2f)
                "sonicPitch" -> sonicPitch = nextDoubleSafe(1.0).toFloat().coerceIn(0.5f, 2f)
                "isNarrator" -> narrator = nextBooleanSafe(false)
                "enabled" -> enabled = nextBooleanSafe(true)
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && roleName.isNotBlank()) { "Vai giọng không hợp lệ." }
            VoiceRoleEntity(
                id = id, storyId = storyId, roleName = roleName, aliasesCsv = aliases, description = description,
                enginePackage = enginePackage?.takeIf(String::isNotBlank), voiceName = voiceName,
                languageTag = languageTag, rate = rate, pitch = pitch, volume = volume,
                expression = expression, expressionStrength = expressionStrength,
                sonicSpeed = sonicSpeed, sonicPitch = sonicPitch,
                isNarrator = narrator, enabled = enabled, updatedAt = updatedAt,
            )
        },
        saveBatch = save,
    )


    private fun JsonWriter.writeChapterTransforms(items: List<ChapterTransformEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("chapterId").value(item.chapterId)
            name("kind").value(item.kind)
            name("provider").value(item.provider)
            name("model").value(item.model)
            name("sourceSha256").value(item.sourceSha256)
            name("transformedText").value(item.transformedText)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readChapterTransforms(
        save: suspend (List<ChapterTransformEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var chapterId = ""; var kind = ""
            var provider = ""; var model = ""; var sourceSha = ""; var text = ""; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "kind" -> kind = nextStringSafe("")
                "provider" -> provider = nextStringSafe("")
                "model" -> model = nextStringSafe("")
                "sourceSha256" -> sourceSha = nextStringSafe("")
                "transformedText" -> text = nextStringSafe("").take(4_000_000)
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && chapterId.isNotBlank() && kind.isNotBlank()) {
                "Kế hoạch AI chương không hợp lệ."
            }
            ChapterTransformEntity(id, storyId, chapterId, kind, provider, model, sourceSha, text, updatedAt)
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeVoiceAssignments(items: List<ChapterVoiceAssignmentEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("chapterId").value(item.chapterId)
            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("roleName").value(item.roleName)
            name("confidence").value(item.confidence.toDouble())
            name("speedAdjustPct").value(item.speedAdjustPct.toDouble())
            name("pitchAdjustPct").value(item.pitchAdjustPct.toDouble())
            name("volumeAdjustPct").value(item.volumeAdjustPct.toDouble())
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readVoiceAssignments(
        save: suspend (List<ChapterVoiceAssignmentEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var chapterId = ""; var paragraph = 0
            var roleName = ""; var confidence = 0f
            var speedAdjustPct = 0f; var pitchAdjustPct = 0f; var volumeAdjustPct = 0f; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "roleName" -> roleName = nextStringSafe("").take(80)
                "confidence" -> confidence = nextDoubleSafe(0.0).toFloat().coerceIn(0f, 1f)
                "speedAdjustPct" -> speedAdjustPct = nextDoubleSafe(0.0).toFloat().coerceIn(-50f, 50f)
                "pitchAdjustPct" -> pitchAdjustPct = nextDoubleSafe(0.0).toFloat().coerceIn(-50f, 50f)
                "volumeAdjustPct" -> volumeAdjustPct = nextDoubleSafe(0.0).toFloat().coerceIn(-50f, 50f)
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && chapterId.isNotBlank() && roleName.isNotBlank()) {
                "Phân vai chương không hợp lệ."
            }
            ChapterVoiceAssignmentEntity(
                id = id,
                storyId = storyId,
                chapterId = chapterId,
                paragraphIndex = paragraph,
                roleName = roleName,
                confidence = confidence,
                speedAdjustPct = speedAdjustPct,
                pitchAdjustPct = pitchAdjustPct,
                volumeAdjustPct = volumeAdjustPct,
                updatedAt = updatedAt,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeSceneMusicTracks(items: List<SceneMusicTrackEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("title").value(item.title)
            name("uri").value(item.uri)
            name("tagsCsv").value(item.tagsCsv)
            name("volume").value(item.volume.toDouble())
            name("enabled").value(item.enabled)
            name("loudnessLufsEstimate").value(item.loudnessLufsEstimate.toDouble())
            name("playCount").value(item.playCount.toLong())
            name("lastPlayedAt").value(item.lastPlayedAt)
            name("orderIndex").value(item.orderIndex.toLong())
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readSceneMusicTracks(
        save: suspend (List<SceneMusicTrackEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = ""; var title = ""; var uri = ""; var tags = ""; var volume = 1f
            var enabled = false; var loudness = -18f; var playCount = 0; var lastPlayedAt = 0L; var orderIndex = 0; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "title" -> title = nextStringSafe("").take(120)
                "uri" -> uri = nextStringSafe("").take(2_000)
                "tagsCsv" -> tags = nextStringSafe("").take(500)
                "volume" -> volume = nextDoubleSafe(1.0).toFloat().coerceIn(0f, 1f)
                "enabled" -> enabled = nextBooleanSafe(false)
                "loudnessLufsEstimate" -> loudness = nextDoubleSafe(-18.0).toFloat().coerceIn(-80f, 0f)
                "playCount" -> playCount = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "lastPlayedAt" -> lastPlayedAt = nextLongSafe(0L).coerceAtLeast(0L)
                "orderIndex" -> orderIndex = nextLongSafe(0L).toInt()
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && title.isNotBlank() && uri.isNotBlank()) { "Track nhạc cảnh không hợp lệ." }
            // URI có thể không còn quyền trên thiết bị mới; khôi phục ở trạng thái tắt để người dùng xác nhận lại.
            SceneMusicTrackEntity(
                id = id, title = title, uri = uri, tagsCsv = tags, volume = volume, enabled = false,
                loudnessLufsEstimate = loudness, playCount = playCount, lastPlayedAt = lastPlayedAt,
                orderIndex = orderIndex, updatedAt = updatedAt,
            )
        },
        saveBatch = save,
    )

    private fun JsonWriter.writeSceneMusicCues(items: List<SceneMusicCueEntity>) {
        beginArray()
        items.forEach { item ->
            beginObject()
            name("id").value(item.id)
            name("storyId").value(item.storyId)
            name("chapterId").value(item.chapterId)
            name("startParagraph").value(item.startParagraph.toLong())
            name("trackId").value(item.trackId)
            name("volume").value(item.volume.toDouble())
            name("mood").value(item.mood)
            name("updatedAt").value(item.updatedAt)
            endObject()
        }
        endArray()
    }

    private suspend fun JsonReader.readSceneMusicCues(
        save: suspend (List<SceneMusicCueEntity>) -> Unit,
    ): Int = readBatches(
        readItem = {
            var id = ""; var storyId = ""; var chapterId = ""; var start = 0
            var trackId = ""; var volume = 0.2f; var mood = ""; var updatedAt = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "id" -> id = nextStringSafe("")
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "startParagraph" -> start = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "trackId" -> trackId = nextStringSafe("")
                "volume" -> volume = nextDoubleSafe(0.2).toFloat().coerceIn(0f, 1f)
                "mood" -> mood = nextStringSafe("").take(120)
                "updatedAt" -> updatedAt = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(id.isNotBlank() && storyId.isNotBlank() && chapterId.isNotBlank() && trackId.isNotBlank()) {
                "Cue nhạc cảnh không hợp lệ."
            }
            SceneMusicCueEntity(id, storyId, chapterId, start, trackId, volume, mood, updatedAt)
        },
        saveBatch = save,
    )

    private fun JsonWriter.nullableValue(item: String?) {
        if (item == null) nullValue() else value(item)
    }

    private fun JsonWriter.nullableValue(item: Long?) {
        if (item == null) nullValue() else value(item)
    }

    private fun JsonReader.nextStringSafe(default: String): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); default }
        JsonToken.STRING -> nextString()
        else -> { skipValue(); default }
    }

    private fun JsonReader.nextNullableString(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING -> nextString()
        else -> { skipValue(); null }
    }

    private fun JsonReader.nextLongSafe(default: Long): Long = when (peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> nextString().toLongOrNull() ?: default
        JsonToken.NULL -> { nextNull(); default }
        else -> { skipValue(); default }
    }

    private fun JsonReader.nextNullableLong(): Long? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.NUMBER -> nextLong()
        JsonToken.STRING -> nextString().toLongOrNull()
        else -> { skipValue(); null }
    }

    private fun JsonReader.nextDoubleSafe(default: Double): Double = when (peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> nextString().toDoubleOrNull() ?: default
        JsonToken.NULL -> { nextNull(); default }
        else -> { skipValue(); default }
    }

    private fun JsonReader.nextBooleanSafe(default: Boolean): Boolean = when (peek()) {
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.STRING -> nextString().toBooleanStrictOrNull() ?: default
        JsonToken.NULL -> { nextNull(); default }
        else -> { skipValue(); default }
    }

    private fun ZipInputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        copyBounded(output, maxBytes.toLong())
        return output.toByteArray()
    }

    private fun InputStream.copyBounded(output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Mục sao lưu vượt giới hạn an toàn.")
            output.write(buffer, 0, read)
        }
    }
}

enum class BackupComponent(val label: String) {
    SETTINGS("Cài đặt ứng dụng"),
    LIBRARY("Thư viện và chương"),
    READING("Lịch sử, tiến độ, ghi chú và phát âm"),
    AI_VOICE("AI, giọng đọc và phân vai"),
    VIETPHRASE("VietPhrase và đề xuất"),
    SOURCES_EXTENSIONS("Nguồn, extension và dữ liệu nguồn"),
    SCENE_MUSIC("Nhạc cảnh"),
}

data class BackupSummary(
    val stories: Int,
    val chapters: Int,
    val bookmarks: Int,
    val notes: Int,
    val readingHistory: Int = 0,
    val following: Int,
    val pronunciations: Int = 0,
    val storyVoiceProfiles: Int = 0,
    val storyAiProfiles: Int = 0,
    val voiceRoles: Int = 0,
    val vietPhraseRules: Int = 0,
    val chapterTransforms: Int = 0,
    val voiceAssignments: Int = 0,
    val sceneMusicTracks: Int = 0,
    val sceneMusicCues: Int = 0,
    val sourceFiles: Int = 0,
    val sceneMusicFiles: Int = 0,
    val attachmentBytes: Long = 0L,
    val components: Set<BackupComponent> = emptySet(),
)
