from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text()

def write(path, text):
    (ROOT / path).write_text(text)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)

def regex_once(text, pattern, repl, label):
    result, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 regex match, found {count}")
    return result

# ---------------------------------------------------------------------------
# StorySearch: the reference search can optionally keep duplicate titles.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt"
text = read(path)
text = replace_once(
    text,
    """        sortMode: SearchSortMode = SearchSortMode.RELEVANCE,\n    ): List<StorySummary> {\n        val normalizedQuery = normalize(query)\n        val selected = results\n            .groupBy(::dedupeKey)\n            .values\n            .map { duplicates ->\n                duplicates.maxWithOrNull(\n                    compareBy<StorySummary> { score(it, normalizedQuery, healthBySource[it.sourceId]) }\n                        .thenByDescending { it.description.length }\n                        .thenByDescending { it.url.length }\n                        .thenBy { it.sourceId },\n                ) ?: duplicates.first()\n            }\n""",
    """        sortMode: SearchSortMode = SearchSortMode.RELEVANCE,\n        groupDuplicates: Boolean = true,\n    ): List<StorySummary> {\n        val normalizedQuery = normalize(query)\n        val selected = if (groupDuplicates) {\n            results\n                .groupBy(::dedupeKey)\n                .values\n                .map { duplicates ->\n                    duplicates.maxWithOrNull(\n                        compareBy<StorySummary> { score(it, normalizedQuery, healthBySource[it.sourceId]) }\n                            .thenByDescending { it.description.length }\n                            .thenByDescending { it.url.length }\n                            .thenBy { it.sourceId },\n                    ) ?: duplicates.first()\n                }\n        } else {\n            results.distinctBy { story -> story.url.ifBlank { \"${story.sourceId}:${story.id}\" } }\n        }\n""",
    "StorySearch group duplicates",
)
write(path, text)

