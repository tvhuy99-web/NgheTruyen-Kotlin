from pathlib import Path

ROOT = Path('.')


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:180]!r}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')

# StorySource: distinguish static categories from a dynamic genre/menu capability.
path = 'app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt'
replace(path,
'''    val supportsHome: Boolean = true,
    val supportsSuggestions: Boolean = false,
''',
'''    val supportsHome: Boolean = true,
    val supportsGenre: Boolean = false,
    val supportsSuggestions: Boolean = false,
''')
replace(path,
'''    suspend fun home(page: Int = 1): AppResult<List<StorySummary>> = search("", page)

    suspend fun suggestions(query: String): AppResult<List<String>> = AppResult.Success(emptyList())
''',
'''    suspend fun home(page: Int = 1): AppResult<List<StorySummary>> = search("", page)

    /** Top-level browse menu. Static sources fall back to descriptor categories. */
    suspend fun genreMenu(): AppResult<List<String>> = AppResult.Success(descriptor.categories)

    suspend fun suggestions(query: String): AppResult<List<String>> = AppResult.Success(emptyList())
''')

# vBook adapter: expose genre.js as an actual dynamic browse menu instead of pretending categories are static.
path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookStorySource.kt'
replace(path,
'''        supportsHome = plugin.script(VBookScriptRole.HOME) != null || plugin.script(VBookScriptRole.EXPLORE) != null,
        supportsSuggestions = plugin.script(VBookScriptRole.SUGGEST) != null,
''',
'''        supportsHome = plugin.script(VBookScriptRole.HOME) != null || plugin.script(VBookScriptRole.EXPLORE) != null,
        supportsGenre = plugin.script(VBookScriptRole.GENRE) != null,
        supportsSuggestions = plugin.script(VBookScriptRole.SUGGEST) != null,
''')
replace(path,
'''    override suspend fun suggestions(query: String): AppResult<List<String>> {
''',
'''    override suspend fun genreMenu(): AppResult<List<String>> {
        if (plugin.script(VBookScriptRole.GENRE) == null) return AppResult.Success(emptyList())
        return when (val menu = executeDeclared(VBookScriptRole.GENRE, input = "")) {
            is AppResult.Failure -> menu
            is AppResult.Success -> AppResult.Success(
                VBookStoryNormalizer.dynamicActions(menu.value.data)
                    .asSequence()
                    .map { it.title.trim() }
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_GENRE_MENU_ENTRIES)
                    .toList(),
            )
        }
    }

    override suspend fun suggestions(query: String): AppResult<List<String>> {
''')
replace(path,
'''        private const val MAX_SUGGESTIONS = 30
''',
'''        private const val MAX_SUGGESTIONS = 30
        private const val MAX_GENRE_MENU_ENTRIES = 2_000
''')

# Remove source-id attribution from every reusable StoryCard, both visible text and accessibility speech.
path = 'app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt'
replace(path,
'''        if (story.author.isNotBlank()) append(". Tác giả: ${story.author}")
        append(". Nguồn: ${story.sourceId}")
        if (story.description.isNotBlank()) append(". ${story.description}")
''',
'''        if (story.author.isNotBlank()) append(". Tác giả: ${story.author}")
        if (story.description.isNotBlank()) append(". ${story.description}")
''')
replace(path,
'''            Text(story.sourceId, style = MaterialTheme.typography.labelMedium, color = ReferenceSecondaryText)
''',
'''            // Source identity remains internal for routing/deduplication; cards never expose raw source ids.
''')

