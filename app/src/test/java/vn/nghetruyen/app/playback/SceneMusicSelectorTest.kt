package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class SceneMusicSelectorTest {
    private val tracks = listOf(
        track("calm", "Bình yên", "Sắc thái: ấm áp", 0, 1, 10),
        track("battle", "Chiến đấu", "Sắc thái: căng thẳng", 1, 5, 20),
        track("sad", "Buồn", "Sắc thái: tuyệt vọng", 2, 0, 0),
    )

    @Test
    fun aiRequestedTrackWinsEvenWhenSmartModeWouldAvoidIt() {
        val selected = SceneMusicSelector.select(
            tracks = tracks,
            requestedTrackId = "battle",
            mood = "buồn tuyệt vọng",
            mode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT,
            recentTrackIds = listOf("battle"),
            seed = "chapter-1",
        )
        assertEquals("battle", selected?.id)
    }

    @Test
    fun intentionalSilenceReturnsControllerSentinelInsteadOfMissingTrack() {
        val selected = SceneMusicSelector.select(
            tracks = tracks,
            requestedTrackId = XpkSceneMusicParity.SILENCE_TRACK_ID,
            mood = "",
            mode = SceneMusicPlaybackMode.SEQUENTIAL,
            recentTrackIds = emptyList(),
            seed = "chapter-1",
        )
        assertNotNull(selected)
        assertEquals(XpkSceneMusicParity.SILENCE_TRACK_ID, selected?.id)
        assertEquals(0f, selected?.volume)
        assertEquals(true, selected?.enabled)
    }

    @Test
    fun missingAiTrackDoesNotGetPlaylistReplacement() {
        val selected = SceneMusicSelector.select(
            tracks = tracks,
            requestedTrackId = "missing",
            mood = "buồn tuyệt vọng",
            mode = SceneMusicPlaybackMode.SEQUENTIAL,
            recentTrackIds = emptyList(),
            seed = "chapter-1",
        )
        assertNull(selected)
    }

    private fun track(id: String, title: String, description: String, order: Int, plays: Int, last: Long) =
        SceneMusicTrackEntity(
            id = id,
            title = title,
            uri = "content://$id",
            tagsCsv = description,
            volume = 1f,
            enabled = true,
            loudnessLufsEstimate = -18f,
            playCount = plays,
            lastPlayedAt = last,
            orderIndex = order,
            updatedAt = 0,
        )
}
