package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceRuntimeMode
import java.io.InputStream
import java.io.OutputStream

/**
 * UI-facing facade that preserves the existing source-management surface while routing ownership
 * to the correct ecosystem. vBook artifacts never flow back through SourcePackStore.
 */
class UnifiedSourcePlatformManager(
    private val legacy: SourcePlatformManager,
    private val vBook: VBookSourcePlatform,
    private val onExternalSourcesChanged: () -> Unit,
) {
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

    fun repositories() = legacy.repositories()
    fun repositoryPackages() = legacy.repositoryPackages()
    fun refreshRepository(url: String) = legacy.refreshRepository(url)
    fun removeRepository(repositoryId: String) = legacy.removeRepository(repositoryId)
    fun prepareRepositoryInstall(repositoryId: String, sourceId: String) = legacy.prepareRepositoryInstall(repositoryId, sourceId)

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
}
