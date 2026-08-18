package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class VBookBrowsePresentationStaticTest {
    @Test
    fun dynamicGenreNavigationAndPrivateSourceIdsStayWired() {
        val root = repositoryRoot()
        val storySource = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt"))
        val vbook = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookStorySource.kt"))
        val viewModel = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"))
        val explore = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt"))
        val common = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt"))
        val app = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt"))
        val legacyApp = readUtf8(root.resolve("app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"))

        assertTrue("StorySource must expose a generic dynamic genre capability", "val supportsGenre: Boolean" in storySource)
        assertTrue("StorySource must expose a generic genre menu API", "suspend fun genreMenu()" in storySource)
        assertTrue("browse entries must separate routing identity from labels", "data class SourceBrowseEntry(" in storySource)
        assertTrue("vBook descriptor must derive genre support from plugin.json", "supportsGenre = plugin.script(VBookScriptRole.GENRE) != null" in vbook)
        assertTrue("vBook genre.js output must become dynamic menu actions", "VBookStoryNormalizer.dynamicActions(menu.value.data)" in vbook)
        assertTrue("duplicate labels must route by stable action identity", "genreActionKey(it) == category" in vbook)
        assertTrue("ViewModel must own an explicit GENRE explore state", "ExploreMode { HOME, GENRE, SEARCH, CATEGORY }" in viewModel)
        assertTrue("ViewModel must preserve a human label beside the opaque category key", "val activeCategoryLabel: String?" in viewModel)
        assertTrue("UI must expose the dynamic genre surface", "text = \"THỂ LOẠI\"" in explore)
        assertTrue("Dynamic genre menu must be vertically scalable", "items(state.genreEntries, key = { it.key })" in explore)
        assertTrue("non-action headings must stay visible but disabled", "if (entry.selectable)" in explore)
        assertTrue("App navigation must route the genre tab", "viewModel.browseGenreMenu()" in app)
        assertTrue("dynamic entries must route key and label separately", "viewModel.browseGenreEntry(key, label)" in app)
        assertTrue("legacy app shell must wire the dynamic genre menu too", "onGenreEntrySelected = viewModel::browseGenreEntry" in legacyApp)

        assertFalse("Story cards must not speak raw source ids", "Nguồn: \${story.sourceId}" in common)
        assertFalse("Story cards must not render raw source ids", "Text(story.sourceId" in common)
    }

    private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOfNotNull(working, working.parent).firstOrNull {
            Files.isDirectory(it.resolve("app")) && Files.isDirectory(it.resolve("source-vbook"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
