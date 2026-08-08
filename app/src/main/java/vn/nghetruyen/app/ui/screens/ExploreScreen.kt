package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.nghetruyen.app.core.model.SearchSortMode
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.sources.ReferenceSearchRuntime
import vn.nghetruyen.app.sources.SourceUiSurface
import vn.nghetruyen.app.ui.ExploreMode
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.LoadingRow
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceDivider
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferencePurple
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceSecondaryText
import vn.nghetruyen.app.ui.components.ReferenceTabButton
import vn.nghetruyen.app.ui.components.ReferenceText
import vn.nghetruyen.app.ui.components.StoryCard

@Composable
fun ExploreScreen(
    state: MainUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchAllSourcesChange: (Boolean) -> Unit,
    onSortModeChange: (SearchSortMode) -> Unit,
    onCancelSearch: () -> Unit,
    onSourceSelected: (String) -> Unit,
    onHomeSelected: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onLoadMore: () -> Unit,
    onStoryClick: (StorySummary) -> Unit,
    onOpenSourceLogin: (String) -> Unit,
    onCheckSource: (String) -> Unit,
    onSourceUiAction: (String, String) -> Unit,
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var searchDialogOpen by remember { mutableStateOf(false) }
    var searchHistoryOpen by remember { mutableStateOf(false) }
    var searchSourcesOpen by remember { mutableStateOf(false) }
    var searchQueryDraft by remember { mutableStateOf("") }
    var searchAllDraft by remember { mutableStateOf(false) }
    var searchSortDraft by remember { mutableStateOf(SearchSortMode.RELEVANCE) }
    var searchGroupDraft by remember { mutableStateOf(true) }
    var searchSourceDraft by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
    val availableSearchSources = state.sources.filter {
        it.health != SourceHealth.DISABLED && it.health != SourceHealth.NOT_PORTED
    }
    val context = LocalContext.current
    val searchPrefs = remember(context) {
        context.getSharedPreferences("reference_search_ui", android.content.Context.MODE_PRIVATE)
    }
    var searchHistory by remember {
        mutableStateOf(
            searchPrefs.getString("history", "").orEmpty()
                .split('\n')
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(30),
        )
    }
    val view = LocalView.current
    val listTitle = when (state.exploreMode) {
        ExploreMode.HOME -> "TRANG CHỦ"
        ExploreMode.CATEGORY -> state.activeCategory.orEmpty().ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()
        ExploreMode.SEARCH -> "KẾT QUẢ TÌM KIẾM"
    }

    fun openReferenceSearch() {
        searchQueryDraft = state.query
        searchAllDraft = state.searchAllSources
        searchSortDraft = state.searchSortMode.takeIf { it != SearchSortMode.AUTHOR } ?: SearchSortMode.RELEVANCE
        searchGroupDraft = ReferenceSearchRuntime.groupDuplicates
        val persisted = searchPrefs.getStringSet("sources", emptySet()).orEmpty()
        searchSourceDraft = ReferenceSearchRuntime.selectedSourceIds
            .ifEmpty { persisted }
            .ifEmpty { availableSearchSources.mapTo(linkedSetOf()) { it.id } }
        searchDialogOpen = true
    }

    fun rememberSearch(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        searchHistory = (listOf(clean) + searchHistory.filterNot { it.equals(clean, ignoreCase = true) }).take(30)
        searchPrefs.edit().putString("history", searchHistory.joinToString("\n")).apply()
    }

    LaunchedEffect(state.exploreMode, state.activeCategory, state.stories.size, state.loading) {
        if (!state.loading) {
            delay(120)
            view.announceForAccessibility("$listTitle, ${state.stories.size} truyện")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReferenceScreenBackground),
    ) {
        ReferenceActionButton(
            text = "TÌM KIẾM",
            onClick = ::openReferenceSearch,
            normalColor = ReferencePurple,
            accessibilityLabel = "Mở tìm kiếm truyện",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ReferencePanelBackground)
                .padding(4.dp),
        ) {
            ReferenceActionButton(
                text = "NGUỒN: ${selectedSource?.displayName ?: "CHƯA CHỌN"}",
                onClick = { sourceMenuOpen = true },
                enabled = !state.searchAllSources,
                normalColor = ReferenceDivider,
                normalContentColor = ReferenceText,
                accessibilityLabel = "Chọn nguồn truyện đang khám phá. Hiện tại ${selectedSource?.displayName ?: "chưa chọn"}",
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = sourceMenuOpen,
                onDismissRequest = { sourceMenuOpen = false },
            ) {
                state.sources.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            val status = if (source.health == SourceHealth.READY) "" else " • ${source.health.name}"
                            Text(source.displayName + status)
                        },
                        onClick = {
                            sourceMenuOpen = false
                            onSourceSelected(source.id)
                        },
                    )
                }
            }

            val customExploreActions = selectedSource?.uiActions.orEmpty()
                .filter { SourceUiSurface.EXPLORE in it.surfaces }
                .sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
            if (!state.searchAllSources && selectedSource != null && customExploreActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    customExploreActions.forEach { action ->
                        ReferenceActionButton(
                            text = action.label,
                            onClick = { onSourceUiAction(selectedSource.id, action.id) },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            minHeight = 48.dp,
                            modifier = Modifier.padding(1.dp),
                        )
                    }
                }
            }

            if (!state.searchAllSources && selectedSource != null &&
                (selectedSource.loginUrl != null || selectedSource.health != SourceHealth.READY)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (selectedSource.loginUrl != null) {
                        ReferenceActionButton(
                            text = if (selectedSource.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN",
                            onClick = { onOpenSourceLogin(selectedSource.id) },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            minHeight = 48.dp,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                    }
                    ReferenceActionButton(
                        text = if (selectedSource.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN",
                        onClick = { onCheckSource(selectedSource.id) },
                        enabled = selectedSource.id !in state.sourceHealthChecking && selectedSource.health != SourceHealth.NOT_PORTED,
                        normalColor = ReferenceDivider,
                        normalContentColor = ReferenceText,
                        minHeight = 48.dp,
                        modifier = Modifier.weight(1f).padding(1.dp),
                    )
                }
            }

            if (!state.searchAllSources && (selectedSource?.supportsHome == true || state.categories.isNotEmpty())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (selectedSource?.supportsHome == true) {
                        ReferenceTabButton(
                            text = "TRANG CHỦ",
                            selected = state.exploreMode == ExploreMode.HOME,
                            onClick = onHomeSelected,
                            accessibilityLabel = "Danh mục Trang chủ",
                            minHeight = 54.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                        )
                    }
                    state.categories.forEach { category ->
                        val selected = state.exploreMode == ExploreMode.CATEGORY && state.activeCategory == category
                        ReferenceTabButton(
                            text = category.uppercase(),
                            selected = selected,
                            onClick = { onCategorySelected(category) },
                            accessibilityLabel = "Danh mục $category",
                            minHeight = 54.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                        )
                    }
                }
            }
        }

        Text(
            text = "$listTitle • ${state.stories.size}",
            color = ReferenceText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .background(ReferencePanelBackground)
                .semantics {
                    heading()
                    contentDescription = "$listTitle, ${state.stories.size} truyện"
                }
                .padding(8.dp),
        )

        if (state.loading) LoadingRow()
        if (state.searchAllSources && state.totalSearchSourceCount > 0) {
            Text(
                "Đã nhận phản hồi ${state.searchedSourceCount}/${state.totalSearchSourceCount} nguồn",
                color = ReferenceSecondaryText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (state.stories.isEmpty() && !state.loading) {
            Text(
                text = when (state.exploreMode) {
                    ExploreMode.HOME -> "Trang chủ nguồn chưa trả về truyện nào."
                    ExploreMode.CATEGORY -> "Danh mục đang chọn chưa có kết quả."
                    ExploreMode.SEARCH -> "Chưa có kết quả từ nguồn đang chọn."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ReferenceSecondaryText,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.stories, key = { it.sourceId + ":" + it.id }) { story ->
                    StoryCard(story = story, onClick = { onStoryClick(story) })
                }
                if (state.canLoadMoreStories) {
                    item(key = "load-more-stories") {
                        ReferenceActionButton(
                            text = "TẢI THÊM • TRANG ${state.explorePage + 1}",
                            onClick = onLoadMore,
                            enabled = !state.loading,
                            accessibilityLabel = "Tải thêm truyện, trang ${state.explorePage + 1}",
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    if (searchDialogOpen) {
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
                        modifier = Modifier.fillMaxWidth().clickable { searchAllDraft = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = !searchAllDraft, onClick = { searchAllDraft = false })
                        Text("Tìm trong nguồn hiện tại")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { searchAllDraft = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = searchAllDraft, onClick = { searchAllDraft = true })
                        Text("Tìm đa nguồn")
                    }
                    if (searchAllDraft) {
                        ReferenceActionButton(
                            text = "CHỌN NGUỒN (${searchSourceDraft.size} / ${availableSearchSources.size})",
                            onClick = { searchSourcesOpen = true },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            minHeight = 52.dp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Text("Sắp xếp kết quả", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        Box(Modifier.fillMaxWidth()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            Button(onClick = { sortExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    when (searchSortDraft) {
                                        SearchSortMode.TITLE -> "Tên truyện A-Z"
                                        SearchSortMode.SOURCE -> "Theo nguồn"
                                        else -> "Độ phù hợp"
                                    },
                                )
                            }
                            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                listOf(
                                    SearchSortMode.RELEVANCE to "Độ phù hợp",
                                    SearchSortMode.TITLE to "Tên truyện A-Z",
                                    SearchSortMode.SOURCE to "Theo nguồn",
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { searchSortDraft = mode; sortExpanded = false },
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { searchGroupDraft = !searchGroupDraft },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = searchGroupDraft, onCheckedChange = { searchGroupDraft = it })
                            Text("Gom truyện trùng tên")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !searchAllDraft || searchSourceDraft.isNotEmpty(),
                    onClick = {
                        val selected = if (searchAllDraft) searchSourceDraft else emptySet()
                        ReferenceSearchRuntime.selectedSourceIds = selected
                        ReferenceSearchRuntime.groupDuplicates = searchGroupDraft
                        searchPrefs.edit()
                            .putStringSet("sources", searchSourceDraft)
                            .putBoolean("group_duplicates", searchGroupDraft)
                            .apply()
                        rememberSearch(searchQueryDraft)
                        onQueryChange(searchQueryDraft)
                        onSortModeChange(searchSortDraft)
                        onSearchAllSourcesChange(searchAllDraft)
                        searchDialogOpen = false
                        onSearch()
                    },
                ) { Text("TÌM") }
            },
            dismissButton = { TextButton(onClick = { searchDialogOpen = false }) { Text("HỦY") } },
        )
    }

    if (searchHistoryOpen) {
        AlertDialog(
            onDismissRequest = { searchHistoryOpen = false },
            title = { Text("LỊCH SỬ TÌM KIẾM") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (searchHistory.isEmpty()) {
                        Text("Chưa có lịch sử tìm kiếm.")
                    } else {
                        searchHistory.forEach { query ->
                            TextButton(
                                onClick = {
                                    searchQueryDraft = query
                                    searchHistoryOpen = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(query) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { searchHistoryOpen = false }) { Text("ĐÓNG") } },
            dismissButton = {
                TextButton(onClick = {
                    searchHistory = emptyList()
                    searchPrefs.edit().remove("history").apply()
                    searchHistoryOpen = false
                }) { Text("XÓA LỊCH SỬ") }
            },
        )
    }

    if (searchSourcesOpen) {
        var pendingSources by remember(searchSourcesOpen) { mutableStateOf(searchSourceDraft) }
        AlertDialog(
            onDismissRequest = { searchSourcesOpen = false },
            title = { Text("CHỌN NGUỒN TÌM KIẾM") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    availableSearchSources.forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                pendingSources = if (source.id in pendingSources) pendingSources - source.id else pendingSources + source.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
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
                    searchSourcesOpen = false
                }) { Text("ÁP DỤNG") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        searchSourceDraft = availableSearchSources.mapTo(linkedSetOf()) { it.id }
                        searchSourcesOpen = false
                    }) { Text("CHỌN TẤT CẢ") }
                    TextButton(onClick = { searchSourcesOpen = false }) { Text("HỦY") }
                }
            },
        )
    }
}
