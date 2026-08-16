package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.repository.VBookUpdateResult
import com.nghetruyen.source.store.SourceArtifactLifecycle
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot

class VBookPreparedCatalogInstall internal constructor(
    val item: VBookAggregatedItem,
    val preview: VBookInstallPreview,
    val packageSha256: String,
    packageBytes: ByteArray,
) {
    internal val bytes: ByteArray = packageBytes.copyOf()
}


class VBookCatalogInstallService(
    private val repositories: VBookRepositoryClient,
    private val platform: VBookSourcePlatform,
    private val onSourcesChanged: () -> Unit = {},
) {
    fun snapshot(indexUrl: String = VBookRepositoryClient.OFFICIAL_INDEX, strict: Boolean = false): VBookRepositorySnapshot =
        repositories.snapshot(indexUrl, strict)


    fun prepare(item: VBookAggregatedItem): VBookPreparedCatalogInstall {
        val bytes = repositories.downloadPackage(item)
        val packageManifest = VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
        val packageVersion = packageManifest.metadata.version.toString()
        val advertisedVersion = item.item.version.trim()
        require(advertisedVersion.isBlank() || advertisedVersion == packageVersion) {
            "VBOOK_PACKAGE_VERSION_MISMATCH:expected=$advertisedVersion:actual=$packageVersion"
        }
        val preview = platform.preview(
            repositoryId = item.repositoryId,
            remoteIdentity = item.remoteIdentity,
            version = packageVersion,
            packageBytes = bytes,
        )
        return VBookPreparedCatalogInstall(
            item = item,
            preview = preview,
            packageSha256 = SourceArtifactLifecycle.sha256(bytes),
            packageBytes = bytes,
        )
    }

    fun preview(item: VBookAggregatedItem): VBookInstallPreview = prepare(item).preview


    fun installPrepared(
        prepared: VBookPreparedCatalogInstall,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult {
        require(SourceArtifactLifecycle.sha256(prepared.bytes) == prepared.packageSha256) {
            "VBOOK_PREPARED_PACKAGE_MUTATED"
        }
        val result = platform.installOrUpdate(
            repositoryId = prepared.item.repositoryId,
            remoteIdentity = prepared.item.remoteIdentity,
            version = prepared.preview.version,
            packageBytes = prepared.bytes,
            trust = trust,
        )
        onSourcesChanged()
        return result
    }


    fun installOrUpdate(
        item: VBookAggregatedItem,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult = installPrepared(prepare(item), trust)

    fun rollback(item: VBookAggregatedItem): SourceArtifactDescriptor {
        val restored = platform.rollback(item.repositoryId, item.remoteIdentity)
        onSourcesChanged()
        return restored
    }
}
