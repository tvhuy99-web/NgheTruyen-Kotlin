package vn.nghetruyen.app.playback

import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

/**
 * XPK scene-music authority boundary.
 *
 * When a scene plan provides a track id, runtime either plays that exact enabled track or plays no
 * replacement. Playlist order, shuffle, mood tags and repeat-avoidance must never override AI's
 * selected track. The extra parameters remain only for source compatibility with the existing player
 * call site until milestone 5 removes the paragraph-era API.
 */
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
