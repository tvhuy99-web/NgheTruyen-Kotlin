package vn.nghetruyen.app.playback

import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import java.util.Locale
import kotlin.random.Random

/** Deterministic playlist selection with tag matching and repeat avoidance. */
object SceneMusicSelector {
    fun select(
        tracks: Collection<SceneMusicTrackEntity>,
        requestedTrackId: String?,
        mood: String,
        mode: SceneMusicPlaybackMode,
        recentTrackIds: Collection<String>,
        seed: String,
    ): SceneMusicTrackEntity? {
        val enabled = tracks.filter(SceneMusicTrackEntity::enabled)
        if (enabled.isEmpty()) return null
        val recent = recentTrackIds.toSet()
        val requested = requestedTrackId?.let { id -> enabled.firstOrNull { it.id == id } }
        if (requested != null && (mode != SceneMusicPlaybackMode.SMART_AVOID_REPEAT || requested.id !in recent || enabled.size == 1)) {
            return requested
        }
        return when (mode) {
            SceneMusicPlaybackMode.SEQUENTIAL -> {
                val ordered = enabled.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase(Locale.ROOT) })
                val requestedIndex = requested?.let(ordered::indexOf) ?: -1
                ordered[(requestedIndex + 1) % ordered.size]
            }
            SceneMusicPlaybackMode.SHUFFLE -> enabled.shuffled(Random(seed.hashCode())).firstOrNull { it.id !in recent }
                ?: enabled.shuffled(Random(seed.hashCode())).first()
            SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> {
                val moodTokens = mood.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 2 }.toSet()
                enabled.maxWithOrNull(
                    compareBy<SceneMusicTrackEntity> { track ->
                        val tags = track.tagsCsv.lowercase(Locale.ROOT).split(',').map(String::trim).toSet()
                        tags.count(moodTokens::contains) * 100
                    }.thenBy { if (it.id in recent) -1 else 1 }
                        .thenBy { -it.playCount }
                        .thenBy { -it.lastPlayedAt },
                )
            }
        }
    }
}