# ViewModel: add a GENRE surface and load its dynamic menu independently of stories.
path = 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt'
replace(path,
'''enum class ExploreMode { HOME, SEARCH, CATEGORY }
''',
'''enum class ExploreMode { HOME, GENRE, SEARCH, CATEGORY }
''')
replace(path,
'''    val categories: List<String> = emptyList(),
    val query: String = "",
''',
'''    val categories: List<String> = emptyList(),
    val genreEntries: List<String> = emptyList(),
    val query: String = "",
''')
replace(path,
'''    fun browseHome() {
        search("")
    }

    fun selectSource(sourceId: String) {
''',
'''    fun browseHome() {
        search("")
    }

    fun browseGenreMenu() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val source = container.sourceRegistry.get(state.value.selectedSourceId) ?: return@launch
            if (!source.descriptor.supportsGenre) {
                mutableState.update {
                    it.copy(
                        exploreMode = ExploreMode.GENRE,
                        genreEntries = source.descriptor.categories,
                        stories = emptyList(),
                        canLoadMoreStories = false,
                        loading = false,
                    )
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    loading = true,
                    message = null,
                    explorePage = 1,
                    exploreMode = ExploreMode.GENRE,
                    activeCategory = null,
                    genreEntries = emptyList(),
                    stories = emptyList(),
                    sourceSuggestions = emptyList(),
                    canLoadMoreStories = false,
                )
            }
            when (val result = source.genreMenu()) {
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        loading = false,
                        genreEntries = result.value,
                        message = if (result.value.isEmpty()) "Tiện ích chưa trả về mục thể loại nào." else null,
                    )
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(loading = false, genreEntries = emptyList(), message = result.message)
                }
            }
        }
    }

    fun selectSource(sourceId: String) {
''')
replace(path,
'''                    categories = descriptor?.categories.orEmpty(),
                    stories = emptyList(),
''',
'''                    categories = descriptor?.categories.orEmpty(),
                    genreEntries = emptyList(),
                    stories = emptyList(),
''')
replace(path,
'''                ExploreMode.HOME -> browseHome()
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let(::browseCategory)
                ExploreMode.SEARCH -> Unit
''',
'''                ExploreMode.HOME -> browseHome()
                ExploreMode.GENRE -> browseGenreMenu()
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let(::browseCategory)
                ExploreMode.SEARCH -> Unit
''')
replace(path,
'''                    exploreMode = if (cleanQuery.isBlank() && !snapshot.searchAllSources) ExploreMode.HOME else ExploreMode.SEARCH,
                    activeCategory = null,
                    canLoadMoreStories = false,
''',
'''                    exploreMode = if (cleanQuery.isBlank() && !snapshot.searchAllSources) ExploreMode.HOME else ExploreMode.SEARCH,
                    activeCategory = null,
                    genreEntries = emptyList(),
                    canLoadMoreStories = false,
''')
replace(path,
'''                    exploreMode = ExploreMode.CATEGORY,
                    activeCategory = category,
                    sourceSuggestions = emptyList(),
''',
'''                    exploreMode = ExploreMode.CATEGORY,
                    activeCategory = category,
                    genreEntries = emptyList(),
                    sourceSuggestions = emptyList(),
''')
replace(path,
'''                ExploreMode.HOME -> source.home(nextPage)
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let { source.category(it, nextPage) }
                    ?: source.home(nextPage)
                ExploreMode.SEARCH -> source.search(snapshot.query, nextPage)
''',
'''                ExploreMode.HOME -> source.home(nextPage)
                ExploreMode.GENRE -> return@launch mutableState.update { it.copy(loading = false, canLoadMoreStories = false) }
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let { source.category(it, nextPage) }
                    ?: source.home(nextPage)
                ExploreMode.SEARCH -> source.search(snapshot.query, nextPage)
''')
# Story-detail genre clicks should use dynamic genre menus too, instead of degrading to text search.
replace(path,
'''        val matched = source.descriptor.categories.firstOrNull {
            StorySearch.normalize(it) == StorySearch.normalize(clean)
        }
''',
'''        val matched = source.descriptor.categories.firstOrNull {
            StorySearch.normalize(it) == StorySearch.normalize(clean)
        }
        val canBrowseDynamicGenre = source.descriptor.supportsGenre
''')
replace(path,
'''            if (matched != null) browseCategory(matched) else search(clean)
''',
'''            if (matched != null) browseCategory(matched)
            else if (canBrowseDynamicGenre) browseCategory(clean)
            else search(clean)
''')