# ---------------------------------------------------------------------------
# AppViewModel: persistent reference-search state and selected-source filtering.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
text = replace_once(
    text,
    """    val searchAllSources: Boolean = false,\n    val searchSortMode: SearchSortMode = SearchSortMode.RELEVANCE,\n    val searchedSourceCount: Int = 0,\n""",
    """    val searchAllSources: Boolean = false,\n    val searchSortMode: SearchSortMode = SearchSortMode.RELEVANCE,\n    val searchSelectedSourceIds: Set<String> = emptySet(),\n    val searchGroupDuplicates: Boolean = true,\n    val searchHistory: List<String> = emptyList(),\n    val searchedSourceCount: Int = 0,\n""",
    "MainUiState reference search fields",
)
text = replace_once(
    text,
    """    private val app = application as NgheTruyenApplication\n    private val container = app.container\n    private val mutableState = MutableStateFlow(\n        MainUiState(\n            sources = container.sourceRegistry.descriptors(),\n""",
    """    private val app = application as NgheTruyenApplication\n    private val container = app.container\n    private val referenceUiPrefs = app.getSharedPreferences(\"reference_ui_state\", 0)\n    private val mutableState = MutableStateFlow(\n        MainUiState(\n            sources = container.sourceRegistry.descriptors(),\n            searchSelectedSourceIds = referenceUiPrefs.getStringSet(\"global_search_sources\", emptySet()).orEmpty().toSet(),\n            searchGroupDuplicates = referenceUiPrefs.getBoolean(\"global_search_group_duplicates\", true),\n            searchHistory = referenceUiPrefs.getString(\"global_search_history\", \"\").orEmpty()\n                .split('\\n').map(String::trim).filter(String::isNotBlank).take(30),\n""",
    "AppViewModel reference prefs",
)
text = replace_once(
    text,
    """    fun setSearchAllSources(enabled: Boolean) {\n        mutableState.update {\n            it.copy(\n                searchAllSources = enabled,\n                activeCategory = null,\n                stories = emptyList(),\n                explorePage = 1,\n                exploreMode = if (enabled) ExploreMode.SEARCH else ExploreMode.HOME,\n                canLoadMoreStories = false,\n                sourceSuggestions = emptyList(),\n                searchedSourceCount = 0,\n                totalSearchSourceCount = 0,\n            )\n        }\n        if (enabled) {\n            if (state.value.query.isNotBlank()) search()\n        } else {\n            search(\"\")\n        }\n    }\n\n    fun setSearchSortMode(mode: SearchSortMode) {\n""",
    """    fun setSearchAllSources(enabled: Boolean) {\n        mutableState.update {\n            it.copy(\n                searchAllSources = enabled,\n                activeCategory = null,\n                explorePage = 1,\n                exploreMode = if (enabled) ExploreMode.SEARCH else it.exploreMode,\n                canLoadMoreStories = false,\n                sourceSuggestions = emptyList(),\n                searchedSourceCount = 0,\n                totalSearchSourceCount = 0,\n            )\n        }\n    }\n\n    fun setSearchSelectedSources(sourceIds: Set<String>) {\n        val allowed = container.sourceRegistry.searchableSources().mapTo(linkedSetOf()) { it.descriptor.id }\n        val selected = sourceIds.filterTo(linkedSetOf()) { it in allowed }\n        referenceUiPrefs.edit().putStringSet(\"global_search_sources\", selected).apply()\n        mutableState.update { it.copy(searchSelectedSourceIds = selected) }\n    }\n\n    fun setSearchGroupDuplicates(enabled: Boolean) {\n        referenceUiPrefs.edit().putBoolean(\"global_search_group_duplicates\", enabled).apply()\n        mutableState.update { it.copy(searchGroupDuplicates = enabled) }\n    }\n\n    fun clearSearchHistory() {\n        referenceUiPrefs.edit().remove(\"global_search_history\").apply()\n        mutableState.update { it.copy(searchHistory = emptyList()) }\n    }\n\n    private fun rememberSearchQuery(query: String) {\n        val clean = query.trim()\n        if (clean.isBlank()) return\n        val next = (listOf(clean) + state.value.searchHistory.filterNot { it.equals(clean, ignoreCase = true) }).take(30)\n        referenceUiPrefs.edit().putString(\"global_search_history\", next.joinToString(\"\\n\")).apply()\n        mutableState.update { it.copy(searchHistory = next) }\n    }\n\n    private fun selectedGlobalSearchSources() = container.sourceRegistry.searchableSources().let { all ->\n        val requested = state.value.searchSelectedSourceIds\n        if (requested.isEmpty()) all else all.filter { it.descriptor.id in requested }\n    }\n\n    fun setSearchSortMode(mode: SearchSortMode) {\n""",
    "search state setters",
)
text = replace_once(
    text,
    """                    StorySearch.merge(current.stories, health, current.query, mode),\n""",
    """                    StorySearch.merge(\n                        current.stories, health, current.query, mode,\n                        groupDuplicates = current.searchGroupDuplicates,\n                    ),\n""",
    "sort current results group flag",
)
text = replace_once(
    text,
    """            if (snapshot.searchAllSources && cleanQuery.isBlank()) {\n                mutableState.update {\n                    it.copy(\n                        loading = false,\n                        query = query,\n                        sourceSuggestions = emptyList(),\n                        stories = emptyList(),\n                        message = \"Hãy nhập tên truyện hoặc tác giả để tìm trên tất cả nguồn.\",\n                    )\n                }\n                return@launch\n            }\n""",
    """            if (snapshot.searchAllSources && cleanQuery.isBlank()) {\n                mutableState.update {\n                    it.copy(\n                        loading = false,\n                        query = query,\n                        sourceSuggestions = emptyList(),\n                        stories = emptyList(),\n                        message = \"Hãy nhập tên truyện.\",\n                    )\n                }\n                return@launch\n            }\n            if (snapshot.searchAllSources && selectedGlobalSearchSources().isEmpty()) {\n                mutableState.update { it.copy(loading = false, message = \"Hãy chọn ít nhất một nguồn để tìm kiếm.\") }\n                return@launch\n            }\n            if (cleanQuery.isNotBlank()) rememberSearchQuery(cleanQuery)\n""",
    "search validation and history",
)
text = replace_once(
    text,
    """                    totalSearchSourceCount = if (snapshot.searchAllSources) container.sourceRegistry.searchableSources().size else 1,\n""",
    """                    totalSearchSourceCount = if (snapshot.searchAllSources) selectedGlobalSearchSources().size else 1,\n""",
    "search selected count",
)
text = replace_once(
    text,
    """    private suspend fun searchAcrossSources(query: String, page: Int, append: Boolean) = supervisorScope {\n        val sources = container.sourceRegistry.searchableSources()\n""",
    """    private suspend fun searchAcrossSources(query: String, page: Int, append: Boolean) = supervisorScope {\n        val sources = selectedGlobalSearchSources()\n""",
    "search selected sources",
)
text = replace_once(
    text,
    """        val merged = StorySearch.merge(previous + successes, health, query, state.value.searchSortMode)\n""",
    """        val merged = StorySearch.merge(\n            previous + successes, health, query, state.value.searchSortMode,\n            groupDuplicates = state.value.searchGroupDuplicates,\n        )\n""",
    "global search group duplicates",
)
text = replace_once(
    text,
    """            sortMode = sortMode,\n        )\n""",
    """            sortMode = sortMode,\n            groupDuplicates = state.value.searchGroupDuplicates,\n        )\n""",
    "mergeExploreStories group duplicates",
)
write(path, text)

