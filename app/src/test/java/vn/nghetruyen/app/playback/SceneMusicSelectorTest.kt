package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class SceneMusicSelectorTest {
    private val tracks = listOf(
        track("calm", "Bình yên", "calm,ấm áp", 0, 1, 10),
        track("battle", "Chiến đấu", "căng thẳng,nguy hiểm", 1, 5, 20),
        track("sad", "Buồn", "buồn,tuyệt vọng", 2, 0, 0),
    )

    @Test
    fun sequentialUsesStableOrder() {
        val selected = SceneMusicSelector.select(
            tracks, null, "", SceneMusicPlaybackMode.SEQUENTIAL, emptyList(), "seed",
        )
        assertEquals("calm", selected?.id)
    }

    @Test
    fun smartModeMatchesMoodAndAvoidsRecentTrack() {
        val selected = SceneMusicSelector.select(
            tracks, null, "buồn tuyệt vọng", SceneMusicPlaybackMode.SMART_AVOID_REPEAT,
            recentTrackIds = listOf("battle"), seed = "chapter-1",
        )
        assertEquals("sad", selected?.id)
        assertNotEquals("battle", selected?.id)
    }

    private fun track(id: String, title: String, tags: String, order: Int, plays: Int, last: Long) =
        SceneMusicTrackEntity(
            id = id, title = title, uri = "content://$id", tagsCsv = tags,
            volume = 1f, enabled = true, loudnessLufsEstimate = -18f,
            playCount = plays, lastPlayedAt = last, orderIndex = order, updatedAt = 0,
        )
}
