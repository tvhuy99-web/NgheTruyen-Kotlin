package vn.nghetruyen.app.freesound

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.data.repository.LibraryRepository

data class FreesoundImportResult(
    val trackId: String,
    val uri: String,
    val title: String,
)

class FreesoundImporter(
    context: Context,
    private val repository: LibraryRepository,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val appContext = context.applicationContext

    suspend fun importPreview(
        sound: FreesoundSound,
        kind: AudioAssetKind,
        normalizationTargetLufs: Float,
    ): Result<FreesoundImportResult> = withContext(Dispatchers.IO) {
        val previewUrl = sound.preferredPreviewUrl
            ?: return@withContext Result.failure(IllegalArgumentException("Âm thanh này không có preview HQ khả dụng."))
        if (!previewUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Địa chỉ preview Freesound không an toàn."))
        }

        val extension = extensionForPreviewUrl(previewUrl)
        val directory = managedDirectory(appContext, kind)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return@withContext Result.failure(IllegalStateException("Không tạo được thư mục lưu âm thanh Freesound."))
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

            val uri = Uri.fromFile(finalFile).toString()
            val title = titleForImport(sound.name, "Âm thanh Freesound ${sound.id}")
            val tagsCsv = tagsForImport(kind, sound.description)
            val trackId = repository.saveSceneMusicTrack(
                title = title,
                uri = uri,
                tagsCsv = tagsCsv,
            ).getOrThrow()
            savedTrackId = trackId

            try {
                SceneMusicAnalysisWorker.enqueue(
                    context = appContext,
                    trackId = trackId,
                    targetLufs = normalizationTargetLufs,
                )
            } catch (error: Throwable) {
                repository.deleteSceneMusicTrack(trackId)
                savedTrackId = null
                throw error
            }

            Result.success(
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
            Result.failure(error)
        }
    }

    companion object {
        private const val USER_AGENT = "NgheTruyen-Android/Freesound"
        internal const val MAX_PREVIEW_BYTES = 64L * 1024L * 1024L
        private const val MANAGED_ROOT = "audio/freesound"

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

        fun deleteManagedFile(context: Context, uri: String): Boolean {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
            if (!parsed.scheme.equals("file", ignoreCase = true)) return false
            val path = parsed.path ?: return false
            val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
            val root = runCatching { File(context.applicationContext.filesDir, MANAGED_ROOT).canonicalFile }.getOrNull()
                ?: return false
            val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
            if (!candidate.path.startsWith(rootPath)) return false
            return !candidate.exists() || candidate.delete()
        }

        private fun managedDirectory(context: Context, kind: AudioAssetKind): File =
            File(
                File(context.applicationContext.filesDir, MANAGED_ROOT),
                kind.name.lowercase(Locale.ROOT),
            )

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
