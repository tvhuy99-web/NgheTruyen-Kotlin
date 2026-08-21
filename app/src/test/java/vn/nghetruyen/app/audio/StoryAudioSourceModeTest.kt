package vn.nghetruyen.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryAudioSourceModeTest {
    @Test
    fun manualPlaylistBelongsOnlyToModeOne() {
        assertTrue(StoryAudioModeRouter.allowsManualPlaylist(StoryAudioSourceMode.LOCAL_MANUAL))
        assertFalse(StoryAudioModeRouter.allowsManualPlaylist(StoryAudioSourceMode.AI_LOCAL))
        assertFalse(StoryAudioModeRouter.allowsManualPlaylist(StoryAudioSourceMode.AI_FREESOUND))
    }

    @Test
    fun localCatalogAndFreesoundContractsCannotMix() {
        assertTrue(
            StoryAudioModeRouter.isValidAiAudioContract(
                StoryAudioSourceMode.LOCAL_MANUAL,
                hasLocalAudioCatalog = false,
                requestsFreesoundRequirements = false,
            ),
        )
        assertTrue(
            StoryAudioModeRouter.isValidAiAudioContract(
                StoryAudioSourceMode.AI_LOCAL,
                hasLocalAudioCatalog = true,
                requestsFreesoundRequirements = false,
            ),
        )
        assertTrue(
            StoryAudioModeRouter.isValidAiAudioContract(
                StoryAudioSourceMode.AI_FREESOUND,
                hasLocalAudioCatalog = false,
                requestsFreesoundRequirements = true,
            ),
        )
        assertFalse(
            StoryAudioModeRouter.isValidAiAudioContract(
                StoryAudioSourceMode.AI_FREESOUND,
                hasLocalAudioCatalog = true,
                requestsFreesoundRequirements = true,
            ),
        )
        assertFalse(
            StoryAudioModeRouter.isValidAiAudioContract(
                StoryAudioSourceMode.AI_LOCAL,
                hasLocalAudioCatalog = true,
                requestsFreesoundRequirements = true,
            ),
        )
    }
}
