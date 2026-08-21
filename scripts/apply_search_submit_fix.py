from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


explore = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt")
replace_once(
    explore,
    """    onQueryChange: (String) -> Unit,\n    onSearch: () -> Unit,\n    onSearchAllSourcesChange: (Boolean) -> Unit,\n    onSortModeChange: (SearchSortMode) -> Unit,\n""",
    """    onSearch: (String, Boolean, SearchSortMode) -> Unit,\n""",
)
replace_once(
    explore,
    """                        rememberSearch(searchQueryDraft)\n                        onQueryChange(searchQueryDraft)\n                        onSortModeChange(searchSortDraft)\n                        onSearchAllSourcesChange(searchAllDraft)\n                        searchDialogOpen = false\n                        onSearch()\n""",
    """                        rememberSearch(searchQueryDraft)\n                        val submittedQuery = searchQueryDraft.trim()\n                        val submittedAllSources = searchAllDraft\n                        val submittedSortMode = searchSortDraft\n                        searchDialogOpen = false\n                        onSearch(submittedQuery, submittedAllSources, submittedSortMode)\n""",
)

app = Path("app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt")
replace_once(
    app,
    """                        onQueryChange = viewModel::updateQuery,\n                        onSearch = { viewModel.search() },\n                        onSearchAllSourcesChange = viewModel::setSearchAllSources,\n                        onSortModeChange = viewModel::setSearchSortMode,\n""",
    """                        onSearch = viewModel::submitSearch,\n""",
)

vm = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
replace_once(
    vm,
    """    fun search(query: String = state.value.query) {\n""",
    """    fun submitSearch(\n        query: String,\n        searchAllSources: Boolean,\n        sortMode: SearchSortMode,\n    ) {\n        val normalized = query.take(240).trim()\n        suggestionJob?.cancel()\n        suggestionJob = null\n        mutableState.update { current ->\n            current.copy(\n                query = normalized,\n                searchAllSources = searchAllSources,\n                searchSortMode = sortMode,\n                sourceSuggestions = emptyList(),\n                stories = emptyList(),\n                genreEntries = emptyList(),\n                explorePage = 1,\n                exploreMode = if (searchAllSources || normalized.isNotBlank()) ExploreMode.SEARCH else ExploreMode.HOME,\n                activeCategory = null,\n                activeCategoryLabel = null,\n                canLoadMoreStories = false,\n                searchedSourceCount = 0,\n                totalSearchSourceCount = 0,\n                message = null,\n            )\n        }\n        search(normalized)\n    }\n\n    fun search(query: String = state.value.query) {\n""",
)

print("Applied atomic search-submit fix")
