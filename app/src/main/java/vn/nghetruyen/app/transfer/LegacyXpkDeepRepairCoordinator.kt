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
import vn.nghetruyen.app.ai.StoryVoiceCastMode
import vn.nghetruyen.app.ai.StoryVoiceCastReferenceCodec
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.ReadingHistoryEntity
import vn.nghetruyen.app.data.local.ReadingProgressEntity
import vn.nghetruyen.app.data.local.StoryAiProfileEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.sourceplatform.UnifiedSourcePlatformManager
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtra
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream











class LegacyXpkDeepRepairCoordinator(
    context: Context,
    private val delegate: LegacyXpkVerifiedRestoreCoordinator,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val sourcePlatformManager: UnifiedSourcePlatformManager,
    private val onSourcesChanged: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class DeepRepair(
        val legacyChaptersExpected: Int = 0,
        val legacyChaptersPersisted: Int = 0,
        val chapterIndicesRebuilt: Int = 0,
        val globalVoiceRolesImported: Int = 0,
        val storyVoiceSettingsImported: Int = 0,
        val storyVoiceRolesImported: Int = 0,
        val vBookPackagesActivated: Int = 0,
        val nativeLuaActivated: Int = 0,
        val settingsPatched: Int = 0,
        val warnings: List<String> = emptyList(),
    )

    data class RestoreSummary(
        val base: LegacyXpkVerifiedRestoreCoordinator.RestoreSummary,
        val repair: DeepRepair,
        val issues: List<String>,
    ) {
        val isComplete: Boolean
            get() = issues.isEmpty() && base.restored.downloadFilesUnconverted == 0

        fun userMessage(): String = buildString {
            append(if (isComplete) "Đã khôi phục XPK và hậu kiểm sâu thành công. " else "Đã khôi phục XPK nhưng vẫn còn mục cần chú ý. ")
            if (repair.legacyChaptersExpected > 0) {
                append("Chương legacy trong Room: ")
                    .append(repair.legacyChaptersPersisted).append('/').append(repair.legacyChaptersExpected)
                    .append("; đã dựng lại ").append(repair.chapterIndicesRebuilt).append(" thứ tự chương. ")
            }
            if (repair.globalVoiceRolesImported > 0 || repair.storyVoiceRolesImported > 0) {
                append("Phân vai: ").append(repair.globalVoiceRolesImported).append(" vai chung, ")
                    .append(repair.storyVoiceRolesImported).append(" vai riêng cho truyện; ")
                    .append(repair.storyVoiceSettingsImported).append(" cấu hình truyện. ")
            }
            if (repair.vBookPackagesActivated + repair.nativeLuaActivated > 0) {
                append("Đã kích hoạt ")
                    .append(repair.vBookPackagesActivated + repair.nativeLuaActivated)
                    .append(" tiện ích từ XPK. ")
            }
            if (issues.isNotEmpty()) append("Cảnh báo đầu tiên: ").append(issues.first().take(300))
        }

        fun diagnosticMessage(): String = buildString {
            append(userMessage())
            append(" Hậu kiểm trước: ").append(base.verification.shortText())
            append(" DeepRepair[ch=" )
                .append(repair.legacyChaptersPersisted).append('/').append(repair.legacyChaptersExpected)
                .append(", rebuilt=").append(repair.chapterIndicesRebuilt)
                .append(", globalRoles=").append(repair.globalVoiceRolesImported)
                .append(", storyVoiceSettings=").append(repair.storyVoiceSettingsImported)
                .append(", storyRoles=").append(repair.storyVoiceRolesImported)
                .append(", vBook=").append(repair.vBookPackagesActivated)
                .append(", lua=").append(repair.nativeLuaActivated)
                .append(", settings=").append(repair.settingsPatched)
                .append("]")
            if (base.restored.downloadFilesPreserved > 0) {
                append(" Downloads[preserved=").append(base.restored.downloadFilesPreserved)
                    .append(", unconverted=").append(base.restored.downloadFilesUnconverted).append("]")
            }
            if (issues.isNotEmpty()) {
                append(" Chi tiết: ")
                append(issues.take(10).joinToString(" || ") { it.replace('\n', ' ').take(260) })
            }
        }
    }

    suspend fun inspect(source: Uri): AppResult<LegacyXpkBackupImporter.Inspection> = delegate.inspect(source)

    suspend fun restoreFrom(
        source: Uri,
        requestedComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<RestoreSummary> = withContext(Dispatchers.IO) {
        val requested = requestedComponents.ifEmpty { BackupComponent.entries.toSet() }
        when (val base = delegate.restoreFrom(source, requested)) {
            is AppResult.Failure -> base
            is AppResult.Success -> {
                val stage = File(appContext.cacheDir, "legacy_xpk_deep_${System.nanoTime()}")
                try {
                    if (!stage.mkdirs()) throw IOException("Không tạo được vùng tạm deep-repair XPK.")
                    resolver.openInputStream(source)?.use { extractRepairPayloads(it, stage, requested) }
                        ?: throw IOException("Không mở lại được bản sao lưu XPK để hậu kiểm sâu.")

                    val warnings = mutableListOf<String>()
                    var chapterRepair = ChapterRepair()
                    if ((BackupComponent.LIBRARY in requested || BackupComponent.READING in requested) &&
                        stage.resolve(LEGACY_DB_ENTRY).isFile
                    ) {
                        chapterRepair = repairLegacyChapters(stage.resolve(LEGACY_DB_ENTRY), warnings)
                    }

                    var voiceRepair = VoiceRepair()
                    if (BackupComponent.AI_VOICE in requested) {
                        voiceRepair = repairVoiceCast(
                            databaseFile = stage.resolve(LEGACY_DB_ENTRY).takeIf(File::isFile),
                            settingsFile = stage.resolve("settings.json").takeIf(File::isFile),
                            warnings = warnings,
                        )
                    }

                    var settingsPatched = 0
                    if (BackupComponent.SETTINGS in requested) {
                        settingsPatched = patchMissingSettings(stage.resolve("settings.json").takeIf(File::isFile), warnings)
                    }

                    val extensionRepair = if (BackupComponent.SOURCES_EXTENSIONS in requested) {
                        retryLegacyExtensions(stage, warnings)
                    } else ExtensionRepair()

                    val repair = DeepRepair(
                        legacyChaptersExpected = chapterRepair.expected,
                        legacyChaptersPersisted = chapterRepair.persisted,
                        chapterIndicesRebuilt = chapterRepair.rebuilt,
                        globalVoiceRolesImported = voiceRepair.globalRoles,
                        storyVoiceSettingsImported = voiceRepair.storySettings,
                        storyVoiceRolesImported = voiceRepair.storyRoles,
                        vBookPackagesActivated = extensionRepair.vBookActivated,
                        nativeLuaActivated = extensionRepair.luaActivated,
                        settingsPatched = settingsPatched,
                        warnings = warnings.distinct(),
                    )
                    val issues = finalIssues(base.value, repair, extensionRepair)
                    AppResult.Success(RestoreSummary(base.value, repair, issues))
                } catch (error: Exception) {
                    AppResult.Failure(
                        "LEGACY_XPK_DEEP_REPAIR_FAILED",
                        error.message ?: "Không hoàn tất được hậu kiểm sâu XPK.",
                        error,
                    )
                } finally {
                    stage.deleteRecursively()
                }
            }
        }
    }

    private data class LegacyChapter(
        val url: String,
        val storyUrl: String,
        val source: String,
        val storyTitle: String,
        val title: String,
        val orderIndex: Int,
        val readAt: Long,
        val downloadedAt: Long,
    )

    private data class ChapterRepair(
        val expected: Int = 0,
        val persisted: Int = 0,
        val rebuilt: Int = 0,
    )

    private suspend fun repairLegacyChapters(file: File, warnings: MutableList<String>): ChapterRepair {
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            require(legacy.hasTable("chapters")) { "Database XPK không có bảng chapters." }
            val rows = mutableListOf<LegacyChapter>()
            legacy.rawQuery(
                "SELECT url,story_url,source,story_title,title,order_index,read_at,downloaded_at FROM chapters",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val url = cursor.string("url")
                    val storyUrl = cursor.string("story_url").ifBlank { deriveStoryUrl(url) }
                    if (url.isBlank() || storyUrl.isBlank()) continue
                    rows += LegacyChapter(
                        url = url,
                        storyUrl = storyUrl,
                        source = cursor.string("source"),
                        storyTitle = cursor.string("story_title").ifBlank { "Truyện" },
                        title = cursor.string("title").ifBlank { "Chương truyện" },
                        orderIndex = cursor.int("order_index"),
                        readAt = cursor.long("read_at"),
                        downloadedAt = cursor.long("downloaded_at"),
                    )
                }
            }
            if (rows.isEmpty()) return ChapterRepair()

            val baseIdGroups = rows.groupBy { stableChapterId(it.url) }
            val resolvedIdByUrl = HashMap<String, String>(rows.size)
            baseIdGroups.forEach { (baseId, group) ->
                val distinctUrls = group.map { canonicalRawUrl(it.url) }.distinct()
                group.forEach { row ->
                    resolvedIdByUrl[row.url] = if (distinctUrls.size <= 1) baseId else stableChapterFullId(row.url)
                }
                if (distinctUrls.size > 1) {
                    warnings += "Phát hiện ${distinctUrls.size} URL chương trùng stable-id cũ; đã dùng ID đầy đủ có query để tránh mất chương."
                }
            }

            val existingStories = database.storyDao().listAll().associateBy(StoryEntity::id)
            val existingChapters = database.chapterDao().listAll()
            val existingByStoryUrl = existingChapters.associateBy { it.storyId to canonicalRawUrl(it.remoteUrl) }
            val expectedIds = linkedSetOf<String>()
            var rebuilt = 0

            database.withTransaction {
                rows.groupBy { stableStoryId(it.storyUrl) }.forEach { (storyId, storyRows) ->
                    val legacyUrlKeys = storyRows.mapTo(hashSetOf()) { canonicalRawUrl(it.url) }
                    val occupied = existingChapters.asSequence()
                        .filter { it.storyId == storyId && canonicalRawUrl(it.remoteUrl) !in legacyUrlKeys }
                        .mapTo(hashSetOf()) { it.chapterIndex }
                    val assigned = hashSetOf<Int>()

                    val sorted = storyRows.sortedWith(
                        compareBy<LegacyChapter> { preferredChapterIndex(it) ?: Int.MAX_VALUE }
                            .thenBy { it.readAt.takeIf { value -> value > 0 } ?: Long.MAX_VALUE }
                            .thenBy { it.downloadedAt.takeIf { value -> value > 0 } ?: Long.MAX_VALUE }
                            .thenBy { canonicalRawUrl(it.url) },
                    )

                    var fallback = 0
                    val entities = ArrayList<ChapterEntity>(sorted.size)
                    sorted.forEach { row ->
                        val preferred = preferredChapterIndex(row)
                        var index = preferred ?: nextFreeIndex(fallback, occupied, assigned)
                        while (index in occupied || index in assigned) index += 1
                        assigned += index
                        fallback = maxOf(fallback, index + 1)

                        val id = resolvedIdByUrl.getValue(row.url)
                        expectedIds += id
                        val previous = existingByStoryUrl[storyId to canonicalRawUrl(row.url)]
                            ?: existingChapters.firstOrNull { it.id == id }
                        entities += ChapterEntity(
                            id = id,
                            storyId = storyId,
                            chapterIndex = index,
                            title = row.title,
                            remoteUrl = row.url,
                            content = previous?.content,
                            downloadedAt = previous?.downloadedAt,
                        )
                        rebuilt += 1
                    }

                    val currentStory = existingStories[storyId]
                    val first = storyRows.first()
                    database.storyDao().upsert(
                        currentStory?.copy(
                            title = currentStory.title.ifBlank { first.storyTitle },
                            remoteUrl = currentStory.remoteUrl.ifBlank { first.storyUrl },
                            updatedAt = maxOf(currentStory.updatedAt, first.readAt, first.downloadedAt, System.currentTimeMillis()),
                        ) ?: StoryEntity(
                            id = storyId,
                            sourceId = normalizeSourceId(first.source),
                            title = first.storyTitle,
                            author = "",
                            description = "",
                            coverUrl = null,
                            remoteUrl = first.storyUrl,
                            isOffline = false,
                            updatedAt = maxOf(first.readAt, first.downloadedAt, System.currentTimeMillis()),
                        ),
                    )
                    database.chapterDao().upsertAll(entities)
                }

                repairLegacyProgress(legacy, resolvedIdByUrl)
                repairLegacyHistory(legacy, resolvedIdByUrl)
                repairLegacyBookmarks(legacy, resolvedIdByUrl)
            }

            val persistedIds = database.chapterDao().listAll().mapTo(hashSetOf()) { it.id }
            return ChapterRepair(
                expected = expectedIds.size,
                persisted = expectedIds.count { it in persistedIds },
                rebuilt = rebuilt,
            )
        } finally {
            legacy.close()
        }
    }

    private suspend fun repairLegacyProgress(db: SQLiteDatabase, ids: Map<String, String>) {
        if (!db.hasTable("stories")) return
        db.rawQuery(
            "SELECT story_url,current_url,para,total_para,last_read_at FROM stories WHERE current_url<>''",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                val chapterUrl = cursor.string("current_url")
                if (storyUrl.isBlank() || chapterUrl.isBlank()) continue
                database.progressDao().save(
                    ReadingProgressEntity(
                        storyId = stableStoryId(storyUrl),
                        chapterId = ids[chapterUrl] ?: stableChapterId(chapterUrl),
                        paragraphIndex = legacyParagraphIndex(cursor.int("para")),
                        totalParagraphs = cursor.int("total_para").coerceAtLeast(0),
                        updatedAt = cursor.long("last_read_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun repairLegacyHistory(db: SQLiteDatabase, ids: Map<String, String>) {
        if (!db.hasTable("reading_history")) return
        db.rawQuery(
            "SELECT source,story_url,chapter_url,title,para,read_at FROM reading_history ORDER BY read_at DESC LIMIT 500",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                val chapterUrl = cursor.string("chapter_url")
                if (storyUrl.isBlank() || chapterUrl.isBlank()) continue
                val storyId = stableStoryId(storyUrl)
                val chapterId = ids[chapterUrl] ?: stableChapterId(chapterUrl)
                database.readingHistoryDao().upsert(
                    ReadingHistoryEntity(
                        id = stableId("history", "$storyId\u0000$chapterId"),
                        storyId = storyId,
                        sourceId = normalizeSourceId(cursor.string("source")),
                        storyTitle = database.storyDao().get(storyId)?.title.orEmpty().ifBlank { "Truyện" },
                        chapterId = chapterId,
                        chapterTitle = cursor.string("title").ifBlank { "Chương truyện" },
                        paragraphIndex = legacyParagraphIndex(cursor.int("para")),
                        totalParagraphs = 0,
                        visitedAt = cursor.long("read_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        database.readingHistoryDao().prune(500)
    }

    private suspend fun repairLegacyBookmarks(db: SQLiteDatabase, ids: Map<String, String>) {
        if (!db.hasTable("bookmarks")) return
        db.rawQuery("SELECT story_url,chapter_url,title,para,created_at FROM bookmarks", null).use { cursor ->
            while (cursor.moveToNext()) {
                val storyUrl = cursor.string("story_url")
                val chapterUrl = cursor.string("chapter_url")
                if (storyUrl.isBlank() || chapterUrl.isBlank()) continue
                val storyId = stableStoryId(storyUrl)
                val chapterId = ids[chapterUrl] ?: stableChapterId(chapterUrl)
                val paragraph = legacyParagraphIndex(cursor.int("para"))
                database.bookmarkDao().upsert(
                    BookmarkEntity(
                        id = stableId("bookmark", "$storyId\u0000$chapterId\u0000$paragraph"),
                        storyId = storyId,
                        chapterId = chapterId,
                        paragraphIndex = paragraph,
                        label = cursor.string("title").ifBlank { "Đánh dấu từ XPK" },
                        createdAt = cursor.long("created_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private data class VoiceRepair(
        val globalRoles: Int = 0,
        val storySettings: Int = 0,
        val storyRoles: Int = 0,
    )

    private suspend fun repairVoiceCast(
        databaseFile: File?,
        settingsFile: File?,
        warnings: MutableList<String>,
    ): VoiceRepair {
        var globalRoles = 0
        var storySettings = 0
        var storyRoles = 0

        if (settingsFile != null) {
            runCatching {
                val root = JSONObject(settingsFile.readText(Charsets.UTF_8))
                globalRoles = importVoiceRoleArray(
                    storyId = GLOBAL_VOICE_PROFILE_STORY_ID,
                    raw = root.optString("ai_voice_cast_profiles_json"),
                )
                if (root.has("ai_voice_cast_enabled")) {
                    settingsRepository.setAutoVoiceCastEnabled(root.optBoolean("ai_voice_cast_enabled", false))
                }
            }.onFailure { warnings += "Không nhập được bộ phân vai chung XPK: ${it.message.orEmpty().take(200)}" }
        }

        if (databaseFile != null) {
            val legacy = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                if (legacy.hasTable("settings")) {
                    legacy.rawQuery(
                        "SELECT key,value FROM settings WHERE key LIKE 'story_ai_voice_cast|%'",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.string("key")
                            val value = cursor.string("value")
                            val remainder = key.removePrefix(STORY_VOICE_KEY_PREFIX)
                            val separator = remainder.indexOf('|')
                            if (separator <= 0 || separator >= remainder.lastIndex || value.isBlank()) continue
                            val storyUrl = remainder.substring(separator + 1)
                            if (storyUrl.isBlank()) continue
                            runCatching {
                                val obj = JSONObject(value)
                                val storyId = stableStoryId(storyUrl)
                                val current = database.storyAiProfileDao().get(storyId)
                                    ?: StoryAiProfileEntity(storyId = storyId, updatedAt = System.currentTimeMillis())
                                val mode = when (obj.optString("mode").lowercase(Locale.ROOT)) {
                                    "story" -> StoryVoiceCastMode.PRIVATE
                                    "off" -> StoryVoiceCastMode.OFF
                                    else -> StoryVoiceCastMode.GLOBAL
                                }
                                val note = StoryVoiceCastReferenceCodec.encode(
                                    mode = mode,
                                    autoRunOnOpenTts = obj.optBoolean("auto_cast_tts", false),
                                    note = obj.optString("note").take(8_000),
                                )
                                database.storyAiProfileDao().upsert(
                                    current.copy(
                                        voiceCastNote = note,
                                        expressiveAdjustment = obj.optBoolean("expressive_adjustment", true),
                                        expressionPrompt = obj.optString("expression_prompt").take(16_000),
                                        expressionSpeedLimitPct = obj.optInt("expression_speed_limit_pct", 10).coerceIn(0, 100),
                                        expressionPitchLimitPct = obj.optInt("expression_pitch_limit_pct", 10).coerceIn(0, 100),
                                        expressionVolumeLimitPct = obj.optInt("expression_volume_limit_pct", 10).coerceIn(0, 100),
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                storySettings += 1
                                val profiles = obj.optJSONArray("profiles")
                                if (profiles != null && profiles.length() > 0) {
                                    storyRoles += importVoiceRoleArray(storyId, profiles.toString())
                                }
                            }.onFailure {
                                warnings += "Không nhập được phân vai riêng của một truyện XPK: ${it.message.orEmpty().take(180)}"
                            }
                        }
                    }
                }
            } finally {
                legacy.close()
            }
        }
        return VoiceRepair(globalRoles, storySettings, storyRoles)
    }

    private suspend fun importVoiceRoleArray(storyId: String, raw: String): Int {
        if (raw.isBlank()) return 0
        val array = JSONArray(raw)
        if (array.length() == 0) return 0
        val roleNames = hashSetOf<String>()
        var count = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val legacyId = item.optString("id").trim().ifBlank { "voice_$index" }
            val narrator = legacyId == LEGACY_NARRATOR_ID
            var name = if (narrator) "Người kể chuyện" else item.optString("name").trim().ifBlank { legacyId }
            if (!roleNames.add(name.lowercase(Locale.ROOT))) {
                name = "$name (${legacyId.take(24)})"
                roleNames += name.lowercase(Locale.ROOT)
            }
            val method = if (item.optString("processing_method").equals("sonic", true)) "sonic" else "system"
            val roleId = stableId("legacy-voice-role", "$storyId\u0000$legacyId")
            val speed = item.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 3f)
            val pitch = item.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f)
            val volumeMax = if (method == "sonic") 2f else 1f
            val volume = item.optDouble("volume", 1.0).toFloat().coerceIn(0f, volumeMax)
            database.voiceRoleDao().upsert(
                VoiceRoleEntity(
                    id = roleId,
                    storyId = storyId,
                    roleName = name.take(80),
                    aliasesCsv = "",
                    description = item.optString("description").trim().take(1_000),
                    enginePackage = item.optString("engine").trim().takeIf(String::isNotBlank),
                    voiceName = item.optString("voice").trim().takeIf(String::isNotBlank),
                    languageTag = item.optString("language").trim().ifBlank { "vi-VN" }.take(32),
                    rate = speed,
                    pitch = pitch,
                    volume = volume,
                    expression = "NEUTRAL",
                    expressionStrength = 0.5f,
                    sonicSpeed = speed,
                    sonicPitch = pitch,
                    isNarrator = narrator,
                    enabled = narrator || item.optBoolean("enabled", true),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            ReferenceVoiceRoleExtras.save(
                appContext,
                roleId,
                ReferenceVoiceRoleExtra(
                    processingMethod = method,
                    sonicAccurate = item.optInt("sonic_quality", 0) == 1,
                ),
            )
            count += 1
        }
        return count
    }

    private suspend fun patchMissingSettings(file: File?, warnings: MutableList<String>): Int {
        if (file == null || !file.isFile) return 0
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            var count = 0
            if (json.has("vietphrase_enabled")) {
                ReferenceVietPhraseRuntime.setEnabled(appContext, json.optBoolean("vietphrase_enabled", false))
                count += 1
            }
            if (json.has("vietphrase_fallback_hanviet")) {
                ReferenceVietPhraseRuntime.setFallbackHanViet(appContext, json.optBoolean("vietphrase_fallback_hanviet", true))
                count += 1
            }
            if (json.has("reader_dark_mode")) {
                val current = settingsRepository.snapshot()
                settingsRepository.restore(
                    current.copy(
                        readerDisplay = current.readerDisplay.copy(
                            theme = if (json.optBoolean("reader_dark_mode", false)) ReaderThemeMode.DARK else ReaderThemeMode.LIGHT,
                        ),
                    ),
                )
                count += 1
            }
            count
        }.getOrElse {
            warnings += "Không hoàn tất được một số cài đặt XPK: ${it.message.orEmpty().take(200)}"
            0
        }
    }

    private data class ExtensionRepair(
        val vBookPayloads: Int = 0,
        val luaPayloads: Int = 0,
        val vBookActivated: Int = 0,
        val luaActivated: Int = 0,
        val warnings: List<String> = emptyList(),
    )

    private fun retryLegacyExtensions(stage: File, warnings: MutableList<String>): ExtensionRepair {
        val packageDir = stage.resolve("extension_packages")
        val luaDir = stage.resolve("extensions")
        val enabledByName = readExtensionEnabled(stage.resolve("extension_preferences.json"))
        val localWarnings = mutableListOf<String>()
        var zipCount = 0
        var luaCount = 0
        var vBookActivated = 0
        var luaActivated = 0
        val activatedNames = hashSetOf<String>()

        packageDir.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach { file ->
            if (!file.extension.equals("zip", true)) return@forEach
            zipCount += 1
            val result = runCatching {
                val preview = file.inputStream().use { sourcePlatformManager.prepareVBookImport(it).getOrThrow() }
                val info = sourcePlatformManager.confirmPendingInstall().getOrThrow()
                enabledByName[preview.name]?.let { enabled -> sourcePlatformManager.setEnabled(info.id, enabled).getOrThrow() }
                info
            }
            result.onSuccess { info ->
                vBookActivated += 1
                activatedNames += normalizedName(info.name)
            }.onFailure { error ->
                sourcePlatformManager.cancelPendingInstall()
                localWarnings += "Gói vBook ${file.name} chưa kích hoạt được: ${error.message.orEmpty().take(220)}"
            }
        }

        luaDir.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach { file ->
            if (!file.extension.equals("lua", true)) return@forEach
            luaCount += 1


            val stem = normalizedName(file.nameWithoutExtension)
            if (activatedNames.any { it == stem || it.contains(stem) || stem.contains(it) }) return@forEach
            val result = runCatching {
                val preview = file.inputStream().use { sourcePlatformManager.prepareNativeLuaImport(it).getOrThrow() }
                val info = sourcePlatformManager.confirmPendingInstall().getOrThrow()
                enabledByName[preview.name]?.let { enabled -> sourcePlatformManager.setEnabled(info.id, enabled).getOrThrow() }
                info
            }
            result.onSuccess {
                luaActivated += 1
                activatedNames += normalizedName(it.name)
            }.onFailure { error ->
                sourcePlatformManager.cancelPendingInstall()
                localWarnings += "Lua ${file.name} vẫn không tương thích runtime Kotlin: ${error.message.orEmpty().take(220)}"
            }
        }

        if (vBookActivated + luaActivated > 0) onSourcesChanged()
        warnings += localWarnings
        return ExtensionRepair(zipCount, luaCount, vBookActivated, luaActivated, localWarnings)
    }

    private fun readExtensionEnabled(file: File): Map<String, Boolean> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val settings = root.optJSONObject("NgheTruyenSettings") ?: JSONObject()
            buildMap {
                settings.keys().forEach { key ->
                    if (!key.startsWith("extension_enabled_")) return@forEach
                    val name = key.removePrefix("extension_enabled_")
                    val item = settings.optJSONObject(key)
                    if (item != null && item.has("value")) put(name, item.optBoolean("value", true))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun finalIssues(
        base: LegacyXpkVerifiedRestoreCoordinator.RestoreSummary,
        repair: DeepRepair,
        extensions: ExtensionRepair,
    ): List<String> = buildList {


        base.warnings.forEach { warning ->
            val lower = warning.lowercase(Locale.ROOT)
            val staleChapter = lower.contains("importer xử lý") && lower.contains("chương") && lower.contains("room")
            val staleExtension = lower.contains("payload tiện ích") ||
                lower.contains("package_source_json_missing") ||
                lower.contains("native_lua_api_version_unsupported") ||
                lower.contains("không kích hoạt được gói tiện ích") ||
                lower.contains("tệp lua")
            if (!staleChapter && !staleExtension) add(warning)
        }

        if (repair.legacyChaptersExpected > 0 && repair.legacyChaptersPersisted < repair.legacyChaptersExpected) {
            add("Deep repair chỉ giữ được ${repair.legacyChaptersPersisted}/${repair.legacyChaptersExpected} chương legacy trong Room.")
        }
        if (extensions.vBookPayloads > 0 && extensions.vBookActivated == 0 && base.restored.complete.extensionsInstalled == 0) {
            add("Có ${extensions.vBookPayloads} plugin.zip XPK nhưng chưa gói vBook nào kích hoạt được.")
        }
        if (extensions.vBookPayloads == 0 && extensions.luaPayloads > 0 && extensions.luaActivated == 0 && base.restored.complete.extensionsInstalled == 0) {
            add("Có ${extensions.luaPayloads} Lua extension XPK nhưng runtime Kotlin chưa kích hoạt được.")
        }
        addAll(repair.warnings)
        if (base.restored.downloadFilesUnconverted > 0) {
            add("Còn ${base.restored.downloadFilesUnconverted} response tải xuống chỉ bảo tồn nguyên bản, chưa chuyển thành nội dung chương.")
        }
    }.map(String::trim).filter(String::isNotBlank).distinct()

    private fun extractRepairPayloads(input: InputStream, stage: File, requested: Set<BackupComponent>) {
        var count = 0
        var total = 0L
        val seen = hashSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                require(count <= MAX_ENTRY_COUNT) { "Backup XPK có quá nhiều mục." }
                val name = entry.name
                validateEntryName(name)
                require(seen.add(name)) { "Backup XPK có mục trùng: $name" }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val wanted = when {
                    name == LEGACY_DB_ENTRY -> requested.any { it in setOf(BackupComponent.LIBRARY, BackupComponent.READING, BackupComponent.AI_VOICE) }
                    name == "settings.json" -> BackupComponent.SETTINGS in requested || BackupComponent.AI_VOICE in requested
                    name == "extension_preferences.json" -> BackupComponent.SOURCES_EXTENSIONS in requested
                    name.startsWith("extension_packages/") && safeLeaf(name.substringAfter("extension_packages/")) -> BackupComponent.SOURCES_EXTENSIONS in requested
                    name.startsWith("extensions/") && safeLeaf(name.substringAfter("extensions/")) -> BackupComponent.SOURCES_EXTENSIONS in requested
                    else -> false
                }
                if (wanted) {
                    val target = File(stage, name).canonicalFile
                    require(target.path.startsWith(stage.canonicalPath + File.separator)) { "Đường dẫn XPK không an toàn." }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val copied = copyBounded(zip, output, entryLimit(name))
                        total += copied
                        require(total <= MAX_TOTAL_BYTES) { "Deep-repair XPK vượt giới hạn dữ liệu." }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun preferredChapterIndex(row: LegacyChapter): Int? {
        if (row.orderIndex > 0) return row.orderIndex - 1
        val titleMatch = CHAPTER_NUMBER_REGEX.find(row.title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (titleMatch != null && titleMatch > 0) return titleMatch - 1
        val path = runCatching { URI(row.url).path.orEmpty() }.getOrDefault(row.url)
        val urlNumber = URL_NUMBER_REGEX.findAll(path).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
        return urlNumber?.takeIf { it > 0 }?.minus(1)
    }

    private fun nextFreeIndex(start: Int, occupied: Set<Int>, assigned: Set<Int>): Int {
        var candidate = start.coerceAtLeast(0)
        while (candidate in occupied || candidate in assigned) candidate += 1
        return candidate
    }

    private fun deriveStoryUrl(url: String): String = runCatching {
        val uri = URI(url)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val parent = path.substringBeforeLast('/', path)
        URI(uri.scheme, uri.authority, parent, null, null).toString()
    }.getOrElse { url.substringBeforeLast('/', url) }

    private fun stableStoryId(url: String): String = stableUrlId("story", url, includeQuery = false)
    private fun stableChapterId(url: String): String = stableUrlId("chapter", url, includeQuery = false)
    private fun stableChapterFullId(url: String): String = stableUrlId("chapter", url, includeQuery = true)

    private fun stableUrlId(prefix: String, rawUrl: String, includeQuery: Boolean): String {
        val value = runCatching {
            val uri = URI(rawUrl.trim())
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = if (includeQuery && !uri.rawQuery.isNullOrBlank()) "?${uri.rawQuery}" else ""
            if (host.isBlank()) canonicalRawUrl(rawUrl) else "$host$path$query"
        }.getOrElse { canonicalRawUrl(rawUrl) }
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

    private fun canonicalRawUrl(value: String): String = value.trim().trimEnd('/')

    private fun normalizeSourceId(value: String): String = value.trim()
        .lowercase(Locale.ROOT)
        .replace("truyen_full", "truyenfull")
        .replace("truyen_cv", "truyencv")
        .ifBlank { "legacy" }

    private fun normalizedName(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")

    private fun legacyParagraphIndex(value: Int): Int = (value - 1).coerceAtLeast(0)

    private fun SQLiteDatabase.hasTable(name: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }

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

    private fun validateEntryName(name: String) {
        require(name.isNotBlank() && name.length <= 1024) { "Tên mục XPK không hợp lệ." }
        require(!name.startsWith('/') && !name.startsWith('\\')) { "Đường dẫn XPK tuyệt đối không được phép." }
        require('\\' !in name && '\u0000' !in name) { "Tên mục XPK không an toàn." }
        require(name.split('/').none { it == ".." }) { "Đường dẫn XPK thoát thư mục." }
    }

    private fun safeLeaf(value: String): Boolean = value.isNotBlank() &&
        !value.contains('/') && !value.contains('\\') && value != "." && value != ".." &&
        value.all { it.isLetterOrDigit() || it in "._-" }

    private fun entryLimit(name: String): Long = when {
        name == LEGACY_DB_ENTRY -> 256L * 1024L * 1024L
        name == "settings.json" -> 1024L * 1024L
        name == "extension_preferences.json" -> 16L * 1024L * 1024L
        name.startsWith("extension_packages/") -> 128L * 1024L * 1024L
        name.startsWith("extensions/") -> 32L * 1024L * 1024L
        else -> 1024L * 1024L
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Mục XPK vượt giới hạn an toàn." }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }

    companion object {
        private const val LEGACY_DB_ENTRY = "database/accessible_reader.db"
        private const val STORY_VOICE_KEY_PREFIX = "story_ai_voice_cast|"
        private const val LEGACY_NARRATOR_ID = "voice_narrator"
        private const val MAX_ENTRY_COUNT = 10_000
        private const val MAX_TOTAL_BYTES = 768L * 1024L * 1024L

        private val CHAPTER_NUMBER_REGEX = Regex("(?i)(?:ch(?:ương|uong)|chapter|chap)\\s*[:#._-]?\\s*(\\d{1,7})")
        private val URL_NUMBER_REGEX = Regex("(?:^|[-_/])(\\d{1,7})(?=$|[-_/.])")
    }
}
