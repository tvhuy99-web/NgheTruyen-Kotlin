package vn.nghetruyen.app.playback

import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity









object SceneMusicSelector {
    @Suppress("UNUSED_PARAMETER")
    fun select(
        tracks: Collection<SceneMusicTrackEntity>,
        requestedTrackId: String?,
        mood: String,
        mode: SceneMusicPlaybackMode,
        recentTrackIds: Collection<String>,
        seed: String,
    ): SceneMusicTrackEntity? {
        val target = requestedTrackId.orEmpty().trim()
        if (target.isBlank()) return null
        return tracks.firstOrNull { it.enabled && it.id == target }
    }
}