# ---------------------------------------------------------------------------
# ExploreScreen: exact reference hierarchy for the TÌM KIẾM dialog.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt"
text = read(path)
text = replace_once(text, "import androidx.compose.foundation.background\n", "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n", "Explore clickable import")
text = replace_once(text, "import androidx.compose.material3.AlertDialog\n", "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Checkbox\nimport androidx.compose.material3.RadioButton\n", "Explore search control imports")
text = replace_once(
    text,
    """    onSearchAllSourcesChange: (Boolean) -> Unit,\n    onSortModeChange: (SearchSortMode) -> Unit,\n    onCancelSearch: () -> Unit,\n""",
    """    onSearchAllSourcesChange: (Boolean) -> Unit,\n    onSortModeChange: (SearchSortMode) -> Unit,\n    onSearchSelectedSourcesChange: (Set<String>) -> Unit,\n    onSearchGroupDuplicatesChange: (Boolean) -> Unit,\n    onClearSearchHistory: () -> Unit,\n    onCancelSearch: () -> Unit,\n""",
    "Explore callback signature",
)
text = replace_once(
    text,
    """    var sourceMenuOpen by remember { mutableStateOf(false) }\n    var searchDialogOpen by remember { mutableStateOf(false) }\n""",
    """    var sourceMenuOpen by remember { mutableStateOf(false) }\n    var searchDialogOpen by remember { mutableStateOf(false) }\n    var searchHistoryOpen by remember { mutableStateOf(false) }\n    var searchSourcesOpen by remember { mutableStateOf(false) }\n    var searchQueryDraft by remember { mutableStateOf(\"\") }\n    var searchAllDraft by remember { mutableStateOf(false) }\n    var searchSortDraft by remember { mutableStateOf(SearchSortMode.RELEVANCE) }\n    var searchGroupDraft by remember { mutableStateOf(true) }\n    var searchSourceDraft by remember { mutableStateOf<Set<String>>(emptySet()) }\n""",
    "Explore search local state",
)
text = replace_once(
    text,
    """            onClick = { searchDialogOpen = true },\n""",
    """            onClick = {\n                val available = state.sources.filter { it.health != SourceHealth.DISABLED && it.health != SourceHealth.NOT_PORTED }\n                searchQueryDraft = state.query\n                searchAllDraft = false\n                searchSortDraft = state.searchSortMode.takeIf { it != SearchSortMode.AUTHOR } ?: SearchSortMode.RELEVANCE\n                searchGroupDraft = state.searchGroupDuplicates\n                searchSourceDraft = state.searchSelectedSourceIds.ifEmpty { available.mapTo(linkedSetOf()) { it.id } }\n                searchDialogOpen = true\n            },\n""",
    "Explore open search initializes drafts",
)
new_search = r'''
    if (searchDialogOpen) {
        val availableSources = state.sources.filter {
            it.health != SourceHealth.DISABLED && it.health != SourceHealth.NOT_PORTED
        }
        AlertDialog(
            onDismissRequest = { searchDialogOpen = false },
            title = { Text("TÌM KIẾM") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQueryDraft,
                        onValueChange = { searchQueryDraft = it.take(240) },
                        placeholder = { Text("Nhập tên truyện hoặc dán link...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ReferenceActionButton(
                        text = "LỊCH SỬ TÌM KIẾM",
                        onClick = { searchHistoryOpen = true },
                        normalColor = ReferenceDivider,
                        normalContentColor = ReferenceText,
                        minHeight = 52.dp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { searchAllDraft = false },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = !searchAllDraft, onClick = { searchAllDraft = false })
                        Text("Tìm trong nguồn hiện tại")
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { searchAllDraft = true },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = searchAllDraft, onClick = { searchAllDraft = true })
                        Text("Tìm đa nguồn")
                    }
                    if (searchAllDraft) {
                        ReferenceActionButton(
                            text = "CHỌN NGUỒN (${searchSourceDraft.size} / ${availableSources.size})",
                            onClick = { searchSourcesOpen = true },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            minHeight = 52.dp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Text("Sắp xếp kết quả", modifier = Modifier.padding(top = 8.dp))
                        Box(Modifier.fillMaxWidth()) {
                            var sortMenu by remember { mutableStateOf(false) }
                            Button(onClick = { sortMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    when (searchSortDraft) {
                                        SearchSortMode.TITLE -> "Tên truyện A-Z"
                                        SearchSortMode.SOURCE -> "Theo nguồn"
                                        else -> "Độ phù hợp"
                                    },
                                )
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(
                                    SearchSortMode.RELEVANCE to "Độ phù hợp",
                                    SearchSortMode.TITLE to "Tên truyện A-Z",
                                    SearchSortMode.SOURCE to "Theo nguồn",
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { searchSortDraft = mode; sortMenu = false },
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { searchGroupDraft = !searchGroupDraft },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = searchGroupDraft, onCheckedChange = { searchGroupDraft = it })
                            Text("Gom truyện trùng tên")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (searchAllDraft && searchSourceDraft.isEmpty()) return@TextButton
                    onQueryChange(searchQueryDraft)
                    onSearchAllSourcesChange(searchAllDraft)
                    onSortModeChange(searchSortDraft)
                    onSearchGroupDuplicatesChange(searchGroupDraft)
                    if (searchAllDraft) onSearchSelectedSourcesChange(searchSourceDraft)
                    searchDialogOpen = false
                    onSearch()
                }) { Text("TÌM") }
            },
            dismissButton = { TextButton(onClick = { searchDialogOpen = false }) { Text("HỦY") } },
        )
    }

    if (searchHistoryOpen) {
        AlertDialog(
            onDismissRequest = { searchHistoryOpen = false },
            title = { Text("LỊCH SỬ TÌM KIẾM") },
            text = {
                Column {
                    if (state.searchHistory.isEmpty()) {
                        Text("Chưa có lịch sử tìm kiếm.")
                    } else {
                        state.searchHistory.forEach { query ->
                            TextButton(
                                onClick = { searchQueryDraft = query; searchHistoryOpen = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(query) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { searchHistoryOpen = false }) { Text("ĐÓNG") } },
            dismissButton = {
                TextButton(onClick = { onClearSearchHistory(); searchHistoryOpen = false }) { Text("XÓA LỊCH SỬ") }
            },
        )
    }

    if (searchSourcesOpen) {
        val availableSources = state.sources.filter {
            it.health != SourceHealth.DISABLED && it.health != SourceHealth.NOT_PORTED
        }
        var pendingSources by remember(searchSourcesOpen) { mutableStateOf(searchSourceDraft) }
        AlertDialog(
            onDismissRequest = { searchSourcesOpen = false },
            title = { Text("CHỌN NGUỒN TÌM KIẾM") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    availableSources.forEach { source ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pendingSources = if (source.id in pendingSources) pendingSources - source.id else pendingSources + source.id
                            },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = source.id in pendingSources,
                                onCheckedChange = { checked ->
                                    pendingSources = if (checked) pendingSources + source.id else pendingSources - source.id
                                },
                            )
                            Text(source.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    searchSourceDraft = pendingSources
                    onSearchSelectedSourcesChange(pendingSources)
                    searchSourcesOpen = false
                }) { Text("ÁP DỤNG") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val all = availableSources.mapTo(linkedSetOf()) { it.id }
                        searchSourceDraft = all
                        onSearchSelectedSourcesChange(all)
                        searchSourcesOpen = false
                    }) { Text("CHỌN TẤT CẢ") }
                    TextButton(onClick = { searchSourcesOpen = false }) { Text("HỦY") }
                }
            },
        )
    }
'''
text = regex_once(
    text,
    r"\n    if \(searchDialogOpen\) \{.*?\n    \}\n\n\}",
    "\n" + new_search + "\n}",
    "replace Explore reference search dialog",
)
# verticalScroll is now used by source selection.
text = replace_once(text, "import androidx.compose.foundation.rememberScrollState\n", "import androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n", "Explore verticalScroll import")
text = replace_once(text, "import androidx.compose.foundation.layout.fillMaxWidth\n", "import androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.heightIn\n", "Explore heightIn import")
text = replace_once(text, "import androidx.compose.material3.MaterialTheme\n", "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Button\nimport androidx.compose.material3.Box\n", "Explore Button Box imports")
write(path, text)

