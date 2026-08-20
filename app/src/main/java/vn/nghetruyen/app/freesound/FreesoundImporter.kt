package vn.nghetruyen.app.freesound

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.StatFs
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.repository.LibraryRepository

data class FreesoundImportResult(
    val trackId: String,
    val uri: String,
    val title: String,
)

class FreesoundDuplicateException(
    val soundId: Int,
) : IllegalStateException("Âm thanh Freesound #$soundId đã có trong thư viện.")

class FreesoundNormalizationException(
    message: String,
) : IllegalStateException(message)

class FreesoundImporter(
    context: Context,
    private val repository: LibraryRepository,
    private val existingTracksProvider: suspend () -> List<SceneMusicTrackEntity>,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val appContext = context.applicationContext

    suspend fun importPreview(
        sound: FreesoundSound,
        kind: AudioAssetKind,
        normalizationTargetLufs: Float,
    ): Result<FreesoundImportResult> = withContext(Dispatchers.IO) {
        importMutex.withLock {
            cleanupStalePartFiles(appContext)
            val duplicateCheck = runCatching { existingTracksProvider() }
            if (duplicateCheck.isFailure) {
                return@withLock Result.failure(
                    duplicateCheck.exceptionOrNull() ?: IllegalStateException("Không kiểm tra được thư viện hiện tại."),
                )
            }

            val matches = duplicateCheck.getOrThrow().filter { soundIdFromManagedUri(it.uri) == sound.id }
            val existing = matches.firstOrNull { managedFileExists(appContext, it.uri) }
            if (existing != null) {
                return@withLock Result.failure(FreesoundDuplicateException(sound.id))
            }
            matches.forEach { stale ->
                runCatching { repository.deleteSceneMusicTrack(stale.id) }
            }

            importPreviewLocked(sound, kind, normalizationTargetLufs)
        }
    }

    private suspend fun importPreviewLocked(
        sound: FreesoundSound,
        kind: AudioAssetKind,
        normalizationTargetLufs: Float,
    ): Result<FreesoundImportResult> {
        val previewUrl = sound.preferredPreviewUrl
            ?: return Result.failure(IllegalArgumentException("Âm thanh này không có preview HQ khả dụng."))
        if (!previewUrl.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Địa chỉ preview Freesound không an toàn."))
        }

        val extension = extensionForPreviewUrl(previewUrl)
        val directory = managedDirectory(appContext, kind)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return Result.failure(IllegalStateException("Không tạo được thư mục lưu âm thanh Freesound."))
        }

        val finalFile = File(
            directory,
            "freesound_${sound.id}_${UUID.randomUUID()}.$extension",
        )
        val partFile = File(directory, "${finalFile.name}.part")
        var savedTrackId: String? = null

        try {
            val request = Request.Builder()
                .url(previewUrl)
                .header("Accept", "audio/*")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Không tải được preview Freesound (HTTP ${response.code}).")
                }
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_PREVIEW_BYTES) {
                    throw IllegalStateException("Preview Freesound vượt giới hạn dung lượng cho phép.")
                }
                ensureFreeSpace(directory, declaredLength)

                var total = 0L
                body.byteStream().use { input ->
                    partFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_PREVIEW_BYTES) {
                                throw IllegalStateException("Preview Freesound vượt giới hạn dung lượng cho phép.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (total <= 0L) {
                    throw IllegalStateException("Preview Freesound tải về bị rỗng.")
                }
            }

            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = false)
                partFile.delete()
            }
            validateDownloadedAudio(finalFile)

            val uri = Uri.fromFile(finalFile).toString()
            val title = titleForImport(sound.name, "Âm thanh Freesound ${sound.id}")
            val tagsCsv = tagsForImport(kind, sound.description)
            val trackId = repository.saveSceneMusicTrack(
                title = title,
                uri = uri,
                tagsCsv = tagsCsv,
            ).getOrThrow()
            savedTrackId = trackId

            val workId = SceneMusicAnalysisWorker.enqueue(
                context = appContext,
                trackId = trackId,
                targetLufs = normalizationTargetLufs,
            )
            awaitNormalization(workId, trackId)

            return Result.success(
                FreesoundImportResult(
                    trackId = trackId,
                    uri = uri,
                    title = title,
                ),
            )
        } catch (cancelled: CancellationException) {
            savedTrackId?.let { runCatching { repository.deleteSceneMusicTrack(it) } }
            partFile.delete()
            finalFile.delete()
            throw cancelled
        } catch (error: Throwable) {
            savedTrackId?.let { runCatching { repository.deleteSceneMusicTrack(it) } }
            partFile.delete()
            finalFile.delete()
            return Result.failure(error)
        }
    }

    private suspend fun awaitNormalization(workId: UUID, trackId: String) {
        val workManager = WorkManager.getInstance(appContext)
        val deadline = System.currentTimeMillis() + NORMALIZATION_TIMEOUT_MS
        while (true) {
            val info = runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
                ?: throw FreesoundNormalizationException("Không đọc được trạng thái chuẩn hóa của tệp vừa nhập.")
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> return
                WorkInfo.State.FAILED -> {
                    val storedError = repository.getSceneMusicTrack(trackId)?.normalizationError.orEmpty().trim()
                    throw FreesoundNormalizationException(
                        storedError.ifBlank { "Chuẩn hóa âm thanh Freesound thất bại." },
                    )
                }
                WorkInfo.State.CANCELLED -> throw FreesoundNormalizationException("Chuẩn hóa âm thanh Freesound đã bị hủy.")
                else -> Unit
            }
            if (System.currentTimeMillis() >= deadline) {
                SceneMusicAnalysisWorker.cancel(appContext, workId)
                throw FreesoundNormalizationException("Chuẩn hóa âm thanh Freesound quá thời gian cho phép.")
            }
            delay(NORMALIZATION_POLL_MS)
        }
    }

    companion object {
        private const val USER_AGENT = "NgheTruyen-Android/Freesound"
        internal const val MAX_PREVIEW_BYTES = 64L * 1024L * 1024L
        internal const val MAX_BATCH_SIZE = 50
        private const val MANAGED_ROOT = "audio/freesound"
        private const val FREE_SPACE_RESERVE_BYTES = 4L * 1024L * 1024L
        private const val UNKNOWN_LENGTH_REQUIRED_BYTES = 12L * 1024L * 1024L
        private const val NORMALIZATION_POLL_MS = 300L
        private const val NORMALIZATION_TIMEOUT_MS = 10L * 60L * 1_000L
        private val importMutex = Mutex()
        private val managedSoundIdRegex = Regex(
            """(?i)(?:^|/)audio/freesound/(?:music|ambience|sfx)/freesound_(\d+)_[-0-9a-f]+\.(?:ogg|mp3)$""",
        )

        internal fun extensionForPreviewUrl(url: String): String {
            val clean = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            return if (clean.endsWith(".ogg")) "ogg" else "mp3"
        }

        internal fun titleForImport(name: String, fallback: String): String {
            val trimmed = name.trim()
            val withoutExtension = trimmed.replace(
                Regex("(?i)\\.(?:wav|wave|mp3|ogg|flac|aac|m4a|aiff|aif|opus)$"),
                "",
            ).trim()
            return withoutExtension.ifBlank { fallback }.take(120)
        }

        internal fun tagsForImport(kind: AudioAssetKind, description: String): String {
            val marker = when (kind) {
                AudioAssetKind.MUSIC -> "type:music"
                AudioAssetKind.AMBIENCE -> "type:ambience"
                AudioAssetKind.SFX -> "type:sfx"
            }
            val clean = description.trim().take(300)
            return if (clean.isBlank()) marker else "$marker, $clean"
        }

        internal fun soundIdFromManagedUri(uri: String): Int? {
            val clean = uri.substringBefore('?').substringBefore('#')
            return managedSoundIdRegex.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun managedFileExists(context: Context, uri: String): Boolean {
            val file = managedFile(context, uri) ?: return false
            return file.isFile && file.length() > 0L
        }

        fun deleteManagedFile(context: Context, uri: String): Boolean {
            val candidate = managedFile(context, uri) ?: return false
            return !candidate.exists() || candidate.delete()
        }

        internal fun cleanupStalePartFiles(context: Context): Int {
            val root = File(context.applicationContext.filesDir, MANAGED_ROOT)
            if (!root.isDirectory) return 0
            var deleted = 0
            root.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".part", ignoreCase = true) && file.delete()) {
                    deleted += 1
                }
            }
            return deleted
        }

        private fun managedFile(context: Context, uri: String): File? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            if (!parsed.scheme.equals("file", ignoreCase = true)) return null
            val path = parsed.path ?: return null
            val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
            val root = runCatching { File(context.applicationContext.filesDir, MANAGED_ROOT).canonicalFile }.getOrNull()
                ?: return null
            val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
            if (!candidate.path.startsWith(rootPath)) return null
            return candidate
        }

        private fun managedDirectory(context: Context, kind: AudioAssetKind): File =
            File(
                File(context.applicationContext.filesDir, MANAGED_ROOT),
                kind.name.lowercase(Locale.ROOT),
            )

        private fun ensureFreeSpace(directory: File, declaredLength: Long) {
            val required = if (declaredLength > 0L) {
                declaredLength.coerceAtMost(MAX_PREVIEW_BYTES) + FREE_SPACE_RESERVE_BYTES
            } else {
                UNKNOWN_LENGTH_REQUIRED_BYTES
            }
            val available = runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (available < required) {
                throw IllegalStateException("Không đủ dung lượng trống để tải âm thanh Freesound.")
            }
        }

        private fun validateDownloadedAudio(file: File) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val hasAudioTrack = (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
                if (!hasAudioTrack) {
                    throw IllegalStateException("Tệp Freesound tải về không phải tệp âm thanh hợp lệ.")
                }
            } finally {
                runCatching { extractor.release() }
            }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
