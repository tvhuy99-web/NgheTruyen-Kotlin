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
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.repository.LibraryRepository

data class FreesoundImportResult(
    val trackId: String,
    val uri: String,
    val title: String,
    val downloadElapsedMs: Long = 0L,
    val normalizationElapsedMs: Long = 0L,
)

class FreesoundDuplicateException(
    val soundId: Int,
) : IllegalStateException("Âm thanh Freesound #$soundId đã có trong thư viện.")

class FreesoundNormalizationException(
    message: String,
    val retryable: Boolean = false,
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
        soundImportLock(sound.id).withLock {
            cleanupStalePartFiles(appContext)
            val duplicateCheck = runCatching { existingTracksProvider() }
            if (duplicateCheck.isFailure) {
                return@withLock Result.failure(
                    duplicateCheck.exceptionOrNull() ?: IllegalStateException("Không kiểm tra được thư viện hiện tại."),
                )
            }

            val matches = duplicateCheck.getOrThrow().filter { rawSoundIdFromManagedUri(it.uri) == sound.id }
            val existingFileTrack = matches.firstOrNull { managedFileExists(appContext, it.uri) }
            if (existingFileTrack != null) {
                if (hasValidNormalization(existingFileTrack)) {
                    normalizationMarker(existingFileTrack.uri)?.delete()
                    return@withLock Result.failure(FreesoundDuplicateException(sound.id))
                }
                return@withLock resumeExistingNormalization(
                    track = existingFileTrack,
                    normalizationTargetLufs = normalizationTargetLufs,
                )
            }
            matches.forEach { stale ->
                runCatching { repository.deleteSceneMusicTrack(stale.id) }
                deleteManagedFile(appContext, stale.uri)
            }

            importPreviewLocked(sound, kind, normalizationTargetLufs)
        }
    }

    private suspend fun resumeExistingNormalization(
        track: SceneMusicTrackEntity,
        normalizationTargetLufs: Float,
    ): Result<FreesoundImportResult> {
        val marker = normalizationMarker(track.uri)
        return try {
            marker?.parentFile?.mkdirs()
            marker?.writeText("normalizing", Charsets.UTF_8)
            val normalizationStartedNanos = System.nanoTime()
            val workId = SceneMusicAnalysisWorker.enqueue(
                context = appContext,
                trackId = track.id,
                targetLufs = normalizationTargetLufs,
                fastFreesound = true,
            )
            awaitNormalization(workId, track.id)
            val normalizationElapsedMs = (System.nanoTime() - normalizationStartedNanos) / 1_000_000L
            marker?.delete()
            Result.success(
                FreesoundImportResult(
                    trackId = track.id,
                    uri = track.uri,
                    title = track.title,
                    downloadElapsedMs = 0L,
                    normalizationElapsedMs = normalizationElapsedMs,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (shouldPreserveImportedFile(error)) {
                // The download is already valid. Keep it plus the marker so a later Mode-3
                // resolve can resume normalization instead of downloading the same sound again.
                Result.failure(error)
            } else {
                runCatching { repository.deleteSceneMusicTrack(track.id) }
                deleteManagedFile(appContext, track.uri)
                marker?.delete()
                Result.failure(error)
            }
        }
    }

    private suspend fun importPreviewLocked(
        sound: FreesoundSound,
        kind: AudioAssetKind,
        normalizationTargetLufs: Float,
    ): Result<FreesoundImportResult> {
        val candidates = previewCandidatesForImport(sound)
        if (candidates.isEmpty()) {
            return Result.failure(IllegalArgumentException("Âm thanh này không có preview HQ khả dụng."))
        }

        val directory = managedDirectory(appContext, kind)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return Result.failure(IllegalStateException("Không tạo được thư mục lưu âm thanh Freesound."))
        }

        var lastError: Throwable? = null
        for (previewUrl in candidates) {
            val attempt = importPreviewCandidate(
                sound = sound,
                kind = kind,
                normalizationTargetLufs = normalizationTargetLufs,
                previewUrl = previewUrl,
                directory = directory,
            )
            if (attempt.isSuccess) return attempt
            val error = attempt.exceptionOrNull()
            if (error is CancellationException) throw error
            if (error is FreesoundNormalizationException && error.retryable) return attempt
            lastError = error
        }

        return Result.failure(
            IllegalStateException(
                buildString {
                    append("Không nhập được preview HQ Freesound")
                    if (candidates.size > 1) append(" bằng cả OGG và MP3")
                    lastError?.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                    append('.')
                },
                lastError,
            ),
        )
    }

    private suspend fun importPreviewCandidate(
        sound: FreesoundSound,
        kind: AudioAssetKind,
        normalizationTargetLufs: Float,
        previewUrl: String,
        directory: File,
    ): Result<FreesoundImportResult> {
        if (!previewUrl.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Địa chỉ preview Freesound không an toàn."))
        }

        val extension = extensionForPreviewUrl(previewUrl)
        val finalFile = File(
            directory,
            "freesound_${sound.id}_${UUID.randomUUID()}.$extension",
        )
        val partFile = File(directory, "${finalFile.name}.part")
        val markerFile = File("${finalFile.absolutePath}$NORMALIZING_SUFFIX")
        var savedTrackId: String? = null

        return try {
            val downloadStartedNanos = System.nanoTime()
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
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
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
            val downloadElapsedMs = (System.nanoTime() - downloadStartedNanos) / 1_000_000L
            markerFile.writeText("normalizing", Charsets.UTF_8)

            val uri = Uri.fromFile(finalFile).toString()
            val title = titleForImport(sound.name, "Âm thanh Freesound ${sound.id}")
            val tagsCsv = tagsForImport(
                kind = kind,
                description = sound.description,
                soundId = sound.id,
                username = sound.username,
                license = sound.license,
                sourceUrl = sound.webUrl,
            )
            val trackId = repository.saveSceneMusicTrack(
                title = title,
                uri = uri,
                tagsCsv = tagsCsv,
            ).getOrThrow()
            savedTrackId = trackId

            val normalizationStartedNanos = System.nanoTime()
            val workId = SceneMusicAnalysisWorker.enqueue(
                context = appContext,
                trackId = trackId,
                targetLufs = normalizationTargetLufs,
                fastFreesound = true,
            )
            awaitNormalization(workId, trackId)
            val normalizationElapsedMs = (System.nanoTime() - normalizationStartedNanos) / 1_000_000L
            markerFile.delete()

            Result.success(
                FreesoundImportResult(
                    trackId = trackId,
                    uri = uri,
                    title = title,
                    downloadElapsedMs = downloadElapsedMs,
                    normalizationElapsedMs = normalizationElapsedMs,
                ),
            )
        } catch (cancelled: CancellationException) {
            savedTrackId?.let { runCatching { repository.deleteSceneMusicTrack(it) } }
            partFile.delete()
            finalFile.delete()
            markerFile.delete()
            throw cancelled
        } catch (error: Throwable) {
            partFile.delete()
            if (!shouldPreserveImportedFile(error)) {
                savedTrackId?.let { runCatching { repository.deleteSceneMusicTrack(it) } }
                finalFile.delete()
                markerFile.delete()
            }
            Result.failure(error)
        }
    }

    private suspend fun awaitNormalization(workId: UUID, trackId: String) {
        val workManager = WorkManager.getInstance(appContext)
        val deadline = System.currentTimeMillis() + NORMALIZATION_TIMEOUT_MS
        while (true) {
            val info = runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
                ?: throw FreesoundNormalizationException(
                    "Không đọc được trạng thái chuẩn hóa của tệp vừa nhập; tệp đã được giữ để thử lại.",
                    retryable = true,
                )
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val latest = repository.getSceneMusicTrack(trackId)
                        ?: throw FreesoundNormalizationException("Tệp vừa chuẩn hóa không còn trong thư viện.")
                    if (!hasValidNormalization(latest)) {
                        throw FreesoundNormalizationException(
                            latest.normalizationError.ifBlank { "Chuẩn hóa kết thúc nhưng dữ liệu loudness chưa hợp lệ." },
                        )
                    }
                    return
                }
                WorkInfo.State.FAILED -> {
                    val storedError = repository.getSceneMusicTrack(trackId)?.normalizationError.orEmpty().trim()
                    throw FreesoundNormalizationException(
                        storedError.ifBlank { "Chuẩn hóa âm thanh Freesound thất bại." },
                    )
                }
                WorkInfo.State.CANCELLED -> throw FreesoundNormalizationException(
                    "Chuẩn hóa âm thanh Freesound đã bị hủy; tệp đã được giữ để thử lại.",
                    retryable = true,
                )
                else -> Unit
            }
            if (System.currentTimeMillis() >= deadline) {
                SceneMusicAnalysisWorker.cancel(appContext, workId)
                throw FreesoundNormalizationException(
                    "Chuẩn hóa âm thanh Freesound quá thời gian cho phép; tệp đã được giữ để thử lại.",
                    retryable = true,
                )
            }
            delay(NORMALIZATION_POLL_MS)
        }
    }

    companion object {
        private const val USER_AGENT = "NgheTruyen-Android/Freesound"
        internal const val MAX_PREVIEW_BYTES = 64L * 1024L * 1024L
        internal const val MAX_BATCH_SIZE = 50
        private const val MANAGED_ROOT = "audio/freesound"
        private const val NORMALIZING_SUFFIX = ".normalizing"
        private const val FREE_SPACE_RESERVE_BYTES = 4L * 1024L * 1024L
        private const val UNKNOWN_LENGTH_REQUIRED_BYTES = 12L * 1024L * 1024L
        private const val NORMALIZATION_POLL_MS = 120L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val NORMALIZATION_TIMEOUT_MS = 10L * 60L * 1_000L
        private const val STALE_PART_AGE_MS = 15L * 60L * 1_000L
        private const val SOUND_LOCK_STRIPES = 64
        private val soundImportLocks = List(SOUND_LOCK_STRIPES) { Mutex() }
        private val managedSoundIdRegex = Regex(
            """(?i)(?:^|/)audio/freesound/(?:music|ambience|sfx)/freesound_(\d+)_[-0-9a-f]+\.(?:ogg|mp3)$""",
        )

        internal fun previewCandidatesForImport(sound: FreesoundSound): List<String> =
            listOfNotNull(sound.previewHqOgg, sound.previewHqMp3)
                .map(String::trim)
                .filter { it.startsWith("https://", ignoreCase = true) }
                .distinct()

        internal fun hasValidNormalization(track: SceneMusicTrackEntity): Boolean =
            track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
                track.normalizationError.isBlank() &&
                track.loudnessLufsEstimate.isFinite() &&
                track.peakDbfs.isFinite() &&
                track.normalizationGainDb.isFinite()

        internal fun shouldPreserveImportedFile(error: Throwable): Boolean =
            error is FreesoundNormalizationException && error.retryable

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

        internal fun tagsForImport(
            kind: AudioAssetKind,
            description: String,
            soundId: Int? = null,
            username: String = "",
            license: String = "",
            sourceUrl: String = "",
        ): String {
            val marker = when (kind) {
                AudioAssetKind.MUSIC -> "type:music"
                AudioAssetKind.AMBIENCE -> "type:ambience"
                AudioAssetKind.SFX -> "type:sfx"
            }
            val pieces = mutableListOf(marker)
            description.trim().take(300).takeIf(String::isNotBlank)?.let(pieces::add)
            soundId?.takeIf { it > 0 }?.let { pieces += "freesound_id:$it" }
            username.trim().take(120).takeIf(String::isNotBlank)?.let { pieces += "freesound_user:$it" }
            license.trim().take(240).takeIf(String::isNotBlank)?.let { pieces += "freesound_license:$it" }
            sourceUrl.trim()
                .takeIf { it.startsWith("https://freesound.org/", ignoreCase = true) }
                ?.let { pieces += "freesound_url:$it" }
            return pieces.joinToString(", ")
        }

        /** Returns a completed local Freesound id. In-progress normalization is intentionally hidden from UI duplicate checks. */
        internal fun soundIdFromManagedUri(uri: String): Int? {
            val id = rawSoundIdFromManagedUri(uri) ?: return null
            if (normalizationMarker(uri)?.isFile == true) return null
            return id
        }

        private fun rawSoundIdFromManagedUri(uri: String): Int? {
            val clean = uri.substringBefore('?').substringBefore('#')
            return managedSoundIdRegex.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        /** Pure string parser so local JVM unit tests do not invoke android.net.Uri stubs. */
        private fun normalizationMarker(uri: String): File? {
            val clean = uri.substringBefore('?').substringBefore('#')
            if (!clean.startsWith("file://", ignoreCase = true)) return null
            val path = clean.substring("file://".length)
            if (path.isBlank()) return null
            return File("$path$NORMALIZING_SUFFIX")
        }

        fun managedFileExists(context: Context, uri: String): Boolean {
            val file = managedFile(context, uri) ?: return false
            return file.isFile && file.length() > 0L
        }

        fun deleteManagedFile(context: Context, uri: String): Boolean {
            val candidate = managedFile(context, uri) ?: return false
            normalizationMarker(uri)?.delete()
            return !candidate.exists() || candidate.delete()
        }

        internal fun cleanupStalePartFiles(context: Context): Int {
            val root = File(context.applicationContext.filesDir, MANAGED_ROOT)
            if (!root.isDirectory) return 0
            val staleBefore = System.currentTimeMillis() - STALE_PART_AGE_MS
            var deleted = 0
            root.walkTopDown().forEach { file ->
                if (
                    file.isFile &&
                    file.name.endsWith(".part", ignoreCase = true) &&
                    file.lastModified() <= staleBefore &&
                    file.delete()
                ) {
                    deleted += 1
                }
            }
            return deleted
        }

        private fun soundImportLock(soundId: Int): Mutex =
            soundImportLocks[(soundId and Int.MAX_VALUE) % SOUND_LOCK_STRIPES]

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
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
