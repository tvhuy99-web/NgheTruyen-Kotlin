package vn.nghetruyen.app.sources

import kotlin.coroutines.Continuation
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticStorySourceGenreContractTest {
    @Test
    fun diagnosticWrapperExplicitlyOverridesGenreMenu() {
        val method = DiagnosticStorySource::class.java.getDeclaredMethod(
            "genreMenu",
            Continuation::class.java,
        )

        assertEquals(DiagnosticStorySource::class.java, method.declaringClass)
    }
}