# Explore UI: top-level THỂ LOẠI opens a scalable vertical dynamic menu.
path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt'
replace(path,
'''    onHomeSelected: () -> Unit,
    onCategorySelected: (String) -> Unit,
''',
'''    onHomeSelected: () -> Unit,
    onGenreSelected: () -> Unit,
    onCategorySelected: (String) -> Unit,
''')
replace(path,
'''        ExploreMode.HOME -> "TRANG CHỦ"
        ExploreMode.CATEGORY -> state.activeCategory.orEmpty().ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()
        ExploreMode.SEARCH -> "KẾT QUẢ TÌM KIẾM"
''',
'''        ExploreMode.HOME -> "TRANG CHỦ"
        ExploreMode.GENRE -> "THỂ LOẠI"
        ExploreMode.CATEGORY -> state.activeCategory.orEmpty().ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()
        ExploreMode.SEARCH -> "KẾT QUẢ TÌM KIẾM"
''')
replace(path,
'''            if (!state.searchAllSources && (selectedSource?.supportsHome == true || state.categories.isNotEmpty())) {
''',
'''            if (!state.searchAllSources && (selectedSource?.supportsHome == true || selectedSource?.supportsGenre == true || state.categories.isNotEmpty())) {
''')
replace(path,
'''                    state.categories.forEach { category ->
''',
'''                    if (selectedSource?.supportsGenre == true) {
                        ReferenceTabButton(
                            text = "THỂ LOẠI",
                            selected = state.exploreMode == ExploreMode.GENRE,
                            onClick = onGenreSelected,
                            accessibilityLabel = "THỂ LOẠI",
                            minHeight = 54.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                        )
                    }
                    state.categories.forEach { category ->
''')
# Count/heading must describe menu entries in GENRE mode, not stale story count.
replace(path,
'''        Text(
            text = "$listTitle • ${state.stories.size}",
''',
'''        val visibleItemCount = if (state.exploreMode == ExploreMode.GENRE) state.genreEntries.size else state.stories.size
        Text(
            text = "$listTitle • $visibleItemCount",
''')
replace(path,
'''                    contentDescription = "$listTitle, ${state.stories.size} truyện"
''',
'''                    contentDescription = if (state.exploreMode == ExploreMode.GENRE) {
                        "$listTitle, ${state.genreEntries.size} mục"
                    } else {
                        "$listTitle, ${state.stories.size} truyện"
                    }
''')
# Replace story-list branch with an explicit genre-menu branch.
replace(path,
'''        if (state.stories.isEmpty() && !state.loading) {
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
                            accessibilityLabel = "TẢI THÊM • TRANG ${state.explorePage + 1}",
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }
        }
''',
'''        if (state.exploreMode == ExploreMode.GENRE) {
            if (state.genreEntries.isEmpty() && !state.loading) {
                Text(
                    text = "Tiện ích chưa trả về mục thể loại nào.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReferenceSecondaryText,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.genreEntries, key = { it }) { category ->
                        ReferenceActionButton(
                            text = category,
                            onClick = { onCategorySelected(category) },
                            accessibilityLabel = category,
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        } else if (state.stories.isEmpty() && !state.loading) {
            Text(
                text = when (state.exploreMode) {
                    ExploreMode.HOME -> "Trang chủ nguồn chưa trả về truyện nào."
                    ExploreMode.GENRE -> "Tiện ích chưa trả về mục thể loại nào."
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
                            accessibilityLabel = "TẢI THÊM • TRANG ${state.explorePage + 1}",
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }
        }
''')

# Navigation wiring + diagnostics context.
path = 'app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt'
replace(path,
'''                        onHomeSelected = {
                            activateExploreDiagnosticContext(mode = ExploreMode.HOME)
                            viewModel.browseHome()
                        },
                        onCategorySelected = { category ->
''',
'''                        onHomeSelected = {
                            activateExploreDiagnosticContext(mode = ExploreMode.HOME)
                            viewModel.browseHome()
                        },
                        onGenreSelected = {
                            activateExploreDiagnosticContext(mode = ExploreMode.GENRE)
                            viewModel.browseGenreMenu()
                        },
                        onCategorySelected = { category ->
''')

# Static regression test: protects both UX requirements and generic vBook mapping.
test = ROOT / 'app/src/test/java/vn/nghetruyen/app/sourceplatform/VBookBrowsePresentationStaticTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(r'''package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookBrowsePresentationStaticTest {
    @Test
    fun dynamicGenreNavigationAndPrivateSourceIdsStayWired() {
        val root = repositoryRoot()
        val storySource = Files.readString(root.resolve("app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt"))
        val vbook = Files.readString(root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookStorySource.kt"))
        val viewModel = Files.readString(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"))
        val explore = Files.readString(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt"))
        val common = Files.readString(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt"))
        val app = Files.readString(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt"))

        assertTrue("StorySource must expose a generic dynamic genre capability", "val supportsGenre: Boolean" in storySource)
        assertTrue("StorySource must expose a generic genre menu API", "suspend fun genreMenu()" in storySource)
        assertTrue("vBook descriptor must derive genre support from plugin.json", "supportsGenre = plugin.script(VBookScriptRole.GENRE) != null" in vbook)
        assertTrue("vBook genre.js output must become dynamic menu actions", "VBookStoryNormalizer.dynamicActions(menu.value.data)" in vbook)
        assertTrue("ViewModel must own an explicit GENRE explore state", "ExploreMode { HOME, GENRE, SEARCH, CATEGORY }" in viewModel)
        assertTrue("UI must expose the dynamic genre surface", "text = \"THỂ LOẠI\"" in explore)
        assertTrue("Dynamic genre menu must be vertically scalable", "items(state.genreEntries" in explore)
        assertTrue("App navigation must route the genre tab", "viewModel.browseGenreMenu()" in app)

        assertFalse("Story cards must not speak raw source ids", "Nguồn: ${story.sourceId}" in common)
        assertFalse("Story cards must not render raw source ids", "Text(story.sourceId" in common)
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOfNotNull(working, working.parent).firstOrNull {
            Files.isDirectory(it.resolve("app")) && Files.isDirectory(it.resolve("source-vbook"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
''', encoding='utf-8')

print('dynamic vBook browse + source attribution patch staged')
