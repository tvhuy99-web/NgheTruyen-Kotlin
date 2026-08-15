package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticCausalTraceTest {
    @Test
    fun causalTraceSurvivesDispatcherChange() = runBlocking {
        // Story-source execution crosses Dispatchers.IO; the causal trace must survive that hop.
        val observed = withContext(DiagnosticCausalTrace("story-open:test")) {
            withContext(Dispatchers.IO) { currentDiagnosticCausalTraceId() }
        }
        assertEquals("story-open:test", observed)
    }
}
