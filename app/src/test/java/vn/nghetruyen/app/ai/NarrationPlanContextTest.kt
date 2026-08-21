package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationPlanContextTest {
    @Test
    fun incomingAmbienceLayersAreEncodedThroughLegacyPromptField() {
        val context = NarrationPlanContext(
            incomingAmbienceId = "legacy",
            incomingAmbienceIds = listOf("rain", "forest", "ignored-third", "rain"),
        )

        assertEquals(listOf("rain", "forest", "ignored-third", "rain"), context.incomingAmbienceIds)
        assertEquals("rain|forest|ignored-third", context.incomingAmbienceId)
    }
}
