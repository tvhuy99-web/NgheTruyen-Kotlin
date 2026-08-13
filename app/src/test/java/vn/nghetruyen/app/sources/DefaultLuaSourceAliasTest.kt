package vn.nghetruyen.app.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary

class DefaultLuaSourceAliasTest {
    @Test
    fun exactNativeSourceUsesStableDatabaseIdAndOutranksLegacyAdapter() = runBlocking {
        val legacy = FakeSource("truyenfull", priority = 100)
        val native = FakeSource("vn.nghetruyen.native.truyenfull-native", priority = 50)
            .withStableDefaultLuaId()
        val registry = SourceRegistry(
            sources = listOf(legacy),
            sourcePackSources = listOf(native),
        )

        val selected = requireNotNull(registry.get("truyenfull"))
        assertEquals(250, selected.selectionPriority)
        assertEquals("truyenfull", selected.descriptor.id)

        val search = selected.search("x") as AppResult.Success
        assertEquals("truyenfull", search.value.single().sourceId)

        val detail = selected.story("https://example.com/story") as AppResult.Success
        assertEquals("truyenfull", detail.value.story.sourceId)
    }

    @Test
    fun unrelatedExternalSourceIsNotRewritten() {
        val source = FakeSource("third.party.source", priority = 150)
        assertSame(source, source.withStableDefaultLuaId())
    }

    private class FakeSource(
        id: String,
        private val priority: Int,
    ) : StorySource {
        override val descriptor = SourceDescriptor(
            id = id,
            displayName = id,
            baseUrl = "https://example.com",
            health = SourceHealth.READY,
            implementationKind = SourceImplementationKind.NATIVE_LUA,
        )
        override val selectionPriority: Int = priority

        private fun summary() = StorySummary(
            id = "story",
            sourceId = descriptor.id,
            title = "Story",
            url = "https://example.com/story",
        )

        override suspend fun search(query: String, page: Int) = AppResult.Success(listOf(summary()))
        override suspend fun category(category: String, page: Int) = AppResult.Success(listOf(summary()))
        override suspend fun story(url: String) = AppResult.Success(StoryDetail(story = summary()))
        override suspend fun chapter(url: String) = AppResult.Success(
            ChapterContent(
                chapter = ChapterSummary("chapter", "story", 0, "Chapter", url),
                paragraphs = listOf("text"),
            ),
        )
    }
}
