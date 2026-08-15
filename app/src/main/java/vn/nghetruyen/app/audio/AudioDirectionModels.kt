package vn.nghetruyen.app.audio

import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

enum class AudioAssetKind {
    MUSIC,
    AMBIENCE,
    SFX,
}

data class AudioDirectionAsset(
    val id: String,
    val title: String,
    val uri: String,
    val description: String,
    val volume: Float,
    val normalizationGainDb: Float,
    val kind: AudioAssetKind,
)

data class AmbienceScene(
    val startUnitId: String,
    val endUnitId: String,
    val ambienceId: String,
)

data class SoundEffectCue(
    val unitId: String,
    val effectId: String,
)

data class AmbienceSfxPlan(
    val ambienceScenes: List<AmbienceScene> = emptyList(),
    val soundEffectCues: List<SoundEffectCue> = emptyList(),
)

/**
 * Existing scene-music rows are intentionally reused as the physical asset library. This preserves
 * URI permissions, per-file volume and loudness-normalization metadata without a Room migration.
 *
 * Classification is metadata-only:
 * - MUSIC: default, or type:music / [music]
 * - AMBIENCE: type:ambience / type:environment / [ambience]
 * - SFX: type:sfx / type:sound_effect / [sfx]
 *
 * The explicit markers keep ordinary words such as "rain" or "battle" as descriptive tags rather
 * than accidentally changing the asset type.
 */
object AudioAssetClassifier {
    fun classify(track: SceneMusicTrackEntity): AudioAssetKind {
        val metadata = "${track.title}\n${track.tagsCsv}".lowercase()
        return when {
            hasAny(metadata, "type:sfx", "type=sfx", "type:sound_effect", "type=sound-effect", "[sfx]") -> AudioAssetKind.SFX
            hasAny(metadata, "type:ambience", "type=ambience", "type:environment", "type=environment", "[ambience]", "[environment]") -> AudioAssetKind.AMBIENCE
            else -> AudioAssetKind.MUSIC
        }
    }

    fun toAsset(track: SceneMusicTrackEntity): AudioDirectionAsset = AudioDirectionAsset(
        id = track.id.trim(),
        title = track.title.trim(),
        uri = track.uri,
        description = track.tagsCsv.trim(),
        volume = track.volume.coerceIn(0f, 1f),
        normalizationGainDb = track.normalizationGainDb,
        kind = classify(track),
    )

    private fun hasAny(value: String, vararg markers: String): Boolean = markers.any(value::contains)
}
