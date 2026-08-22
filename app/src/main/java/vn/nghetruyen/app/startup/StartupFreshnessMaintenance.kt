package vn.nghetruyen.app.startup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.playback.NextChapterCache
import vn.nghetruyen.app.playback.XpkPlaybackRuntime
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Startup policy for data that must never survive into a new application process.
 *
 * Explicitly downloaded chapters are durable offline data and are never touched here. Reader cache,
 * generated narration/audio plans and file caches are disposable so the next online read/planning
 * pass is forced to use current source data.
 */
class StartupFreshnessMaintenance(
    context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val audioStore = LocalAudioAssetStore(appContext)

    data class AudioMigrationResult(
        val sceneTracksMigrated: Int,
        val backgroundMusicMigrated: Boolean,
        val failedExternalUris: Int,
    )

    suspend fun clearTransientState() = withContext(Dispatchers.IO) {
        XpkPlaybackRuntime.resetCanonicalPlans()
        NextChapterCache.clear()

        database.withTransaction {
            val sql = database.openHelper.writableDatabase

            // downloadedAt == NULL is the authoritative marker for reader cache rather than a real
            // offline download. Preserve every explicitly downloaded chapter byte-for-byte.
            sql.execSQL(
                "UPDATE chapters SET content = NULL, downloadedAt = NULL WHERE downloadedAt IS NULL",
            )

            // Per-chapter AI/runtime products must be rebuilt from the newest chapter text/assets.
            sql.execSQL("DELETE FROM chapter_voice_assignments")
            sql.execSQL("DELETE FROM scene_music_cues")
            sql.execSQL(
                "DELETE FROM chapter_transforms WHERE kind IN (?, ?, ?, ?)",
                arrayOf(
                    ChapterAiWorkflow.KIND_VOICE_CAST,
                    ChapterAiWorkflow.KIND_SCENE_MUSIC,
                    NarrationPlanCoordinator.KIND_AUDIO_DIRECTION,
                    NarrationPlanCoordinator.KIND_FREESOUND_AUTO_AUDIO,
                ),
            )

            // Keep the listening position/queue, but never restore a scene assignment from an old plan.
            sql.execSQL("UPDATE playback_checkpoint SET activeSceneTrackId = NULL")
        }

        clearDirectoryContents(appContext.cacheDir)
        appContext.externalCacheDir?.let(::clearDirectoryContents)

        // This catalog is intentionally an offline cache, not an installed source or user setting.
        runCatching {
            File(appContext.filesDir, VBOOK_REPOSITORY_CACHE_DIRECTORY).deleteRecursively()
        }
    }

    /**
     * Copies legacy external audio references (typically Downloads content:// URIs) into persistent
     * app-managed storage. The database row id and every piece of classification/normalization
     * metadata stay unchanged; only the physical URI is replaced.
     */
    suspend fun internalizeLegacyLocalAudio(): AudioMigrationResult = withContext(Dispatchers.IO) {
        var migratedTracks = 0
        var failedUris = 0
        val dao = database.sceneMusicTrackDao()

        dao.listAll().forEach { track ->
            when (val imported = audioStore.internalize(track.uri)) {
                is LocalAudioAssetStore.Result.AlreadyManaged -> Unit
                is LocalAudioAssetStore.Result.Imported -> {
                    dao.upsert(
                        track.copy(
                            uri = imported.uri,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    migratedTracks += 1
                }
                is LocalAudioAssetStore.Result.ExternalImportFailed -> failedUris += 1
                is LocalAudioAssetStore.Result.NotLocal -> Unit
            }
        }

        var migratedBackground = false
        val backgroundUri = settingsRepository.snapshot().backgroundMusicUri
        if (!backgroundUri.isNullOrBlank()) {
            when (val imported = audioStore.internalize(backgroundUri)) {
                is LocalAudioAssetStore.Result.Imported -> {
                    settingsRepository.setBackgroundMusic(imported.uri)
                    migratedBackground = true
                }
                is LocalAudioAssetStore.Result.ExternalImportFailed -> failedUris += 1
                is LocalAudioAssetStore.Result.AlreadyManaged,
                is LocalAudioAssetStore.Result.NotLocal -> Unit
            }
        }

        AudioMigrationResult(
            sceneTracksMigrated = migratedTracks,
            backgroundMusicMigrated = migratedBackground,
            failedExternalUris = failedUris,
        )
    }

    private fun clearDirectoryContents(directory: File) {
        runCatching {
            directory.listFiles().orEmpty().forEach { child -> child.deleteRecursively() }
            directory.mkdirs()
        }
    }

    private companion object {
        const val VBOOK_REPOSITORY_CACHE_DIRECTORY = "vbook_repository_catalog_cache_v1"
    }
}

/** Persistent file store for user-provided MUSIC / AMBIENCE / SFX and legacy background music. */
internal class LocalAudioAssetStore(context: Context) {
    sealed interface Result {
        data class Imported(val uri: String) : Result
        data object AlreadyManaged : Result
        data object NotLocal : Result
        data object ExternalImportFailed : Result
    }

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val root = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }

    fun internalize(rawUri: String): Result {
        val clean = rawUri.trim()
        if (clean.isBlank()) return Result.NotLocal
        val uri = runCatching { Uri.parse(clean) }.getOrNull() ?: return Result.NotLocal

        if (uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true)) {
            val source = uri.path?.let(::File) ?: return Result.ExternalImportFailed
            if (isInside(source, appContext.filesDir)) return Result.AlreadyManaged
            if (!source.isFile) return Result.ExternalImportFailed
            return importStream(
                sourceName = source.name,
                mimeType = null,
                open = { FileInputStream(source) },
            )
        }

        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return importStream(
                sourceName = displayName(uri),
                mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
                open = { resolver.openInputStream(uri) },
            )
        }

        return Result.NotLocal
    }

    private fun importStream(
        sourceName: String?,
        mimeType: String?,
        open: () -> InputStream?,
    ): Result {
        root.mkdirs()
        val temp = File(root, ".import-${System.nanoTime()}.tmp")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = open() ?: return Result.ExternalImportFailed
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!temp.isFile || temp.length() <= 0L) {
                temp.delete()
                return Result.ExternalImportFailed
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val extension = safeExtension(sourceName, mimeType)
            val target = File(root, if (extension.isBlank()) hash else "$hash.$extension")
            if (target.isFile && target.length() > 0L) {
                temp.delete()
            } else if (!temp.renameTo(target)) {
                FileInputStream(temp).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_SIZE) }
                }
                temp.delete()
            }
            if (!target.isFile || target.length() <= 0L) {
                target.delete()
                Result.ExternalImportFailed
            } else {
                Result.Imported(Uri.fromFile(target).toString())
            }
        } catch (_: Throwable) {
            temp.delete()
            Result.ExternalImportFailed
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column < 0) null else cursor.getString(column)
        }
    }.getOrNull()

    private fun safeExtension(sourceName: String?, mimeType: String?): String {
        val nameExtension = sourceName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        if (!nameExtension.isNullOrBlank()) return nameExtension
        return mimeType
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            .orEmpty()
    }

    private fun isInside(file: File, directory: File): Boolean = runCatching {
        val candidate = file.canonicalFile
        val parent = directory.canonicalFile
        candidate.path == parent.path || candidate.path.startsWith(parent.path + File.separator)
    }.getOrDefault(false)

    private companion object {
        const val DIRECTORY = "audio/local_imports_v1"
        const val BUFFER_SIZE = 64 * 1024
    }
}
