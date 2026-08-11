package vn.nghetruyen.app.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import java.util.concurrent.atomic.AtomicReference

class SourceRegistryTest {
    @Test
    fun builtInAdapterOutranksCompatibilityPackWithSameStableId() {
        val builtIn = FakeSource("same-source", 100, SourceImplementationKind.BUILT_IN)
        val compatibilityPack = FakeSource("same-source", 50, SourceImplementationKind.SOURCE_PACK)

        val registry = SourceRegistry(sources = listOf(builtIn), sourcePackSources = listOf(compatibilityPack))

        assertSame(builtIn, registry.get("same-source"))
    }

    @Test
    fun explicitFullParityPackCanReplaceBuiltInAdapter() {
        val builtIn = FakeSource("same-source", 100, SourceImplementationKind.BUILT_IN)
        val fullParityPack = FakeSource("same-source", 200, SourceImplementationKind.SOURCE_PACK)

        val registry = SourceRegistry(sources = listOf(builtIn), sourcePackSources = listOf(fullParityPack))

        assertSame(fullParityPack, registry.get("same-source"))
    }

    @Test
    fun vBookSourceExecutionLeavesCallerThreadBeforeBlockingRuntimeWork() = runBlocking {
        val callerThread = Thread.currentThread()
        val executionThread = AtomicReference<Thread>()
        val vBook = FakeSource(
            id = "vbook-source",
            selectionPriority = 120,
            implementationKind = SourceImplementationKind.VBOOK,
            onSearch = {
                executionThread.set(Thread.currentThread())
                check(Thread.currentThread() !== callerThread) { "NetworkOnMainThreadException" }
            },
        )
        val registry = SourceRegistry(sources = emptyList(), sourcePackSources = listOf(vBook))

        val result = requireNotNull(registry.get("vbook-source")).search("needle")

        assertTrue(result is AppResult.Success)
        assertTrue(executionThread.get() !== callerThread)
    }

    @Test
    fun readyDegradedAndPendingSourcesAreReportedExplicitly() = runBlocking {
        val registry = SourceRegistry()
        val demo = requireNotNull(registry.get("vn.nghetruyen.sources.demo"))
        val demoResult = demo.search("gió")
        assertTrue(demoResult is AppResult.Success)

        val ported = requireNotNull(registry.get("truyenfull"))
        assertEquals(SourceHealth.READY, ported.descriptor.health)

        val degraded = requireNotNull(registry.get("truyencv"))
        assertEquals(SourceHealth.DEGRADED, degraded.descriptor.health)
        val truyenCom = requireNotNull(registry.get("truyencom"))
        assertEquals(SourceHealth.DEGRADED, truyenCom.descriptor.health)
        val truyenYy = requireNotNull(registry.get("truyenyy"))
        assertEquals(SourceHealth.DEGRADED, truyenYy.descriptor.health)

        val wikidich = requireNotNull(registry.get("wikidich"))
        assertEquals(SourceHealth.DEGRADED, wikidich.descriptor.health)
        val sangTacViet = requireNotNull(registry.get("sangtacviet"))
        assertEquals(SourceHealth.DEGRADED, sangTacViet.descriptor.health)
        assertTrue(sangTacViet.descriptor.loginUrl != null)

        val pending = requireNotNull(registry.get("wattpad"))
        assertEquals(SourceHealth.NOT_PORTED, pending.descriptor.health)
        val pendingResult = pending.search("kiếm hiệp")
        assertTrue(pendingResult is AppResult.Failure)
        assertEquals("SOURCE_NOT_PORTED", (pendingResult as AppResult.Failure).code)
    }

    private class FakeSource(
        id: String,
        override val selectionPriority: Int,
        implementationKind: SourceImplementationKind,
        private val onSearch: () -> Unit = {},
    ) : StorySource {
        override val descriptor = SourceDescriptor(
            id = id,
            displayName = id,
            baseUrl = "https://example.invalid",
            health = SourceHealth.READY,
            implementationKind = implementationKind,
        )

        override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> {
            onSearch()
            return AppResult.Success(emptyList())
        }

        override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = AppResult.Success(emptyList())
        override suspend fun story(url: String): AppResult<StoryDetail> = AppResult.Failure("NOT_IMPLEMENTED", "test")
        override suspend fun chapter(url: String): AppResult<ChapterContent> = AppResult.Failure("NOT_IMPLEMENTED", "test")
    }
}
