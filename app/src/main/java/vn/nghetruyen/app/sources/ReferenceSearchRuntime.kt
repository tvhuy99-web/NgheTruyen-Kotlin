package vn.nghetruyen.app.sources





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
