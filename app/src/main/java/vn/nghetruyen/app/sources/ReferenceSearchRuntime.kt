package vn.nghetruyen.app.sources

/**
 * Process-local search options mirroring the XPK search dialog.
 * The dialog updates these values immediately before invoking AppViewModel.search().
 */
object ReferenceSearchRuntime {
    @Volatile
    var selectedSourceIds: Set<String> = emptySet()

    @Volatile
    var groupDuplicates: Boolean = true

    fun accepts(sourceId: String): Boolean {
        val selected = selectedSourceIds
        return selected.isEmpty() || sourceId in selected
    }
}
