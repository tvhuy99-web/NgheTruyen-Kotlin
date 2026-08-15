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

/**
 * One logical ambience interval. Two rows may overlap on the same UNIT range to represent the
 * PRIMARY + SECONDARY ambience layers. Keeping one id per row preserves the persisted/export format
 * while allowing the runtime and mixer to compose at most two compatible environmental layers.
 */
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

object AudioDirectionLimits {
    const val MAX_CONCURRENT_AMBIENCE = 2
    const val MIN_AMBIENCE_SCENE_UNITS = 2
}

/**
 * Groups explicitly numbered ambience variants such as forest_01/forest_02 or rain-v1/rain-v2.
 * Descriptive titles without a trailing variant number remain distinct, so unrelated assets are
 * never shuffled together merely because they share words such as "rain" or "forest".
 */
object AudioAssetVariantFamily {
    private val extension = Regex("(?i)\\.(mp3|m4a|aac|wav|ogg|flac|opus|wma|webm|aiff|aif)$")
    private val numericVariant = Regex("(?i)(?:[\\s._-]+(?:v|var|variant)?\\s*\\d{1,3}|\\s*\\(\\d{1,3}\\))$")

    fun key(title: String): String {
        val clean = title.trim().replace(extension, "").trim().lowercase()
        if (clean.isBlank()) return ""
        val family = clean.replace(numericVariant, "").trim(' ', '_', '-', '.')
        return family.ifBlank { clean }
    }
}

/**
 * Existing scene-music rows are intentionally reused as the physical asset library. This preserves
 * URI permissions, per-file volume and loudness-normalization metadata without a Room migration.
 *
 * Classification is metadata-only:
 * - MUSIC: default, or type:music / [music]
 * - AMBIENCE: type:ambience / type:environment / [ambience]
 * - CONTINUOUS SFX: type:sfx_continuous / type:continuous / [continuous] is promoted to AMBIENCE
 * - SFX: type:sfx / type:sound_effect / [sfx]
 *
 * The explicit markers keep ordinary words such as "rain" or "battle" as descriptive tags rather
 * than accidentally changing the asset type.
 */
object AudioAssetClassifier {
    fun classify(track: SceneMusicTrackEntity): AudioAssetKind {
        val metadata = "${track.title}\n${track.tagsCsv}".lowercase()
        return when {
            hasAny(
                metadata,
                "type:sfx_continuous",
                "type=sfx_continuous",
                "type:sfx-continuous",
                "type=sfx-continuous",
                "type:continuous",
                "type=continuous",
                "[continuous]",
                "[sfx_continuous]",
                "[sfx-continuous]",
            ) -> AudioAssetKind.AMBIENCE
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
