from pathlib import Path

ROOT = Path('.')


def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# Generic browse contract: labels are presentation, keys are routing identity.
path = 'app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt'
replace(path,
'''data class SourceUiActionResult(
    val message: String = "",
    val openUrl: String? = null,
    val refresh: Boolean = false,
)

data class SourceDescriptor(
''',
'''data class SourceUiActionResult(
    val message: String = "",
    val openUrl: String? = null,
    val refresh: Boolean = false,
)

data class SourceBrowseEntry(
    val key: String,
    val label: String,
    val selectable: Boolean = true,
)

data class SourceDescriptor(
''')
replace(path,
'''    /** Top-level browse menu. Static sources fall back to descriptor categories. */
    suspend fun genreMenu(): AppResult<List<String>> = AppResult.Success(descriptor.categories)
''',
'''    /** Top-level browse menu. Labels are presentation only; keys are passed back to category(). */
    suspend fun genreMenu(): AppResult<List<SourceBrowseEntry>> = AppResult.Success(
        descriptor.categories.map { category -> SourceBrowseEntry(key = category, label = category) },
    )
''')

# vBook adapter: preserve every dynamic action identity, including duplicate labels.
path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookStorySource.kt'
replace(path,
'''import vn.nghetruyen.app.sources.SourceCommentCapability
import vn.nghetruyen.app.sources.SourceDescriptor
''',
'''import vn.nghetruyen.app.sources.SourceBrowseEntry
import vn.nghetruyen.app.sources.SourceCommentCapability
import vn.nghetruyen.app.sources.SourceDescriptor
''')
replace(path,
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
''',
'''    override suspend fun genreMenu(): AppResult<List<SourceBrowseEntry>> {
        if (plugin.script(VBookScriptRole.GENRE) == null) return AppResult.Success(emptyList())
        return when (val menu = executeDeclared(VBookScriptRole.GENRE, input = "")) {
            is AppResult.Failure -> menu
            is AppResult.Success -> AppResult.Success(
                VBookStoryNormalizer.dynamicActions(menu.value.data)
                    .asSequence()
                    .mapNotNull { action ->
                        val label = action.title.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                        SourceBrowseEntry(
                            key = genreActionKey(action),
                            label = label,
                            selectable = action.input.isNotBlank() || action.hasDataArgument || !action.type.isNullOrBlank(),
                        )
                    }
                    .distinctBy(SourceBrowseEntry::key)
                    .take(MAX_GENRE_MENU_ENTRIES)
                    .toList(),
            )
        }
    }
''')
replace(path,
'''        val actions = VBookStoryNormalizer.dynamicActions((menu as AppResult.Success).value.data)
        val action = actions.firstOrNull {
            it.title.equals(category, ignoreCase = true) || it.input == category
        } ?: return failure("VBOOK_CATEGORY_NOT_FOUND:$category")
''',
'''        val actions = VBookStoryNormalizer.dynamicActions((menu as AppResult.Success).value.data)
        val action = actions.firstOrNull { genreActionKey(it) == category }
            ?: actions.firstOrNull {
                it.title.equals(category, ignoreCase = true) || it.input == category
            }
            ?: return failure("VBOOK_CATEGORY_NOT_FOUND:$category")
''')
replace(path,
'''    private fun chooseListAction(actions: List<VBookDynamicAction>): VBookDynamicAction? =
        actions.firstOrNull { it.type.equals("list", ignoreCase = true) } ?: actions.firstOrNull()
''',
'''    private fun chooseListAction(actions: List<VBookDynamicAction>): VBookDynamicAction? =
        actions.firstOrNull { it.type.equals("list", ignoreCase = true) } ?: actions.firstOrNull()

    private fun genreActionKey(action: VBookDynamicAction): String {
        val identity = listOf(
            action.title,
            action.input,
            action.scriptPath,
            action.data,
            action.hasDataArgument.toString(),
            action.type.orEmpty(),
        ).joinToString("\u0000")
        return VBOOK_GENRE_ACTION_PREFIX + VBookStoryNormalizer.stableId(identity)
    }
''')
replace(path,
'''        private const val COMMENT_CURSOR_PREFIX = "vbook-comment:"
''',
'''        private const val COMMENT_CURSOR_PREFIX = "vbook-comment:"
        private const val VBOOK_GENRE_ACTION_PREFIX = "vbook-genre-action:"
''')

# ViewModel: keep opaque routing key separate from human label.
path = 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt'
replace(path,
'''import vn.nghetruyen.app.sources.SourceCheckReport
import vn.nghetruyen.app.sources.SourceDescriptor
''',
'''import vn.nghetruyen.app.sources.SourceBrowseEntry
import vn.nghetruyen.app.sources.SourceCheckReport
import vn.nghetruyen.app.sources.SourceDescriptor
''')
replace(path,
'''    val categories: List<String> = emptyList(),
    val genreEntries: List<String> = emptyList(),
''',
'''    val categories: List<String> = emptyList(),
    val genreEntries: List<SourceBrowseEntry> = emptyList(),
''')
replace(path,
'''    val exploreMode: ExploreMode = ExploreMode.HOME,
    val activeCategory: String? = null,
''',
'''    val exploreMode: ExploreMode = ExploreMode.HOME,
    val activeCategory: String? = null,
    val activeCategoryLabel: String? = null,
''')
replace(path,
'''                searchAllSources = false,
                query = if (matched == null) clean else "",
                stories = emptyList(),
                explorePage = 1,
                activeCategory = null,
''',
'''                searchAllSources = false,
                query = if (matched == null && !canBrowseDynamicGenre) clean else "",
                stories = emptyList(),
                explorePage = 1,
                activeCategory = null,
                activeCategoryLabel = null,
                genreEntries = emptyList(),
''')
replace(path,
'''            if (matched != null) browseCategory(matched)
            else if (canBrowseDynamicGenre) browseCategory(clean)
''',
'''            if (matched != null) browseCategory(matched, matched)
            else if (canBrowseDynamicGenre) browseCategory(clean, clean)
''')
replace(path,
'''            if (!source.descriptor.supportsGenre) {
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
''',
'''            if (!source.descriptor.supportsGenre) {
                mutableState.update {
                    it.copy(
                        exploreMode = ExploreMode.GENRE,
                        genreEntries = source.descriptor.categories.map { category ->
                            SourceBrowseEntry(key = category, label = category)
                        },
                        stories = emptyList(),
                        canLoadMoreStories = false,
                        loading = false,
                    )
                }
                return@launch
            }
''')
replace(path,
'''                    exploreMode = ExploreMode.GENRE,
                    activeCategory = null,
                    genreEntries = emptyList(),
''',
'''                    exploreMode = ExploreMode.GENRE,
                    activeCategory = null,
                    activeCategoryLabel = null,
                    genreEntries = emptyList(),
''')
# select source category state
replace(path,
'''                    exploreMode = ExploreMode.HOME,
                    activeCategory = null,
                    canLoadMoreStories = false,
''',
'''                    exploreMode = ExploreMode.HOME,
                    activeCategory = null,
                    activeCategoryLabel = null,
                    canLoadMoreStories = false,
''')
# Search-all state clears dynamic menu and label.
replace(path,
'''                searchAllSources = enabled,
                activeCategory = null,
                stories = emptyList(),
''',
'''                searchAllSources = enabled,
                activeCategory = null,
                activeCategoryLabel = null,
                genreEntries = emptyList(),
                stories = emptyList(),
''')
replace(path,
'''                ExploreMode.GENRE -> browseGenreMenu()
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let(::browseCategory)
''',
'''                ExploreMode.GENRE -> browseGenreMenu()
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let { key ->
                    browseCategory(key, snapshot.activeCategoryLabel ?: key)
                }
''')
replace(path,
'''                    activeCategory = null,
                    genreEntries = emptyList(),
                    canLoadMoreStories = false,
''',
'''                    activeCategory = null,
                    activeCategoryLabel = null,
                    genreEntries = emptyList(),
                    canLoadMoreStories = false,
''')
replace(path,
'''    fun browseCategory(category: String) {
''',
'''    fun browseCategory(category: String, displayName: String = category) {
''')
replace(path,
'''                    exploreMode = ExploreMode.CATEGORY,
                    activeCategory = category,
                    genreEntries = emptyList(),
''',
'''                    exploreMode = ExploreMode.CATEGORY,
                    activeCategory = category,
                    activeCategoryLabel = displayName,
                    query = "",
                    genreEntries = emptyList(),
''')

# Explore UI: route duplicate labels by opaque key; headings remain visible and disabled.
path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt'
replace(path,
'''    onGenreSelected: () -> Unit,
    onCategorySelected: (String) -> Unit,
''',
'''    onGenreSelected: () -> Unit,
    onGenreEntrySelected: (String, String) -> Unit,
    onCategorySelected: (String) -> Unit,
''')
replace(path,
'''        ExploreMode.CATEGORY -> state.activeCategory.orEmpty().ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()
''',
'''        ExploreMode.CATEGORY -> state.activeCategoryLabel.orEmpty()
            .ifBlank { state.activeCategory.orEmpty() }
            .ifBlank { "DANH SÁCH TRUYỆN" }
            .uppercase()
''')
replace(path,
'''    LaunchedEffect(state.exploreMode, state.activeCategory, state.stories.size, state.loading) {
        if (!state.loading) {
            delay(120)
            view.announceForAccessibility("$listTitle, ${state.stories.size} truyện")
        }
    }
''',
'''    LaunchedEffect(state.exploreMode, state.activeCategory, state.genreEntries.size, state.stories.size, state.loading) {
        if (!state.loading) {
            delay(120)
            val count = if (state.exploreMode == ExploreMode.GENRE) state.genreEntries.size else state.stories.size
            val unit = if (state.exploreMode == ExploreMode.GENRE) "mục" else "truyện"
            view.announceForAccessibility("$listTitle, $count $unit")
        }
    }
''')
replace(path,
'''                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
''',
'''                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.genreEntries, key = { it.key }) { entry ->
                        if (entry.selectable) {
                            ReferenceActionButton(
                                text = entry.label,
                                onClick = { onGenreEntrySelected(entry.key, entry.label) },
                                accessibilityLabel = entry.label,
                                normalColor = ReferencePanelBackground,
                                normalContentColor = ReferenceText,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        } else {
                            Text(
                                text = entry.label,
                                color = ReferenceSecondaryText,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
''')

# Navigation: diagnostics use the human label while ViewModel receives both label and opaque key.
path = 'app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt'
replace(path,
'''                        onGenreSelected = {
                            activateExploreDiagnosticContext(mode = ExploreMode.GENRE)
                            viewModel.browseGenreMenu()
                        },
                        onCategorySelected = { category ->
''',
'''                        onGenreSelected = {
                            activateExploreDiagnosticContext(mode = ExploreMode.GENRE)
                            viewModel.browseGenreMenu()
                        },
                        onGenreEntrySelected = { key, label ->
                            activateExploreDiagnosticContext(
                                mode = ExploreMode.CATEGORY,
                                category = label,
                            )
                            viewModel.browseCategory(key, label)
                        },
                        onCategorySelected = { category ->
''')

# Strengthen regression test for duplicate labels / private routing keys.
path = 'app/src/test/java/vn/nghetruyen/app/sourceplatform/VBookBrowsePresentationStaticTest.kt'
replace(path,
'''        assertTrue("StorySource must expose a generic genre menu API", "suspend fun genreMenu()" in storySource)
        assertTrue("vBook descriptor must derive genre support from plugin.json", "supportsGenre = plugin.script(VBookScriptRole.GENRE) != null" in vbook)
        assertTrue("vBook genre.js output must become dynamic menu actions", "VBookStoryNormalizer.dynamicActions(menu.value.data)" in vbook)
''',
'''        assertTrue("StorySource must expose a generic genre menu API", "suspend fun genreMenu()" in storySource)
        assertTrue("browse entries must separate routing identity from labels", "data class SourceBrowseEntry(" in storySource)
        assertTrue("vBook descriptor must derive genre support from plugin.json", "supportsGenre = plugin.script(VBookScriptRole.GENRE) != null" in vbook)
        assertTrue("vBook genre.js output must become dynamic menu actions", "VBookStoryNormalizer.dynamicActions(menu.value.data)" in vbook)
        assertTrue("duplicate labels must route by stable action identity", "genreActionKey(it) == category" in vbook)
''')
replace(path,
'''        assertTrue("Dynamic genre menu must be vertically scalable", "items(state.genreEntries" in explore)
        assertTrue("App navigation must route the genre tab", "viewModel.browseGenreMenu()" in app)
''',
'''        assertTrue("Dynamic genre menu must be vertically scalable", "items(state.genreEntries, key = { it.key })" in explore)
        assertTrue("non-action headings must stay visible but disabled", "if (entry.selectable)" in explore)
        assertTrue("App navigation must route the genre tab", "viewModel.browseGenreMenu()" in app)
        assertTrue("dynamic entries must route key and label separately", "viewModel.browseCategory(key, label)" in app)
''')

print('keyed vBook genre-entry refinement staged')