# ---------------------------------------------------------------------------
# NgheTruyenApp: wire the new search callbacks.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"
text = read(path)
text = replace_once(
    text,
    """                        onSearchAllSourcesChange = viewModel::setSearchAllSources,\n                        onSortModeChange = viewModel::setSearchSortMode,\n                        onCancelSearch = viewModel::cancelSearch,\n""",
    """                        onSearchAllSourcesChange = viewModel::setSearchAllSources,\n                        onSortModeChange = viewModel::setSearchSortMode,\n                        onSearchSelectedSourcesChange = viewModel::setSearchSelectedSources,\n                        onSearchGroupDuplicatesChange = viewModel::setSearchGroupDuplicates,\n                        onClearSearchHistory = viewModel::clearSearchHistory,\n                        onCancelSearch = viewModel::cancelSearch,\n""",
    "wire reference search callbacks",
)
write(path, text)

# ---------------------------------------------------------------------------
# LibraryScreen: exact top actions/search/sort hierarchy for the four shelves.
# Keep existing rich row rendering behind those reference controls.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt"
text = read(path)
text = replace_once(text, "import androidx.compose.foundation.layout.Column\n", "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.heightIn\n", "Library height import")
text = replace_once(text, "import androidx.compose.foundation.lazy.items\n", "import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n", "Library scroll imports")
old_block = r'''        if (state.librarySection == LibrarySection.DOWNLOADED) {
            ReferenceActionButton(
                text = "NHẬP TỆP",
                onClick = onImportFile,
                accessibilityLabel = "Nhập tệp truyện từ thiết bị để đọc",
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            )
        }

        when (state.librarySection) {
            LibrarySection.READING -> StoryEntityList(state.readingStories, onStoryClick, "Chưa có truyện đang đọc.")
            LibrarySection.DOWNLOADED -> DownloadedSection(
                stories = state.downloadedStories,
                jobs = state.downloads,
                failures = state.downloadFailures,
                storage = state.offlineStorage,
                onStoryClick = onStoryClick,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onRetryDownload = onRetryDownload,
                onRetryFailedChapter = onRetryFailedChapter,
                onCancelDownload = onCancelDownload,
                onRemoveOffline = onRemoveOffline,
            )
            LibrarySection.BOOKMARKS, LibrarySection.NOTES -> BookmarkAndNoteList(
                bookmarks = state.bookmarks,
                notes = state.notes,
                onBookmarkOpen = onBookmarkClick,
                onBookmarkDelete = onDeleteBookmark,
                onNoteOpen = onNoteClick,
                onNoteDelete = onDeleteNote,
            )
            LibrarySection.FOLLOWING -> FollowingList(state.following, onFollowingClick, onCheckFollowing)
        }
'''
new_block = r'''        var libraryQuery by remember { mutableStateOf("") }
        var querySection by remember { mutableStateOf(state.librarySection) }
        var showLibrarySearch by remember { mutableStateOf(false) }
        var showLibrarySort by remember { mutableStateOf(false) }
        var showDownloadQueue by remember { mutableStateOf(false) }
        var showReadingHistory by remember { mutableStateOf(false) }
        var readingSort by remember { mutableStateOf("recent") }
        var downloadedSort by remember { mutableStateOf("recent") }
        var bookmarkSort by remember { mutableStateOf("recent") }
        LaunchedEffect(state.librarySection) {
            if (querySection != state.librarySection) {
                libraryQuery = ""
                querySection = state.librarySection
            }
        }
        val normalizedQuery = libraryQuery.trim().lowercase()
        val readingVisible = state.readingStories
            .filter { normalizedQuery.isBlank() || listOf(it.title, it.author, it.description).any { value -> value.lowercase().contains(normalizedQuery) } }
            .let { items ->
                when (readingSort) {
                    "title" -> items.sortedBy { it.title.lowercase() }
                    "progress" -> items.sortedByDescending { it.updatedAt }
                    else -> items.sortedByDescending { it.updatedAt }
                }
            }
        val downloadedVisible = state.downloadedStories
            .filter { normalizedQuery.isBlank() || listOf(it.title, it.author).any { value -> value.lowercase().contains(normalizedQuery) } }
            .let { items ->
                when (downloadedSort) {
                    "name" -> items.sortedBy { it.title.lowercase() }
                    "size" -> items.sortedByDescending { state.offlineStorage[it.id]?.bytes ?: 0L }
                    "chapters" -> items.sortedByDescending { state.offlineStorage[it.id]?.chapterCount ?: 0 }
                    else -> items.sortedByDescending { it.updatedAt }
                }
            }
        val visibleBookmarks = state.bookmarks
            .filter { normalizedQuery.isBlank() || it.label.lowercase().contains(normalizedQuery) || it.storyId.lowercase().contains(normalizedQuery) }
            .let { items -> if (bookmarkSort == "title") items.sortedBy { it.label.lowercase() } else items.sortedByDescending { it.createdAt } }
        val visibleNotes = state.notes
            .filter { normalizedQuery.isBlank() || it.text.lowercase().contains(normalizedQuery) || it.storyId.lowercase().contains(normalizedQuery) }
            .let { items -> if (bookmarkSort == "title") items.sortedBy { it.text.lowercase() } else items.sortedByDescending { it.updatedAt } }
        val followingVisible = state.following.filter {
            normalizedQuery.isBlank() || it.title.lowercase().contains(normalizedQuery) ||
                it.sourceId.lowercase().contains(normalizedQuery) || it.latestKnownChapter.lowercase().contains(normalizedQuery)
        }

        when (state.librarySection) {
            LibrarySection.READING -> {
                ReferenceActionButton("TÌM TRUYỆN", { showLibrarySearch = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                ReferenceActionButton(
                    "SẮP XẾP: " + when (readingSort) { "title" -> "TÊN A-Z"; "progress" -> "TIẾN ĐỘ ĐỌC"; else -> "ĐỌC GẦN ĐÂY" },
                    { showLibrarySort = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
                )
                ReferenceActionButton("HÀNG ĐỢI TẢI", { showDownloadQueue = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                ReferenceActionButton("LỊCH SỬ ĐỌC", { showReadingHistory = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                ReferenceActionButton("NHẬP TRUYỆN", onImportFile, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                StoryEntityList(readingVisible, onStoryClick, "Chưa có truyện đang đọc.")
            }
            LibrarySection.DOWNLOADED -> {
                ReferenceActionButton("TÌM TRUYỆN", { showLibrarySearch = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                ReferenceActionButton(
                    "SẮP XẾP: " + when (downloadedSort) { "name" -> "TÊN A-Z"; "size" -> "DUNG LƯỢNG LỚN NHẤT"; "chapters" -> "NHIỀU CHƯƠNG NHẤT"; else -> "MỚI TẢI" },
                    { showLibrarySort = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
                )
                ReferenceActionButton("NHẬP TỆP", onImportFile, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                DownloadedSection(
                    stories = downloadedVisible,
                    jobs = emptyList(),
                    failures = emptyList(),
                    storage = state.offlineStorage,
                    onStoryClick = onStoryClick,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onRetryFailedChapter = onRetryFailedChapter,
                    onCancelDownload = onCancelDownload,
                    onRemoveOffline = onRemoveOffline,
                )
            }
            LibrarySection.BOOKMARKS, LibrarySection.NOTES -> {
                ReferenceActionButton("TÌM TRUYỆN", { showLibrarySearch = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                ReferenceActionButton(
                    "SẮP XẾP: " + if (bookmarkSort == "title") "TÊN A-Z" else "MỚI ĐÁNH DẤU",
                    { showLibrarySort = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
                )
                BookmarkAndNoteList(
                    bookmarks = visibleBookmarks,
                    notes = visibleNotes,
                    onBookmarkOpen = onBookmarkClick,
                    onBookmarkDelete = onDeleteBookmark,
                    onNoteOpen = onNoteClick,
                    onNoteDelete = onDeleteNote,
                )
            }
            LibrarySection.FOLLOWING -> {
                ReferenceActionButton("TÌM TRUYỆN", { showLibrarySearch = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                ReferenceActionButton("KIỂM TRA CẬP NHẬT", onCheckFollowing, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp))
                FollowingList(followingVisible, onFollowingClick, onCheckFollowing = {})
            }
        }

        if (showLibrarySearch) {
            val meta = when (state.librarySection) {
                LibrarySection.READING -> "TÌM TRONG ĐANG ĐỌC" to "Nhập tên truyện, chương hoặc vài ký tự liên quan"
                LibrarySection.DOWNLOADED -> "TÌM TRUYỆN ĐÃ TẢI" to "Nhập tên truyện hoặc vài ký tự liên quan"
                LibrarySection.BOOKMARKS, LibrarySection.NOTES -> "TÌM TRUYỆN ĐÃ ĐÁNH DẤU" to "Nhập tên truyện, chương hoặc vài ký tự liên quan"
                LibrarySection.FOLLOWING -> "TÌM TRUYỆN ĐANG THEO DÕI" to "Nhập tên truyện, nguồn hoặc vài ký tự liên quan"
            }
            var draft by remember(showLibrarySearch) { mutableStateOf(libraryQuery) }
            AlertDialog(
                onDismissRequest = { showLibrarySearch = false },
                title = { Text(meta.first) },
                text = { OutlinedTextField(draft, { draft = it.take(160) }, placeholder = { Text(meta.second) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { libraryQuery = draft; showLibrarySearch = false }) { Text("TÌM") } },
                dismissButton = { Row {
                    TextButton(onClick = { libraryQuery = ""; showLibrarySearch = false }) { Text("HIỆN TẤT CẢ") }
                    TextButton(onClick = { showLibrarySearch = false }) { Text("HỦY") }
                } },
            )
        }

        if (showLibrarySort) {
            val title: String
            val options: List<Pair<String, String>>
            when (state.librarySection) {
                LibrarySection.READING -> { title = "SẮP XẾP ĐANG ĐỌC"; options = listOf("recent" to "ĐỌC GẦN ĐÂY", "title" to "TÊN A-Z", "progress" to "TIẾN ĐỘ ĐỌC") }
                LibrarySection.DOWNLOADED -> { title = "SẮP XẾP TRUYỆN ĐÃ TẢI"; options = listOf("recent" to "MỚI TẢI", "name" to "TÊN A-Z", "size" to "DUNG LƯỢNG LỚN NHẤT", "chapters" to "NHIỀU CHƯƠNG NHẤT") }
                LibrarySection.BOOKMARKS, LibrarySection.NOTES -> { title = "SẮP XẾP ĐÁNH DẤU"; options = listOf("recent" to "MỚI ĐÁNH DẤU", "title" to "TÊN A-Z") }
                LibrarySection.FOLLOWING -> { title = ""; options = emptyList() }
            }
            if (options.isNotEmpty()) AlertDialog(
                onDismissRequest = { showLibrarySort = false },
                title = { Text(title) },
                text = { Column { options.forEach { (value, label) ->
                    ReferenceActionButton(label, {
                        when (state.librarySection) {
                            LibrarySection.READING -> readingSort = value
                            LibrarySection.DOWNLOADED -> downloadedSort = value
                            LibrarySection.BOOKMARKS, LibrarySection.NOTES -> bookmarkSort = value
                            LibrarySection.FOLLOWING -> Unit
                        }
                        showLibrarySort = false
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                } } },
                confirmButton = { TextButton(onClick = { showLibrarySort = false }) { Text("ĐÓNG") } },
            ) else showLibrarySort = false
        }

        if (showReadingHistory) {
            AlertDialog(
                onDismissRequest = { showReadingHistory = false },
                title = { Text("LỊCH SỬ ĐỌC") },
                text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (state.readingStories.isEmpty()) Text("Chưa có lịch sử đọc.")
                    state.readingStories.sortedByDescending { it.updatedAt }.forEach { story ->
                        ReferenceActionButton(story.title, { showReadingHistory = false; onStoryClick(story) }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                    }
                } },
                confirmButton = { TextButton(onClick = { showReadingHistory = false }) { Text("ĐÓNG") } },
            )
        }

        if (showDownloadQueue) {
            AlertDialog(
                onDismissRequest = { showDownloadQueue = false },
                title = { Text("HÀNG ĐỢI TẢI") },
                text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (state.downloads.isEmpty()) Text("Hàng đợi tải đang trống.")
                    state.downloads.forEach { job ->
                        Text("${job.currentChapterTitle.ifBlank { job.storyId }} • ${job.completedChapters}/${job.totalChapters}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Text(job.state, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth()) {
                            when (job.state) {
                                "RUNNING", "QUEUED" -> Button({ onPauseDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("TẠM DỪNG") }
                                "PAUSED" -> Button({ onResumeDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("TIẾP TỤC") }
                                "FAILED" -> Button({ onRetryDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("THỬ LẠI") }
                            }
                            Button({ onCancelDownload(job.id) }, Modifier.weight(1f).padding(1.dp)) { Text("HỦY") }
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { showDownloadQueue = false }) { Text("ĐÓNG") } },
            )
        }
'''
if old_block not in text:
    raise SystemExit("Library main block not found")
