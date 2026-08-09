package com.nghetruyen.source.repository

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceEcosystem
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot

enum class VBookRepositoryItemState {
    NOT_INSTALLED,
    CURRENT,
    UPDATE_AVAILABLE,
    REMOTE_OLDER,
    VERSION_UNKNOWN,
}

data class VBookRepositoryUpdateRow(
    val item: VBookAggregatedItem,
    val installed: SourceArtifactDescriptor?,
    val state: VBookRepositoryItemState,
)

data class VBookRepositoryUpdatePlan(
    val rows: List<VBookRepositoryUpdateRow>,
) {
    val updates: List<VBookRepositoryUpdateRow> get() = rows.filter { it.state == VBookRepositoryItemState.UPDATE_AVAILABLE }
    val unknownVersions: List<VBookRepositoryUpdateRow> get() = rows.filter { it.state == VBookRepositoryItemState.VERSION_UNKNOWN }
}

object VBookRepositoryUpdatePlanner {
    fun plan(
        snapshot: VBookRepositorySnapshot,
        installed: List<SourceArtifactDescriptor>,
    ): VBookRepositoryUpdatePlan {
        val active = installed
            .filter { it.identity.ecosystem == SourceEcosystem.VBOOK }
            .associateBy { it.identity.repositoryId to it.identity.remoteIdentity }
        val rows = snapshot.items.map { item ->
            val local = active[item.repositoryId to item.remoteIdentity]
            VBookRepositoryUpdateRow(item, local, state(local?.version, item.item.version?.toString()))
        }
        return VBookRepositoryUpdatePlan(rows)
    }

    private fun state(local: String?, remote: String?): VBookRepositoryItemState {
        if (local == null) return VBookRepositoryItemState.NOT_INSTALLED
        if (remote == null) return VBookRepositoryItemState.VERSION_UNKNOWN
        if (local == remote) return VBookRepositoryItemState.CURRENT
        val localNumber = local.toLongOrNull()
        val remoteNumber = remote.toLongOrNull()
        if (localNumber == null || remoteNumber == null) return VBookRepositoryItemState.VERSION_UNKNOWN
        return when {
            remoteNumber > localNumber -> VBookRepositoryItemState.UPDATE_AVAILABLE
            remoteNumber < localNumber -> VBookRepositoryItemState.REMOTE_OLDER
            else -> VBookRepositoryItemState.CURRENT
        }
    }
}
