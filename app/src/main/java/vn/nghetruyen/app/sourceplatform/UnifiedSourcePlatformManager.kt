package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * UI-facing facade that preserves the existing source-management surface while routing ownership
 * to the correct ecosystem. vBook artifacts never flow back through SourcePackStore.
 */
class UnifiedSourcePlatformManager(
    private val legacy: SourcePlatformManager,
    private val vBook: VBookSourcePlatform,
    private val vBookRepositories: VBookRepositoryClient,
    private val onExternalSourcesChanged: () -> Unit,
) {
    private val vBookSnapshots = linkedMapOf<String, VBookRepositorySnapshot>()

    fun activeStorySources(): List<StorySource> = legacy.activeStorySources() + vBook.activeStorySources()

    fun installedPacks(): List<SourcePackUiInfo> = buildList {
        addAll(legacy.installedPacks())
        addAll(vBook.installedSources().map { installed ->
            SourcePackUiInfo(
                id = installed.sourceId,
                name = installed.name,
                version = installed.version,
                enabled = installed.enabled,
                installedVersions = installed.installedVersions,
                canRollback = installed.canRollback,
                signerKeyId = "vbook-unmodified",
                runtimeMode = SourceRuntimeMode.VBOOK_JS_COMPAT.name,
                commentCapability = "VBOOK_DYNAMIC",
                commentFixtureCount = 0,
                removable = true,
            )
        })
    }.distinctBy(SourcePackUiInfo::id)

    fun repositories(): List<SourceRepositoryUiInfo> = buildList {
        addAll(legacy.repositories())
        addAll(vBookSnapshots.map { (uiId, snapshot) ->
            SourceRepositoryUiInfo(
                id = uiId,
                name = "vBook · ${snapshot.repositories.size} catalog",
                url = snapshot.indexUrl,
                generatedAtEpochMs = 0L,
                expiresAtEpochMs = 0L,
                packageCount = snapshot.items.size,
                signerKeyId = if (snapshot.complete) "vbook-index-hash" else "vbook-index-partial",
            )
        })
    }.distinctBy(SourceRepositoryUiInfo::id)

    fun repositoryPackages(): List<SourceRepositoryPackageUiInfo> = buildList {
        addAll(legacy.repositoryPackages())
        val installed = vBook.installedSources().associateBy { it.repositoryId to it.remoteIdentity }
        vBookSnapshots.forEach { (uiId, snapshot) ->
            snapshot.items.forEach { aggregated ->
                val local = installed[aggregated.repositoryId to aggregated.remoteIdentity]
                val state = repositoryState(local?.version, aggregated.item.version)
                add(SourceRepositoryPackageUiInfo(
                    repositoryId = uiId,
                    sourceId = aggregated.installIdentity,
                    name = aggregated.item.name.ifBlank { aggregated.remoteIdentity },
                    version = aggregated.item.version,
                    installedVersion = local?.version,
                    description = buildString {
                        if (aggregated.item.description.isNotBlank()) append(aggregated.item.description.trim())
                        if (aggregated.repository.author.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("Catalog: ").append(aggregated.repository.author)
                        }
                    },
                    changelog = "",
                    packageBytes = 0,
                    status = state,
                    canInstall = state in setOf("NOT_INSTALLED", "UPDATE_AVAILABLE", "VERSION_UNKNOWN"),
                ))
            }
        }
    }.distinctBy { it.repositoryId to it.sourceId }

    /** Try the native signed-repository schema first; fall back to the canonical vBook index schema. */
    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> {
        val native = legacy.refreshRepository(url)
        if (native.isSuccess) return native
        return runCatching {
            val snapshot = vBookRepositories.snapshot(url, strict = false)
            require(snapshot.repositories.isNotEmpty()) {
                "vBook repository không có catalog hợp lệ: ${snapshot.errors.joinToString { it.code }}"
            }
            val uiId = vBookIndexUiId(snapshot.indexUrl)
            vBookSnapshots[uiId] = snapshot
            repositories().first { it.id == uiId }
        }.recoverCatching { vBookError ->
            val nativeMessage = native.exceptionOrNull()?.message.orEmpty()
            error(listOf(nativeMessage, vBookError.message.orEmpty()).filter(String::isNotBlank).joinToString(" | "))
        }
    }

    fun removeRepository(repositoryId: String): Result<Unit> {
        if (vBookSnapshots.remove(repositoryId) != null) return Result.success(Unit)
        return legacy.removeRepository(repositoryId)
    }

    /**
     * For vBook rows, download exactly once then reuse the local raw-import preview path. The legacy
     * manager only supplies the existing UI preview surface; confirmation still commits raw bytes to
     * VBookSourcePlatform rather than SourcePackStore.
     */
    fun prepareRepositoryInstall(repositoryId: String, sourceId: String): Result<SourceInstallPreview> {
        val snapshot = vBookSnapshots[repositoryId] ?: return legacy.prepareRepositoryInstall(repositoryId, sourceId)
        return runCatching {
            val item = snapshot.items.firstOrNull { it.installIdentity == sourceId }
                ?: error("Không tìm thấy tiện ích vBook trong repository snapshot.")
            val bytes = vBookRepositories.downloadPackage(item)
            ByteArrayInputStream(bytes).use { legacy.prepareVBookImport(it).getOrThrow() }
        }
    }

    fun prepareInstall(input: InputStream) = legacy.prepareInstall(input)
    fun prepareVBookImport(input: InputStream) = legacy.prepareVBookImport(input)
    fun prepareNativeLuaImport(input: InputStream) = legacy.prepareNativeLuaImport(input)
    fun pendingInstallWarnings() = legacy.pendingInstallWarnings()
    fun confirmPendingInstall() = legacy.confirmPendingInstall()
    fun cancelPendingInstall() = legacy.cancelPendingInstall()

    fun trustKeys() = legacy.trustKeys()
    fun enrollTrustKey(keyId: String, algorithm: String, publicKeyBase64: String, fingerprint: String) =
        legacy.enrollTrustKey(keyId, algorithm, publicKeyBase64, fingerprint)
    fun revokeTrustKey(keyId: String) = legacy.revokeTrustKey(keyId)
    fun applyTrustKeyRotation(raw: ByteArray) = legacy.applyTrustKeyRotation(raw)

    fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> {
        val vBookInstalled = vBook.installedSources().any { it.sourceId == sourceId }
        if (!vBookInstalled) return legacy.setEnabled(sourceId, enabled)
        return runCatching {
            vBook.setEnabled(sourceId, enabled)
            onExternalSourcesChanged()
        }
    }

    fun rollback(sourceId: String): Result<Unit> {
        val vBookInstalled = vBook.installedSources().any { it.sourceId == sourceId }
        if (!vBookInstalled) return legacy.rollback(sourceId)
        return runCatching {
            vBook.rollbackBySourceId(sourceId)
            onExternalSourcesChanged()
        }
    }

    fun removeInstalledPack(sourceId: String): Result<Unit> {
        val vBookInstalled = vBook.installedSources().any { it.sourceId == sourceId }
        if (!vBookInstalled) return legacy.removeInstalledPack(sourceId)
        return runCatching {
            require(vBook.uninstallBySourceId(sourceId)) { "Không tìm thấy tiện ích vBook để xóa." }
            onExternalSourcesChanged()
        }
    }

    /** vBook export is the exact immutable ZIP originally staged, not a reconstructed archive. */
    fun exportInstalledPack(sourceId: String, output: OutputStream): Result<Unit> {
        val installed = vBook.installedSources().firstOrNull { it.sourceId == sourceId }
            ?: return legacy.exportInstalledPack(sourceId, output)
        return runCatching {
            val bytes = vBook.originalPackageBytes(installed.artifactId)
                ?: error("Không tìm thấy ZIP vBook gốc trong archive.")
            output.write(bytes)
            output.flush()
        }
    }

    fun inspectSelector(html: String, selector: String, baseUrl: String) = legacy.inspectSelector(html, selector, baseUrl)
    fun diagnosticsSnapshot(sourceId: String? = null) = legacy.diagnosticsSnapshot(sourceId)
    fun diagnosticTraces(limit: Int = 20) = legacy.diagnosticTraces(limit)
    fun diagnosticSummaries(limit: Int = 20) = legacy.diagnosticSummaries(limit)
    fun exportDiagnostics() = legacy.exportDiagnostics()
    fun clearDiagnostics() = legacy.clearDiagnostics()

    private fun repositoryState(local: String?, remote: String): String {
        if (local == null) return "NOT_INSTALLED"
        if (local == remote) return "CURRENT"
        val localNumber = local.toLongOrNull()
        val remoteNumber = remote.toLongOrNull()
        if (localNumber == null || remoteNumber == null) return "VERSION_UNKNOWN"
        return when {
            remoteNumber > localNumber -> "UPDATE_AVAILABLE"
            remoteNumber < localNumber -> "REMOTE_OLDER"
            else -> "CURRENT"
        }
    }

    private fun vBookIndexUiId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "vbook.index.$digest"
    }
}
