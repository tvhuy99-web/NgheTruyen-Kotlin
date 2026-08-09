package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.repository.VBookUpdateDisposition
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.vbook.VBookHostManifestFactory
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
    private val vBookCatalog = VBookCatalogInstallService(vBookRepositories, vBook)
    private var pendingVBookCatalog: VBookPreparedCatalogInstall? = null

    fun activeStorySources(): List<StorySource> = legacy.activeStorySources() + vBook.activeStorySources()

    fun installedPacks(): List<SourcePackUiInfo> = buildList {
        addAll(legacy.installedPacks())
        addAll(vBook.installedSources().map(::installedUi))
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
                val packageIsHttps = aggregated.item.packageUrl.startsWith("https://", ignoreCase = true)
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
                        if (!packageIsHttps) {
                            if (isNotEmpty()) append(" · ")
                            append("Gói HTTP không an toàn")
                        }
                    },
                    changelog = "",
                    packageBytes = 0,
                    status = if (packageIsHttps) state else "INSECURE_PACKAGE_URL",
                    canInstall = packageIsHttps && state in setOf("NOT_INSTALLED", "UPDATE_AVAILABLE", "VERSION_UNKNOWN"),
                ))
            }
        }
    }.distinctBy { it.repositoryId to it.sourceId }

    /** Try the native signed-repository schema first; fall back to the canonical vBook index schema. */
    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> {
        clearPendingCatalogInstall()
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
        clearPendingCatalogInstall()
        if (vBookSnapshots.remove(repositoryId) != null) return Result.success(Unit)
        return legacy.removeRepository(repositoryId)
    }

    /**
     * Bind repository preview and confirmation to the same exact ZIP and canonical catalog identity.
     * The legacy manager is used only to render the existing permission-preview UI; it never owns
     * confirmation for a catalog vBook package.
     */
    fun prepareRepositoryInstall(repositoryId: String, sourceId: String): Result<SourceInstallPreview> {
        val snapshot = vBookSnapshots[repositoryId] ?: run {
            clearPendingCatalogInstall()
            return legacy.prepareRepositoryInstall(repositoryId, sourceId)
        }
        return runCatching {
            val item = snapshot.items.firstOrNull { it.installIdentity == sourceId }
                ?: error("Không tìm thấy tiện ích vBook trong repository snapshot.")
            require(item.item.packageUrl.startsWith("https://", ignoreCase = true)) {
                "VBOOK_REPOSITORY_PACKAGE_HTTPS_REQUIRED"
            }
            val prepared = vBookCatalog.prepare(item)
            require(prepared.preview.activatable) {
                val blockers = prepared.preview.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
                val failures = prepared.preview.validation.failures.joinToString("; ") { it.message }
                "VBOOK_IMPORT_NOT_ACTIVATABLE:${listOf(blockers, failures).filter(String::isNotBlank).joinToString(" | ")}"
            }
            val presentation = ByteArrayInputStream(prepared.bytes).use { legacy.prepareVBookImport(it).getOrThrow() }
            pendingVBookCatalog = prepared
            val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, item.repositoryId, item.remoteIdentity)
            presentation.copy(
                sourceId = VBookHostManifestFactory.stableSourceId(identity.canonicalKey()),
                name = prepared.preview.name,
                version = prepared.preview.version ?: item.item.version,
            )
        }.onFailure {
            pendingVBookCatalog = null
            legacy.cancelPendingInstall()
        }
    }

    fun prepareInstall(input: InputStream): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return legacy.prepareInstall(input)
    }

    fun prepareVBookImport(input: InputStream): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return legacy.prepareVBookImport(input)
    }

    fun prepareNativeLuaImport(input: InputStream): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return legacy.prepareNativeLuaImport(input)
    }

    fun pendingInstallWarnings(): List<String> = buildList {
        addAll(legacy.pendingInstallWarnings())
        pendingVBookCatalog?.preview?.warnings?.let(::addAll)
    }.distinct()

    fun confirmPendingInstall(): Result<SourcePackUiInfo> {
        val prepared = pendingVBookCatalog ?: return legacy.confirmPendingInstall()
        pendingVBookCatalog = null
        legacy.cancelPendingInstall()
        return runCatching {
            val result = vBookCatalog.installPrepared(prepared)
            require(result.disposition == VBookUpdateDisposition.ACTIVATED && result.active != null) {
                val blockers = result.validation.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
                "VBOOK_INSTALL_QUARANTINED:${blockers.ifBlank { result.validation.failures.joinToString("; ") { it.message } }}"
            }
            onExternalSourcesChanged()
            val info = vBook.installedSources().firstOrNull {
                it.repositoryId == prepared.item.repositoryId && it.remoteIdentity == prepared.item.remoteIdentity
            } ?: error("VBOOK_INSTALLED_SOURCE_NOT_FOUND_AFTER_ACTIVATION")
            installedUi(info)
        }
    }

    fun cancelPendingInstall() {
        pendingVBookCatalog = null
        legacy.cancelPendingInstall()
    }

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

    private fun installedUi(installed: VBookInstalledSourceInfo): SourcePackUiInfo = SourcePackUiInfo(
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

    private fun clearPendingCatalogInstall() {
        pendingVBookCatalog = null
        legacy.cancelPendingInstall()
    }

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
