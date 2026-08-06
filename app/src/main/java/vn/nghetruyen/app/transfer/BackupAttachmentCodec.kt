package vn.nghetruyen.app.transfer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class BackupAttachment(
    val entry: String,
    val component: BackupComponent,
    val logicalId: String?,
    val file: File,
    val sha256: String,
    val size: Long,
)

internal data class BackupAttachmentDescriptor(
    val entry: String,
    val component: BackupComponent,
    val logicalId: String?,
    val sha256: String,
    val size: Long,
)

internal data class BackupAttachmentRestore(
    val sceneMusicUris: Map<String, String> = emptyMap(),
    val sourceFiles: Int = 0,
    val sceneMusicFiles: Int = 0,
)

/** Stages and restores portable non-database data without exporting credentials or cookies. */
internal class BackupAttachmentCodec(context: Context) {
    private val appContext = context.applicationContext

    fun stage(
        components: Set<BackupComponent>,
        sceneTracks: List<SceneMusicTrackEntity>,
        stageRoot: File,
    ): List<BackupAttachment> {
        val result = mutableListOf<BackupAttachment>()
        var totalBytes = 0L
        if (BackupComponent.SOURCES_EXTENSIONS in components) {
            val sourceRoot = appContext.filesDir.resolve("source-platform-v2").canonicalFile
            if (sourceRoot.isDirectory) {
                sourceRoot.walkTopDown().filter(File::isFile).forEach { source ->
                    val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
                    if (isPortableSourceFile(relative, source)) {
                        val staged = stageRoot.resolve("sources/$relative")
                        FileInputStream(source).use { input -> copyBounded(input, staged, MAX_SINGLE_FILE_BYTES) }
                        totalBytes += staged.length()
                        require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) { "BACKUP_ATTACHMENT_TOTAL_LIMIT" }
                        result += attachment("attachments/sources/$relative", BackupComponent.SOURCES_EXTENSIONS, null, staged)
                        require(result.size <= MAX_ATTACHMENT_COUNT) { "BACKUP_ATTACHMENT_COUNT_LIMIT" }
                    }
                }
            }
            val trustJson = appContext.getSharedPreferences(TRUST_PREFERENCES, Context.MODE_PRIVATE)
                .getString(TRUST_KEYS, null)
            if (!trustJson.isNullOrBlank()) {
                val staged = stageRoot.resolve("sources/trust-keys.json").apply { parentFile?.mkdirs(); writeText(trustJson, Charsets.UTF_8) }
                totalBytes += staged.length()
                require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) { "BACKUP_ATTACHMENT_TOTAL_LIMIT" }
                result += attachment("attachments/sources/trust-keys.json", BackupComponent.SOURCES_EXTENSIONS, "trust-keys", staged)
                require(result.size <= MAX_ATTACHMENT_COUNT) { "BACKUP_ATTACHMENT_COUNT_LIMIT" }
            }
        }
        if (BackupComponent.SCENE_MUSIC in components) {
            sceneTracks.take(MAX_SCENE_TRACKS).forEach { track ->
                val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return@forEach
                val extension = extensionFor(uri).takeIf(String::isNotBlank) ?: "bin"
                val safeId = track.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { UUID.randomUUID().toString() }
                val staged = stageRoot.resolve("scene_music/$safeId.$extension")
                val input = openUri(uri) ?: return@forEach
                runCatching { input.use { copyBounded(it, staged, MAX_SCENE_FILE_BYTES) } }.getOrElse { staged.delete(); return@forEach }
                totalBytes += staged.length()
                require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) { "BACKUP_ATTACHMENT_TOTAL_LIMIT" }
                result += attachment("attachments/scene_music/$safeId.$extension", BackupComponent.SCENE_MUSIC, track.id, staged)
                require(result.size <= MAX_ATTACHMENT_COUNT) { "BACKUP_ATTACHMENT_COUNT_LIMIT" }
            }
        }
        return result.sortedBy(BackupAttachment::entry)
    }

    fun toJson(attachments: List<BackupAttachment>): JSONArray = JSONArray().also { array ->
        attachments.forEach { item ->
            array.put(JSONObject()
                .put("entry", item.entry)
                .put("component", item.component.name)
                .put("logicalId", item.logicalId ?: JSONObject.NULL)
                .put("sha256", item.sha256)
                .put("size", item.size))
        }
    }

    fun parse(array: JSONArray?): List<BackupAttachmentDescriptor> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_ATTACHMENT_COUNT) { "RESTORE_ATTACHMENT_COUNT_LIMIT" }
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val entry = obj.getString("entry")
            require(isAttachmentEntry(entry)) { "RESTORE_ATTACHMENT_PATH_INVALID" }
            val component = BackupComponent.valueOf(obj.getString("component"))
            require(component in setOf(BackupComponent.SOURCES_EXTENSIONS, BackupComponent.SCENE_MUSIC)) { "RESTORE_ATTACHMENT_COMPONENT_INVALID" }
            val hash = obj.getString("sha256")
            require(hash.matches(Regex("^[0-9a-fA-F]{64}$"))) { "RESTORE_ATTACHMENT_HASH_INVALID" }
            val size = obj.getLong("size")
            val maxSize = if (component == BackupComponent.SCENE_MUSIC) MAX_SCENE_FILE_BYTES else MAX_SINGLE_FILE_BYTES
            require(size in 0..maxSize) { "RESTORE_ATTACHMENT_SIZE_INVALID" }
            val logicalId = if (obj.isNull("logicalId")) null else obj.optString("logicalId").takeIf { it.isNotBlank() && it != "null" }
            BackupAttachmentDescriptor(entry, component, logicalId, hash.lowercase(), size)
        }.also { values -> require(values.map { it.entry }.distinct().size == values.size) { "RESTORE_ATTACHMENT_DUPLICATE" } }
    }

    fun verify(stageRoot: File, descriptors: List<BackupAttachmentDescriptor>, extractedEntries: Set<String>) {
        val expected = descriptors.mapTo(linkedSetOf(), BackupAttachmentDescriptor::entry)
        require(extractedEntries == expected) { "RESTORE_ATTACHMENT_MANIFEST_MISMATCH" }
        descriptors.forEach { descriptor ->
            val file = stageRoot.resolve(descriptor.entry).canonicalFile
            require(file.isFile && file.length() == descriptor.size) { "RESTORE_ATTACHMENT_SIZE_MISMATCH:${descriptor.entry}" }
            require(sha256(file) == descriptor.sha256) { "RESTORE_ATTACHMENT_CHECKSUM_MISMATCH:${descriptor.entry}" }
        }
    }

    fun restore(
        stageRoot: File,
        descriptors: List<BackupAttachmentDescriptor>,
        components: Set<BackupComponent>,
    ): BackupAttachmentRestore {
        val selected = descriptors.filter { it.component in components }
        val musicUris = linkedMapOf<String, String>()
        var sourceFiles = 0
        var musicFiles = 0

        selected.filter { it.component == BackupComponent.SCENE_MUSIC }.forEach { descriptor ->
            val id = descriptor.logicalId ?: return@forEach
            val source = stageRoot.resolve(descriptor.entry)
            val extension = descriptor.entry.substringAfterLast('.', "bin").take(12)
            val target = appContext.filesDir.resolve("scene-music-restored/${descriptor.sha256}.$extension")
            target.parentFile?.mkdirs()
            atomicCopy(source, target)
            musicUris[id] = Uri.fromFile(target).toString()
            musicFiles += 1
        }

        val sourceDescriptors = selected.filter { it.component == BackupComponent.SOURCES_EXTENSIONS && it.logicalId != "trust-keys" }
        if (sourceDescriptors.isNotEmpty()) {
            val targetRoot = appContext.filesDir.resolve("source-platform-v2")
            val stagedRoot = appContext.filesDir.resolve(".source-platform-v2.restore.${System.nanoTime()}")
            stagedRoot.deleteRecursively(); stagedRoot.mkdirs()
            sourceDescriptors.forEach { descriptor ->
                val relative = descriptor.entry.removePrefix("attachments/sources/")
                require(relative.isNotBlank() && ".." !in relative) { "RESTORE_SOURCE_PATH_INVALID" }
                val target = stagedRoot.resolve(relative).canonicalFile
                require(target.path.startsWith(stagedRoot.canonicalPath + File.separator)) { "RESTORE_SOURCE_PATH_ESCAPE" }
                target.parentFile?.mkdirs()
                atomicCopy(stageRoot.resolve(descriptor.entry), target)
                sourceFiles += 1
            }
            atomicReplaceDirectory(stagedRoot, targetRoot)
        }
        selected.firstOrNull { it.logicalId == "trust-keys" }?.let { descriptor ->
            val raw = stageRoot.resolve(descriptor.entry).readText(Charsets.UTF_8)
            require(raw.length <= 1024 * 1024) { "RESTORE_TRUST_KEYS_TOO_LARGE" }
            appContext.getSharedPreferences(TRUST_PREFERENCES, Context.MODE_PRIVATE).edit().putString(TRUST_KEYS, raw).commit()
        }
        return BackupAttachmentRestore(musicUris, sourceFiles, musicFiles)
    }

    private fun attachment(entry: String, component: BackupComponent, logicalId: String?, file: File) = BackupAttachment(
        entry = entry,
        component = component,
        logicalId = logicalId,
        file = file,
        sha256 = sha256(file),
        size = file.length(),
    )

    private fun openUri(uri: Uri): InputStream? = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
        else -> appContext.contentResolver.openInputStream(uri)
    }

    private fun extensionFor(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty().substringAfterLast('/', "").substringAfterLast('.', "")
        return name.lowercase().replace(Regex("[^a-z0-9]"), "").take(12)
    }

    private fun isPortableSourceFile(relative: String, file: File): Boolean =
        relative.isNotBlank() && ".." !in relative && file.length() <= MAX_SINGLE_FILE_BYTES &&
            !relative.endsWith(".tmp") && !relative.endsWith(".lock") &&
            relative.split('/').none { it.equals("cookies", true) || it.contains("credential", true) || it.contains("secret", true) } &&
            !relative.contains("token", true)

    private fun isAttachmentEntry(entry: String): Boolean =
        (entry.startsWith("attachments/sources/") || entry.startsWith("attachments/scene_music/")) &&
            entry.length <= 1024 && ".." !in entry && '\\' !in entry && '\u0000' !in entry &&
            !entry.startsWith('/') && entry.substringAfter("attachments/").isNotBlank()

    private fun copyBounded(input: InputStream, target: File, maxBytes: Long) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "BACKUP_ATTACHMENT_FILE_LIMIT" }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        source.inputStream().use { input -> FileOutputStream(temp).use(input::copyTo) }
        try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun atomicReplaceDirectory(staged: File, target: File) {
        val backup = File(target.parentFile, ".${target.name}.backup.${System.nanoTime()}")
        if (target.exists() && !target.renameTo(backup)) error("RESTORE_SOURCE_BACKUP_FAILED")
        try {
            if (!staged.renameTo(target)) {
                target.mkdirs()
                staged.copyRecursively(target, overwrite = true)
                staged.deleteRecursively()
            }
            backup.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TRUST_PREFERENCES = "source_trust_registry_v2"
        private const val TRUST_KEYS = "user.keys"
        private const val MAX_ATTACHMENT_COUNT = 2048
        private const val MAX_SCENE_TRACKS = 512
        private const val MAX_SINGLE_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_SCENE_FILE_BYTES = 256L * 1024L * 1024L
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 1024L * 1024L * 1024L
    }
}
