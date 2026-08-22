package vn.nghetruyen.app.sources

import kotlin.coroutines.Continuation
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStorySourceGenreContractTest {
    @Test
    fun allStorySourceWrappersCoverEverySuspendOperation() {
        val sourceOperations = StorySource::class.java.declaredMethods
            .asSequence()
            .filter { method -> method.parameterTypes.lastOrNull() == Continuation::class.java }
            .map { it.name }
            .toSet()

        val wrappers = listOf(
            DiagnosticStorySource::class.java,
            Class.forName("vn.nghetruyen.app.sources.StableDefaultLuaSourceAlias"),
            Class.forName("vn.nghetruyen.app.sources.DeferredStartupStorySource"),
            Class.forName("vn.nghetruyen.app.sources.IoBoundVBookStorySource"),
            Class.forName("vn.nghetruyen.app.sources.ChapterCatalogSafetyStorySource"),
        )

        wrappers.forEach { wrapper ->
            val wrapperOperations = wrapper.declaredMethods
                .asSequence()
                .filter { method -> method.parameterTypes.lastOrNull() == Continuation::class.java }
                .map { it.name }
                .toSet()
            val missing = sourceOperations - wrapperOperations
            assertTrue("${wrapper.name} is missing StorySource operations: $missing", missing.isEmpty())
        }
    }
}
