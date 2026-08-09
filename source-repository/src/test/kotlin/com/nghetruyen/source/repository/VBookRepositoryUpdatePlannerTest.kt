package com.nghetruyen.source.repository

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookCatalog
import vn.nghetruyen.source.vbook.VBookCatalogItem
import vn.nghetruyen.source.vbook.VBookRepositoryCatalog
import vn.nghetruyen.source.vbook.VBookRepositoryDescriptor
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot

class VBookRepositoryUpdatePlannerTest {
    @Test
    fun numericVersionsUseStableIdentityAndUnknownVersionsAreNotGuessed() {
        val repo = VBookRepositoryDescriptor("https://repo.example/a.json", "A", "")
        val currentItem = item(repo, "one", 2)
        val updateItem = item(repo, "two", 3)
        val newItem = item(repo, "three", 1)
        val snapshot = VBookRepositorySnapshot(
            indexUrl = "https://index.example/repository.json",
            indexSha256 = "0".repeat(64),
            repositories = listOf(VBookRepositoryCatalog(repo, VBookCatalog("A", "", listOf(currentItem.item, updateItem.item, newItem.item)))),
            items = listOf(currentItem, updateItem, newItem),
            errors = emptyList(),
        )
        val installed = listOf(
            descriptor(currentItem, "2"),
            descriptor(updateItem, "2"),
        )

        val plan = VBookRepositoryUpdatePlanner.plan(snapshot, installed)
        assertEquals(VBookRepositoryItemState.CURRENT, plan.rows[0].state)
        assertEquals(VBookRepositoryItemState.UPDATE_AVAILABLE, plan.rows[1].state)
        assertEquals(VBookRepositoryItemState.NOT_INSTALLED, plan.rows[2].state)
        assertEquals(1, plan.updates.size)
    }

    private fun item(repo: VBookRepositoryDescriptor, suffix: String, version: Int): VBookAggregatedItem {
        val catalogItem = VBookCatalogItem(
            name = suffix,
            author = "A",
            packageUrl = "https://pkg.example/$suffix/plugin.zip",
            version = version,
            source = "https://site.example/$suffix",
            iconUrl = null,
            description = "",
            type = null,
            locale = null,
            adult = false,
            unknown = emptyMap(),
        )
        val repositoryId = vn.nghetruyen.source.vbook.VBookRepositoryAggregator.repositoryId(repo.link)
        return VBookAggregatedItem(repositoryId, repo, catalogItem)
    }

    private fun descriptor(item: VBookAggregatedItem, version: String) = SourceArtifactDescriptor(
        artifactId = "artifact-${item.item.name}",
        identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, item.repositoryId, item.remoteIdentity),
        version = version,
        sha256 = "1".repeat(64),
        compatibilityProfile = SourceCompatibilityProfile(SourceEcosystem.VBOOK, "current-js"),
        state = SourceArtifactState.ACTIVE,
        trust = SourceTrustState.REPOSITORY_TRUSTED,
        installedAtEpochMs = 1,
        activatedAtEpochMs = 2,
    )
}
