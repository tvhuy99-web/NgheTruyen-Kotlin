package vn.nghetruyen.app.sourceplatform

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
