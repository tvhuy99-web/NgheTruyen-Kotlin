package vn.nghetruyen.app.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.core.model.SearchSortMode
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.ui.ExploreMode
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.LoadingRow
import vn.nghetruyen.app.ui.components.ScreenHeading
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
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeading("KHÁM PHÁ")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { sourceMenuOpen = true },
                    enabled = !state.searchAllSources,
                    modifier = Modifier.weight(1f).padding(end = 3.dp),
                ) {
                    Text("NGUỒN: ${selectedSource?.displayName ?: "Chưa chọn"}")
                }
                Button(
                    onClick = { onSearchAllSourcesChange(!state.searchAllSources) },
                    modifier = Modifier.weight(1f).padding(start = 3.dp),
                ) {
                    Text(if (state.searchAllSources) "✓ TẤT CẢ NGUỒN" else "TÌM MỘT NGUỒN")
                }
            }
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
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (selectedSource?.supportsHome == true) {
                        Button(onClick = onHomeSelected) {
                            Text(if (state.exploreMode == ExploreMode.HOME) "✓ TRANG CHỦ" else "TRANG CHỦ")
                        }
                    }
                    state.categories.forEach { category ->
                        Button(onClick = { onCategorySelected(category) }) {
                            Text(if (state.exploreMode == ExploreMode.CATEGORY && state.activeCategory == category) "✓ $category" else category)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Tên truyện, tác giả hoặc URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.sourceSuggestions.isNotEmpty() && !state.searchAllSources) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.sourceSuggestions.forEach { suggestion ->
                        Button(onClick = { onSuggestionSelected(suggestion) }) {
                            Text(suggestion)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(onClick = onSearch, modifier = Modifier.weight(1f).padding(end = 2.dp)) {
                    Text("TÌM KIẾM")
                }
                if (state.loading && state.searchAllSources) {
                    Button(onClick = onCancelSearch, modifier = Modifier.weight(0.7f).padding(start = 2.dp)) {
                        Text("HỦY")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    SearchSortMode.RELEVANCE to "LIÊN QUAN",
                    SearchSortMode.TITLE to "TÊN",
                    SearchSortMode.AUTHOR to "TÁC GIẢ",
                    SearchSortMode.SOURCE to "NGUỒN",
                ).forEach { (mode, label) ->
                    Button(onClick = { onSortModeChange(mode) }) {
                        Text(if (state.searchSortMode == mode) "✓ $label" else label)
                    }
                }
            }
        }

        if (state.loading) LoadingRow()
        if (state.searchAllSources && state.totalSearchSourceCount > 0) {
            Text(
                "Đã nhận phản hồi ${state.searchedSourceCount}/${state.totalSearchSourceCount} nguồn",
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
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.stories, key = { it.sourceId + ":" + it.id }) { story ->
                    StoryCard(story = story, onClick = { onStoryClick(story) })
                }
                if (state.canLoadMoreStories) {
                    item(key = "load-more-stories") {
                        Button(
                            onClick = onLoadMore,
                            enabled = !state.loading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Text("TẢI THÊM • TRANG ${state.explorePage + 1}")
                        }
                    }
                }
            }
        }
    }
}
