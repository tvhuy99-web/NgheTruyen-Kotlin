package vn.nghetruyen.app.sources

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStorySourceGenreContractTest {
    @Test
    fun diagnosticWrapperCoversEveryStorySourceApi() {
        val sourceApi = StorySource::class.java.declaredMethods
            .asSequence()
            .map { it.name }
            .filterNot { it.endsWith("\$default") }
            .toSet()
        val diagnosticApi = DiagnosticStorySource::class.java.declaredMethods
            .asSequence()
            .map { it.name }
            .toSet()

        val missing = sourceApi - diagnosticApi
        assertTrue("DiagnosticStorySource is missing StorySource methods: $missing", missing.isEmpty())
    }
}
