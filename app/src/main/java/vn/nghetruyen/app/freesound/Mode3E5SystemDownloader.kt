package vn.nghetruyen.app.freesound

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object Mode3E5SystemDownloader {
    data class PendingStatus(
        val pending: Boolean,
        val failed: Boolean,
        val reason: String? = null,
    )

    fun enqueue(context: Context): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = prefs(appContext)

        if (hasPending(appContext)) return@runCatching

        val downloadRoot = downloadDirectory(appContext).apply { mkdirs() }
        val modelDownload = File(downloadRoot, MODEL_FILE)
        val tokenizerDownload = File(downloadRoot, TOKENIZER_FILE)
        modelDownload.delete()
        tokenizerDownload.delete()

        clearDownloadIds(appContext)

        val modelId = manager.enqueue(
            DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("Multilingual E5 Small")
                .setDescription("Đang tải mô hình ngữ nghĩa")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    "$DOWNLOAD_FOLDER/$MODEL_FILE",
                ),
        )
        val tokenizerId = try {
            manager.enqueue(
                DownloadManager.Request(Uri.parse(TOKENIZER_URL))
                    .setTitle("Tokenizer Multilingual E5")
                    .setDescription("Đang tải tokenizer ngữ nghĩa")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(
                        appContext,
                        Environment.DIRECTORY_DOWNLOADS,
                        "$DOWNLOAD_FOLDER/$TOKENIZER_FILE",
                    ),
            )
        } catch (error: Throwable) {
            manager.remove(modelId)
            throw error
        }

        prefs.edit()
            .putLong(KEY_MODEL_DOWNLOAD_ID, modelId)
            .putLong(KEY_TOKENIZER_DOWNLOAD_ID, tokenizerId)
            .apply()
    }

    fun pendingStatus(context: Context): PendingStatus {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val modelId = prefs.getLong(KEY_MODEL_DOWNLOAD_ID, 0L)
        val tokenizerId = prefs.getLong(KEY_TOKENIZER_DOWNLOAD_ID, 0L)
        if (modelId <= 0L || tokenizerId <= 0L) return PendingStatus(false, false)
        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val model = query(manager, modelId)
        val tokenizer = query(manager, tokenizerId)
        if (model == null || tokenizer == null) {
            clearDownloadIds(appContext)
            return PendingStatus(false, true, "Android không còn tìm thấy tác vụ tải mô hình.")
        }
        val failed = model.first == DownloadManager.STATUS_FAILED || tokenizer.first == DownloadManager.STATUS_FAILED
        if (failed) {
            manager.remove(modelId, tokenizerId)
            clearDownloadIds(appContext)
            return PendingStatus(false, true, "Android DownloadManager báo tải mô hình thất bại.")
        }
        val completed = model.first == DownloadManager.STATUS_SUCCESSFUL && tokenizer.first == DownloadManager.STATUS_SUCCESSFUL
        return PendingStatus(!completed, false)
    }

    fun hasPending(context: Context): Boolean = pendingStatus(context).pending

    suspend fun finalizeIfComplete(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val prefs = prefs(appContext)
            val modelId = prefs.getLong(KEY_MODEL_DOWNLOAD_ID, 0L)
            val tokenizerId = prefs.getLong(KEY_TOKENIZER_DOWNLOAD_ID, 0L)
            if (modelId <= 0L || tokenizerId <= 0L) return@runCatching false

            val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val modelState = query(manager, modelId)
            val tokenizerState = query(manager, tokenizerId)
            if (modelState == null || tokenizerState == null) {
                clearDownloadIds(appContext)
                throw IllegalStateException("Android không còn tìm thấy tác vụ tải mô hình.")
            }
            if (modelState.first == DownloadManager.STATUS_FAILED || tokenizerState.first == DownloadManager.STATUS_FAILED) {
                manager.remove(modelId, tokenizerId)
                clearDownloadIds(appContext)
                throw IllegalStateException("Tải mô hình ngữ nghĩa thất bại trong Android DownloadManager.")
            }
            if (modelState.first != DownloadManager.STATUS_SUCCESSFUL || tokenizerState.first != DownloadManager.STATUS_SUCCESSFUL) {
                return@runCatching false
            }

            val root = downloadDirectory(appContext)
            val downloadedModel = File(root, MODEL_FILE)
            val downloadedTokenizer = File(root, TOKENIZER_FILE)
            check(downloadedModel.isFile) { "Không tìm thấy tệp mô hình đã tải." }
            check(downloadedTokenizer.isFile) { "Không tìm thấy tokenizer đã tải." }
            check(sha256File(downloadedModel) == MODEL_SHA256) { "Checksum mô hình E5 không hợp lệ." }
            check(sha256File(downloadedTokenizer) == TOKENIZER_SHA256) { "Checksum tokenizer E5 không hợp lệ." }

            val destination = modelDirectory(appContext).apply { mkdirs() }
            copyAtomically(downloadedModel, File(destination, MODEL_FILE))
            copyAtomically(downloadedTokenizer, File(destination, TOKENIZER_FILE))
            writeManifest(destination)

            manager.remove(modelId, tokenizerId)
            downloadedModel.delete()
            downloadedTokenizer.delete()
            clearDownloadIds(appContext)
            true
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val modelId = prefs.getLong(KEY_MODEL_DOWNLOAD_ID, 0L)
        val tokenizerId = prefs.getLong(KEY_TOKENIZER_DOWNLOAD_ID, 0L)
        val ids = listOf(modelId, tokenizerId).filter { it > 0L }.toLongArray()
        if (ids.isNotEmpty()) {
            val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(*ids)
        }
        downloadDirectory(appContext).deleteRecursively()
        clearDownloadIds(appContext)
    }

    private fun query(manager: DownloadManager, id: Long): Pair<Int, Int>? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return status to reason
        }
    }

    private fun copyAtomically(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        source.inputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(sha256File(temporary) == if (destination.name == MODEL_FILE) MODEL_SHA256 else TOKENIZER_SHA256) {
            temporary.delete()
            "Checksum thay đổi trong khi cài mô hình."
        }
        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun writeManifest(directory: File) {
        val properties = Properties().apply {
            setProperty("modelId", MODEL_ID)
            setProperty("packVersion", PACK_VERSION.toString())
            setProperty("dimensions", EMBEDDING_DIMENSIONS.toString())
            setProperty("maxTokens", MAX_TOKENS.toString())
            setProperty("modelSha256", MODEL_SHA256)
            setProperty("tokenizerSha256", TOKENIZER_SHA256)
            setProperty("verified", "true")
        }
        val temporary = File(directory, "$MANIFEST_FILE.tmp")
        temporary.outputStream().buffered().use { properties.store(it, null) }
        val destination = File(directory, MANIFEST_FILE)
        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearDownloadIds(context: Context) {
        prefs(context).edit()
            .remove(KEY_MODEL_DOWNLOAD_ID)
            .remove(KEY_TOKENIZER_DOWNLOAD_ID)
            .apply()
    }

    private fun downloadDirectory(context: Context): File =
        File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), DOWNLOAD_FOLDER)

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, "models/$MODEL_ID/v$PACK_VERSION")

    private const val PREFS_NAME = "mode3_e5_system_download"
    private const val KEY_MODEL_DOWNLOAD_ID = "model_download_id"
    private const val KEY_TOKENIZER_DOWNLOAD_ID = "tokenizer_download_id"
    private const val DOWNLOAD_FOLDER = "mode3_e5_v2"

    private const val MODEL_ID = "multilingual-e5-small"
    private const val PACK_VERSION = 2
    private const val MODEL_FILE = "model_int8.onnx"
    private const val TOKENIZER_FILE = "tokenizer.json"
    private const val MANIFEST_FILE = "model-pack.properties"
    private const val MODEL_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_int8.onnx?download=true"
    private const val TOKENIZER_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json?download=true"
    private const val MODEL_SHA256 = "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c"
    private const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    private const val EMBEDDING_DIMENSIONS = 384
    private const val MAX_TOKENS = 192
}