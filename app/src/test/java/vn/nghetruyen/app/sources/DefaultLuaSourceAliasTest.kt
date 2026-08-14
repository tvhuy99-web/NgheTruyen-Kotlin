package vn.nghetruyen.app.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun registryNormalizesRawBundledLuaSourceWithoutCallerHelp() = runBlocking {
        val legacy = FakeSource("sangtacviet", priority = 100)
        val nativeId = "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50"
        val native = FakeSource(nativeId, priority = 50)
        val registry = SourceRegistry(
            sources = listOf(legacy),
            sourcePackSources = listOf(native),
        )

        val selected = requireNotNull(registry.get("sangtacviet"))
        assertEquals(250, selected.selectionPriority)
        assertEquals("sangtacviet", selected.descriptor.id)
        assertNull(registry.get(nativeId))

        val search = selected.search("x") as AppResult.Success
        assertEquals("sangtacviet", search.value.single().sourceId)
        assertEquals(nativeId, search.value.single().title)
    }

    @Test
    fun uiStyleRefreshCannotRestoreLegacyAdapterOverBundledLua() = runBlocking {
        val legacy = FakeSource("sangtacviet", priority = 100)
        val nativeId = "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50"
        val native = FakeSource(nativeId, priority = 50)
        val registry = SourceRegistry(
            sources = listOf(legacy),
            sourcePackSources = listOf(native.withStableDefaultLuaId()),
        )

        // AppViewModel refreshSourcePlatformState() supplies raw activeStorySources(). The registry
        // must normalize that boundary again instead of allowing the Kotlin adapter to win back.
        registry.refreshSourcePacks(listOf(native))

        val selected = requireNotNull(registry.get("sangtacviet"))
        assertEquals(250, selected.selectionPriority)
        val search = selected.search("x") as AppResult.Success
        assertEquals(nativeId, search.value.single().title)
        assertEquals("sangtacviet", search.value.single().sourceId)
    }

    @Test
    fun allSevenDefaultPackageIdsArePinnedToStableUserDataIds() {
        assertEquals(
            mapOf(
                "vn.nghetruyen.native.truyenfull-native" to "truyenfull",
                "vn.nghetruyen.native.truyencv-io-default-native" to "truyencv",
                "vn.nghetruyen.native.truyencom-default-native" to "truyencom",
                "vn.nghetruyen.native.truyenyy-co-native" to "truyenyy",
                "vn.nghetruyen.native.wikidich-default-native-v9-complete-scroll" to "wikidich",
                "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50" to "sangtacviet",
                "vn.nghetruyen.vbook.wattpad-default-vbook" to "wattpad",
            ),
            DEFAULT_LUA_STABLE_IDS,
        )
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
            title = descriptor.id,
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
