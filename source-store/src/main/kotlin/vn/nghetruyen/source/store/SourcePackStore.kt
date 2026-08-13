package vn.nghetruyen.source.store

import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePermissionDiff
import vn.nghetruyen.source.api.SourcePermissionSnapshot
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.packagekit.SourceManifestParser
import vn.nghetruyen.source.packagekit.SourcePackArchiveVerifier
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.UUID

private const val ACTIVE_FILE = "active.version"
private const val ENABLED_FILE = "enabled"
private const val HISTORY_FILE = "activation.history"
private const val META_FILE = "package.properties"

data class InstalledSourceVersion(
    val manifest: SourceManifest,
    val directory: File,
    val packageSha256: String,
    val signerKeyId: String,
    val signatureAlgorithm: String,
    val installedAtEpochMs: Long,
)

data class InstalledSource(
    val sourceId: String,
    val enabled: Boolean,
    val activeVersion: SemanticVersion?,
    val versions: List<InstalledSourceVersion>,
) {
    val active: InstalledSourceVersion? get() = versions.firstOrNull { it.manifest.version == activeVersion }
}

class SourcePackStore(
    root: File,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val maxRetainedVersions: Int = 3,
) {
    private val rootDir = root.canonicalFile
    private val sourcesDir = File(rootDir, "sources")
    private val stagingDir = File(rootDir, "staging")
    private val lock = Any()

    init {
        require(maxRetainedVersions in 2..10) { "SOURCE_STORE_RETENTION_INVALID" }
        sourcesDir.mkdirs()
        stagingDir.mkdirs()
        cleanupAbandonedStaging()
    }

    fun install(pack: VerifiedSourcePack, activate: Boolean = true, traceId: String = UUID.randomUUID().toString()): SourcePlatformResult<InstalledSource> =
        synchronized(lock) {
            val started = System.currentTimeMillis()
            runCatching {
                val sourceRoot = sourceRoot(pack.manifest.id)
                val versionsRoot = File(sourceRoot, "versions").also(File::mkdirs)
                val finalVersionDir = child(versionsRoot, pack.manifest.version.toString())
                if (finalVersionDir.exists()) {
                    val existing = loadVersion(finalVersionDir)
                    require(existing.packageSha256 == pack.packageSha256) { "SOURCE_VERSION_HASH_CONFLICT" }
                    if (activate) activateInternal(sourceRoot, pack.manifest.version)
                    return@synchronized SourcePlatformResult.Success(load(pack.manifest.id)!!)
                }
                val stage = child(stagingDir, "${pack.manifest.id}-${pack.manifest.version}-${UUID.randomUUID()}")
                stage.mkdirs()
                try {
                    writePack(stage, pack)
                    // Re-read from disk before activation so partial/corrupt writes cannot become active.
                    val staged = loadVersion(stage)
                    require(staged.manifest.id == pack.manifest.id && staged.manifest.version == pack.manifest.version) {
                        "SOURCE_STAGE_VERIFY_FAILED"
                    }
                    atomicMove(stage, finalVersionDir)
                    ensureEnabledFile(sourceRoot)
                    if (activate) activateInternal(sourceRoot, pack.manifest.version)
                    pruneVersions(sourceRoot)
                } finally {
                    if (stage.exists()) stage.deleteRecursively()
                }
                load(pack.manifest.id) ?: error("SOURCE_INSTALL_NOT_VISIBLE")
            }.fold(
                onSuccess = { installed ->
                    diagnostics.emit(
                        DiagnosticEvent(
                            timestampEpochMs = System.currentTimeMillis(),
                            traceId = traceId,
                            sourceId = pack.manifest.id,
                            sourceVersion = pack.manifest.version.toString(),
                            category = DiagnosticCategory.STORE,
                            name = "SOURCE_INSTALLED",
                            durationMs = System.currentTimeMillis() - started,
                            attributes = mapOf("active" to activate.toString(), "signerKeyId" to pack.signerKeyId),
                        ),
                    )
                    SourcePlatformResult.Success(installed)
                },
                onFailure = { error ->
                    diagnostics.emit(
                        DiagnosticEvent(
                            timestampEpochMs = System.currentTimeMillis(),
                            traceId = traceId,
                            sourceId = pack.manifest.id,
                            sourceVersion = pack.manifest.version.toString(),
                            category = DiagnosticCategory.STORE,
                            name = "SOURCE_INSTALL_FAILED",
                            severity = DiagnosticSeverity.ERROR,
                            durationMs = System.currentTimeMillis() - started,
                            attributes = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
                        ),
                    )
                    SourcePlatformResult.Failure(
                        SourcePlatformFailure(SourceErrorCode.INSTALL_FAILED, error.message ?: "SOURCE_INSTALL_FAILED", traceId, error),
                    )
                },
            )
        }

    fun permissionDiff(manifest: SourceManifest): SourcePermissionDiff = synchronized(lock) {
        val old = load(manifest.id)?.active?.manifest?.let(SourcePermissionSnapshot::from)
        SourcePermissionDiff.between(old, SourcePermissionSnapshot.from(manifest))
    }

    fun setEnabled(sourceId: String, enabled: Boolean): SourcePlatformResult<InstalledSource> = synchronized(lock) {
        runCatching {
            val root = sourceRoot(sourceId)
            require(root.isDirectory) { "SOURCE_NOT_INSTALLED" }
            atomicWrite(File(root, ENABLED_FILE), enabled.toString().toByteArray(StandardCharsets.UTF_8))
            load(sourceId) ?: error("SOURCE_NOT_INSTALLED")
        }.fold(
            { SourcePlatformResult.Success(it) },
            { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.INSTALL_FAILED, it.message ?: "SOURCE_ENABLE_FAILED", cause = it)) },
        )
    }

    fun activate(sourceId: String, version: SemanticVersion): SourcePlatformResult<InstalledSource> = synchronized(lock) {
        runCatching {
            val root = sourceRoot(sourceId)
            val versionDir = child(File(root, "versions"), version.toString())
            require(versionDir.isDirectory) { "SOURCE_VERSION_NOT_INSTALLED" }
            activateInternal(root, version)
            load(sourceId) ?: error("SOURCE_NOT_INSTALLED")
        }.fold(
            { SourcePlatformResult.Success(it) },
            { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.INSTALL_FAILED, it.message ?: "SOURCE_ACTIVATE_FAILED", cause = it)) },
        )
    }

    fun rollback(sourceId: String, traceId: String = UUID.randomUUID().toString()): SourcePlatformResult<InstalledSource> = synchronized(lock) {
        val started = System.currentTimeMillis()
        runCatching {
            val root = sourceRoot(sourceId)
            val active = readActiveVersion(root) ?: error("SOURCE_ACTIVE_VERSION_MISSING")
            val history = File(root, HISTORY_FILE).takeIf(File::isFile)?.readLines().orEmpty()
            val target = history.asReversed()
                .mapNotNull { runCatching { SemanticVersion.parse(it.trim()) }.getOrNull() }
                .firstOrNull { it != active && child(File(root, "versions"), it.toString()).isDirectory }
                ?: listVersions(root).map { it.manifest.version }.filter { it != active }.maxOrNull()
                ?: error("SOURCE_ROLLBACK_UNAVAILABLE")
            activateInternal(root, target)
            load(sourceId) ?: error("SOURCE_NOT_INSTALLED")
        }.fold(
            onSuccess = {
                diagnostics.emit(
                    DiagnosticEvent(
                        System.currentTimeMillis(), traceId, sourceId, it.activeVersion?.toString(),
                        DiagnosticCategory.STORE, "SOURCE_ROLLED_BACK", durationMs = System.currentTimeMillis() - started,
                    ),
                )
                SourcePlatformResult.Success(it)
            },
            onFailure = {
                SourcePlatformResult.Failure(
                    SourcePlatformFailure(SourceErrorCode.ROLLBACK_UNAVAILABLE, it.message ?: "SOURCE_ROLLBACK_UNAVAILABLE", traceId, it),
                )
            },
        )
    }

    fun remove(sourceId: String): Boolean = synchronized(lock) {
        val root = sourceRoot(sourceId)
        root.exists() && root.deleteRecursively()
    }

    fun load(sourceId: String): InstalledSource? = synchronized(lock) {
        val root = sourceRoot(sourceId)
        if (!root.isDirectory) return@synchronized null
        val versions = listVersions(root)
        InstalledSource(
            sourceId = sourceId,
            enabled = File(root, ENABLED_FILE).takeIf(File::isFile)?.readText()?.trim()?.toBooleanStrictOrNull() ?: true,
            activeVersion = readActiveVersion(root),
            versions = versions,
        )
    }

    fun list(): List<InstalledSource> = synchronized(lock) {
        sourcesDir.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { load(it.name) }.sortedBy { it.sourceId }
    }

    /**
     * Reads the installed active artifact irrespective of whether execution is enabled.
     * Runtime callers already filter disabled sources before dispatch. Management operations such
     * as export, inspection and backup must still be able to read a disabled installed artifact.
     */
    fun readActivePack(sourceId: String): VerifiedSourcePack? = synchronized(lock) {
        val installed = load(sourceId) ?: return@synchronized null
        val active = installed.active ?: return@synchronized null
        val entries = linkedMapOf<String, ByteArray>()
        active.directory.walkTopDown().filter(File::isFile).forEach { file ->
            if (file.name == META_FILE) return@forEach
            val relative = active.directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            entries[SourcePackArchiveVerifier.canonicalArchivePath(relative)] = file.readBytes()
        }
        VerifiedSourcePack(
            manifest = active.manifest,
            entries = entries,
            packageSha256 = active.packageSha256,
            signerKeyId = active.signerKeyId,
            signatureAlgorithm = vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm.valueOf(active.signatureAlgorithm),
        )
    }

    private fun writePack(stage: File, pack: VerifiedSourcePack) {
        pack.entries.forEach { (path, bytes) ->
            val target = child(stage, path)
            target.parentFile?.mkdirs()
            atomicWrite(target, bytes)
        }
        val meta = Properties().apply {
            setProperty("packageSha256", pack.packageSha256)
            setProperty("signerKeyId", pack.signerKeyId)
            setProperty("signatureAlgorithm", pack.signatureAlgorithm.name)
            setProperty("payloadTreeSha256", payloadTreeSha256(pack.entries))
            setProperty("installedAtEpochMs", System.currentTimeMillis().toString())
        }
        val bytes = java.io.ByteArrayOutputStream().use { output -> meta.store(output, null); output.toByteArray() }
        atomicWrite(File(stage, META_FILE), bytes)
    }

    private fun listVersions(sourceRoot: File): List<InstalledSourceVersion> {
        val versionsRoot = File(sourceRoot, "versions")
        return versionsRoot.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { dir ->
            runCatching { loadVersion(dir) }.getOrElse { error ->
                diagnostics.emit(
                    DiagnosticEvent(
                        timestampEpochMs = System.currentTimeMillis(),
                        traceId = UUID.randomUUID().toString(),
                        sourceId = sourceRoot.name,
                        sourceVersion = dir.name,
                        category = DiagnosticCategory.STORE,
                        name = "SOURCE_VERSION_REJECTED",
                        severity = DiagnosticSeverity.ERROR,
                        attributes = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
                    ),
                )
                null
            }
        }.sortedByDescending { it.manifest.version }
    }

    private fun loadVersion(directory: File): InstalledSourceVersion {
        val manifest = SourceManifestParser.parse(File(directory, "source.json").readBytes())
        val meta = Properties().apply { File(directory, META_FILE).inputStream().use(::load) }
        val storedEntries = directory.walkTopDown()
            .filter(File::isFile)
            .filterNot { it.name == META_FILE }
            .associate { file ->
                val relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                SourcePackArchiveVerifier.canonicalArchivePath(relative) to file.readBytes()
            }
        val expectedTreeHash = meta.getProperty("payloadTreeSha256") ?: error("SOURCE_META_TREE_HASH_MISSING")
        require(payloadTreeSha256(storedEntries) == expectedTreeHash) { "SOURCE_STORED_PAYLOAD_CORRUPT" }
        return InstalledSourceVersion(
            manifest = manifest,
            directory = directory,
            packageSha256 = meta.getProperty("packageSha256") ?: error("SOURCE_META_HASH_MISSING"),
            signerKeyId = meta.getProperty("signerKeyId") ?: error("SOURCE_META_SIGNER_MISSING"),
            signatureAlgorithm = meta.getProperty("signatureAlgorithm") ?: error("SOURCE_META_ALGORITHM_MISSING"),
            installedAtEpochMs = meta.getProperty("installedAtEpochMs")?.toLongOrNull() ?: 0L,
        )
    }

    private fun payloadTreeSha256(entries: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(MessageDigest.getInstance("SHA-256").digest(bytes))
            digest.update(10.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun activateInternal(sourceRoot: File, version: SemanticVersion) {
        val current = readActiveVersion(sourceRoot)
        if (current != null && current != version) {
            val history = File(sourceRoot, HISTORY_FILE)
            history.appendText("$current\n", Charsets.UTF_8)
            trimHistory(history)
        }
        atomicWrite(File(sourceRoot, ACTIVE_FILE), version.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun trimHistory(file: File) {
        val lines = file.readLines().takeLast(20)
        atomicWrite(file, (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun readActiveVersion(sourceRoot: File): SemanticVersion? = File(sourceRoot, ACTIVE_FILE)
        .takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)?.let { runCatching { SemanticVersion.parse(it) }.getOrNull() }

    private fun ensureEnabledFile(sourceRoot: File) {
        sourceRoot.mkdirs()
        val file = File(sourceRoot, ENABLED_FILE)
        if (!file.exists()) atomicWrite(file, "true".toByteArray(StandardCharsets.UTF_8))
    }

    private fun pruneVersions(sourceRoot: File) {
        val active = readActiveVersion(sourceRoot)
        val versions = listVersions(sourceRoot)
        val keep = buildSet {
            active?.let(::add)
            versions.take(maxRetainedVersions).forEach { add(it.manifest.version) }
        }
        versions.filterNot { it.manifest.version in keep }.forEach { it.directory.deleteRecursively() }
    }

    private fun cleanupAbandonedStaging() {
        stagingDir.listFiles().orEmpty().forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000L) file.deleteRecursively()
        }
    }

    private fun sourceRoot(sourceId: String): File {
        require(SOURCE_ID.matches(sourceId)) { "SOURCE_ID_INVALID" }
        return child(sourcesDir, sourceId)
    }

    private fun child(parent: File, relative: String): File {
        val target = File(parent, relative).canonicalFile
        val prefix = parent.canonicalFile.path + File.separator
        require(target.path.startsWith(prefix)) { "SOURCE_STORE_PATH_ESCAPE" }
        return target
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temp.outputStream().use { output ->
            output.write(bytes)
            output.fdSyncIfPossible()
        }
        atomicMove(temp, target, replace = true)
    }

    private fun atomicMove(source: File, target: File, replace: Boolean = false) {
        target.parentFile?.mkdirs()
        val options = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replace) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        try {
            Files.move(source.toPath(), target.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            Files.move(source.toPath(), target.toPath(), *fallback)
        }
    }

    private fun java.io.OutputStream.fdSyncIfPossible() {
        if (this is java.io.FileOutputStream) fd.sync()
    }

    companion object {
        private val SOURCE_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
    }
}
