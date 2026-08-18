package vn.nghetruyen.app.transfer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.sourceplatform.UnifiedSourcePlatformManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.pow









class LegacyXpkCompleteRestoreCoordinator(
    context: Context,
    private val legacyImporter: LegacyXpkBackupImporter,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val sourcePlatformManager: UnifiedSourcePlatformManager,
    private val onSourcesChanged: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class RestoreSummary(
        val legacy: LegacyXpkBackupImporter.RestoreSummary,
        val musicTracks: Int = 0,
        val extensionsInstalled: Int = 0,
        val extensionFilesPreserved: Int = 0,
        val warnings: List<String> = emptyList(),
    ) {
        fun userMessage(): String = buildString {
            append("Đã chuyển đổi bản sao lưu XPK: ")
            append(legacy.stories).append(" truyện, ")
            append(legacy.chapters).append(" chương, ")
            append(legacy.progress).append(" tiến độ, ")
            append(legacy.readingHistory).append(" lịch sử, ")
            append(legacy.bookmarks).append(" đánh dấu")
            if (legacy.vietPhraseRules > 0) append(", ").append(legacy.vietPhraseRules).append(" mục VietPhrase")
            if (musicTracks > 0) append(", ").append(musicTracks).append(" bài nhạc nền")
            if (extensionsInstalled > 0) append(", ").append(extensionsInstalled).append(" tiện ích")
            append(".")
            if (extensionFilesPreserved > extensionsInstalled) {
                append(" Đã giữ nguyên ").append(extensionFilesPreserved)
                    .append(" tệp tiện ích gốc trong vùng bảo tồn; định dạng tương thích đã được cài vào runtime mới.")
            }
            if (warnings.isNotEmpty()) {
                append(" Có ").append(warnings.size).append(" cảnh báo chuyển đổi.")
            }
        }
    }

    suspend fun inspect(source: Uri): AppResult<LegacyXpkBackupImporter.Inspection> = withContext(Dispatchers.IO) {
        try {
            val input = resolver.openInputStream(source)
                ?: return@withContext AppResult.Failure("RESTORE_OPEN_FAILED", "Không mở được tệp sao lưu.")
            val scan = input.use(::scanArchive)
            val manifest = scan.manifest
                ?: return@withContext AppResult.Success(LegacyXpkBackupImporter.Inspection(isLegacyXpk = false))

            if (manifest.optString("format") == CURRENT_KOTLIN_FORMAT) {
                return@withContext AppResult.Success(LegacyXpkBackupImporter.Inspection(isLegacyXpk = false))
            }

            val version = manifest.optInt("format_version", -1)
            val app = manifest.optString("app")
            val legacyShape = scan.entries.any { name ->
                name == LEGACY_DB_ENTRY ||
                    name == LEGACY_VP_DB_ENTRY ||
                    name == "settings.json" ||
                    name == "library_preferences.json" ||
                    name == "background_music/manifest.json" ||
                    name.startsWith("background_music/files/") ||
                    name.startsWith("extensions/") ||
                    name.startsWith("extension_packages/") ||
                    name.startsWith("downloads/")
            }
            if (
                version !in 1..MAX_LEGACY_FORMAT_VERSION ||
                !legacyShape ||
                (app.isNotBlank() &&
                    !app.contains("NgheTruyen", ignoreCase = true) &&
                    !app.contains("Nghe Truyen", ignoreCase = true))
            ) {
                return@withContext AppResult.Success(LegacyXpkBackupImporter.Inspection(isLegacyXpk = false))
            }

            val components = manifest.optJSONArray("components").toStringSet().ifEmpty {
                buildSet {
                    if ("settings.json" in scan.entries) add("settings")
                    if (LEGACY_DB_ENTRY in scan.entries || scan.entries.any { it.startsWith("downloads/") }) add("library")
                    if (LEGACY_VP_DB_ENTRY in scan.entries) add("vietphrase")
                    if (scan.entries.any { it.startsWith("extensions/") || it.startsWith("extension_packages/") }) add("extensions")
                    if (scan.entries.any { it.startsWith("background_music/") }) add("music")
                }
            }
            AppResult.Success(
                LegacyXpkBackupImporter.Inspection(
                    isLegacyXpk = true,
                    preview = LegacyXpkBackupImporter.Preview(
                        formatVersion = version,
                        databaseSchema = manifest.optInt("database_schema", 0),
                        scope = manifest.optString("scope", "all").ifBlank { "all" },
                        components = components,
                        hasLibraryDatabase = LEGACY_DB_ENTRY in scan.entries,
                        hasVietPhraseDatabase = LEGACY_VP_DB_ENTRY in scan.entries,
                        hasSettings = "settings.json" in scan.entries,
                        hasExtensions = scan.entries.any { it.startsWith("extensions/") || it.startsWith("extension_packages/") },
                        hasMusic = scan.entries.any { it.startsWith("background_music/") },
                        hasDownloadedFiles = scan.entries.any { it.startsWith("downloads/") },
                    ),
                ),
            )
        } catch (error: Exception) {
            AppResult.Failure(
                "LEGACY_XPK_INSPECT_FAILED",
                error.message ?: "Không kiểm tra được bản sao lưu XPK.",
                error,
            )
        }
    }

    suspend fun restoreFrom(
        source: Uri,
        requestedComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<RestoreSummary> = withContext(Dispatchers.IO) {
        val requested = requestedComponents.ifEmpty { BackupComponent.entries.toSet() }
        when (val base = legacyImporter.restoreFrom(source, requested)) {
            is AppResult.Failure -> base
            is AppResult.Success -> {
                val stageRoot = File(appContext.cacheDir, "legacy_xpk_complete_${System.nanoTime()}")
                try {
                    if (!stageRoot.mkdirs()) throw IOException("Không tạo được vùng tạm để nhập dữ liệu XPK đầy đủ.")
                    resolver.openInputStream(source)?.use { extractExternalPayloads(it, stageRoot, requested) }
                        ?: throw IOException("Không mở lại được tệp sao lưu XPK.")

                    val warnings = mutableListOf<String>()
                    val musicCount = if (BackupComponent.SCENE_MUSIC in requested) {
                        restoreMusic(stageRoot, warnings)
                    } else 0
                    val extensionResult = if (BackupComponent.SOURCES_EXTENSIONS in requested) {
                        restoreExtensions(stageRoot, warnings)
                    } else ExtensionRestore()

                    AppResult.Success(
                        RestoreSummary(
                            legacy = base.value,
                            musicTracks = musicCount,
                            extensionsInstalled = extensionResult.installed,
                            extensionFilesPreserved = extensionResult.preserved,
                            warnings = warnings,
                        ),
                    )
                } catch (error: Exception) {
                    AppResult.Failure(
                        "LEGACY_XPK_EXTERNAL_RESTORE_FAILED",
                        error.message ?: "Không nhập được nhạc nền hoặc tiện ích từ bản sao lưu XPK.",
                        error,
                    )
                } finally {
                    stageRoot.deleteRecursively()
                }
            }
        }
    }

    private data class ArchiveScan(val manifest: JSONObject?, val entries: Set<String>)

    private fun scanArchive(input: InputStream): ArchiveScan {
        var manifest: JSONObject? = null
        val names = linkedSetOf<String>()
        var count = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                require(count <= MAX_ENTRY_COUNT) { "Bản sao lưu có quá nhiều mục." }
                val name = entry.name
                validateEntryName(name)
                require(names.add(name)) { "Bản sao lưu có mục trùng lặp: $name" }
                if (!entry.isDirectory) {
                    if (name == "manifest.json") {
                        val bytes = readBounded(zip, MAX_MANIFEST_BYTES)
                        total += bytes.size
                        manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                    } else {
                        total += drainBounded(zip, MAX_SCAN_ENTRY_BYTES)
                    }
                    require(total <= MAX_SCAN_BYTES) { "Tệp XPK vượt giới hạn kiểm tra an toàn." }
                }
                zip.closeEntry()
            }
        }
        return ArchiveScan(manifest, names)
    }

    private fun extractExternalPayloads(input: InputStream, stageRoot: File, requested: Set<BackupComponent>) {
        var total = 0L
        var count = 0
        val seen = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                require(count <= MAX_ENTRY_COUNT) { "Bản sao lưu có quá nhiều mục." }
                val name = entry.name
                validateEntryName(name)
                require(seen.add(name)) { "Bản sao lưu có mục trùng lặp: $name" }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val shouldExtract = when {
                    name == "background_music/manifest.json" -> BackupComponent.SCENE_MUSIC in requested
                    name.startsWith("background_music/files/") && safeLeaf(name.substringAfter("background_music/files/")) ->
                        BackupComponent.SCENE_MUSIC in requested
                    name == "extension_preferences.json" -> BackupComponent.SOURCES_EXTENSIONS in requested
                    name.startsWith("extensions/") && safeLeaf(name.substringAfter("extensions/")) ->
                        BackupComponent.SOURCES_EXTENSIONS in requested
                    name.startsWith("extension_packages/") && safeLeaf(name.substringAfter("extension_packages/")) ->
                        BackupComponent.SOURCES_EXTENSIONS in requested
                    else -> false
                }
                if (shouldExtract) {
                    val target = File(stageRoot, name).canonicalFile
                    require(target.path.startsWith(stageRoot.canonicalPath + File.separator)) { "Đường dẫn XPK không an toàn." }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val copied = copyBounded(zip, output, entryLimit(name))
                        total += copied
                    }
                    require(total <= MAX_EXTERNAL_RESTORE_BYTES) { "Dữ liệu nhạc/tiện ích XPK vượt giới hạn an toàn." }
                }
                zip.closeEntry()
            }
        }
    }

    private suspend fun restoreMusic(stageRoot: File, warnings: MutableList<String>): Int {
        val musicRoot = stageRoot.resolve("background_music")
        val filesRoot = musicRoot.resolve("files")
        val manifestFile = musicRoot.resolve("manifest.json")
        if (!manifestFile.isFile && !filesRoot.isDirectory) return 0

        val manifest = if (manifestFile.isFile) {
            runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }
                .getOrElse {
                    warnings += "Manifest nhạc nền XPK không đọc được; các tệp nhạc vẫn được nhập theo tên tệp."
                    JSONObject()
                }
        } else JSONObject()
        preserveRaw(manifestFile.takeIf(File::isFile), "music")

        val imported = mutableListOf<SceneMusicTrackEntity>()
        val legacyIdToUri = mutableMapOf<String, String>()
        val referenced = mutableSetOf<String>()
        val tracks = manifest.optJSONArray("tracks")
        if (tracks != null) {
            for (index in 0 until tracks.length()) {
                val item = tracks.optJSONObject(index) ?: continue
                val archiveName = item.optString("archive_name")
                if (!archiveName.startsWith("background_music/files/")) continue
                val leaf = archiveName.substringAfter("background_music/files/")
                if (!safeLeaf(leaf)) continue
                referenced += archiveName
                val source = stageRoot.resolve(archiveName)
                if (!source.isFile) {
                    warnings += "Thiếu tệp nhạc nền ${item.optString("name", leaf)} trong backup."
                    continue
                }
                val restored = restoreMusicFile(source, archiveName)
                val legacyId = item.optString("id").ifBlank { archiveName }
                val id = stableMusicId(legacyId, restored.sha256)
                val uri = Uri.fromFile(restored.file).toString()
                legacyIdToUri[legacyId] = uri
                imported += SceneMusicTrackEntity(
                    id = id,
                    title = item.optString("name", leaf).ifBlank { leaf }.take(120),
                    uri = uri,
                    tagsCsv = item.optString("description").take(300),
                    volume = 1f,
                    enabled = item.optBoolean("enabled", true),
                    loudnessLufsEstimate = item.optDouble("loudness_lufs", -18.0).toFloat().coerceIn(-120f, 12f),
                    peakDbfs = item.optDouble("peak_dbfs", 0.0).toFloat().coerceIn(-120f, 12f),
                    normalizationTargetLufs = item.optDouble("normalization_target_lufs", -24.0).toFloat().coerceIn(-36f, -18f),
                    normalizationGainDb = item.optDouble("normalization_gain_db", 0.0).toFloat().coerceIn(-36f, 12f),
                    normalizationPeakLimited = item.optBoolean("normalization_peak_limited", false),
                    normalizationVersion = item.optInt("normalization_version", 0).coerceAtLeast(0),
                    normalizationError = item.optString("normalization_error").take(300),
                    orderIndex = index,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        if (filesRoot.isDirectory) {
            filesRoot.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEachIndexed { index, source ->
                val archiveName = "background_music/files/${source.name}"
                if (archiveName in referenced) return@forEachIndexed
                val restored = restoreMusicFile(source, archiveName)
                val uri = Uri.fromFile(restored.file).toString()
                imported += SceneMusicTrackEntity(
                    id = stableMusicId(archiveName, restored.sha256),
                    title = source.nameWithoutExtension.ifBlank { source.name }.take(120),
                    uri = uri,
                    tagsCsv = "",
                    volume = 1f,
                    enabled = true,
                    orderIndex = imported.size + index,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        imported.forEach { database.sceneMusicTrackDao().upsert(it) }
        if (imported.isNotEmpty()) {
            val currentLegacyId = manifest.optString("current_track_id")
            val currentUri = legacyIdToUri[currentLegacyId] ?: imported.firstOrNull { it.enabled }?.uri ?: imported.first().uri
            settingsRepository.setBackgroundMusic(currentUri)
            settingsRepository.setBackgroundMusicEnabled(manifest.optBoolean("enabled", true))
            settingsRepository.setAutoSceneMusicEnabled(manifest.optBoolean("scene_enabled", false))
            val mode = when (manifest.optString("play_mode", "sequential").lowercase(Locale.ROOT)) {
                "shuffle" -> SceneMusicPlaybackMode.SHUFFLE
                else -> SceneMusicPlaybackMode.SEQUENTIAL
            }
            settingsRepository.setSceneMusicPlaybackMode(mode)
            settingsRepository.setSceneMusicTargetLufs(manifest.optDouble("normalization_target_lufs", -24.0).toFloat())
            settingsRepository.setBackgroundMusicVolume(manifest.optDouble("export_volume", 0.25).toFloat())
            val duckingDb = manifest.optDouble("ducking_db", 4.0).coerceIn(0.0, 36.0)
            settingsRepository.setBackgroundMusicDuckFactor(10.0.pow(-duckingDb / 20.0).toFloat())
            settingsRepository.setBackgroundMusicAttackMillis(manifest.optInt("duck_attack_ms", 1850))
            settingsRepository.setBackgroundMusicReleaseMillis(manifest.optInt("duck_release_ms", 2050))
        }
        return imported.size
    }

    private data class RestoredMusicFile(val file: File, val sha256: String)

    private fun restoreMusicFile(source: File, archiveName: String): RestoredMusicFile {
        val hash = sha256(source)
        val extension = source.extension.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").take(12).ifBlank { "bin" }
        val target = appContext.filesDir.resolve("scene-music-restored/legacy-xpk/$hash.$extension")
        atomicCopy(source, target)
        return RestoredMusicFile(target, hash)
    }

    private data class ExtensionRestore(val installed: Int = 0, val preserved: Int = 0)

    private fun restoreExtensions(stageRoot: File, warnings: MutableList<String>): ExtensionRestore {
        val extensionRoot = stageRoot.resolve("extensions")
        val packageRoot = stageRoot.resolve("extension_packages")
        val preferences = readExtensionPreferences(stageRoot.resolve("extension_preferences.json"))
        preserveRaw(stageRoot.resolve("extension_preferences.json").takeIf(File::isFile), "extensions")

        var installed = 0
        var preserved = 0
        val installedNames = mutableSetOf<String>()

        fun applyEnabledState(sourceId: String, name: String) {
            preferences.enabledByName[name]?.let { enabled ->
                sourcePlatformManager.setEnabled(sourceId, enabled).onFailure {
                    warnings += "Không khôi phục được trạng thái bật/tắt của tiện ích $name."
                }
            }
        }

        packageRoot.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach { file ->
            preserveRaw(file, "extensions/packages")
            preserved += 1
            if (!file.extension.equals("zip", true)) return@forEach
            val result = runCatching {
                file.inputStream().use { sourcePlatformManager.prepareInstall(it).getOrThrow() }
                sourcePlatformManager.confirmPendingInstall().getOrThrow()
            }
            result.onSuccess { info ->
                installed += 1
                installedNames += info.name
                applyEnabledState(info.id, info.name)
            }.onFailure { error ->
                sourcePlatformManager.cancelPendingInstall()
                warnings += "Không kích hoạt được gói tiện ích ${file.name}: ${error.message.orEmpty().take(180)}"
            }
        }

        extensionRoot.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach { file ->
            preserveRaw(file, "extensions/files")
            preserved += 1
            when (file.extension.lowercase(Locale.ROOT)) {
                "lua" -> {
                    val result = runCatching {
                        file.inputStream().use { sourcePlatformManager.prepareNativeLuaImport(it).getOrThrow() }
                        sourcePlatformManager.confirmPendingInstall().getOrThrow()
                    }
                    result.onSuccess { info ->
                        if (info.name !in installedNames) installed += 1
                        installedNames += info.name
                        applyEnabledState(info.id, info.name)
                    }.onFailure { error ->
                        sourcePlatformManager.cancelPendingInstall()
                        warnings += "Tệp Lua ${file.name} đã được bảo tồn nhưng không thể kích hoạt: ${error.message.orEmpty().take(180)}"
                    }
                }
                "json" -> warnings += "Tệp JSON legacy ${file.name} đã được bảo tồn; runtime XPK v34 và runtime Kotlin đều không kích hoạt định dạng JSON extension cũ."
            }
        }

        if (installed > 0) onSourcesChanged()
        return ExtensionRestore(installed, preserved)
    }

    private data class ExtensionPreferences(val enabledByName: Map<String, Boolean>)

    private fun readExtensionPreferences(file: File): ExtensionPreferences {
        if (!file.isFile) return ExtensionPreferences(emptyMap())
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val settings = root.optJSONObject("NgheTruyenSettings") ?: JSONObject()
            val enabled = linkedMapOf<String, Boolean>()
            settings.keys().forEach { key ->
                if (key.startsWith("extension_enabled_")) {
                    val item = settings.optJSONObject(key)
                    if (item != null && item.has("value")) {
                        enabled[key.removePrefix("extension_enabled_")] = item.optBoolean("value", true)
                    }
                }
            }
            ExtensionPreferences(enabled)
        }.getOrDefault(ExtensionPreferences(emptyMap()))
    }

    private fun preserveRaw(source: File?, category: String): File? {
        if (source == null || !source.isFile) return null
        val hash = sha256(source)
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(140).ifBlank { "file" }
        val target = appContext.filesDir.resolve("legacy-xpk-preserved/$category/${hash.take(16)}-$safeName")
        atomicCopy(source, target)
        return target
    }

    private fun stableMusicId(legacyId: String, sha256: String): String = UUID.nameUUIDFromBytes(
        "legacy-xpk\u0000$legacyId\u0000$sha256".toByteArray(Charsets.UTF_8),
    ).toString()

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
        name == "background_music/manifest.json" -> 1024L * 1024L
        name == "extension_preferences.json" -> 16L * 1024L * 1024L
        name.startsWith("background_music/files/") -> 512L * 1024L * 1024L
        name.startsWith("extension_packages/") -> 128L * 1024L * 1024L
        name.startsWith("extensions/") -> 32L * 1024L * 1024L
        else -> 1024L * 1024L
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Mục XPK vượt giới hạn an toàn." }
            output.write(buffer, 0, count)
        }
        output.flush()
        return total
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Manifest XPK vượt giới hạn." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun drainBounded(input: InputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Mục XPK vượt giới hạn kiểm tra." }
        }
        return total
    }

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        source.inputStream().use { input -> FileOutputStream(temp).use(input::copyTo) }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    companion object {
        private const val CURRENT_KOTLIN_FORMAT = "nghe-truyen-kotlin"
        private const val MAX_LEGACY_FORMAT_VERSION = 7
        private const val LEGACY_DB_ENTRY = "database/accessible_reader.db"
        private const val LEGACY_VP_DB_ENTRY = "database/vietphrase_dictionary.db"
        private const val MAX_ENTRY_COUNT = 10_000
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_SCAN_ENTRY_BYTES = 512L * 1024L * 1024L
        private const val MAX_SCAN_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_EXTERNAL_RESTORE_BYTES = 1536L * 1024L * 1024L
    }
}
