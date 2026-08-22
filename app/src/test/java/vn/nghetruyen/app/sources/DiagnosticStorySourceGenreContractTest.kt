package vn.nghetruyen.app.sources

import kotlin.coroutines.Continuation
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStorySourceGenreContractTest {
    @Test
    fun manualStorySourceWrappersExplicitlyForwardGenreMenu() {
        val wrappers = listOf(
            DiagnosticStorySource::class.java,
            Class.forName("vn.nghetruyen.app.sources.StableDefaultLuaSourceAlias"),
            Class.forName("vn.nghetruyen.app.sources.DeferredStartupStorySource"),
            Class.forName("vn.nghetruyen.app.sources.IoBoundVBookStorySource"),
        )

        wrappers.forEach { wrapper ->
            val declaresGenreMenu = wrapper.declaredMethods.any { method ->
                method.name == "genreMenu" &&
                    method.parameterTypes.lastOrNull() == Continuation::class.java
            }
            assertTrue("${wrapper.name} must explicitly forward StorySource.genreMenu()", declaresGenreMenu)
        }
    }
}