text = text.replace(old_block, new_block, 1)
write(path, text)

# ---------------------------------------------------------------------------
# Story chapter sorting: the reference opens a choice dialog instead of toggling.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
text = read(path)
text = replace_once(
    text,
    """    var showChapterSearchDialog by remember(detail.story.id) { mutableStateOf(false) }\n    val chapterSortDescending = state.chapterSortDescending\n""",
    """    var showChapterSearchDialog by remember(detail.story.id) { mutableStateOf(false) }\n    var showChapterSortDialog by remember(detail.story.id) { mutableStateOf(false) }\n    val chapterSortDescending = state.chapterSortDescending\n""",
    "Story chapter sort state",
)
text = replace_once(
    text,
    """                    onClick = { onChapterSortDescendingChange(!chapterSortDescending) },\n""",
    """                    onClick = { showChapterSortDialog = true },\n""",
    "Story sort button opens dialog",
)
anchor = """    if (showChapterSearchDialog) {\n        AlertDialog(\n"""
sort_dialog = """    if (showChapterSortDialog) {\n        AlertDialog(\n            onDismissRequest = { showChapterSortDialog = false },\n            title = { Text(\"SẮP XẾP DANH SÁCH CHƯƠNG\") },\n            text = { Column {\n                ReferenceActionButton(\"CŨ NHẤT TRƯỚC\", {\n                    onChapterSortDescendingChange(false)\n                    showChapterSortDialog = false\n                }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))\n                ReferenceActionButton(\"MỚI NHẤT TRƯỚC\", {\n                    onChapterSortDescendingChange(true)\n                    showChapterSortDialog = false\n                }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))\n            } },\n            confirmButton = { TextButton(onClick = { showChapterSortDialog = false }) { Text(\"ĐÓNG\") } },\n        )\n    }\n\n"""
text = replace_once(text, anchor, sort_dialog + anchor, "insert Story chapter sort dialog")
write(path, text)

print("REFERENCE_PARITY_PHASE1_PATCH_OK")
