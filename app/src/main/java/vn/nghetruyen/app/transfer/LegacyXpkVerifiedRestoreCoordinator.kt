package vn.nghetruyen.app.transfer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.sourceplatform.UnifiedSourcePlatformManager
import vn.nghetruyen.app.sources.SourceDescriptor
import vn.nghetruyen.app.sources.SourceRegistry
import java.text.Normalizer
import java.util.Locale

/**
 * Final guard around the XPK migration path.
 *
 * The lower compatibility layers perform the conversion. This layer repairs legacy source aliases,
 * makes restored offline chapters visible to the current library model, and verifies persisted
 * Room/runtime state before the UI is told that the operation completed.
 */
class LegacyXpkVerifiedRestoreCoordinator(
    context: Context,
    private val delegate: LegacyXpkEverythingRestoreCoordinator,
    private val database: AppDatabase,
    private val sourceRegistry: SourceRegistry,
    private val sourcePlatformManager: UnifiedSourcePlatformManager,
) {
    private val appContext = context.applicationContext

    data class Verification(
        val totalStories: Int,
        val newStories: Int,
        val totalChapters: Int,
        val newChapters: Int,
        val totalReadingProgress: Int,
        val newReadingProgress: Int,
        val totalReadingHistory: Int,
        val newReadingHistory: Int,
        val totalDownloadedStories: Int,
        val newDownloadedStories: Int,
        val totalDownloadedChapters: Int,
        val newDownloadedChapters: Int,
        val totalSceneMusicTracks: Int,
        val newSceneMusicTracks: Int,
        val totalInstalledExtensions: Int,
        val newInstalledExtensions: Int,
        val repairedSourceAliases: Int,
        val repairedOfflineStories: Int,
        val issues: List<String> = emptyList(),
    ) {
        fun shortText(): String = buildString {
            append("Hậu kiểm: ")
            append(totalStories).append(" truyện (+").append(newStories).append("), ")
            append(totalReadingProgress).append(" tiến độ (+").append(newReadingProgress).append("), ")
            append(totalDownloadedStories).append(" truyện offline (+").append(newDownloadedStories).append("), ")
            append(totalDownloadedChapters).append(" chương offline (+").append(newDownloadedChapters).append("), ")
            append(totalSceneMusicTracks).append(" nhạc (+").append(newSceneMusicTracks).append("), ")
            append(totalInstalledExtensions).append(" tiện ích (+").append(newInstalledExtensions).append(").")
        }
    }

    data class RestoreSummary(
        val restored: LegacyXpkEverythingRestoreCoordinator.RestoreSummary,
        val verification: Verification,
        val warnings: List<String>,
    ) {
        val isComplete: Boolean
            get() = warnings.isEmpty() && verification.issues.isEmpty() && restored.downloadFilesUnconverted == 0

        fun userMessage(): String = buildString {
            val complete = restored.complete
            val legacy = complete.legacy
            append(if (isComplete) "Đã khôi phục XPK và hậu kiểm thành công: " else "Đã khôi phục XPK một phần và đã hậu kiểm: ")
            append(legacy.stories).append(" truyện, ")
            append(legacy.chapters).append(" chương, ")
            append(legacy.progress).append(" tiến độ, ")
            append(legacy.readingHistory).append(" lịch sử, ")
            append(legacy.bookmarks).append(" đánh dấu")
            if (legacy.vietPhraseRules > 0) append(", ").append(legacy.vietPhraseRules).append(" VietPhrase")
            if (complete.musicTracks > 0) append(", ").append(complete.musicTracks).append(" nhạc")
            if (complete.extensionsInstalled > 0) append(", ").append(complete.extensionsInstalled).append(" tiện ích")
            if (restored.downloadedChapters > 0) append(", ").append(restored.downloadedChapters).append(" chương offline")
            append(". ").append(verification.shortText())

            if (complete.extensionFilesPreserved > 0 && complete.extensionsInstalled == 0) {
                append(" Không có tiện ích XPK nào kích hoạt được; ")
                    .append(complete.extensionFilesPreserved)
                    .append(" tệp gốc chỉ được bảo tồn.")
            }
            if (warnings.isNotEmpty()) {
                append(" Cảnh báo đầu tiên: ").append(warnings.first().take(260))
            }
        }

        fun diagnosticMessage(): String = buildString {
            append(userMessage())
            if (verification.repairedSourceAliases > 0) {
                append(" Đã sửa ").append(verification.repairedSourceAliases).append(" alias nguồn legacy.")
            }
            if (verification.repairedOfflineStories > 0) {
                append(" Đã đồng bộ cờ offline cho ").append(verification.repairedOfflineStories).append(" truyện.")
            }
            if (restored.downloadFilesPreserved > 0) {
                append(" Payload tải xuống bảo tồn=").append(restored.downloadFilesPreserved)
                    .append(", chưa chuyển=").append(restored.downloadFilesUnconverted).append(".")
            }
            val allIssues = (verification.issues + warnings).distinct()
            if (allIssues.isNotEmpty()) {
                append(" Chi tiết: ")
                append(allIssues.take(6).joinToString(" || ") { it.replace('\n', ' ').take(220) })
            }
        }
    }

    suspend fun inspect(source: Uri): AppResult<LegacyXpkBackupImporter.Inspection> = delegate.inspect(source)

    suspend fun restoreFrom(
        source: Uri,
        requestedComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<RestoreSummary> = withContext(Dispatchers.IO) {
        val before = snapshot()
        when (val result = delegate.restoreFrom(source, requestedComponents)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val aliasRepairs = repairLegacySourceAliases()
                val offlineRepairs = repairOfflineStoryFlags()
                val after = snapshot()
                val verification = verify(before, after, result.value, aliasRepairs, offlineRepairs)
                val warnings = buildList {
                    addAll(result.value.complete.warnings)
                    addAll(result.value.warnings)
                    addAll(verification.issues)
                }.map(String::trim).filter(String::isNotBlank).distinct()
                AppResult.Success(RestoreSummary(result.value, verification, warnings))
            }
        }
    }

    private data class PersistedState(
        val storyIds: Set<String>,
        val chapterIds: Set<String>,
        val progressStoryIds: Set<String>,
        val historyIds: Set<String>,
        val downloadedStoryIds: Set<String>,
        val downloadedChapterIds: Set<String>,
        val musicIds: Set<String>,
        val extensionIds: Set<String>,
    )

    private suspend fun snapshot(): PersistedState {
        val stories = database.storyDao().listAll()
        val chapters = database.chapterDao().listAll()
        return PersistedState(
            storyIds = stories.mapTo(linkedSetOf()) { it.id },
            chapterIds = chapters.mapTo(linkedSetOf()) { it.id },
            progressStoryIds = database.progressDao().listAll().mapTo(linkedSetOf()) { it.storyId },
            historyIds = database.readingHistoryDao().listRecent(500).mapTo(linkedSetOf()) { it.id },
            downloadedStoryIds = stories.filter { it.isOffline }.mapTo(linkedSetOf()) { it.id },
            downloadedChapterIds = chapters
                .filter { it.downloadedAt != null && !it.content.isNullOrBlank() }
                .mapTo(linkedSetOf()) { it.id },
            musicIds = database.sceneMusicTrackDao().listAll().mapTo(linkedSetOf()) { it.id },
            extensionIds = sourcePlatformManager.installedPacks().mapTo(linkedSetOf()) { it.id },
        )
    }

    private suspend fun repairOfflineStoryFlags(): Int {
        val stories = database.storyDao().listAll().associateBy { it.id }
        val offlineStoryIds = database.chapterDao().listAll()
            .asSequence()
            .filter { it.downloadedAt != null && !it.content.isNullOrBlank() }
            .map { it.storyId }
            .toSet()
        var repaired = 0
        val now = System.currentTimeMillis()
        offlineStoryIds.forEach { storyId ->
            val story = stories[storyId] ?: return@forEach
            if (!story.isOffline) {
                database.storyDao().setOffline(storyId, true, now)
                repaired += 1
            }
        }
        return repaired
    }

    private suspend fun repairLegacySourceAliases(): Int {
        val descriptors = sourceRegistry.descriptors()
        var repaired = 0

        database.storyDao().listAll().forEach { story ->
            val mapped = canonicalSourceId(story.sourceId, descriptors)
            if (mapped != story.sourceId) {
                database.storyDao().upsert(story.copy(sourceId = mapped))
                repaired += 1
            }
        }
        database.readingHistoryDao().listRecent(500).forEach { item ->
            val mapped = canonicalSourceId(item.sourceId, descriptors)
            if (mapped != item.sourceId) {
                database.readingHistoryDao().upsert(item.copy(sourceId = mapped))
                repaired += 1
            }
        }
        database.followingDao().listAll().forEach { item ->
            val mapped = canonicalSourceId(item.sourceId, descriptors)
            if (mapped != item.sourceId) {
                database.followingDao().upsert(item.copy(sourceId = mapped))
                repaired += 1
            }
        }
        return repaired
    }

    private fun canonicalSourceId(raw: String, descriptors: List<SourceDescriptor>): String {
        val clean = raw.trim()
        if (clean.isBlank()) return raw
        descriptors.firstOrNull { it.id.equals(clean, ignoreCase = true) }?.let { return it.id }

        val token = sourceToken(clean)
        descriptors.firstOrNull {
            sourceToken(it.id) == token || sourceToken(it.displayName) == token
        }?.let { return it.id }

        val alias = when (token) {
            "truyenfull", "truyenfullnative" -> "truyenfull"
            "tcv", "truyencv", "truyencv7clean", "truyencvionative", "truyencviodefaultnative" -> "truyencv"
            "truyencom", "truyencomdefaultnative" -> "truyencom"
            "truyenyy", "truyenyyco", "truyenyyconative" -> "truyenyy"
            "wikidich", "wikidichdefaultnativev9completescroll" -> "wikidich"
            "sangtacviet", "stv", "sangtacvietnativeinstantfastv50" -> "sangtacviet"
            "wattpad", "wattpadtiengviet", "wattpaddefaultvbook" -> "wattpad"
            else -> null
        } ?: return raw
        return descriptors.firstOrNull { it.id == alias }?.id ?: alias
    }

    private fun sourceToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")

    private fun verify(
        before: PersistedState,
        after: PersistedState,
        restored: LegacyXpkEverythingRestoreCoordinator.RestoreSummary,
        aliasRepairs: Int,
        offlineRepairs: Int,
    ): Verification {
        val legacy = restored.complete.legacy
        val issues = mutableListOf<String>()
        if (legacy.stories > 0 && after.storyIds.size < legacy.stories) {
            issues += "Importer xử lý ${legacy.stories} truyện nhưng Room sau restore chỉ có ${after.storyIds.size} truyện."
        }
        if (legacy.chapters > 0 && after.chapterIds.size < legacy.chapters) {
            issues += "Importer xử lý ${legacy.chapters} chương nhưng Room sau restore chỉ có ${after.chapterIds.size} chương."
        }
        if (legacy.progress > 0 && after.progressStoryIds.size < legacy.progress) {
            issues += "Importer xử lý ${legacy.progress} tiến độ nhưng Room sau restore chỉ có ${after.progressStoryIds.size} tiến độ."
        }
        if (restored.complete.musicTracks > 0 && after.musicIds.size < restored.complete.musicTracks) {
            issues += "Importer xử lý ${restored.complete.musicTracks} nhạc nhưng Room sau restore chỉ có ${after.musicIds.size} track."
        }
        if (restored.downloadedChapters > 0 && after.downloadedChapterIds.size < restored.downloadedChapters) {
            issues += "Importer xử lý ${restored.downloadedChapters} chương offline nhưng Room sau restore chỉ có ${after.downloadedChapterIds.size} chương offline."
        }
        if (restored.complete.extensionFilesPreserved > 0 && restored.complete.extensionsInstalled == 0) {
            issues += "Có ${restored.complete.extensionFilesPreserved} payload tiện ích XPK nhưng không tiện ích nào kích hoạt được trong runtime Kotlin."
        }

        return Verification(
            totalStories = after.storyIds.size,
            newStories = (after.storyIds - before.storyIds).size,
            totalChapters = after.chapterIds.size,
            newChapters = (after.chapterIds - before.chapterIds).size,
            totalReadingProgress = after.progressStoryIds.size,
            newReadingProgress = (after.progressStoryIds - before.progressStoryIds).size,
            totalReadingHistory = after.historyIds.size,
            newReadingHistory = (after.historyIds - before.historyIds).size,
            totalDownloadedStories = after.downloadedStoryIds.size,
            newDownloadedStories = (after.downloadedStoryIds - before.downloadedStoryIds).size,
            totalDownloadedChapters = after.downloadedChapterIds.size,
            newDownloadedChapters = (after.downloadedChapterIds - before.downloadedChapterIds).size,
            totalSceneMusicTracks = after.musicIds.size,
            newSceneMusicTracks = (after.musicIds - before.musicIds).size,
            totalInstalledExtensions = after.extensionIds.size,
            newInstalledExtensions = (after.extensionIds - before.extensionIds).size,
            repairedSourceAliases = aliasRepairs,
            repairedOfflineStories = offlineRepairs,
            issues = issues,
        )
    }
}
