package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundMode3RegressionTest {
    @Test
    fun resolveResultCarriesRetryableFailureWithoutPretendingSilenceIsFinal() {
        val result = FreesoundAutoResolveResult(
            resolved = emptyList(),
            warnings = listOf("network"),
            importedTrackIds = emptySet(),
            retryableFailure = true,
        )
        assertTrue(result.retryableFailure)
        assertEquals(0, result.resolvedCount)
    }

    @Test
    fun preferredPreviewUsesRealMp3ThenRealOgg() {
        val both = FreesoundSound(
            id = 1,
            name = "test",
            description = "",
            durationSeconds = 1.0,
            previewHqMp3 = "https://cdn.example/a.mp3",
            previewHqOgg = "https://cdn.example/a.ogg",
        )
        assertEquals("https://cdn.example/a.mp3", both.preferredPreviewUrl)

        val oggOnly = both.copy(previewHqMp3 = null)
        assertEquals("https://cdn.example/a.ogg", oggOnly.preferredPreviewUrl)
        assertFalse(FreesoundPreviewPlayer.previewCandidates(oggOnly.preferredPreviewUrl!!).isEmpty())
    }
}
