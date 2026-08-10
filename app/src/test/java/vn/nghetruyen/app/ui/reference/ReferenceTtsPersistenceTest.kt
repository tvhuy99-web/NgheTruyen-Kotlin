package vn.nghetruyen.app.ui.reference

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceTtsPersistenceTest {
    @Test
    fun systemPreviewRoutesSharedControlsToAndroidTts() {
        val preview = ReferenceTtsPersistence.previewDraft(
            ReferenceTtsDraft(
                processingMethod = "system",
                speed = 1.6f,
                pitch = 1.25f,
            ),
        )

        assertEquals(1.6f, preview.rate, 0.0001f)
        assertEquals(1.25f, preview.pitch, 0.0001f)
        assertEquals(1f, preview.sonicSpeed, 0.0001f)
        assertEquals(1f, preview.sonicPitch, 0.0001f)
    }

    @Test
    fun sonicPreviewRoutesSharedControlsToSonicOnly() {
        val preview = ReferenceTtsPersistence.previewDraft(
            ReferenceTtsDraft(
                processingMethod = "sonic",
                sonicAccurate = true,
                speed = 1.6f,
                pitch = 1.25f,
            ),
        )

        assertEquals(1f, preview.rate, 0.0001f)
        assertEquals(1f, preview.pitch, 0.0001f)
        assertEquals(1.6f, preview.sonicSpeed, 0.0001f)
        assertEquals(1.25f, preview.sonicPitch, 0.0001f)
        assertEquals("sonic", preview.processingMethod)
        assertEquals(true, preview.sonicAccurate)
    }
}
