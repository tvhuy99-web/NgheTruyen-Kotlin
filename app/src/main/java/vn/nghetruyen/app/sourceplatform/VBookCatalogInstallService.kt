package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.repository.VBookUpdateResult
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot

/** One transaction boundary for repository-driven vBook install/update/rollback. */
class VBookCatalogInstallService(
    private val repositories: VBookRepositoryClient,
    private val platform: VBookSourcePlatform,
    private val onSourcesChanged: () -> Unit = {},
) {
    fun snapshot(indexUrl: String = VBookRepositoryClient.OFFICIAL_INDEX, strict: Boolean = false): VBookRepositorySnapshot =
        repositories.snapshot(indexUrl, strict)

    fun preview(item: VBookAggregatedItem): VBookInstallPreview {
        val bytes = repositories.downloadPackage(item)
        return platform.preview(
            repositoryId = item.repositoryId,
            remoteIdentity = item.remoteIdentity,
            version = item.item.version?.toString(),
            packageBytes = bytes,
        )
    }

    fun installOrUpdate(
        item: VBookAggregatedItem,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult {
        val bytes = repositories.downloadPackage(item)
        val preview = platform.preview(
            repositoryId = item.repositoryId,
            remoteIdentity = item.remoteIdentity,
            version = item.item.version?.toString(),
            packageBytes = bytes,
        )
        if (!preview.activatable) {
            // The coordinator still archives/quarantines exact bytes so the failure remains auditable.
            val result = platform.installOrUpdate(
                repositoryId = item.repositoryId,
                remoteIdentity = item.remoteIdentity,
                version = item.item.version?.toString(),
                packageBytes = bytes,
                trust = trust,
            )
            onSourcesChanged()
            return result
        }
        val result = platform.installOrUpdate(
            repositoryId = item.repositoryId,
            remoteIdentity = item.remoteIdentity,
            version = item.item.version?.toString(),
            packageBytes = bytes,
            trust = trust,
        )
        onSourcesChanged()
        return result
    }

    fun rollback(item: VBookAggregatedItem): SourceArtifactDescriptor {
        val restored = platform.rollback(item.repositoryId, item.remoteIdentity)
        onSourcesChanged()
        return restored
    }
}
