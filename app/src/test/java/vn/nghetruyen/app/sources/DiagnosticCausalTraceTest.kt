package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticCausalTraceTest {
    @Test
    fun causalTraceSurvivesDispatcherChange() = runBlocking {

        val observed = withContext(DiagnosticCausalTrace("story-open:test")) {
            withContext(Dispatchers.IO) { currentDiagnosticCausalTraceId() }
        }
        assertEquals("story-open:test", observed)
    }
}
