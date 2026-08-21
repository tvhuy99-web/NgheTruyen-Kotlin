package vn.nghetruyen.app.playback

import org.junit.Assert.assertFalse
import org.junit.Test

class NarrationAutomaticPlanPolicyTest {
    @Test
    fun automaticForegroundAndPrefetchNeverForceRegeneration() {
        assertFalse(NarrationAutomaticPlanPolicy.FORCE_REGENERATION)
    }
}
