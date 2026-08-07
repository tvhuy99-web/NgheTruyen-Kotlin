package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var searchDialogOpen by remember { mutableStateOf(false) }
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
    val view = LocalView.current
    val listTitle = when (state.exploreMode) {
        ExploreMode.HOME -> "TRANG CHỦ"
        ExploreMode.CATEGORY -> state.activeCategory.orEmpty().ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()
        ExploreMode.SEARCH -> "KẾT QUẢ TÌM KIẾM"
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
            onClick = { searchDialogOpen = true },
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
                        value = state.query,
                        onValueChange = onQueryChange,
                        label = { Text("Tên truyện, tác giả hoặc URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        ReferenceTabButton(
                            text = "MỘT NGUỒN",
                            selected = !state.searchAllSources,
                            onClick = { onSearchAllSourcesChange(false) },
                            accessibilityLabel = "Tìm trên một nguồn",
                            minHeight = 50.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                        ReferenceTabButton(
                            text = "TẤT CẢ NGUỒN",
                            selected = state.searchAllSources,
                            onClick = { onSearchAllSourcesChange(true) },
                            accessibilityLabel = "Tìm trên tất cả nguồn",
                            minHeight = 50.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                    }
                    Text("Sắp xếp kết quả", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        listOf(
                            SearchSortMode.RELEVANCE to "LIÊN QUAN",
                            SearchSortMode.TITLE to "TÊN",
                            SearchSortMode.AUTHOR to "TÁC GIẢ",
                            SearchSortMode.SOURCE to "NGUỒN",
                        ).forEach { (mode, label) ->
                            ReferenceTabButton(
                                text = label,
                                selected = state.searchSortMode == mode,
                                onClick = { onSortModeChange(mode) },
                                accessibilityLabel = "Sắp xếp theo ${label.lowercase()}",
                                minHeight = 48.dp,
                                unselectedColor = ReferenceDivider,
                                unselectedContentColor = ReferenceText,
                            )
                        }
                    }
                    if (state.sourceSuggestions.isNotEmpty() && !state.searchAllSources) {
                        Text("Gợi ý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            state.sourceSuggestions.forEach { suggestion ->
                                TextButton(onClick = { onSuggestionSelected(suggestion) }) {
                                    Text(suggestion)
                                }
                            }
                        }
                    }
                    if (state.searchAllSources && state.totalSearchSourceCount > 0) {
                        Text(
                            "Đã nhận phản hồi ${state.searchedSourceCount}/${state.totalSearchSourceCount} nguồn",
                            color = ReferenceSecondaryText,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.loading,
                    onClick = {
                        searchDialogOpen = false
                        onSearch()
                    },
                ) { Text("TÌM") }
            },
            dismissButton = {
                if (state.loading && state.searchAllSources) {
                    TextButton(onClick = {
                        onCancelSearch()
                        searchDialogOpen = false
                    }) { Text("HỦY TÌM") }
                } else {
                    TextButton(onClick = { searchDialogOpen = false }) { Text("ĐÓNG") }
                }
            },
        )
    }

}
