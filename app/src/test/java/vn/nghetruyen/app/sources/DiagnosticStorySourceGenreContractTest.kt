package vn.nghetruyen.app.sources

import kotlin.coroutines.Continuation
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStorySourceGenreContractTest {
    @Test
    fun manualStorySourceWrappersCoverEverySuspendOperation() {
        val sourceOperations = StorySource::class.java.declaredMethods
            .asSequence()
            .filter { method -> method.parameterTypes.lastOrNull() == Continuation::class.java }
            .map { it.name }
            .toSet()

        // Only wrappers that manually forward StorySource operations belong in this contract.
        // ChapterCatalogSafetyStorySource uses `StorySource by delegate`, so Kotlin generates the
        // forwarding methods and automatically tracks future interface additions.
        val wrappers = listOf(
            DiagnosticStorySource::class.java,
            Class.forName("vn.nghetruyen.app.sources.StableDefaultLuaSourceAlias"),
            Class.forName("vn.nghetruyen.app.sources.DeferredStartupStorySource"),
            Class.forName("vn.nghetruyen.app.sources.IoBoundVBookStorySource"),
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
