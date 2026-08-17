package vn.nghetruyen.app.playback

import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

/**
 * XPK scene-music authority boundary.
 *
 * When a scene plan provides a track id, runtime either plays that exact enabled track or plays no
 * replacement. Playlist order, shuffle, mood tags and repeat-avoidance must never override AI's
 * selected track. Intentional silence is represented by a zero-volume sentinel copied from an
 * existing catalog row; [SceneMusicController] recognizes the sentinel id and stops music without
 * opening its URI. This keeps the existing service call site from misclassifying NONE as a missing
 * track while keeping silence out of the persisted audio library.
 *
 * The extra parameters remain only for source compatibility with the existing player call site until
 * milestone 5 removes the paragraph-era API.
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
        if (target == XpkSceneMusicParity.SILENCE_TRACK_ID) {
            return tracks.firstOrNull()?.copy(
                id = XpkSceneMusicParity.SILENCE_TRACK_ID,
                volume = 0f,
                enabled = true,
            )
        }
        return tracks.firstOrNull { it.enabled && it.id == target }
    }
}
