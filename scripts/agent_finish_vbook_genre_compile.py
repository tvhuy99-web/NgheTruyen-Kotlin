from pathlib import Path

ROOT = Path('.')


def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# Keep the existing one-argument browseCategory API stable. Dynamic menus get a separate keyed entry API.
path = 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt'
replace(path,
'''            if (matched != null) browseCategory(matched, matched)
            else if (canBrowseDynamicGenre) browseCategory(clean, clean)
''',
'''            if (matched != null) browseCategory(matched)
            else if (canBrowseDynamicGenre) browseCategory(clean)
''')
replace(path,
'''                ExploreMode.CATEGORY -> snapshot.activeCategory?.let { key ->
                    browseCategory(key, snapshot.activeCategoryLabel ?: key)
                }
''',
'''                ExploreMode.CATEGORY -> snapshot.activeCategory?.let { key ->
                    browseCategoryInternal(key, snapshot.activeCategoryLabel ?: key)
                }
''')
replace(path,
'''    fun browseCategory(category: String, displayName: String = category) {
        searchJob?.cancel()
''',
'''    fun browseCategory(category: String) {
        browseCategoryInternal(category, category)
    }

    fun browseGenreEntry(categoryKey: String, displayName: String) {
        browseCategoryInternal(categoryKey, displayName)
    }

    private fun browseCategoryInternal(category: String, displayName: String) {
        searchJob?.cancel()
''')
replace(path,
'''                            SourceUiSurface.EXPLORE -> when (state.value.exploreMode) {
                                ExploreMode.HOME -> browseHome()
                                ExploreMode.CATEGORY -> state.value.activeCategory?.let(::browseCategory) ?: browseHome()
                                ExploreMode.SEARCH -> search()
                            }
''',
'''                            SourceUiSurface.EXPLORE -> when (state.value.exploreMode) {
                                ExploreMode.HOME -> browseHome()
                                ExploreMode.GENRE -> browseGenreMenu()
                                ExploreMode.CATEGORY -> state.value.activeCategory?.let { key ->
                                    browseCategoryInternal(key, state.value.activeCategoryLabel ?: key)
                                } ?: browseHome()
                                ExploreMode.SEARCH -> search()
                            }
''')

# Host-kernel refresh must understand the new top-level browse mode.
path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/AppViewModelHostKernel.kt'
replace(path,
'''                        RootTab.EXPLORE -> when (current.exploreMode) {
                            ExploreMode.HOME -> host.browseHome()
                            ExploreMode.CATEGORY -> current.activeCategory?.let(host::browseCategory) ?: host.browseHome()
                            ExploreMode.SEARCH -> host.search()
                        }
''',
'''                        RootTab.EXPLORE -> when (current.exploreMode) {
                            ExploreMode.HOME -> host.browseHome()
                            ExploreMode.GENRE -> host.browseGenreMenu()
                            ExploreMode.CATEGORY -> current.activeCategory?.let(host::browseCategory) ?: host.browseHome()
                            ExploreMode.SEARCH -> host.search()
                        }
''')

# Reference navigation routes opaque dynamic keys without exposing them.
path = 'app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt'
replace(path,
'''                            viewModel.browseCategory(key, label)
''',
'''                            viewModel.browseGenreEntry(key, label)
''')

# The legacy app shell must pass the same new callbacks so both UI entry points remain compilable.
path = 'app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt'
replace(path,
'''                        onSourceSelected = viewModel::selectSource,
                        onHomeSelected = viewModel::browseHome,
                        onCategorySelected = viewModel::browseCategory,
''',
'''                        onSourceSelected = viewModel::selectSource,
                        onHomeSelected = viewModel::browseHome,
                        onGenreSelected = viewModel::browseGenreMenu,
                        onGenreEntrySelected = viewModel::browseGenreEntry,
                        onCategorySelected = viewModel::browseCategory,
''')

# Regression wording follows the dedicated keyed API.
path = 'app/src/test/java/vn/nghetruyen/app/sourceplatform/VBookBrowsePresentationStaticTest.kt'
replace(path,
'''        assertTrue("dynamic entries must route key and label separately", "viewModel.browseCategory(key, label)" in app)
''',
'''        assertTrue("dynamic entries must route key and label separately", "viewModel.browseGenreEntry(key, label)" in app)
''')

print('final vBook genre compile integration staged')
